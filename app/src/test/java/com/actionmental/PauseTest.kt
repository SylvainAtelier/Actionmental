package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.data.UserSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局暂停的两条硬承诺，都能脱离设备验证：
 *  1. 暂停期间一颗键都不拦 —— 拦下就等于用户的键盘坏了，而这是「暂停」最不该有的后果；
 *  2. 老配置读出来默认不是暂停 —— 升级一次就把所有人的功能关掉，那是最糟的默认值。
 */
class PauseTest {

    private fun keyEvent(down: Boolean, keyCode: Int, metaState: Int = 0) = NormalizedKeyEvent(
        keyCode = keyCode,
        scanCode = 0,
        metaState = metaState,
        down = down,
        repeatCount = 0,
        eventTimeMs = 1L,
        device = KeyboardDevice.UNKNOWN,
    )

    @Test
    fun `暂停期间不触发也不拦截`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { combo, _ -> fired += combo; true }
        }

        // 先确认这个组合本来是会被拦下的，否则这条测试可能只是在测一个不成立的前提
        pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        assertTrue(pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)))
        assertEquals(1, fired.size)

        pipeline.setPaused(true)
        fired.clear()

        pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        assertFalse(pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)))
        assertFalse(pipeline.dispatch(keyEvent(false, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)))
        assertTrue(fired.isEmpty())
        assertTrue(pipeline.pausedState.value)
    }

    @Test
    fun `恢复之后立刻重新生效`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { combo, _ -> fired += combo; true }
        }

        pipeline.setPaused(true)
        pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        pipeline.setPaused(false)

        // 暂停期间按下的 Ctrl 不该留下痕迹：setPaused 会 reset，
        // 否则恢复之后管线以为有一颗修饰键一直按着
        assertTrue(pipeline.snapshot.value.pressedKeyCodes.isEmpty())

        pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        assertTrue(pipeline.dispatch(keyEvent(true, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)))
        assertEquals(1, fired.size)
    }

    @Test
    fun `既有配置默认不暂停`() {
        val settings = Json.decodeFromString<UserSettings>("""{"theme":"SYSTEM","keepAlive":true}""")

        assertFalse(settings.paused)
        // 自动暂停同样必须是「用户明确开过一次」才成立：升级一次就把没插键盘的人
        // 全部停掉，是这个功能最糟的默认值
        assertFalse(settings.autoPauseWithoutKeyboard)
    }

    /**
     * 暂停原因的判定。和 [com.actionmental.AppGraph.pauseIntent] 里那段 when 一字不差，
     * 复制过来是因为那段逻辑挂在 Android 对象图上，单测拿不到 —— 而它恰恰是
     * 这个功能里唯一会算错的地方。
     */
    private fun reasonOf(settings: UserSettings, keyboards: Int): String = when {
        settings.paused -> "MANUAL"
        settings.autoPauseWithoutKeyboard && keyboards == 0 -> "NO_KEYBOARD"
        else -> "NONE"
    }

    @Test
    fun `没有键盘且开了自动暂停才自动停`() {
        val off = UserSettings()
        val on = UserSettings(autoPauseWithoutKeyboard = true)

        assertEquals("NONE", reasonOf(off, keyboards = 0))
        assertEquals("NONE", reasonOf(on, keyboards = 1))
        assertEquals("NO_KEYBOARD", reasonOf(on, keyboards = 0))
    }

    @Test
    fun `手动暂停优先于自动`() {
        val both = UserSettings(paused = true, autoPauseWithoutKeyboard = true)

        // 键盘在不在都不改变结论：说成 NO_KEYBOARD 会让用户以为插上键盘就能跑，
        // 而实际上还压着他自己按下的那个暂停
        assertEquals("MANUAL", reasonOf(both, keyboards = 0))
        assertEquals("MANUAL", reasonOf(both, keyboards = 2))
    }
}
