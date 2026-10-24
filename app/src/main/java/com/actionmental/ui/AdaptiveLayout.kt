package com.actionmental.ui

/** Window decisions use the space left after navigation, not device categories. */
object AdaptiveLayout {
    const val RAIL_WIDTH = 88f
    const val SIDEBAR_WIDTH = 240f
    const val MIN_PANE_WIDTH = 360f

    fun navigationWidth(windowWidth: Float): Float = when {
        windowWidth >= 840f -> SIDEBAR_WIDTH
        windowWidth >= 600f -> RAIL_WIDTH
        else -> 0f
    }

    fun useTwoPanes(windowWidth: Float, fontScale: Float): Boolean {
        val available = windowWidth - navigationWidth(windowWidth) - 1f
        return available >= MIN_PANE_WIDTH * 2 * fontScale.coerceAtLeast(1f)
    }

    fun statusColumns(contentWidth: Float, fontScale: Float): Int {
        val readableWidth = contentWidth / fontScale.coerceAtLeast(1f)
        return when {
            readableWidth >= 640f -> 4
            readableWidth >= 300f -> 2
            else -> 1
        }
    }
}
