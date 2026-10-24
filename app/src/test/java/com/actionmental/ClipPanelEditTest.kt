package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.key.KeyCombo
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipPanelEditTest {
    private fun entry(id: Long, text: String) = ClipEntry(
        id = id,
        text = text,
        sourcePackage = null,
        createdAtMs = 10L,
        lastUsedAtMs = 20L,
        useCount = 1,
        pinnedAtMs = 0L,
    )

    @Test
    fun ctrlEOpensTheSelectedClipWithoutChangingSearchOrSelection() {
        val selected = entry(2L, "Selected clip")
        val state = ClipPanelState(query = "clip", entries = listOf(entry(1L, "Other clip"), selected), selected = 1)
        val key = PanelKeys.map(KeyEvent.KEYCODE_E, KeyCombo.MOD_CTRL, 'e')
        assertEquals(PanelKey.Edit, key)
        val (next, effect) = state.reduce(key!!)
        assertEquals(state, next)
        assertEquals(PanelEffect.Edit(selected), effect)
    }

    @Test
    fun editingAnEmptyListDoesNothing() {
        val state = ClipPanelState(query = "no matches")
        assertEquals(state to PanelEffect.None, state.reduce(PanelKey.Edit))
    }

    @Test
    fun plainEStillSearchesInsteadOfEditing() {
        assertEquals(PanelKey.Type('e'), PanelKeys.map(KeyEvent.KEYCODE_E, 0, 'e'))
    }

    @Test
    fun refreshingAfterEditingFollowsTheSameId() {
        val first = entry(1L, "First")
        val second = entry(2L, "Before editing")
        val updated = second.copy(text = "After editing")
        val state = ClipPanelState(entries = listOf(first, second), selected = 1)
        val refreshed = state.withEntries(listOf(updated, first), follow = true)
        assertEquals(0, refreshed.selected)
        assertEquals(updated, refreshed.current)
    }

    @Test
    fun editingDoesNotInsertOrCopyTheClip() {
        val selected = entry(1L, "Private content")
        val state = ClipPanelState(entries = listOf(selected))
        assertEquals(PanelEffect.Edit(selected), state.reduce(PanelKey.Edit).second)
    }
}
