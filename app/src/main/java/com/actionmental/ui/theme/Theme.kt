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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.ColorUtils
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
) {
    val onAccent: Color get() = contrastingForeground(accent)
}

/** Select an opaque foreground meeting AA on any solid accent color. */
fun contrastingForeground(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

val LightAmColors = AmColors(
    bg = Color(0xFFE8E4DC),
    bgScreen = Color(0xFFEFEBE2),
    surface = Color(0xFFFFFDF8),
    surfaceSunken = Color(0xFFF4F0E6),
    line = Color(0xFFDED8CA),
    lineSoft = Color(0xFFEAE4D7),
    shadowSolid = Color(0xFFE2DCCE),
    ink = Color(0xFF1B1917),
    // 三级墨色在卡片面上分别 ≥ 9 / 6.4 / 4.6 : 1，最淡的一级也过 WCAG AA 正文线
    inkMid = Color(0xFF4A453F),
    inkMuted = Color(0xFF635D54),
    inkFaint = Color(0xFF6C655C),
    accent = Color(0xFFC8452B),
    accentInk = Color(0xFF8A2C16),
    accentBg = Color(0xFFFFF3EF),
    accentLine = Color(0xFFE8BCB0),
    ok = Color(0xFF2F7A4F),
    warn = Color(0xFF96610F),
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
    inkMid = Color(0xFFC8C1B6),
    inkMuted = Color(0xFFB3ACA0),
    inkFaint = Color(0xFFA39B8F),
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

/**
 * 某个主题、某个强调色下的整套令牌。只换强调色那四个，其余照旧。
 *
 * 默认的赤陶直接用上面手调的两套；别的颜色从种子推，推法是照着赤陶那两套手调值反算出来的：
 * - 浅色：强调色就是种子；墨色压到种子明度的 0.65；底与边框是种子在卡片面上 5% / 33% 的混色；
 * - 深色：同色相提亮到 0.52～0.62（深底上要亮一档才压得住，再亮白字就看不清了），饱和度略收；
 *   墨色提到明度 0.75；底与边框是它在页面底色上 7% / 25% 的混色。
 */
fun amColorsFor(dark: Boolean, accent: UserSettings.Accent): AmColors {
    val base = if (dark) DarkAmColors else LightAmColors
    if (accent == UserSettings.Accent.TERRACOTTA) return base
    return accentCache.getOrPut(dark to accent) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent.seed.toInt(), hsl)
        val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        if (dark) {
            val main = ColorUtils.HSLToColor(floatArrayOf(h, s * 0.9f, (l + 0.12f).coerceIn(0.52f, 0.62f)))
            base.copy(
                accent = Color(main),
                accentInk = Color(ColorUtils.HSLToColor(floatArrayOf(h, s * 0.8f, 0.75f))),
                accentBg = Color(ColorUtils.blendARGB(base.bgScreen.toArgb(), main, 0.07f)),
                accentLine = Color(ColorUtils.blendARGB(base.bgScreen.toArgb(), main, 0.25f)),
            )
        } else {
            val main = accent.seed.toInt()
            base.copy(
                accent = Color(main),
                accentInk = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, l * 0.65f))),
                accentBg = Color(ColorUtils.blendARGB(base.surface.toArgb(), main, 0.05f)),
                accentLine = Color(ColorUtils.blendARGB(base.surface.toArgb(), main, 0.33f)),
            )
        }
    }
}

private val accentCache = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, UserSettings.Accent>, AmColors>()

val LocalAmColors = staticCompositionLocalOf { LightAmColors }

/**
 * 字号阶梯：正文 14sp、最小 11sp（只给全大写技术标签），行高约 1.4 倍。
 * 原来正文 12.5sp、标签 9.5sp，在手机上要凑近才看得清，系统放大字体后层级也挤成一团。
 * IBM Plex 若随包提供，把 FontFamily 换成 res/font 即可，其余不用动。
 */
object AmType {
    val mono = FontFamily.Monospace
    val ui = FontFamily.SansSerif

    val pageTitle = TextStyle(fontFamily = ui, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val cardTitle = TextStyle(fontFamily = ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = ui, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val secondary = TextStyle(fontFamily = ui, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val data = TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp)
    val label = TextStyle(
        fontFamily = ui, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp, lineHeight = 15.sp,
    )
    val keycap = TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val keycapLarge = TextStyle(fontFamily = mono, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

/** 4dp 网格；屏幕边距 16dp（Material 手机标准），列表行与按钮触控高度 48dp。 */
object AmSpace {
    val s1 = 8.dp
    val s2 = 12.dp
    val s3 = 16.dp
    val s4 = 20.dp
    val screen = 16.dp
    val card = 16.dp
    val rowMin = 48.dp

    /** 单栏页面的最大正文宽度：平板上不让卡片拉成一条横线。 */
    val contentMax = 840.dp
}

object AmShape {
    val card = 12.dp
    val sheet = 18.dp
    val key = 6.dp
    val chip = 20.dp
    val iconBox = 10.dp
}

@Composable
fun ActionmentalTheme(
    theme: UserSettings.Theme = UserSettings.Theme.SYSTEM,
    language: UserSettings.Language = UserSettings.Language.CHINESE,
    accent: UserSettings.Accent = UserSettings.Accent.TERRACOTTA,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        UserSettings.Theme.LIGHT -> false
        UserSettings.Theme.DARK -> true
        UserSettings.Theme.SYSTEM -> isSystemInDarkTheme()
    }
    val am = amColorsFor(dark, accent)
    // 出错永远是红的：强调色换成蓝、绿之后，错误提示不能跟着变成「好看」的颜色
    val errorRed = (if (dark) DarkAmColors else LightAmColors).accent

    val scheme = if (dark) {
        darkColorScheme(
            primary = am.accent, onPrimary = am.onAccent,
            background = am.bgScreen, onBackground = am.ink,
            surface = am.surface, onSurface = am.ink,
            surfaceVariant = am.surfaceSunken, onSurfaceVariant = am.inkMid,
            outline = am.inkFaint, error = errorRed, onError = contrastingForeground(errorRed),
        )
    } else {
        lightColorScheme(
            primary = am.accent, onPrimary = am.onAccent,
            background = am.bgScreen, onBackground = am.ink,
            surface = am.surface, onSurface = am.ink,
            surfaceVariant = am.surfaceSunken, onSurfaceVariant = am.inkMid,
            outline = am.inkFaint, error = errorRed, onError = contrastingForeground(errorRed),
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
