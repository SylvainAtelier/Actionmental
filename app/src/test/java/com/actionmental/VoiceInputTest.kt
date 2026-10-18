package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.core.voice.VoiceInputRules
import com.actionmental.core.voice.VoiceInputRules.Ime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `语音输入三条动作在目录里、不强制 Shizuku 且能原样存回来`() {
        val group = ActionCatalog.groups.first { it.id == "voice_input" }
        assertEquals(
            listOf("VOICE_INPUT", "VOICE_INPUT_KEY", "VOICE_INPUT_IME"),
            group.actions.map { it.technical },
        )
        assertTrue(group.actions.none { it.requiresPrivilege })

        Action.VoiceInput.Mode.entries.forEach { mode ->
            val shortcut = Shortcut(id = "voice", combo = KeyCombo(0, 0), action = Action.VoiceInput(mode))
            val restored = json.decodeFromString(
                Shortcut.serializer(),
                json.encodeToString(Shortcut.serializer(), shortcut),
            )
            assertEquals(shortcut, restored)
        }
    }

    @Test
    fun `认得出常见输入法的语音键`() {
        assertTrue(VoiceInputRules.score(null, "语音输入", null) > 0)
        assertTrue(VoiceInputRules.score(null, "Voice input", null) > 0)
        assertTrue(VoiceInputRules.score(null, "Use voice typing", null) > 0)
        assertTrue(VoiceInputRules.score(null, "Mic", null) > 0)
        assertTrue(VoiceInputRules.score("按住说话", null, null) > 0)
        assertTrue(VoiceInputRules.score(null, null, "com.tencent.wetype:id/btn_voice") > 0)
        assertTrue(VoiceInputRules.score(null, null, "com.foo:id/mic_key") > 0)
    }

    @Test
    fun `不把普通按键和设置入口当成语音键`() {
        assertEquals(0, VoiceInputRules.score("a", "a", null))
        assertEquals(0, VoiceInputRules.score(null, "Microsoft", null))
        assertEquals(0, VoiceInputRules.score(null, "语音设置", null))
        assertEquals(0, VoiceInputRules.score(null, "Voice typing settings", null))
        assertEquals(0, VoiceInputRules.score(null, "Language", null))
        assertEquals(0, VoiceInputRules.score(null, null, "com.foo:id/microsoft_logo"))
    }

    @Test
    fun `描述整串匹配排在包含匹配前面`() {
        val exact = VoiceInputRules.score(null, "语音", null)
        val partial = VoiceInputRules.score(null, "长按语音转文字", null)
        val byId = VoiceInputRules.score(null, null, "x:id/voice")
        assertTrue(exact > partial)
        assertTrue(partial > byId)
    }

    private val gboard = Ime("com.google.android.inputmethod.latin/.LatinIME", "Gboard", listOf("keyboard", "keyboard"))
    private val googleVoice = Ime(
        "com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService",
        "Google 语音输入",
        listOf("voice", "voice"),
    )
    private val mixed = Ime("com.foo/.Ime", "Foo", listOf("keyboard", "voice"))

    @Test
    fun `优先切到专职语音输入法，且不切到当前输入法自己`() {
        assertEquals(googleVoice, VoiceInputRules.pickVoiceIme(listOf(gboard, mixed, googleVoice), gboard.id))
        assertEquals(mixed, VoiceInputRules.pickVoiceIme(listOf(gboard, mixed), gboard.id))
        assertNull(VoiceInputRules.pickVoiceIme(listOf(gboard, mixed), mixed.id))
        assertNull(VoiceInputRules.pickVoiceIme(listOf(gboard), gboard.id))
    }

    @Test
    fun `停在语音输入法上时切回记下的那一个`() {
        val enabled = listOf(gboard, mixed, googleVoice)
        assertTrue(VoiceInputRules.isOnVoiceIme(enabled, googleVoice.id))
        assertFalse(VoiceInputRules.isOnVoiceIme(enabled, mixed.id))
        assertEquals(mixed, VoiceInputRules.pickReturnIme(enabled, googleVoice.id, mixed.id))
        // 记下的那个已经停用：退回第一个普通输入法
        assertEquals(gboard, VoiceInputRules.pickReturnIme(enabled, googleVoice.id, "gone/.Ime"))
    }

    @Test
    fun `没唤起时提示原因，切了输入法时说切到了谁`() {
        val action = Action.VoiceInput()
        val failed = TriggerFeedback.of(action, ActionResult.Failed(ActionResult.Reason.VOICE_KEY_NOT_FOUND), { it })
        assertNotNull(failed)
        assertTrue(failed!!.failed)
        assertEquals("输入法键盘上没找到语音键", failed.value)

        assertNull(TriggerFeedback.of(action, ActionResult.Ok("已点按语音键"), { it }))
        assertEquals("已切到 Google 语音输入", TriggerFeedback.of(action, ActionResult.Ok("已切到 Google 语音输入"), { it })?.title)
    }
}
