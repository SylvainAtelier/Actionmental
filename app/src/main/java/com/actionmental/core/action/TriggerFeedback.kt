package com.actionmental.core.action

/**
 * 快捷键触发后浮在屏幕顶部的那一枚提示。
 *
 * 只给「按下去看不出结果」的动作：启动应用要确认拉起的是哪一个，
 * 复制地址要看清剪贴板里到底是什么，Termux 命令在后台跑、要确认已经发出去了。导航、音量、旋转这类动作本身就是反馈，
 * 再叠一层提示只会挡视线。
 *
 * [value] 是要逐字核对的内容（IP、端口），界面用等宽字体单独排；
 * [packageName] 用来取应用图标，认图标比读名字快。
 */
data class TriggerFeedback(
    val title: String,
    val value: String? = null,
    val packageName: String? = null,
    val failed: Boolean = false,
) {
    /** 要核对的内容与失败原因多留一会儿：一串数字扫一眼是记不住的。 */
    val holdMs: Long get() = if (value != null || failed) LONG_HOLD_MS else SHORT_HOLD_MS

    companion object {
        const val SHORT_HOLD_MS = 1_400L
        const val LONG_HOLD_MS = 2_400L
        private const val TERMUX_PACKAGE = "com.termux"

        /** 其余动作返回 null，不提示。[translate] 把文案换成界面语言。 */
        fun of(action: Action, result: ActionResult, translate: (String) -> String): TriggerFeedback? =
            when (action) {
                is Action.LaunchApp -> when (result) {
                    is ActionResult.Ok -> TriggerFeedback(action.appLabel, packageName = action.packageName)
                    is ActionResult.Failed -> TriggerFeedback(
                        action.appLabel,
                        value = translate(result.reason.message),
                        packageName = action.packageName,
                        failed = true,
                    )
                }
                is Action.WirelessDebug -> when (result) {
                    is ActionResult.Ok -> result.copied?.let { TriggerFeedback(translate("已复制"), value = it) }
                    is ActionResult.Failed -> TriggerFeedback(translate(result.reason.message), failed = true)
                }
                // 带 Termux 的图标：按下去立刻知道命令发出去了，结果稍后走通知
                is Action.Termux -> when (result) {
                    is ActionResult.Ok -> TriggerFeedback(action.displayName, packageName = TERMUX_PACKAGE)
                    is ActionResult.Failed -> TriggerFeedback(
                        action.displayName,
                        value = translate(result.reason.message),
                        packageName = TERMUX_PACKAGE,
                        failed = true,
                    )
                }
                // 面板本身就是反馈；只有没开出来时要说清为什么
                is Action.ClipboardHistory -> (result as? ActionResult.Failed)?.let {
                    TriggerFeedback(translate(action.label), value = translate(it.message), failed = true)
                }
                else -> null
            }
    }
}
