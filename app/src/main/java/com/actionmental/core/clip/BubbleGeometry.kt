package com.actionmental.core.clip

/**
 * 悬浮按钮的位置换算。纯函数，单元测试不需要设备。
 *
 * 竖直位置存成「可用高度的比例」而不是像素：转屏、分屏、换分辨率之后，
 * 按钮还在用户放的那个相对位置，不会跑出屏幕。
 */
object BubbleGeometry {

    /** 松手时离哪边近就贴哪边。 */
    fun snapToRight(centerX: Int, screenWidth: Int): Boolean = centerX * 2 >= screenWidth

    /** 像素 → 比例。[top] / [bottom] 是上下要让开的边（状态栏、导航栏）。 */
    fun fractionOf(y: Int, screenHeight: Int, size: Int, top: Int, bottom: Int): Float {
        val span = screenHeight - top - bottom - size
        if (span <= 0) return 0f
        return ((y - top).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 比例 → 像素，永远落在上下边之间。 */
    fun yOf(fraction: Float, screenHeight: Int, size: Int, top: Int, bottom: Int): Int {
        val span = (screenHeight - top - bottom - size).coerceAtLeast(0)
        return top + (fraction.coerceIn(0f, 1f) * span).toInt()
    }
}
