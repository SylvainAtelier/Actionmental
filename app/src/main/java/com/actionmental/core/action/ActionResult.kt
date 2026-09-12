package com.actionmental.core.action

/** 动作执行结果。系统不得静默失败（PRD 25 / 35.14）。 */
sealed interface ActionResult {

    data class Ok(val detail: String = "") : ActionResult

    data class Failed(val reason: Reason, val detail: String = "") : ActionResult

    enum class Reason(val message: String) {
        SHIZUKU_NOT_INSTALLED("Shizuku 未安装"),
        SHIZUKU_NOT_RUNNING("Shizuku 未运行"),
        SHIZUKU_DENIED("Shizuku 未授权"),
        ACCESSIBILITY_OFF("无障碍键盘服务未开启"),
        TARGET_NOT_FOUND("目标应用不存在"),
        UNSUPPORTED("系统不支持该操作"),
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
