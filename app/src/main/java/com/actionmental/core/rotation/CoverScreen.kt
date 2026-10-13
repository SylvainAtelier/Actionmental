package com.actionmental.core.rotation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 默认屏此刻背后的那块物理面板。
 *
 * 折叠屏合上、展开时，display 0 不换编号，换的是背后的面板：`Display.Mode` 的物理尺寸
 * 跟着变成另一块屏的。尺寸按自然方向给出，与此刻转没转无关 —— 转屏不会被误认成换屏。
 *
 * @param interactive 屏幕亮着且没锁：用户真的在用这块屏，而不是合上后熄着、或者停在锁屏上。
 */
data class PanelSample(val width: Int, val height: Int, val interactive: Boolean) {
    private val short get() = min(width, height)
    private val long get() = max(width, height)
    private val area get() = width.toLong() * height

    fun samePanel(other: PanelSample): Boolean = short == other.short && long == other.long

    /**
     * 这块是不是比 [other] 小的另一块屏。
     *
     * 只比面积不够：部分机型能在设置里切分辨率（FHD+ / HD+），同一块屏的物理尺寸也会变小。
     * 内外屏的长宽比差得很远（Fold 内屏接近方形，Flip 外屏更是），切分辨率则不改长宽比。
     */
    fun isCoverOf(other: PanelSample): Boolean {
        if (area >= other.area) return false
        val ratio = long.toFloat() / short
        val otherRatio = other.long.toFloat() / other.short
        return abs(ratio - otherRatio) / otherRatio > ASPECT_TOLERANCE
    }

    private companion object {
        const val ASPECT_TOLERANCE = 0.05f
    }
}

/**
 * 认出「合上折叠屏、在外屏上继续用」的那一刻。
 *
 * 判据是**换屏的方向**而不是屏幕大小：大屏换到小屏就是进了外屏，反过来就是展开了。
 * 用一个绝对阈值（比如 600dp）去分大小屏，Flip 类机型的内屏本身就在阈值以下，会被当成外屏。
 * 代价是进程刚起来时若已经在外屏上，要等展开再合上一次才认得出 —— 没见过另一块屏，就无从比较。
 *
 * 每一段外屏只报一次：用户在外屏上自己转回横屏，不该在下一次亮屏时被扳回来。
 */
class CoverScreenDetector {
    private var last: PanelSample? = null

    /** 此刻在外屏上（相对于见过的另一块屏）。 */
    var onCover = false
        private set

    private var handled = false

    /**
     * 记下这一刻的面板。
     *
     * @return true 表示刚刚确认「在外屏上被用起来了」，这一段外屏里只返回这一次。
     */
    fun observe(sample: PanelSample): Boolean {
        val prev = last
        last = sample
        if (prev != null && !prev.samePanel(sample)) {
            when {
                sample.isCoverOf(prev) -> {
                    onCover = true
                    handled = false
                }
                prev.isCoverOf(sample) -> {
                    onCover = false
                    handled = false
                }
                // 同一块屏切了分辨率：不算换屏
            }
        }
        if (!onCover || handled || !sample.interactive) return false
        handled = true
        return true
    }

    /** 报出去之后没能落地（复核时屏幕又熄了）：这一段外屏还欠一次，下一次亮起来再报。 */
    fun rearm() {
        if (onCover) handled = false
    }
}
