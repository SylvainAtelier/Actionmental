package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.key.KeyChord
import com.actionmental.core.key.KeyChord.Step
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.core.voice.VoiceInputRules
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendKeyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    @Test
    fun `Alt + D 拆成 Alt 按下、D 按下、D 抬起、Alt 抬起`() {
        assertEquals(
            listOf(
                Step(KeyEvent.KEYCODE_ALT_LEFT, true, alt),
                Step(KeyEvent.KEYCODE_D, true, alt),
                Step(KeyEvent.KEYCODE_D, false, alt),
                Step(KeyEvent.KEYCODE_ALT_LEFT, false, 0),
            ),
            KeyChord.steps(KeyCombo(KeyEvent.KEYCODE_D, KeyCombo.MOD_ALT)),
        )
    }

    @Test
    fun `多个修饰键按 Ctrl Alt 顺序按下、倒序抬起`() {
        val steps = KeyChord.steps(KeyCombo(KeyEvent.KEYCODE_V, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT))
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_V,
                KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT,
            ),
            steps.map { it.keyCode },
        )
        assertEquals(listOf(true, true, true, false, false, false), steps.map { it.down })
        assertEquals(ctrl, steps[1].metaState and ctrl)
        assertEquals(ctrl, steps[4].metaState)
        assertEquals(0, steps.last().metaState)
    }

    @Test
    fun `主键自己是修饰键时不再另按同族的左键`() {
        val steps = KeyChord.steps(KeyCombo(KeyEvent.KEYCODE_ALT_RIGHT, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT))
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
            ),
            steps.map { it.keyCode },
        )
        assertEquals(ctrl, steps[2].metaState)
    }

    @Test
    fun `单独一颗键就是按下抬起`() {
        assertEquals(
            listOf(Step(KeyEvent.KEYCODE_F5, true, 0), Step(KeyEvent.KEYCODE_F5, false, 0)),
            KeyChord.steps(KeyCombo(KeyEvent.KEYCODE_F5)),
        )
    }

    @Test
    fun `发送组合键要 Shizuku、能原样存回来、有自己的目录入口`() {
        val action = Action.SendKey(KeyCombo(KeyEvent.KEYCODE_D, KeyCombo.MOD_ALT), "语音")
        assertTrue(action.requiresPrivilege)
        val shortcut = Shortcut(id = "send", combo = KeyCombo(KeyEvent.KEYCODE_SPACE, KeyCombo.MOD_CTRL), action = action)
        val restored = json.decodeFromString(Shortcut.serializer(), json.encodeToString(Shortcut.serializer(), shortcut))
        assertEquals(shortcut, restored)

        val group = ActionCatalog.groups.first { it.id == ActionCatalog.GROUP_SEND_KEY }
        assertTrue(group.direct)
    }

    @Test
    fun `起了名字就用名字，桌面上也不拿去翻译`() {
        // 没起名字时的「发送 Alt + D」要查键名，那是 Android 的接口，单元测试里拿不到
        assertEquals("Gboard 语音输入", Action.SendKey.GBOARD_VOICE.label)
        assertEquals("Gboard 语音输入", DesktopName.of(Action.SendKey.GBOARD_VOICE))
        assertTrue(!DesktopName.isBuiltIn(Action.SendKey.GBOARD_VOICE))
    }

    @Test
    fun `只有 Gboard 有现成的语音快捷键`() {
        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_D, KeyCombo.MOD_ALT),
            VoiceInputRules.hotkeyFor("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"),
        )
        assertNull(VoiceInputRules.hotkeyFor("com.tencent.wetype/.plugin.hld.WxHldService"))
        assertNull(VoiceInputRules.hotkeyFor(null))
    }

    @Test
    fun `发出去不提示，发不出去说清原因`() {
        val action = Action.SendKey.GBOARD_VOICE
        assertNull(TriggerFeedback.of(action, ActionResult.Ok("已发送 Alt + D")) { it })
        val failed = TriggerFeedback.of(
            action,
            ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "系统拒绝了这次注入"),
        ) { it }
        assertNotNull(failed)
        assertTrue(failed!!.failed)
        assertEquals("系统拒绝了这次注入", failed.output)
    }
}
