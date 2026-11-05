package com.actionmental.core.rotation

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 默认屏此刻背后的那块物理面板。
 *
 * 折叠屏合上、展开时，display 0 不换编号，换的是背后的面板：`Display.Mode` 的物理尺寸
 * 跟着变成另一块屏的。尺寸按自然方向给出，与此刻转没转无关 —— 转屏不会被误认成换屏。
 * 存的是短边、长边，同一块屏横竖怎么报都是同一个值。会落盘，改字段就是改存档格式。
 */
@Serializable
data class Panel(val short: Int, val long: Int) {
    private val area get() = short.toLong() * long

    /** 记忆表里的键。 */
    val key: String get() = "${short}x$long"

    /**
     * 这块是不是比 [other] 小的另一块屏。
     *
     * 只比面积不够：部分机型能在设置里切分辨率（FHD+ / HD+），同一块屏的物理尺寸也会变小。
     * 内外屏的长宽比差得很远（Fold 内屏接近方形，Flip 外屏更是），切分辨率则不改长宽比。
     */
    fun isCoverOf(other: Panel): Boolean {
        if (area >= other.area) return false
        val ratio = long.toFloat() / short
        val otherRatio = other.long.toFloat() / other.short
        return abs(ratio - otherRatio) / otherRatio > ASPECT_TOLERANCE
    }

    companion object {
        private const val ASPECT_TOLERANCE = 0.05f

        fun of(width: Int, height: Int) = Panel(min(width, height), max(width, height))
    }
}

/**
 * 取一次样。
 *
 * @param interactive 屏幕亮着且没锁：用户真的在用这块屏，而不是合上后熄着、或者停在锁屏上。
 */
data class PanelSample(val panel: Panel, val interactive: Boolean)

/**
 * 从 [from] 换到了 [to]，并且 [to] 已经被用起来了。
 *
 * [from] 是全局方向此刻还属于的那块屏；为 null 表示还不知道（刚打开开关），这一次只认下 [to]。
 */
data class PanelSwitch(val from: Panel?, val to: Panel) {
    /**
     * 换过来之后该用的全局方向；null 表示不动。
     *
     * 判据是**换屏的方向**而不是屏幕大小：用一个绝对阈值（比如 600dp）去分大小屏，
     * Flip 类机型的内屏本身就在阈值以下，会被当成外屏。
     * 见过的屏用它上一次的方向；没见过的外屏落到 0° —— 内屏配着键盘横着用，
     * 合上之后窄长的外屏跟着横过来几乎没法用。没见过的其他屏（包括切了分辨率）不动。
     */
    fun target(remembered: Map<String, RotationMode>): RotationMode? {
        if (from == null) return null
        return remembered[to.key] ?: RotationMode.FORCE_PORTRAIT.takeIf { to.isCoverOf(from) }
    }
}

/**
 * 认出「换到了另一块屏、并且用起来了」的那一刻。
 *
 * [settled] 是全局方向此刻属于的那块屏，跨进程靠盘上的记录接续 —— 进程死在口袋里、
 * 在外屏上被拉起来，也认得出换过屏。每次换屏只交出一次：用户在新屏上自己转过的方向，
 * 不该在下一次亮屏时被扳回来。中途换回原来那块屏，就当没换过。
 */
class PanelSwitchDetector(private var settled: Panel?) {
    private var current: Panel? = null

    /** @return true 表示有一次换屏在等着，而这块屏此刻正被用着。 */
    fun observe(sample: PanelSample): Boolean {
        current = sample.panel
        return sample.panel != settled && sample.interactive
    }

    /** 交出等着的那次换屏，并把它记为已落地；没有就是 null。 */
    fun take(): PanelSwitch? {
        val to = current ?: return null
        if (to == settled) return null
        return PanelSwitch(settled, to).also { settled = to }
    }
}
