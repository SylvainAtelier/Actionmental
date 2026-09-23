package com.actionmental.core.rotation

import android.view.Surface
import kotlinx.serialization.Serializable

/**
 * PRD 8 建议的旋转模式。UNKNOWN 表示「查询不到」，绝不能被当成「已关闭」。
 *
 * 对外一律用角度表述（0° / 90° / 180° / 270°）：这是用户在设备上真正能看见的东西，
 * 「反向横屏」这类说法在不同 OEM 上指向不同方向，角度不会有歧义。
 * [technical] 保持原值不变，序列化与 shell 写入路径不受影响。
 */
@Serializable
enum class RotationMode(val angle: String, val shape: String, val technical: String) {
    NORMAL("", "系统默认", "NORMAL"),
    FORCE_PORTRAIT("0°", "竖屏", "FORCE_PORTRAIT"),
    FORCE_LANDSCAPE("90°", "横屏", "FORCE_LANDSCAPE"),
    FORCE_REVERSE_PORTRAIT("180°", "倒置竖屏", "FORCE_REVERSE_PORTRAIT"),
    FORCE_REVERSE_LANDSCAPE("270°", "反向横屏", "FORCE_REVERSE_LANDSCAPE"),
    CUSTOM("", "自动旋转关闭", "AUTO_ROTATE_OFF"),
    UNKNOWN("", "未知", "UNKNOWN");

    /** 单行展示名：强制方向带角度前缀，其余就是原来的说法。 */
    val label: String get() = if (angle.isEmpty()) shape else angle + " " + shape

    val isForced: Boolean
        get() = this == FORCE_LANDSCAPE || this == FORCE_REVERSE_LANDSCAPE ||
            this == FORCE_PORTRAIT || this == FORCE_REVERSE_PORTRAIT

    /** 对应 Settings.System.user_rotation 的取值。 */
    val surfaceRotation: Int?
        get() = when (this) {
            FORCE_PORTRAIT -> Surface.ROTATION_0
            FORCE_LANDSCAPE -> Surface.ROTATION_90
            FORCE_REVERSE_PORTRAIT -> Surface.ROTATION_180
            FORCE_REVERSE_LANDSCAPE -> Surface.ROTATION_270
            else -> null
        }

    /** 顺时针角度。非强制模式没有角度。 */
    val degrees: Int?
        get() = when (this) {
            FORCE_PORTRAIT -> 0
            FORCE_LANDSCAPE -> 90
            FORCE_REVERSE_PORTRAIT -> 180
            FORCE_REVERSE_LANDSCAPE -> 270
            else -> null
        }

    companion object {
        /** 按角度升序，界面上的方向盘与菜单都用这个顺序。 */
        val forced = listOf(FORCE_PORTRAIT, FORCE_LANDSCAPE, FORCE_REVERSE_PORTRAIT, FORCE_REVERSE_LANDSCAPE)

        /** 方向页与规则页共用的可选项：系统默认 + 四个角度。 */
        val selectable = listOf(NORMAL) + forced

        fun toggleTarget(current: RotationMode, selected: RotationMode): RotationMode =
            if (current == selected) CUSTOM else selected

        fun fromSurfaceRotation(rotation: Int): RotationMode = when (rotation) {
            Surface.ROTATION_0 -> FORCE_PORTRAIT
            Surface.ROTATION_90 -> FORCE_LANDSCAPE
            Surface.ROTATION_180 -> FORCE_REVERSE_PORTRAIT
            Surface.ROTATION_270 -> FORCE_REVERSE_LANDSCAPE
            else -> UNKNOWN
        }

        fun fromDegrees(degrees: Int): RotationMode? = forced.firstOrNull { it.degrees == degrees }
    }
}

/**
 * 一次真实系统查询的结果。所有字段都来自系统，不来自应用缓存（PRD 3.2 / 8）。
 */
data class RotationState(
    val mode: RotationMode,
    val userRotation: Int?,
    val accelerometerRotation: Int?,
    val ignoreAppRequest: Boolean?,
    val fixedToUserRotation: Int?,
    val verifiedAtMs: Long,
    val failure: String? = null,
    /** 此刻能用哪一级去写。读是不要特权的，所以「读得到」不代表「写得了」。 */
    val tier: RotationTier = RotationTier.NONE,
) {
    val available: Boolean get() = mode != RotationMode.UNKNOWN

    /** 磁贴与界面能不能下发写入：读得到，且有一级写得了。 */
    val writable: Boolean get() = available && tier.writable

    companion object {
        fun unknown(reason: String) = RotationState(
            mode = RotationMode.UNKNOWN,
            userRotation = null,
            accelerometerRotation = null,
            ignoreAppRequest = null,
            fixedToUserRotation = null,
            verifiedAtMs = System.currentTimeMillis(),
            failure = reason,
        )
    }
}
