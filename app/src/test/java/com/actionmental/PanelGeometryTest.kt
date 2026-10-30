package com.actionmental

import com.actionmental.core.clip.PanelGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelGeometryTest {

    @Test
    fun columnsFollowWindowWidthNotOrientation() {
        // Portrait phone
        assertFalse(PanelGeometry.columns(411f))
        // Landscape phone
        assertTrue(PanelGeometry.columns(850f))
        // Unfolded foldable held upright and portrait tablet: wide but not rotated
        assertTrue(PanelGeometry.columns(673f))
        assertTrue(PanelGeometry.columns(800f))
        // Boundary: 600dp card plus the compact gutter on both sides
        assertTrue(PanelGeometry.columns(648f))
        assertFalse(PanelGeometry.columns(647f))
    }

    @Test
    fun gutterUsesShortSideSoBothOrientationsMatch() {
        assertEquals(PanelGeometry.gutter(411f, 891f), PanelGeometry.gutter(891f, 411f))
        assertEquals(PanelGeometry.COMPACT_GUTTER_DP, PanelGeometry.gutter(891f, 411f))
        assertEquals(PanelGeometry.WIDE_GUTTER_DP, PanelGeometry.gutter(673f, 841f))
    }

    @Test
    fun splitCardShrinksGutterToKeepTwoColumnsReadable() {
        val side = PanelGeometry.side(673f, PanelGeometry.WIDE_GUTTER_DP, 960f, columns = true)
        assertEquals(36.5f, side, 0.01f)
        assertTrue(673f - side * 2 >= PanelGeometry.SPLIT_MIN_DP)
        // Never below the compact gutter
        assertEquals(PanelGeometry.COMPACT_GUTTER_DP, PanelGeometry.side(648f, 48f, 960f, columns = true), 0.01f)
    }

    @Test
    fun wideWindowsKeepCenteredCappedCard() {
        assertEquals(120f, PanelGeometry.side(1200f, 48f, 960f, columns = true), 0.01f)
        assertEquals(48f, PanelGeometry.side(673f, 48f, 640f, columns = false), 0.01f)
        assertEquals(280f, PanelGeometry.side(1200f, 48f, 640f, columns = false), 0.01f)
    }
}
