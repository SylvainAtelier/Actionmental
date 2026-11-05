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
    val area: Long get() = short.toLong() * long
    private val aspect get() = long.toFloat() / short

    /**
     * 是不是同一块屏。
     *
     * 只比像素不够：部分机型能在设置里切分辨率（FHD+ / HD+），同一块屏的物理尺寸也会变。
     * 切分辨率不改长宽比，而内外屏的长宽比差得很远（Fold 内屏接近方形，Flip 外屏更是）。
     */
    fun sameScreen(other: Panel): Boolean = abs(aspect - other.aspect) / other.aspect <= ASPECT_TOLERANCE

    /** 这块是不是比 [other] 小的另一块屏。 */
    fun isCoverOf(other: Panel): Boolean = area < other.area && !sameScreen(other)

    companion object {
        private const val ASPECT_TOLERANCE = 0.05f

        fun of(width: Int, height: Int) = Panel(min(width, height), max(width, height))
    }
}

/**
 * 一块屏自己的方向配置。
 *
 * 当前这块屏的 [mode] 与全局意图（`UserSettings.globalRotationMode`）在同一次写盘里更新，
 * 所以它永远是「这块屏上最后一次设下的方向」—— 换屏时只需要读，不需要在离开时补记。
 *
 * @param inches 对角线英寸，见到这块屏时按 dpi 算出来的，给人认屏用；算不出来是 null。
 * @param appRules 在这块屏上套用应用规则。外屏默认不套：规则是为内屏接键盘准备的。
 */
@Serializable
data class ScreenProfile(
    val panel: Panel,
    val inches: Float? = null,
    val mode: RotationMode,
    val appRules: Boolean,
)

/** [panel] 对应的那一份配置。 */
fun List<ScreenProfile>.profileOf(panel: Panel): ScreenProfile? = firstOrNull { it.panel.sameScreen(panel) }

/**
 * 看到了 [panel]：把它记进表里（没见过就新建，切了分辨率就更新尺寸），返回新表与它的配置。
 *
 * 没见过的屏：比已知的某块屏小、长宽比又不同，就是外屏 —— 落到 0° 竖屏、不套应用规则
 * （内屏配着键盘横着用，合上后窄长的外屏跟着横过来几乎没法用）。否则沿用 [current]，
 * 第一次见到时什么都不变。不用 600dp 一类绝对阈值：Flip 的内屏本身就在阈值以下。
 */
fun List<ScreenProfile>.observe(panel: Panel, inches: Float?, current: RotationMode): Pair<List<ScreenProfile>, ScreenProfile> {
    val known = profileOf(panel)
    if (known != null) {
        val seen = known.copy(panel = panel, inches = inches ?: known.inches)
        return map { if (it === known) seen else it } to seen
    }
    val cover = any { panel.isCoverOf(it.panel) }
    val fresh = ScreenProfile(
        panel = panel,
        inches = inches,
        mode = if (cover) RotationMode.FORCE_PORTRAIT else current,
        appRules = !cover,
    )
    return this + fresh to fresh
}

/** 把 [panel] 那块屏的某一项改掉；表里没有它就原样返回。 */
fun List<ScreenProfile>.edit(panel: Panel, change: (ScreenProfile) -> ScreenProfile): List<ScreenProfile> =
    map { if (it.panel.sameScreen(panel)) change(it) else it }

/** 按面积从大到小，最大的那块叫内屏。界面与认屏都用这个顺序。 */
fun List<ScreenProfile>.ordered(): List<ScreenProfile> = sortedByDescending { it.panel.area }
