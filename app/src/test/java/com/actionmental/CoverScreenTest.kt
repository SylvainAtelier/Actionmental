package com.actionmental

import com.actionmental.core.rotation.Panel
import com.actionmental.core.rotation.PanelSample
import com.actionmental.core.rotation.PanelSwitch
import com.actionmental.core.rotation.PanelSwitchDetector
import com.actionmental.core.rotation.RotationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 认的是「换屏的方向」，不是屏幕大小；每次换屏只交出一次，见过的屏用它上一次的方向。 */
class CoverScreenTest {

    // Galaxy Z Fold 一类：内屏接近方形，外屏窄长
    private val inner = Panel.of(1812, 2176)
    private val cover = Panel.of(904, 2316)

    private fun on(panel: Panel) = PanelSample(panel, interactive = true)
    private fun off(panel: Panel) = PanelSample(panel, interactive = false)

    @Test
    fun `横竖报出来的尺寸是同一块屏`() {
        assertEquals(Panel.of(2176, 1812), inner)
    }

    @Test
    fun `换屏且亮着就交出一次`() {
        val d = PanelSwitchDetector(inner)
        assertFalse(d.observe(on(inner)))
        assertTrue(d.observe(on(cover)))
        assertEquals(PanelSwitch(inner, cover), d.take())
        // 同一块屏里转屏、亮灭屏都不再交
        assertFalse(d.observe(on(cover)))
        assertFalse(d.observe(off(cover)))
        assertNull(d.take())
    }

    @Test
    fun `合上时熄着要等用起来才交`() {
        val d = PanelSwitchDetector(inner)
        assertFalse(d.observe(off(cover)))
        assertTrue(d.observe(on(cover)))
        assertEquals(PanelSwitch(inner, cover), d.take())
    }

    @Test
    fun `没用起来又换回原来那块屏就当没换过`() {
        val d = PanelSwitchDetector(inner)
        d.observe(off(cover))
        assertFalse(d.observe(on(inner)))
        assertNull(d.take())
    }

    @Test
    fun `展开再合上各交一次`() {
        val d = PanelSwitchDetector(inner)
        d.observe(on(cover))
        assertEquals(PanelSwitch(inner, cover), d.take())
        assertTrue(d.observe(on(inner)))
        assertEquals(PanelSwitch(cover, inner), d.take())
    }

    @Test
    fun `接着盘上的记录认出进程起来前就换过屏`() {
        val d = PanelSwitchDetector(settled = inner)
        assertTrue(d.observe(on(cover)))
        assertEquals(PanelSwitch(inner, cover), d.take())
    }

    @Test
    fun `没有记录时只认下当前这块屏`() {
        val d = PanelSwitchDetector(settled = null)
        assertTrue(d.observe(on(cover)))
        val first = d.take()
        assertEquals(PanelSwitch(null, cover), first)
        assertNull(first!!.target(mapOf(cover.key to RotationMode.FORCE_LANDSCAPE)))
    }

    @Test
    fun `没见过的外屏落到 0 度`() {
        assertEquals(RotationMode.FORCE_PORTRAIT, PanelSwitch(inner, cover).target(emptyMap()))
    }

    @Test
    fun `没见过的大屏不动`() {
        assertNull(PanelSwitch(cover, inner).target(emptyMap()))
    }

    @Test
    fun `见过的屏用它上一次的方向`() {
        val remembered = mapOf(
            inner.key to RotationMode.FORCE_LANDSCAPE,
            cover.key to RotationMode.FORCE_REVERSE_PORTRAIT,
        )
        assertEquals(RotationMode.FORCE_LANDSCAPE, PanelSwitch(cover, inner).target(remembered))
        assertEquals(RotationMode.FORCE_REVERSE_PORTRAIT, PanelSwitch(inner, cover).target(remembered))
    }

    @Test
    fun `切分辨率不当成外屏`() {
        val fhd = Panel.of(1440, 3120)
        val hd = Panel.of(1080, 2340)
        assertFalse(hd.isCoverOf(fhd))
        assertNull(PanelSwitch(fhd, hd).target(emptyMap()))
    }

    @Test
    fun `Flip 内屏不会被当成外屏`() {
        val flipInner = Panel.of(1080, 2640)
        val flipCover = Panel.of(748, 720)
        assertEquals(RotationMode.FORCE_PORTRAIT, PanelSwitch(flipInner, flipCover).target(emptyMap()))
        assertNull(PanelSwitch(flipCover, flipInner).target(emptyMap()))
    }
}
