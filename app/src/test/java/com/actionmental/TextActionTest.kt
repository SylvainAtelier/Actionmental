package com.actionmental

import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.translate.TextAction
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationRequest
import com.actionmental.core.translate.TranslationSettings
import com.actionmental.core.translate.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextActionTest {
    private val english = TranslationSettings.DEFAULT_LANGUAGES[1]

    @Test fun actionsUseInPlaceRulesWhileTranslationKeepsItsOwn() {
        val polish = TextAction.POLISH.target("Polish")!!
        val prompt = TranslationRequest("text", polish, "m").prompt
        assertTrue(prompt.endsWith(TranslationRequest.ACTION_RULES))
        assertFalse(prompt.contains("target language"))
        assertTrue(TranslationRequest("text", english, "m").prompt.endsWith(TranslationRequest.RULES))
        assertTrue(TranslationRequest("text", TextAction.DICTATION, "m").prompt.endsWith(TranslationRequest.ACTION_RULES))
    }

    @Test fun customActionNeedsAnInstructionWithinLimits() {
        assertNull(TextAction.CUSTOM.target("Custom", "   "))
        assertNull(TextAction.CUSTOM.target("Custom", "x".repeat(TextAction.MAX_INSTRUCTION + 1)))
        assertEquals("Make it formal", TextAction.CUSTOM.target("Custom", " Make it formal ")!!.prompt)
        assertNull(TextAction.TRANSLATE.target("Translate"))
    }

    @Test fun actionIdsResolveAndLanguagesFallBackToTranslate() {
        TextAction.entries.filter { it != TextAction.TRANSLATE }.forEach { assertEquals(it, TextAction.of(it.id)) }
        assertEquals(TextAction.TRANSLATE, TextAction.of(english.id))
        assertEquals(TextAction.TRANSLATE, TextAction.of(null))
    }

    @Test fun historyRestoresAnActionWithItsOwnInstruction() {
        val custom = TextAction.CUSTOM.target("Custom", "Turn into a haiku")!!
        val request = TranslationRequest("source", custom, "m")
        val entry = TranslationHistoryEntry(1, request.prompt, "m", "source", custom.id, custom.name, "result", 0)
        assertEquals("Turn into a haiku", TranslationRequest.instructionOf(entry))
        val state = ClipTranslationState("", english)
        state.restore(entry, TranslationSettings.DEFAULT_LANGUAGES)
        assertEquals(custom.id, state.target?.id)
        assertEquals("Turn into a haiku", state.target?.prompt)
        assertEquals("result", state.result)
    }

    @Test fun clearingTheTargetDropsTheResult() {
        val state = ClipTranslationState("text", english)
        state.selectTarget(null)
        assertNull(state.target)
        assertNull(state.result)
    }

    @Test fun voiceCommandPromptListsCandidatesAndStaysWithinTheLimit() {
        val candidates = (1..200).map { VoiceCommand.Candidate(it.toString(), "Name $it\nwith | pipe", "Detail") }
        val target = VoiceCommand.target(candidates)
        assertTrue(target.prompt.length <= 2000)
        assertTrue(target.prompt.contains("1 | Name 1 with / pipe | Detail"))
        TranslationRequest("open the camera", target, "m").validate()
        assertTrue(TranslationRequest("x", target, "m").prompt.endsWith(VoiceCommand.RULES))
    }

    @Test fun voiceCommandReplyMustNameAKnownCandidate() {
        val candidates = listOf(VoiceCommand.Candidate("1", "Camera", ""), VoiceCommand.Candidate("12", "Rotate", ""))
        assertEquals("12", VoiceCommand.parse(" 12 ", candidates)?.id)
        assertEquals("1", VoiceCommand.parse("\"1\".\nbecause it opens the camera", candidates)?.id)
        assertNull(VoiceCommand.parse("NONE", candidates))
        assertNull(VoiceCommand.parse("none", candidates))
        assertNull(VoiceCommand.parse("7", candidates))
        assertNull(VoiceCommand.parse("", candidates))
        assertNull(VoiceCommand.parse("Rotate", candidates))
    }
}
