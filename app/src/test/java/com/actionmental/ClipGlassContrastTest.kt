package com.actionmental

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.actionmental.platform.ClipPalette
import com.actionmental.ui.theme.AmColors
import com.actionmental.ui.theme.DarkAmColors
import com.actionmental.ui.theme.LightAmColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glass card is translucent over content the panel cannot see. Composite it over the worst
 * cases (a pure black or pure white screen, then the glass scrim) and require AA for every ink tier.
 * Blending happens in gamma-encoded sRGB, as the compositor does.
 */
class ClipGlassContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private fun over(top: Color, alpha: Float, bottom: Color) = Color(
        red = top.red * alpha + bottom.red * (1 - alpha),
        green = top.green * alpha + bottom.green * (1 - alpha),
        blue = top.blue * alpha + bottom.blue * (1 - alpha),
    )

    private fun assertReadable(colors: AmColors, cardAlpha: Int, scrim: Int) {
        val scrimColor = Color(scrim)
        listOf(Color.Black, Color.White).forEach { content ->
            val behind = over(scrimColor.copy(alpha = 1f), scrimColor.alpha, content)
            val card = over(colors.surface, cardAlpha / 255f, behind)
            listOf(colors.ink, colors.inkMid, colors.inkMuted, colors.inkFaint).forEach { ink ->
                val contrast = ratio(ink, card)
                assertTrue("$ink on glass over $content: $contrast", contrast >= 4.5f)
            }
        }
    }

    @Test
    fun lightGlassKeepsAAOverAnyBackground() =
        assertReadable(LightAmColors, ClipPalette.LIGHT_GLASS_ALPHA, ClipPalette.LIGHT_GLASS_SCRIM)

    @Test
    fun darkGlassKeepsAAOverAnyBackground() =
        assertReadable(DarkAmColors, ClipPalette.DARK_GLASS_ALPHA, ClipPalette.DARK_GLASS_SCRIM)
}
