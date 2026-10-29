package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.data.UserSettings
import com.actionmental.ui.i18n.AppTranslations
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechShortcutTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun speechToTextIsListedWithoutPrivilegeAndRoundTrips() {
        val group = ActionCatalog.groups.first { it.id == "speech_to_text" }
        assertEquals(listOf<Action>(Action.SpeechToText), group.actions)
        assertFalse(Action.SpeechToText.requiresPrivilege)
        val shortcut = Shortcut(id = "speech", combo = KeyCombo(0, 0), action = Action.SpeechToText)
        val encoded = json.encodeToString(Shortcut.serializer(), shortcut)
        assert("\"speech_to_text\"" in encoded)
        assertEquals(shortcut, json.decodeFromString(Shortcut.serializer(), encoded))
    }

    @Test fun onlyFailuresProduceFeedback() {
        val translate = { text: String -> text }
        assertNull(TriggerFeedback.of(Action.SpeechToText, ActionResult.Ok("语音转文字"), translate))
        val failed = TriggerFeedback.of(Action.SpeechToText,
            ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "锁屏时无法录音，请先解锁"), translate)
        assertNotNull(failed)
        assert(failed!!.failed)
    }

    @Test fun catalogCopyHasEnglish() {
        listOf("语音转文字", "录音转写后直接上屏或填入翻译", "按一次开始录音，再按一次完成并上屏").forEach {
            assertFalse(AppTranslations.translate(it, UserSettings.Language.ENGLISH).contains(Regex("[\\p{IsHan}]")))
        }
    }
}
