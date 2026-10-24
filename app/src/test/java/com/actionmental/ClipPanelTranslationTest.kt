package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.clip.PickKeys
import com.actionmental.core.clip.TranslationPanelKey
import com.actionmental.core.clip.TranslationPanelKeys
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationLanguage
import com.actionmental.core.translate.TranslationRequest
import org.junit.Assert.*
import org.junit.Test

class ClipPanelTranslationTest {
    private val chinese = TranslationLanguage("zh", "Chinese", "Translate to Chinese")
    private val english = TranslationLanguage("en", "English", "Translate to English")
    private val entry = ClipEntry(1L, "Preview", null, 10L, 20L, 1, 0L)
    private fun history() = TranslationHistoryEntry(2L, "Prompt", "Model", "Full historical source", "en", "English", "Historical translation", 30L)

    @Test fun ctrlTOpensSelectedTranslationWithoutEditingSearchOrInserting() {
        val state = ClipPanelState("query", listOf(entry), 0)
        val key = PanelKeys.map(KeyEvent.KEYCODE_T, KeyCombo.MOD_CTRL, 't')
        assertEquals(PanelKey.Translate, key)
        assertEquals(state to PanelEffect.Translate(entry), state.reduce(key!!))
        assertEquals(PanelKey.Type('t'), PanelKeys.map(KeyEvent.KEYCODE_T, 0, 't'))
        assertEquals(PanelEffect.None, ClipPanelState().reduce(PanelKey.Translate).second)
    }

    @Test fun detailKeyboardCommandsHaveExplicitRoutesAndLeaveTabToNativeFocus() {
        fun key(code: Int, modifiers: Int = 0) = TranslationPanelKeys.map(code, modifiers, PickKeys.CTRL_DIGITS)
        assertEquals(TranslationPanelKey.PreviousTarget, key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TranslationPanelKey.NextTarget, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(TranslationPanelKey.Confirm, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(TranslationPanelKey.Confirm, key(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(TranslationPanelKey.Escape, key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(TranslationPanelKey.Escape, key(KeyEvent.KEYCODE_BACK))
        assertEquals(TranslationPanelKey.BackToList, key(KeyEvent.KEYCODE_T, KeyCombo.MOD_CTRL))
        assertEquals(TranslationPanelKey.Retry, key(KeyEvent.KEYCODE_R, KeyCombo.MOD_CTRL))
        assertNull(key(KeyEvent.KEYCODE_TAB))
        assertNull(key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull(key(KeyEvent.KEYCODE_T))
        assertNull(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyCombo.MOD_CTRL))
    }

    @Test fun detailLanguagePicksUseExactlyTheConfiguredBrowsePickGroup() {
        assertEquals(TranslationPanelKey.PickTarget(2), TranslationPanelKeys.map(KeyEvent.KEYCODE_3, KeyCombo.MOD_CTRL, PickKeys.CTRL_DIGITS))
        assertNull(TranslationPanelKeys.map(KeyEvent.KEYCODE_3, 0, PickKeys.CTRL_DIGITS))
        assertNull(TranslationPanelKeys.map(KeyEvent.KEYCODE_3, KeyCombo.MOD_ALT, PickKeys.CTRL_DIGITS))
        assertEquals(TranslationPanelKey.PickTarget(2), TranslationPanelKeys.map(KeyEvent.KEYCODE_3, KeyCombo.MOD_ALT, PickKeys.ALT_DIGITS))
        assertNull(TranslationPanelKeys.map(KeyEvent.KEYCODE_3, KeyCombo.MOD_CTRL, PickKeys.ALT_DIGITS))
        assertEquals(TranslationPanelKey.PickTarget(2), TranslationPanelKeys.map(KeyEvent.KEYCODE_F3, 0, PickKeys.FUNCTION_KEYS))
        assertNull(TranslationPanelKeys.map(KeyEvent.KEYCODE_F3, KeyCombo.MOD_CTRL, PickKeys.FUNCTION_KEYS))
    }

    @Test fun openingAndSelectingLanguageDoNotStartRequests() {
        val state = ClipTranslationState("Full source", chinese)
        assertFalse(state.loading)
        assertNull(state.result)
        state.selectTarget(english)
        assertFalse(state.loading)
        assertEquals("Full source", state.source)
        assertEquals(english, state.target)
    }

    @Test fun changingLanguageWhileLoadingCancelsWithoutResendingAndRejectsLateResult() {
        val state = ClipTranslationState("Source", chinese)
        val old = state.beginRequest()
        state.selectTarget(english)
        assertFalse(state.loading)
        assertFalse(state.finishRequest(old, Result.success("Stale translation")))
        assertNull(state.result)
        val fresh = state.beginRequest()
        assertTrue(state.finishRequest(fresh, Result.success("Fresh translation")))
        assertEquals("Fresh translation", state.result)
    }

    @Test fun restoreHistoryRestoresBothSidesWithoutRequestOrInsertion() {
        val state = ClipTranslationState("Different source", chinese)
        val old = state.beginRequest()
        state.restore(history(), english)
        assertEquals("Full historical source", state.source)
        assertEquals("Historical translation", state.result)
        assertEquals(english, state.target)
        assertFalse(state.loading)
        assertFalse(state.inserting)
        assertFalse(state.finishRequest(old, Result.success("Late response")))
    }

    @Test fun historyOfDeletedLanguagePreservesTheEntireMultilinePrompt() {
        val state = ClipTranslationState("Source", chinese)
        val prompt = "Translate naturally.\n\nPreserve technical terminology.\n\nUse short sentences."
        val stored = history().copy(prompt = prompt + "\n\n" + TranslationRequest.RULES)
        state.restore(stored, listOf(chinese))
        assertEquals(prompt, state.target!!.prompt)
        assertEquals(stored.targetId, state.target!!.id)
        assertEquals(stored.result, state.result)
        assertFalse(state.loading)
    }

    @Test fun retryFailureAndEmptyResponseKeepThePreviousTranslation() {
        val state = ClipTranslationState("Source", chinese)
        state.restore(history(), english)
        assertTrue(state.finishRequest(state.beginRequest(), Result.failure(IllegalStateException("Network"))))
        assertEquals("Historical translation", state.result)
        assertTrue(state.finishRequest(state.beginRequest(), Result.success("  ")))
        assertEquals("Historical translation", state.result)
    }

    @Test fun closedOrReplacedDetailRejectsLateCompletionAndDuplicateCompletion() {
        val state = ClipTranslationState("Source", chinese)
        val closed = state.beginRequest()
        state.cancel()
        assertFalse(state.finishRequest(closed, Result.success("Late result")))
        val fresh = state.beginRequest()
        assertTrue(state.finishRequest(fresh, Result.success("Result")))
        assertFalse(state.finishRequest(fresh, Result.success("Duplicate")))
        assertEquals("Result", state.result)
    }
}
