package com.actionmental

import com.actionmental.core.rotation.Panel
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.rotation.ScreenProfile
import com.actionmental.core.rotation.edit
import com.actionmental.core.rotation.observe
import com.actionmental.core.rotation.ordered
import com.actionmental.core.rotation.profileOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每块屏一份配置：认屏按长宽比，没见过的外屏落到 0° 且不套应用规则，见过的屏用它自己的。 */
class ScreenProfileTest {

    // OPPO Find N3：内屏接近方形，外屏窄长
    private val inner = Panel.of(2248, 2480)
    private val cover = Panel.of(1140, 2616)

    @Test
    fun `横竖报出来的尺寸是同一块屏`() {
        assertEquals(Panel.of(2480, 2248), inner)
    }

    @Test
    fun `第一次见到的屏沿用当前方向并套用规则`() {
        val (screens, profile) = emptyList<ScreenProfile>().observe(inner, 7.8f, RotationMode.FORCE_LANDSCAPE)
        assertEquals(RotationMode.FORCE_LANDSCAPE, profile.mode)
        assertTrue(profile.appRules)
        assertEquals(listOf(profile), screens)
    }

    @Test
    fun `没见过的外屏落到 0 度且不套应用规则`() {
        val known = emptyList<ScreenProfile>().observe(inner, null, RotationMode.FORCE_LANDSCAPE).first
        val (screens, profile) = known.observe(cover, 6.3f, RotationMode.FORCE_LANDSCAPE)
        assertEquals(RotationMode.FORCE_PORTRAIT, profile.mode)
        assertFalse(profile.appRules)
        assertEquals(2, screens.size)
    }

    @Test
    fun `没见过的大屏沿用当前方向`() {
        val known = emptyList<ScreenProfile>().observe(cover, null, RotationMode.FORCE_PORTRAIT).first
        val profile = known.observe(inner, null, RotationMode.FORCE_PORTRAIT).second
        assertEquals(RotationMode.FORCE_PORTRAIT, profile.mode)
        assertTrue(profile.appRules)
    }

    @Test
    fun `见过的屏用它自己的配置`() {
        val screens = listOf(
            ScreenProfile(inner, 7.8f, RotationMode.FORCE_LANDSCAPE, appRules = true),
            ScreenProfile(cover, 6.3f, RotationMode.FORCE_REVERSE_PORTRAIT, appRules = false),
        )
        val (same, profile) = screens.observe(cover, 6.3f, RotationMode.FORCE_LANDSCAPE)
        assertEquals(RotationMode.FORCE_REVERSE_PORTRAIT, profile.mode)
        assertEquals(screens, same)
    }

    @Test
    fun `取不到 dpi 时保留上次的尺寸`() {
        val screens = listOf(ScreenProfile(inner, 7.8f, RotationMode.FORCE_LANDSCAPE, appRules = true))
        assertEquals(7.8f, screens.observe(inner, null, RotationMode.NORMAL).second.inches)
    }

    @Test
    fun `切分辨率还是同一块屏，只更新尺寸`() {
        val fhd = Panel.of(1440, 3120)
        val hd = Panel.of(1080, 2340)
        assertTrue(hd.sameScreen(fhd))
        assertFalse(hd.isCoverOf(fhd))
        val screens = listOf(ScreenProfile(fhd, null, RotationMode.FORCE_LANDSCAPE, appRules = true))
        val (updated, profile) = screens.observe(hd, null, RotationMode.NORMAL)
        assertEquals(1, updated.size)
        assertEquals(hd, profile.panel)
        assertEquals(RotationMode.FORCE_LANDSCAPE, profile.mode)
    }

    @Test
    fun `Flip 内屏不会被当成外屏`() {
        val flipInner = Panel.of(1080, 2640)
        val flipCover = Panel.of(748, 720)
        assertTrue(flipCover.isCoverOf(flipInner))
        assertFalse(flipInner.isCoverOf(flipCover))
    }

    @Test
    fun `只改指定那块屏`() {
        val screens = listOf(
            ScreenProfile(inner, null, RotationMode.FORCE_LANDSCAPE, appRules = true),
            ScreenProfile(cover, null, RotationMode.FORCE_PORTRAIT, appRules = false),
        )
        val edited = screens.edit(cover) { it.copy(appRules = true) }
        assertTrue(edited.profileOf(cover)!!.appRules)
        assertSame(screens[0], edited[0])
        assertNull(emptyList<ScreenProfile>().profileOf(cover))
    }

    @Test
    fun `按面积从大到小排，最大的是内屏`() {
        val screens = listOf(
            ScreenProfile(cover, null, RotationMode.FORCE_PORTRAIT, appRules = false),
            ScreenProfile(inner, null, RotationMode.FORCE_LANDSCAPE, appRules = true),
        )
        assertEquals(listOf(inner, cover), screens.ordered().map { it.panel })
    }
}
