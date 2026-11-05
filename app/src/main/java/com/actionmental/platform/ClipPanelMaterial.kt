package com.actionmental.platform

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.actionmental.data.UserSettings
import com.actionmental.ui.theme.AmColors
import com.actionmental.ui.theme.amColorsFor
import java.util.function.Consumer

/**
 * 剪贴板面板的配色，取应用自己的设计令牌（[amColorsFor]，跟着用户选的强调色），不另起一套色值。
 *
 * 当前项要一眼认得出，靠的是三样东西叠在一起，而不是一块淡色：
 * 强调色调过的底（浅色 14%、深色 24%，深色底上同样的透明度显得更暗，所以加量）、
 * 左侧 3dp 的强调条、实心强调色的序号角标。三者任一种在色弱或强光下失效，另外两种仍然看得出。
 * 「淡」一档用 inkMuted 而不是 inkFaint：浅色下 inkFaint 落在白底上几乎看不见。
 *
 * [glass]：窗口背后有系统模糊时，卡片换成厚玻璃 —— 底色留一点透明，四周的遮罩改成玻璃的那一档。
 * 背后是什么看不到，所以透明度按最坏情况定：背后整屏纯黑或纯白时，各级墨色仍不低于 4.5:1
 * （见 ClipGlassContrastTest）。卡片里的输入框、选中行、按钮、菜单都还是实心的，只有卡片本身透。
 */
internal class ClipPalette private constructor(private val colors: AmColors, val dark: Boolean, val glass: Boolean) {
    val surface = colors.surface.toArgb()
    val field = colors.surfaceSunken.toArgb()
    val line = colors.line.toArgb()
    val ink = colors.ink.toArgb()
    val mid = colors.inkMid.toArgb()
    val faint = colors.inkMuted.toArgb()
    val accent = colors.accent.toArgb()
    val emphasis = colors.accentInk.toArgb()
    val error = amColorsFor(dark, UserSettings.Accent.TERRACOTTA).accentInk.toArgb()
    val onAccent = Color.WHITE

    /** 主按钮与应用里的 AmPrimaryButton 一致：墨色面、底色字、3dp 硬阴影。两种主题下对比度都在 12:1 以上。 */
    val primary = ink
    val onPrimary = colors.bgScreen.toArgb()
    val shadow = colors.shadowSolid.toArgb()

    /** 快捷操作图标的底：强调色淡淡一层。 */
    val accentSoft = ColorUtils.blendARGB(surface, accent, if (dark) 0.26f else 0.14f)
    val selected = ColorUtils.blendARGB(surface, accent, if (dark) 0.24f else 0.14f)
    val ripple = ColorUtils.setAlphaComponent(accent, if (dark) 0x40 else 0x2E)

    /**
     * 背后的遮罩。实心卡片：浅色下压得轻一点，否则整屏发灰。
     * 玻璃：浅色提亮成一层乳白，深色压暗；两者都把背后的极端亮度往卡片的底色拉，卡片才透得起来。
     */
    val backdrop = when {
        glass && dark -> DARK_GLASS_SCRIM
        glass -> LIGHT_GLASS_SCRIM
        dark -> 0x73000000
        else -> 0x33000000
    }

    /**
     * 卡片的底。玻璃卡片不投影：半透明的面上，阴影会从卡片中间透出来发脏；边界改由发丝线勾出，
     * 深色下这条线是一道淡淡的反光，浅色下仍是 line。
     */
    fun card(radius: Float, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (glass) ColorUtils.setAlphaComponent(surface, if (dark) DARK_GLASS_ALPHA else LIGHT_GLASS_ALPHA) else surface)
        setStroke(stroke, if (glass && dark) ColorUtils.setAlphaComponent(Color.WHITE, 0x1F) else line)
    }

    /** 系统中途关了模糊（省电模式等）：背后的内容变清晰，卡片必须退回实心。 */
    fun opaque() = ClipPalette(colors, dark, glass = false)

    companion object {
        /**
         * 玻璃卡片的不透明度，与遮罩成对取值；改任何一个都要让 ClipGlassContrastTest 通过。
         *
         * 卡片连同它底下那层遮罩，合起来的不透明度被可读性钉在九成左右，遮罩轻了卡片就得补上。
         * 所以遮罩取得很轻，让卡片四周的背景尽量透；卡片自己贴着对比度下限取值。
         */
        const val LIGHT_GLASS_ALPHA = 0xE4
        const val DARK_GLASS_ALPHA = 0xE1
        const val LIGHT_GLASS_SCRIM = 0x1AFFFFFF
        const val DARK_GLASS_SCRIM = 0x33000000

        fun of(dark: Boolean, accent: UserSettings.Accent, glass: Boolean): ClipPalette =
            ClipPalette(amColorsFor(dark, accent), dark, glass)

        /** 系统此刻允许跨窗口模糊吗。Android 12 以下没有，省电模式、部分机型与开发者选项也会关掉。 */
        fun blurAvailable(wm: WindowManager): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled
    }
}

/**
 * 面板窗口背后的模糊（FLAG_BLUR_BEHIND，Android 12 起才有）。只由 [attach] 在系统允许时建出来，
 * 每个方法仍各自判一次版本：低版本上加载这个类不碰到新接口，lint 也看得见守卫。
 *
 * 模糊半径和遮罩一起渐入，不在第一帧整屏一下糊掉；系统关了动画时直接到位。
 * 系统中途收回模糊时通知 onLost，由面板换回实心卡片。
 */
internal class PanelBlur private constructor(
    private val wm: WindowManager,
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val radius: Int,
    onLost: () -> Unit,
) {
    private val listener = Consumer<Boolean> { enabled -> if (!enabled) onLost() }
    private var animator: ValueAnimator? = null

    @RequiresApi(Build.VERSION_CODES.S)
    private fun start() {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        animator = ValueAnimator.ofInt(0, radius).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.blurBehindRadius = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
        // 注册时会先按当前状态回调一次：此刻可用，什么也不做
        wm.addCrossWindowBlurEnabledListener(listener)
    }

    /** 面板让开（上屏、系统权限框）时整屏不能还糊着；只改参数，由调用方提交窗口更新。 */
    fun show(visible: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        animator?.cancel()
        params.blurBehindRadius = if (visible) radius else 0
    }

    /** 撤掉模糊；只改参数，窗口还在的话由调用方提交更新。 */
    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        animator?.cancel()
        animator = null
        wm.removeCrossWindowBlurEnabledListener(listener)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.blurBehindRadius = 0
    }

    companion object {
        /** [view] 已经加进窗口之后调用；系统不允许模糊时是 null。 */
        fun attach(wm: WindowManager, view: View, params: WindowManager.LayoutParams, radius: Int, onLost: () -> Unit): PanelBlur? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !wm.isCrossWindowBlurEnabled) return null
            return PanelBlur(wm, view, params, radius, onLost).also { it.start() }
        }
    }
}
