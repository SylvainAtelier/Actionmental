package com.actionmental.core.clip

/**
 * 剪贴板面板卡片的版式换算，全用 dp。纯函数，单元测试不需要设备。
 *
 * 分不分栏只看窗口此刻有多宽，不看屏幕转没转：平板竖放、折叠屏展开、分屏与小窗都按同一把尺子量。
 * 留白随短边变宽是为了好看，不该反过来让本来放得下两栏的窗口退回单列。
 */
object PanelGeometry {

    /** 窄窗口的留白，也是分栏时最少留多少。 */
    const val COMPACT_GUTTER_DP = 24f

    /** 短边够大时的留白。 */
    const val WIDE_GUTTER_DP = 48f

    /** 短边不到这么多 dp 就用窄留白。 */
    const val WIDE_GUTTER_MIN_SHORT_SIDE_DP = 480f

    /** 卡片能有多宽（dp）时才分两栏：再窄右栏的两行预览就读不下一句话了。 */
    const val SPLIT_MIN_DP = 600f

    /** 留白按短边取：横屏时也是同一个数，四周看起来一样宽。 */
    fun gutter(widthDp: Float, heightDp: Float): Float =
        if (minOf(widthDp, heightDp) < WIDE_GUTTER_MIN_SHORT_SIDE_DP) COMPACT_GUTTER_DP else WIDE_GUTTER_DP

    /** [availWidthDp] 是避开系统栏之后的窗口宽度。留最少的白也放得下两栏就分。 */
    fun columns(availWidthDp: Float): Boolean = availWidthDp - COMPACT_GUTTER_DP * 2 >= SPLIT_MIN_DP

    /**
     * 卡片左右各留多少：宽窗口里卡片封顶 [maxCardDp] 居中；分栏时留白可以从 [gutterDp] 收窄，
     * 但不少于 [COMPACT_GUTTER_DP]，保证卡片至少有 [SPLIT_MIN_DP] 宽。
     */
    fun side(availWidthDp: Float, gutterDp: Float, maxCardDp: Float, columns: Boolean): Float {
        val centered = maxOf(gutterDp, (availWidthDp - maxCardDp) / 2)
        if (!columns) return centered
        return minOf(centered, maxOf(COMPACT_GUTTER_DP, (availWidthDp - SPLIT_MIN_DP) / 2))
    }
}
