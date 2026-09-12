package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.action.Action
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.rotation.batchScript
import com.actionmental.core.rotation.parseBatch
import com.actionmental.core.rotation.rotationWritePlan
import com.actionmental.core.shortcut.AppScope
import com.actionmental.core.shortcut.DeviceScope
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.core.shortcut.ShortcutMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 PRD 37 验收项里可以脱离设备验证的部分。
 * 覆盖的是架构承诺：组合键顺序无关、作用域唯一性、匹配优先级、旋转模式解析。
 */
class CoreInvariantsTest {

    private val anyKeyboard = KeyboardDevice(12, "Test KB", "desc-a")

    @Test
    fun `不同按键顺序产生相同组合`() {
        val ctrlThenAlt = KeyCombo.modifiersOf(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        val altThenCtrl = KeyCombo.modifiersOf(KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON)
        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_L, ctrlThenAlt),
            KeyCombo(KeyEvent.KEYCODE_L, altThenCtrl),
        )
    }

    @Test
    fun `修饰键识别覆盖左右两侧`() {
        assertTrue(KeyCombo.isModifier(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertTrue(KeyCombo.isModifier(KeyEvent.KEYCODE_META_LEFT))
        assertTrue(!KeyCombo.isModifier(KeyEvent.KEYCODE_L))
    }

    @Test
    fun `禁用的快捷键不参与匹配`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL)
        val matcher = ShortcutMatcher()
        matcher.update(listOf(shortcut("a", combo, enabled = false)))
        assertNull(matcher.match(combo, anyKeyboard, null))
    }

    @Test
    fun `应用限定优先于全局`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL)
        val global = shortcut("global", combo)
        val scoped = shortcut(
            "scoped",
            combo,
            appScope = AppScope(AppScope.Mode.INCLUDE, listOf("com.example")),
        )
        val matcher = ShortcutMatcher()
        matcher.update(listOf(global, scoped))

        assertEquals("scoped", matcher.match(combo, anyKeyboard, "com.example")?.id)
        assertEquals("global", matcher.match(combo, anyKeyboard, "com.other")?.id)
    }

    @Test
    fun `设备作用域用 descriptor 而不是 deviceId`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL)
        val scoped = shortcut("only-a", combo, deviceScope = DeviceScope(listOf("desc-a")))
        val matcher = ShortcutMatcher()
        matcher.update(listOf(scoped))

        // deviceId 变了但 descriptor 没变 —— 仍然应当命中
        val reconnected = anyKeyboard.copy(id = 99)
        assertEquals("only-a", matcher.match(combo, reconnected, null)?.id)
        assertNull(matcher.match(combo, anyKeyboard.copy(descriptor = "desc-b"), null))
    }

    @Test
    fun `同作用域同组合键的 scopeKey 相等——这是冲突检测的依据`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT)
        assertEquals(shortcut("a", combo).scopeKey, shortcut("b", combo).scopeKey)
    }

    @Test
    fun `已匹配快捷键会消费完整主键序列`() {
        val pipeline = KeyPipeline(traceCapacity = 0)
        pipeline.onTrigger = { combo, _ -> combo == KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL) }

        assertFalse(pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)))
        assertTrue(
            pipeline.dispatch(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
            ),
        )
        assertTrue(
            pipeline.dispatch(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON, repeatCount = 1),
            ),
        )
        assertTrue(
            pipeline.dispatch(
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
            ),
        )
        assertFalse(pipeline.dispatch(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT)))
    }

    @Test
    fun `轻点修饰键在抬起时触发，并且不拦截事件`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = recordingPipeline(fired)

        assertFalse(
            pipeline.dispatch(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON),
            ),
        )
        assertTrue(fired.isEmpty())

        // 抬起才成立；事件仍然放行，否则前台应用会以为 Ctrl 卡住了
        assertFalse(
            pipeline.dispatch(
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON),
            ),
        )
        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_CTRL_LEFT, 0)), fired)
    }

    @Test
    fun `修饰键参与了组合就不再算轻点`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = recordingPipeline(fired)

        pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON))
        pipeline.dispatch(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON))
        pipeline.dispatch(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))

        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL)), fired)
    }

    @Test
    fun `按住不放的修饰键不算轻点`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = recordingPipeline(fired)

        pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON))
        pipeline.dispatch(
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON, repeatCount = 1),
        )
        pipeline.dispatch(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON))

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `锁定键按下即触发并拦截`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = recordingPipeline(fired)

        assertTrue(pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK)))
        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_CAPS_LOCK, 0)), fired)
        // 抬起同样被吞掉，系统不会再收到这次 Caps Lock
        assertTrue(pipeline.dispatch(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK)))
    }

    @Test
    fun `压着别的修饰键时修饰键本身就是主键`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = recordingPipeline(fired)

        pipeline.dispatch(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON))
        assertTrue(
            pipeline.dispatch(
                keyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_ALT_RIGHT,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
                ),
            ),
        )
        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_ALT_RIGHT, KeyCombo.MOD_CTRL)), fired)
    }

    private fun recordingPipeline(sink: MutableList<KeyCombo>) = KeyPipeline(traceCapacity = 0).apply {
        onTrigger = { combo, _ -> sink += combo; true }
    }

    @Test
    fun `旋转模式与 user_rotation 双向一致`() {
        RotationMode.forced.forEach { mode ->
            val rotation = requireNotNull(mode.surfaceRotation)
            assertEquals(mode, RotationMode.fromSurfaceRotation(rotation))
        }
    }

    @Test
    fun `未知状态不会被当成关闭`() {
        val state = com.actionmental.core.rotation.RotationState.unknown("Shizuku 未运行")
        assertEquals(RotationMode.UNKNOWN, state.mode)
        assertTrue(!state.available)
    }

    @Test
    fun `旋转方向快捷键再次触发时关闭自动旋转`() {
        RotationMode.forced.forEach { selected ->
            assertEquals(selected, RotationMode.toggleTarget(RotationMode.NORMAL, selected))
            assertEquals(RotationMode.CUSTOM, RotationMode.toggleTarget(selected, selected))
        }
    }

    @Test
    fun `取消旋转模式时回到竖屏锁定`() {
        val plan = requireNotNull(rotationWritePlan(RotationMode.CUSTOM))

        assertEquals(
            listOf(
                "cmd window set-ignore-orientation-request false",
                "cmd window set-fix-to-user-rotation default",
                "cmd window user-rotation lock 0",
            ),
            plan,
        )
    }

    @Test
    fun `批量执行把一串命令合成一次 shell`() {
        val script = batchScript(listOf("cmd window get-a", "cmd window get-b"))

        // 每条命令后面各跟一行退出码标记，所以是 4 行而不是 2 行
        assertEquals(4, script.lines().size)
        assertEquals("cmd window get-a", script.lines()[0])
        assertEquals("cmd window get-b", script.lines()[2])
    }

    @Test
    fun `批量输出按标记切回逐条结果`() {
        val results = parseBatch(
            count = 2,
            output = """
                true
                __AM_RC__0
                Unknown command
                __AM_RC__255
            """.trimIndent(),
        )

        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertEquals("true", results[0].output)
        assertFalse(results[1].ok)
        assertEquals("Unknown command", results[1].output)
    }

    @Test
    fun `批量输出被截断时缺失的命令算失败`() {
        // shell 中途没了：只有第一条留下了标记。第二条绝不能被当成成功
        val results = parseBatch(
            count = 2,
            output = """
                true
                __AM_RC__0
            """.trimIndent(),
        )

        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertFalse(results[1].ok)
    }

    private fun shortcut(
        id: String,
        combo: KeyCombo,
        enabled: Boolean = true,
        deviceScope: DeviceScope = DeviceScope.ALL,
        appScope: AppScope = AppScope.GLOBAL,
    ) = Shortcut(
        id = id,
        combo = combo,
        action = Action.Rotation(Action.Rotation.Op.TOGGLE_LANDSCAPE),
        enabled = enabled,
        deviceScope = deviceScope,
        appScope = appScope,
    )

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
        repeatCount: Int = 0,
    ) = NormalizedKeyEvent(
        keyCode = keyCode,
        scanCode = 0,
        metaState = metaState,
        down = action == KeyEvent.ACTION_DOWN,
        repeatCount = repeatCount,
        eventTimeMs = 1L,
        device = KeyboardDevice.UNKNOWN,
    )
}
