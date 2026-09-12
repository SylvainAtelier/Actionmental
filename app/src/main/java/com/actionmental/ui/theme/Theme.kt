package com.actionmental.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actionmental.data.UserSettings
import com.actionmental.ui.i18n.LocalAppLanguage

/**
 * 设计令牌（assets/design-tokens.css 的 1:1 移植）。
 *
 * Material3 的 ColorScheme 表达不了「键帽面 / 硬阴影 / 三级墨色」这类语义，
 * 所以另开一份 [AmColors]，两者并存：M3 负责组件默认值，AmColors 负责设计语言。
 */
@Immutable
data class AmColors(
    val bg: Color,
    val bgScreen: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val line: Color,
    val lineSoft: Color,
    val shadowSolid: Color,
    val ink: Color,
    val inkMid: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentInk: Color,
    val accentBg: Color,
    val accentLine: Color,
    val ok: Color,
    val warn: Color,
    val keycapFace: Color,
    val keycapLine: Color,
    val keycapShadow: Color,
)

val LightAmColors = AmColors(
    bg = Color(0xFFE8E4DC),
    bgScreen = Color(0xFFEFEBE2),
    surface = Color(0xFFFFFDF8),
    surfaceSunken = Color(0xFFF4F0E6),
    line = Color(0xFFDED8CA),
    lineSoft = Color(0xFFEAE4D7),
    shadowSolid = Color(0xFFE2DCCE),
    ink = Color(0xFF1B1917),
    inkMid = Color(0xFF57514A),
    inkMuted = Color(0xFF8A837A),
    inkFaint = Color(0xFFB4ADA2),
    accent = Color(0xFFC8452B),
    accentInk = Color(0xFF8A2C16),
    accentBg = Color(0xFFFFF3EF),
    accentLine = Color(0xFFE8BCB0),
    ok = Color(0xFF2F7A4F),
    warn = Color(0xFFB8791F),
    keycapFace = Color(0xFFF7F3EA),
    keycapLine = Color(0xFFD8D2C6),
    keycapShadow = Color(0xFFC9C2B4),
)

val DarkAmColors = AmColors(
    bg = Color(0xFF0F0E0D),
    bgScreen = Color(0xFF171614),
    surface = Color(0xFF201F1C),
    surfaceSunken = Color(0xFF221F1B),
    line = Color(0xFF302E29),
    lineSoft = Color(0xFF2A2822),
    shadowSolid = Color(0xFF100F0E),
    ink = Color(0xFFF2EEE6),
    inkMid = Color(0xFFA29A8E),
    inkMuted = Color(0xFF8A837A),
    inkFaint = Color(0xFF6B655C),
    accent = Color(0xFFE4653F),
    accentInk = Color(0xFFF2A98F),
    accentBg = Color(0xFF241A16),
    accentLine = Color(0xFF4A2C22),
    ok = Color(0xFF5FBF87),
    warn = Color(0xFFD8A23E),
    keycapFace = Color(0xFF2E2B26),
    keycapLine = Color(0xFF45403A),
    keycapShadow = Color(0xFF100F0E),
)

val LocalAmColors = staticCompositionLocalOf { LightAmColors }

/**
 * 字号阶梯直接对应设计规范第 3 节。
 * IBM Plex 若随包提供，把 FontFamily 换成 res/font 即可，其余不用动。
 */
object AmType {
    val mono = FontFamily.Monospace
    val ui = FontFamily.SansSerif

    val pageTitle = TextStyle(fontFamily = ui, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    val cardTitle = TextStyle(fontFamily = ui, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
    val secondary = TextStyle(fontFamily = ui, fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
    val data = TextStyle(fontFamily = mono, fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
    val label = TextStyle(
        fontFamily = ui, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 1.14.sp, lineHeight = 13.sp,
    )
    val keycap = TextStyle(fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val keycapLarge = TextStyle(fontFamily = mono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

object AmSpace {
    val s1 = 8.dp
    val s2 = 10.dp
    val s3 = 14.dp
    val s4 = 18.dp
    val screen = 18.dp
    val card = 13.dp
    val rowMin = 44.dp
}

object AmShape {
    val card = 12.dp
    val sheet = 18.dp
    val key = 6.dp
    val chip = 20.dp
    val iconBox = 9.dp
}

@Composable
fun ActionmentalTheme(
    theme: UserSettings.Theme = UserSettings.Theme.SYSTEM,
    language: UserSettings.Language = UserSettings.Language.CHINESE,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        UserSettings.Theme.LIGHT -> false
        UserSettings.Theme.DARK -> true
        UserSettings.Theme.SYSTEM -> isSystemInDarkTheme()
    }
    val am = if (dark) DarkAmColors else LightAmColors

    val scheme = if (dark) {
        darkColorScheme(
            primary = am.accent, onPrimary = Color.White,
            background = am.bgScreen, onBackground = am.ink,
            surface = am.surface, onSurface = am.ink,
            surfaceVariant = am.surfaceSunken, onSurfaceVariant = am.inkMid,
            outline = am.line, error = am.accent,
        )
    } else {
        lightColorScheme(
            primary = am.accent, onPrimary = Color.White,
            background = am.bgScreen, onBackground = am.ink,
            surface = am.surface, onSurface = am.ink,
            surfaceVariant = am.surfaceSunken, onSurfaceVariant = am.inkMid,
            outline = am.line, error = am.accent,
        )
    }

    CompositionLocalProvider(
        LocalAmColors provides am,
        LocalAppLanguage provides language,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                titleLarge = AmType.pageTitle,
                titleMedium = AmType.cardTitle,
                bodyMedium = AmType.body,
                bodySmall = AmType.secondary,
                labelSmall = AmType.label,
            ),
            content = content,
        )
    }
}

val amColors: AmColors
    @Composable @ReadOnlyComposable get() = LocalAmColors.current
