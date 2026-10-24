package com.actionmental

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.actionmental.ui.theme.DarkAmColors
import com.actionmental.ui.theme.LightAmColors
import com.actionmental.ui.theme.contrastingForeground
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test
    fun normalTextMeetsAAOnEveryNeutralSurface() {
        listOf(LightAmColors, DarkAmColors).forEach { colors ->
            val foregrounds = listOf(colors.ink, colors.inkMid, colors.inkMuted, colors.inkFaint)
            val backgrounds = listOf(colors.bg, colors.bgScreen, colors.surface, colors.surfaceSunken, colors.keycapFace)
            foregrounds.forEach { foreground ->
                backgrounds.forEach { background ->
                    val contrast = ratio(foreground, background)
                    assertTrue("$foreground on $background: $contrast", contrast >= 4.5f)
                }
            }
        }
    }

    @Test
    fun accentForegroundMeetsAAAcrossLuminanceRange() {
        listOf(LightAmColors, DarkAmColors).forEach { colors ->
            assertTrue(ratio(colors.onAccent, colors.accent) >= 4.5f)
        }
        // Include the midpoint where neither a fixed white nor fixed black is safe.
        for (channel in 0..255) {
            val background = Color(channel, channel, channel)
            assertTrue(ratio(contrastingForeground(background), background) >= 4.5f)
        }
        listOf(0xFF3568B0, 0xFF398165, 0xFF8C5BB5, 0xFFE4653F).forEach { argb ->
            val background = Color(argb)
            assertTrue(ratio(contrastingForeground(background), background) >= 4.5f)
        }
    }
}
