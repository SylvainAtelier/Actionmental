package com.actionmental

import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import com.actionmental.core.action.ActionResult
import com.actionmental.core.key.KeyChannel
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyRouting
import com.actionmental.core.rotation.LocalRotationBackend
import com.actionmental.core.rotation.OverlayOutcome
import com.actionmental.core.rotation.RotationController
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.rotation.RotationTier
import com.actionmental.core.rotation.screenOrientationFor
import com.actionmental.platform.UnavailableBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 没有 Shizuku 时的降级行为。
 *
 * 三条承诺：
 *  1. 映射按目标键分流，走不通就返回 null（调用方据此放行原键，绝不吞键）；
 *  2. 旋转退到悬浮层 / 系统设置时，回读说的仍然是真话；
 *  3. 一级都写不了时如实失败，且不把没写下去的意图落盘。
 */
class DegradedModeTest {

    // --- 映射分流 ---------------------------------------------------------------

    @Test
    fun `注入就绪时一律走注入`() {
        val esc = KeyCombo(KeyEvent.KEYCODE_ESCAPE)
        val back = KeyCombo(KeyEvent.KEYCODE_BACK)
        assertEquals(KeyChannel.INJECT, KeyRouting.route(esc, 34, injectReady = true, inputConnectionReady = false))
        assertEquals(KeyChannel.INJECT, KeyRouting.route(back, 34, injectReady = true, inputConnectionReady = true))
    }

    @Test
    fun `系统键与媒体键不需要注入也不需要输入框`() {
        fun route(keyCode: Int) = KeyRouting.route(KeyCombo(keyCode), 34, injectReady = false, inputConnectionReady = false)

        assertEquals(KeyChannel.GLOBAL_ACTION, route(KeyEvent.KEYCODE_BACK))
        assertEquals(KeyChannel.GLOBAL_ACTION, route(KeyEvent.KEYCODE_HOME))
        assertEquals(KeyChannel.GLOBAL_ACTION, route(KeyEvent.KEYCODE_APP_SWITCH))
        assertEquals(KeyChannel.GLOBAL_ACTION, route(KeyEvent.KEYCODE_SYSRQ))
        assertEquals(KeyChannel.GLOBAL_ACTION, route(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(KeyChannel.MEDIA, route(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(KeyChannel.MEDIA, route(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `带修饰键的系统键不冒充全局动作`() {
        val ctrlBack = KeyCombo(KeyEvent.KEYCODE_BACK, KeyCombo.MOD_CTRL)
        assertNull(KeyRouting.globalActionFor(ctrlBack, 34))
        assertNull(KeyRouting.route(ctrlBack, 34, injectReady = false, inputConnectionReady = false))
        assertEquals(
            KeyChannel.INPUT_CONNECTION,
            KeyRouting.route(ctrlBack, 34, injectReady = false, inputConnectionReady = true),
        )
    }

    @Test
    fun `普通键只能交给输入通道，没有输入框就不可用`() {
        val esc = KeyCombo(KeyEvent.KEYCODE_ESCAPE)
        assertEquals(KeyChannel.INPUT_CONNECTION, KeyRouting.route(esc, 33, injectReady = false, inputConnectionReady = true))
        assertNull(KeyRouting.route(esc, 33, injectReady = false, inputConnectionReady = false))
        // 输入通道是 Android 13 才有的：更早的系统上哪怕报告有连接也不走
        assertNull(KeyRouting.route(esc, 32, injectReady = false, inputConnectionReady = true))
    }

    @Test
    fun `按版本给出的全局动作`() {
        val dpad = KeyCombo(KeyEvent.KEYCODE_DPAD_UP)
        assertNull(KeyRouting.globalActionFor(dpad, 32))
        assertEquals(AccessibilityService.GLOBAL_ACTION_DPAD_UP, KeyRouting.globalActionFor(dpad, 33))

        val hook = KeyCombo(KeyEvent.KEYCODE_HEADSETHOOK)
        assertNull(KeyRouting.globalActionFor(hook, 30))
        // 旧系统上没有全局动作，退回当媒体键
        assertEquals(KeyChannel.MEDIA, KeyRouting.route(hook, 30, injectReady = false, inputConnectionReady = false))
    }

    @Test
    fun `降级预判与映射页的提示一致`() {
        assertEquals(KeyChannel.GLOBAL_ACTION, KeyRouting.fallbackChannel(KeyCombo(KeyEvent.KEYCODE_BACK), 34))
        assertEquals(KeyChannel.INPUT_CONNECTION, KeyRouting.fallbackChannel(KeyCombo(KeyEvent.KEYCODE_DEL), 34))
        assertNull(KeyRouting.fallbackChannel(KeyCombo(KeyEvent.KEYCODE_DEL), 31))
    }

    // --- 角度映射 ---------------------------------------------------------------

    @Test
    fun `自然竖屏的设备`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, screenOrientationFor(0, naturalLandscape = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, screenOrientationFor(1, naturalLandscape = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, screenOrientationFor(2, naturalLandscape = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, screenOrientationFor(3, naturalLandscape = false))
    }

    @Test
    fun `自然横屏的设备上横屏就是 0 度`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, screenOrientationFor(0, naturalLandscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, screenOrientationFor(2, naturalLandscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, screenOrientationFor(3, naturalLandscape = true))
    }

    @Test
    fun `对调只影响 90 与 270 度`() {
        assertEquals(
            screenOrientationFor(3, naturalLandscape = false),
            screenOrientationFor(1, naturalLandscape = false, reversed = true),
        )
        assertEquals(
            screenOrientationFor(0, naturalLandscape = false),
            screenOrientationFor(0, naturalLandscape = false, reversed = true),
        )
    }

    // --- 旋转降级 ---------------------------------------------------------------

    /** 一张 system 表加一个悬浮层，行为与平台实现对齐：写设置就是改表，被忽略的悬浮层会被撤掉。 */
    private class FakeLocal(
        var overlay: Boolean,
        var settingsOk: Boolean,
        val table: MutableMap<String, Int> = mutableMapOf("user_rotation" to 0, "accelerometer_rotation" to 1),
        var outcome: OverlayOutcome = OverlayOutcome.ADOPTED,
    ) : LocalRotationBackend {
        var overlayRotation: Int? = null

        override fun overlayAvailable() = overlay
        override fun settingsWritable() = settingsOk

        override suspend fun forceOverlay(surfaceRotation: Int?): OverlayOutcome {
            if (surfaceRotation == null) {
                overlayRotation = null
                return OverlayOutcome.REMOVED
            }
            if (!overlay) return OverlayOutcome.FAILED
            overlayRotation = if (outcome == OverlayOutcome.ADOPTED) surfaceRotation else null
            return outcome
        }

        override fun overlayRotation(): Int? = overlayRotation

        override fun writeSettings(autoRotate: Boolean, userRotation: Int?): Result<Unit> {
            if (!settingsOk) return Result.failure(IllegalStateException("未授权"))
            userRotation?.let { table["user_rotation"] = it }
            table["accelerometer_rotation"] = if (autoRotate) 1 else 0
            return Result.success(Unit)
        }
    }

    private class Harness(val local: FakeLocal) {
        val persisted = mutableListOf<RotationMode>()
        var writes = 0
        val controller = RotationController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            backend = { UnavailableBackend(ActionResult.Reason.SHIZUKU_NOT_RUNNING) },
            settings = { key -> local.table[key] },
            persistGlobalMode = { persisted += it },
            local = local,
            onWrite = { writes++ },
        )

        init {
            runBlocking { controller.setPaused(false) }
        }
    }

    @Test
    fun `没有 Shizuku 时由悬浮层强制并能切回`() = runBlocking {
        val h = Harness(FakeLocal(overlay = true, settingsOk = true))

        val on = h.controller.toggle(RotationMode.FORCE_LANDSCAPE)
        assertTrue(on.message, on.succeeded)
        val state = h.controller.state.value
        assertEquals(RotationMode.FORCE_LANDSCAPE, state.mode)
        assertEquals(RotationTier.OVERLAY, state.tier)
        // 设置也一并锁过去：以前经 Shizuku 设下的 fix-to-user-rotation 残留时，这一步本身就能转屏
        assertEquals(1, h.local.table["user_rotation"])

        // 再按一次回到「自动旋转关闭」：悬浮层撤掉，锁回 0°
        val off = h.controller.toggle(RotationMode.FORCE_LANDSCAPE)
        assertTrue(off.message, off.succeeded)
        assertNull(h.local.overlayRotation)
        assertEquals(RotationMode.CUSTOM, h.controller.state.value.mode)
        assertEquals(0, h.local.table["user_rotation"])
        assertEquals(0, h.local.table["accelerometer_rotation"])
    }

    @Test
    fun `只剩系统设置时锁定角度，回读认得出是自己锁的`() = runBlocking {
        val h = Harness(FakeLocal(overlay = false, settingsOk = true))

        val result = h.controller.toggle(RotationMode.FORCE_REVERSE_LANDSCAPE)
        assertTrue(result.message, result.succeeded)
        assertEquals(RotationTier.SETTINGS, h.controller.state.value.tier)
        assertEquals(RotationMode.FORCE_REVERSE_LANDSCAPE, h.controller.state.value.mode)

        // 认得出来，切换键才能再按一下转回去
        h.controller.toggle(RotationMode.FORCE_REVERSE_LANDSCAPE)
        assertEquals(RotationMode.CUSTOM, h.controller.state.value.mode)
    }

    @Test
    fun `用户自己改了 user_rotation 就不再算作强制`() = runBlocking {
        val h = Harness(FakeLocal(overlay = false, settingsOk = true))
        h.controller.toggle(RotationMode.FORCE_LANDSCAPE)
        h.local.table["user_rotation"] = 0
        assertEquals(RotationMode.CUSTOM, h.controller.refresh().mode)
    }

    @Test
    fun `悬浮层被系统忽略时退到锁定，不谎报已强制`() = runBlocking {
        val h = Harness(FakeLocal(overlay = true, settingsOk = true, outcome = OverlayOutcome.IGNORED))
        val result = h.controller.setGlobal(RotationMode.FORCE_LANDSCAPE)
        assertTrue(result.message, result.succeeded)
        assertTrue(result.message, result.message.contains("忽略"))
        assertNull(h.local.overlayRotation)

        val ignoredNoSettings = Harness(FakeLocal(overlay = true, settingsOk = false, outcome = OverlayOutcome.IGNORED))
        val failed = ignoredNoSettings.controller.setGlobal(RotationMode.FORCE_LANDSCAPE)
        assertFalse(failed.succeeded)
        assertFalse(ignoredNoSettings.controller.state.value.mode.isForced)
    }

    @Test
    fun `一级都写不了时读得到、写不了，也不落盘`() = runBlocking {
        val h = Harness(FakeLocal(overlay = false, settingsOk = false))
        val state = h.controller.refresh()
        assertTrue(state.available)
        assertFalse(state.writable)
        assertEquals(RotationMode.NORMAL, state.mode)

        val result = h.controller.toggle(RotationMode.FORCE_LANDSCAPE)
        assertFalse(result.succeeded)
        assertEquals(ActionResult.Reason.SHIZUKU_NOT_RUNNING, (result as ActionResult.Failed).reason)
        assertTrue(h.persisted.isEmpty())
    }

    @Test
    fun `只有悬浮层时非强制模式之间不来回白写`() = runBlocking {
        // 用户自己关着自动旋转；全局意图是「系统默认」。写不了设置时这两者对我们没有区别
        val local = FakeLocal(
            overlay = true,
            settingsOk = false,
            table = mutableMapOf("user_rotation" to 0, "accelerometer_rotation" to 0),
        )
        val h = Harness(local)
        val before = h.writes
        assertTrue(h.controller.setOverride(null).succeeded)
        assertTrue(h.controller.setOverride(RotationMode.NORMAL).succeeded)
        assertEquals(before, h.writes)
    }

    @Test
    fun `暂停时撤掉悬浮层`() = runBlocking {
        val h = Harness(FakeLocal(overlay = true, settingsOk = false))
        h.controller.setGlobal(RotationMode.FORCE_LANDSCAPE)
        assertEquals(1, h.local.overlayRotation)

        h.controller.setPaused(true)
        assertNull(h.local.overlayRotation)
    }
}
