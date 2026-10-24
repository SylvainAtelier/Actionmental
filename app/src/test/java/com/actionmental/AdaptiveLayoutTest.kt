package com.actionmental

import com.actionmental.ui.AdaptiveLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun navigationUsesWindowBreakpoints() {
        assertEquals(0f, AdaptiveLayout.navigationWidth(375f), 0f)
        assertEquals(88f, AdaptiveLayout.navigationWidth(600f), 0f)
        assertEquals(88f, AdaptiveLayout.navigationWidth(839f), 0f)
        assertEquals(240f, AdaptiveLayout.navigationWidth(840f), 0f)
    }

    @Test
    fun panesMustFitAfterSidebarAndDivider() {
        assertFalse(AdaptiveLayout.useTwoPanes(839f, 1f))
        assertFalse(AdaptiveLayout.useTwoPanes(840f, 1f))
        assertFalse(AdaptiveLayout.useTwoPanes(960f, 1f))
        assertTrue(AdaptiveLayout.useTwoPanes(961f, 1f))
        assertTrue(AdaptiveLayout.useTwoPanes(1200f, 1f))
    }

    @Test
    fun largeFontsFallBackToOnePane() {
        assertFalse(AdaptiveLayout.useTwoPanes(1200f, 1.5f))
        assertFalse(AdaptiveLayout.useTwoPanes(1440f, 2f))
        assertTrue(AdaptiveLayout.useTwoPanes(1920f, 2f))
        assertEquals(AdaptiveLayout.useTwoPanes(961f, 1f), AdaptiveLayout.useTwoPanes(961f, 0.85f))
    }

    @Test
    fun statusGridRespondsToAvailableTextSpace() {
        assertEquals(2, AdaptiveLayout.statusColumns(343f, 1f))
        assertEquals(1, AdaptiveLayout.statusColumns(343f, 2f))
        assertEquals(4, AdaptiveLayout.statusColumns(720f, 1f))
        assertEquals(2, AdaptiveLayout.statusColumns(720f, 2f))
        assertEquals(1, AdaptiveLayout.statusColumns(288f, 1f))
    }
}
