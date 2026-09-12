package com.actionmental.core.status

import com.actionmental.core.awake.ScreenAwakeState
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.rotation.AppRotationRule
import com.actionmental.core.rotation.RotationState
import com.actionmental.platform.shizuku.ShizukuStatus

/**
 * 首页与磁贴共用的一份系统状态快照（PRD 13 / 23 / 24）。
 *
 * 所有页面订阅同一个流，不各自查询、不各自缓存。
 */
data class SystemStatus(
    /** 系统设置里的开关，判断「有没有授权」只能看这个。 */
    val accessibilityEnabledInSettings: Boolean = false,
    /** 服务实例是否已绑到本进程，决定「现在能不能执行动作」。 */
    val accessibilityConnected: Boolean = false,
    /** 服务在 enabled_accessibility_services 那一串里。 */
    val accessibilityListedInSettings: Boolean = false,
    /** 系统的无障碍总开关。关着时列表写得再对，服务也不会被绑定。 */
    val accessibilityMasterSwitchOn: Boolean = false,
    val shizuku: ShizukuStatus = ShizukuStatus(),
    val keyboards: List<KeyboardDevice> = emptyList(),
    val rotation: RotationState = RotationState.unknown("尚未查询"),
    val screenAwake: ScreenAwakeState = ScreenAwakeState(),
    val shortcutCount: Int = 0,
    val disabledCount: Int = 0,
    val ruleCount: Int = 0,
    val remapCount: Int = 0,
    val foregroundPackage: String? = null,
    val activeRule: AppRotationRule? = null,
) {
    /** 已授权但服务实例还没回连：常见于进程被系统回收后重启。 */
    val accessibilityWaitingForBind: Boolean
        get() = accessibilityEnabledInSettings && !accessibilityConnected

    /**
     * 服务在列表里，总开关却是关的。
     *
     * 这是「看起来已授权、实际永远连不上」的那一种：系统设置界面照样把开关画成打开，
     * 但 accessibility_enabled = 0 时一个服务都不会被绑定。得单独说，
     * 否则用户只会看到「未开启」，然后去设置里发现它明明是开着的。
     */
    val accessibilityMasterSwitchOff: Boolean
        get() = accessibilityListedInSettings && !accessibilityMasterSwitchOn

    val keyboardConnected: Boolean get() = keyboards.isNotEmpty()
    val primaryKeyboard: KeyboardDevice? get() = keyboards.firstOrNull()

    val rotationControlHealth: Health
        get() = when {
            !shizuku.usable -> Health.UNAVAILABLE
            rotation.mode == com.actionmental.core.rotation.RotationMode.UNKNOWN -> Health.ERROR
            else -> Health.OK
        }

    enum class Health(val label: String) { OK("正常"), UNAVAILABLE("不可用"), ERROR("异常") }
}
