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
        TERMUX_START_BLOCKED("系统拦下了 Termux 的启动 · 先打开一次 Termux，或在系统设置里允许它自启动与关联启动"),
        SYNC_INCOMPLETE("同步还没配置完整 · 缺本地文件夹、NAS 地址或共享名"),
        SYNC_STORAGE_DENIED("未授予「所有文件访问」权限 · 读不了本地文件夹"),
        SYNC_NO_PASSWORD("没有保存 NAS 密码 · 请在动作里重新输入"),
        SYNC_NETWORK("没有可用网络"),
        SYNC_WIFI_ONLY("当前不是 Wi-Fi · 已设置为仅 Wi-Fi 同步"),
        SYNC_RUNNING("已有同步正在进行"),
        ADB_NEEDS_WIRELESS("需要先开启无线调试 · 本应用经它向 adbd 发请求"),
        ADB_NOT_PAIRED("本应用还没与 adbd 配对 · 首次执行需要 Shizuku"),
        ADB_PAIRING_FAILED("与 adbd 配对失败"),
        ADB_NOT_LISTENING("adbd 没有在新端口上监听"),
        ADB_BUSY("上一次 ADB 端口切换还没结束"),
        NO_INPUT_FIELD("当前没有获得焦点的输入框"),
        KEYBOARD_HIDDEN("输入法键盘没有弹出来"),
        VOICE_KEY_NOT_FOUND("输入法键盘上没找到语音键"),
        NO_VOICE_IME("没有启用语音输入法 · 如 Google 语音输入"),
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
