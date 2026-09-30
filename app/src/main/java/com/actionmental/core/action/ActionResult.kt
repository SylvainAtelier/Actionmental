package com.actionmental.core.action

/** 动作执行结果。系统不得静默失败（PRD 25 / 35.14）。 */
sealed interface ActionResult {

    /** [copied] 是这一次写进剪贴板的原文，触发提示要原样显示它，不从 [detail] 里反解。 */
    data class Ok(val detail: String = "", val copied: String? = null) : ActionResult

    data class Failed(val reason: Reason, val detail: String = "") : ActionResult

    enum class Reason(val message: String) {
        SHIZUKU_NOT_INSTALLED("Shizuku 未安装"),
        SHIZUKU_NOT_RUNNING("Shizuku 未运行"),
        SHIZUKU_DENIED("Shizuku 未授权"),
        ACCESSIBILITY_OFF("无障碍键盘服务未开启"),
        TARGET_NOT_FOUND("目标应用不存在"),
        UNSUPPORTED("系统不支持该操作"),
        WIRELESS_DEBUG_OFF("无线调试未开启"),
        WIFI_UNAVAILABLE("未连接 Wi-Fi"),
        TERMUX_NOT_INSTALLED("Termux 未安装"),
        TERMUX_PERMISSION_DENIED("未授予 Termux 执行权限"),
        TERMUX_OVERLAY_DENIED("Termux 缺少「显示在其他应用上层」权限"),
        EXECUTION_FAILED("执行失败"),
    }

    val succeeded: Boolean get() = this is Ok

    val message: String
        get() = when (this) {
            is Ok -> detail.ifBlank { "已执行" }
            is Failed -> if (detail.isBlank()) reason.message else reason.message + " · " + detail
        }

    companion object {
        val OK: ActionResult = Ok()
    }
}
