package com.actionmental.core.rotation

import android.content.pm.ActivityInfo

/**
 * 旋转写入走的是哪一级。
 *
 * 同一个意图（「90° 横屏」）在不同级别上能做到的程度不一样，界面必须说清楚是哪一级在起作用，
 * 否则用户会以为「强制横屏」失灵了 —— 实际只是退到了只能锁定、压不住应用自带方向的那一级。
 */
enum class RotationTier(val label: String, val technical: String) {
    /** Shizuku：ignore-orientation-request + fix-to-user-rotation，应用自带方向也压得住，覆盖所有屏幕。 */
    SHELL("Shizuku · 完整强制", "SHELL"),

    /** 无障碍悬浮层的 screenOrientation：压得住应用自带方向，但部分大屏 / 折叠屏会忽略。 */
    OVERLAY("无障碍悬浮层 · 强制方向", "OVERLAY"),

    /** 只写 accelerometer_rotation / user_rotation：能锁定角度，应用自带方向仍然优先。 */
    SETTINGS("系统设置 · 仅锁定方向", "SETTINGS"),

    /** 读得到、写不了。 */
    NONE("只读 · 没有可用的写入通道", "NONE");

    val writable: Boolean get() = this != NONE
}

/** 悬浮层下发一次强制方向的结局。 */
enum class OverlayOutcome {
    /** 撤掉了（或本来就没挂）。 */
    REMOVED,

    /** 挂上了，而且屏幕真的转到了要求的角度。 */
    ADOPTED,

    /** 屏幕没转过去：系统忽略了这个窗口的方向要求。窗口已经撤掉，不留一个说谎的强制。 */
    IGNORED,

    /** 窗口挂不上（服务没连上、WindowManager 拒绝）。 */
    FAILED,
}

/**
 * 不经 shell 的旋转写入口。Shizuku 不在时 [RotationController] 靠它降级。
 *
 * 两样能力互相独立，各自可有可无：
 *  - 悬浮层：无障碍服务挂一个带 `screenOrientation` 的 1px 窗口。WindowManager 定方向时先看
 *    应用上层的窗口，所以它压得住应用自己声明的方向；
 *  - 系统设置：写 `accelerometer_rotation` / `user_rotation`。只能锁定，应用自带方向仍然优先。
 */
interface LocalRotationBackend {

    /** 悬浮层此刻挂不挂得上（无障碍服务已连接）。 */
    fun overlayAvailable(): Boolean

    /** 能不能写 system 表里的旋转设置（WRITE_SETTINGS 或 WRITE_SECURE_SETTINGS）。 */
    fun settingsWritable(): Boolean

    /**
     * 挂上（或换掉）强制方向的悬浮层，并等屏幕转过去；传 null 撤掉。
     *
     * 挂起是因为要等：窗口加上之后屏幕旋转是异步的，立刻回读一定还是旧角度。
     */
    suspend fun forceOverlay(surfaceRotation: Int?): OverlayOutcome

    /** 悬浮层此刻要求的 Surface 角度；没挂（或随旧服务实例作废了）返回 null。 */
    fun overlayRotation(): Int?

    /**
     * 写旋转设置。
     *
     * @param autoRotate accelerometer_rotation
     * @param userRotation 要锁定的 Surface 角度；null 表示不动 user_rotation
     */
    fun writeSettings(autoRotate: Boolean, userRotation: Int?): Result<Unit>
}

/**
 * Surface 角度 → 窗口的 `screenOrientation`。
 *
 * 不能直接把 90° 当成 LANDSCAPE：WindowManager 按设备的**自然方向**分配角度
 * （AOSP DisplayRotation.configure）。自然竖屏的手机上横屏是 90°，
 * 自然横屏的平板上横屏是 0°、竖屏是 270°。另有 `config_reverseDefaultRotation`
 * 会把 90° / 270° 对调 —— 它读不到，只能下发之后看屏幕实际转到哪，
 * 转反了就带着 [reversed] 再发一次（见平台实现）。0° / 180° 不受它影响。
 */
fun screenOrientationFor(surfaceRotation: Int, naturalLandscape: Boolean, reversed: Boolean = false): Int {
    val r = if (reversed && surfaceRotation % 2 == 1) (surfaceRotation + 2) % 4 else surfaceRotation
    return if (naturalLandscape) {
        when (r) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            1 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    } else {
        when (r) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }
}
