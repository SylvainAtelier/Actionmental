package com.actionmental

import com.actionmental.core.rotation.CoverScreenDetector
import com.actionmental.core.rotation.PanelSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 认的是「换屏的方向」，不是屏幕大小；每段外屏只报一次。 */
class CoverScreenTest {

    // Galaxy Z Fold 一类：内屏接近方形，外屏窄长
    private fun inner(on: Boolean = true) = PanelSample(1812, 2176, on)
    private fun cover(on: Boolean = true) = PanelSample(904, 2316, on)

    @Test
    fun `从内屏换到外屏且亮着就报一次`() {
        val d = CoverScreenDetector()
        assertFalse(d.observe(inner()))
        assertTrue(d.observe(cover()))
        // 同一段外屏里转屏、亮灭屏都不再报
        assertFalse(d.observe(cover()))
        assertFalse(d.observe(cover(on = false)))
        assertFalse(d.observe(cover()))
    }

    @Test
    fun `合上时熄着要等用起来才报`() {
        val d = CoverScreenDetector()
        d.observe(inner())
        assertFalse(d.observe(cover(on = false)))
        assertTrue(d.observe(cover(on = true)))
    }

    @Test
    fun `展开再合上会再报一次`() {
        val d = CoverScreenDetector()
        d.observe(inner())
        assertTrue(d.observe(cover()))
        assertFalse(d.observe(inner()))
        assertFalse(d.onCover)
        assertTrue(d.observe(cover()))
    }

    @Test
    fun `进程起来时已在外屏上认不出`() {
        val d = CoverScreenDetector()
        assertFalse(d.observe(cover()))
        assertFalse(d.onCover)
    }

    @Test
    fun `切分辨率不算换屏`() {
        val d = CoverScreenDetector()
        d.observe(PanelSample(1440, 3120, true))
        assertFalse(d.observe(PanelSample(1080, 2340, true)))
        assertFalse(d.onCover)
    }

    @Test
    fun `Flip 内屏不会被当成外屏`() {
        val d = CoverScreenDetector()
        val flipInner = PanelSample(1080, 2640, true)
        val flipCover = PanelSample(748, 720, true)
        assertFalse(d.observe(flipInner))
        assertTrue(d.observe(flipCover))
        assertFalse(d.observe(flipInner))
    }

    @Test
    fun `复核没落地会重新武装`() {
        val d = CoverScreenDetector()
        d.observe(inner())
        assertTrue(d.observe(cover()))
        d.rearm()
        assertTrue(d.observe(cover()))
    }
}
