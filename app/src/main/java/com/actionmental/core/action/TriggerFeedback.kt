package com.actionmental.core.action

import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncReport
import com.actionmental.core.sync.SyncSummary

/**
 * 快捷键触发后浮在屏幕顶部的那一枚提示。
 *
 * 只给「按下去看不出结果」的动作：启动应用要确认拉起的是哪一个，
 * 复制地址要看清剪贴板里到底是什么，Termux 命令在后台跑、要确认已经发出去了。导航、音量、旋转这类动作本身就是反馈，
 * 再叠一层提示只会挡视线。
 *
 * [value] 是要逐字核对的内容（IP、端口），界面用等宽字体单独排；
 * [packageName] 用来取应用图标，认图标比读名字快。
 *
 * 后台 Termux 命令要多两种样子，同一枚胶囊原地变过去，不另开窗口：
 * - 运行中（[pending]）：转圈加计时，不倒计时，等结果回来替换它；等太久就换成 [then]。
 * - 结果卡：[meta] 靠右放结论（耗时 / 退出码），[output] 是输出末尾几行，等宽排在下面。
 *   [succeeded] 才画对勾 —— 结论不只靠颜色说。
 */
data class TriggerFeedback(
    val title: String,
    val value: String? = null,
    val packageName: String? = null,
    val failed: Boolean = false,
    val succeeded: Boolean = false,
    val pending: Boolean = false,
    val meta: String? = null,
    val output: String? = null,
    /** 停留时间到了不淡出，而是换成这一条。 */
    val then: TriggerFeedback? = null,
) {
    /**
     * 要核对的内容与失败原因多留一会儿：一串数字扫一眼是记不住的。
     * 带输出时按行数加时间（最多 [OUTPUT_LINES] 行，自然封顶），失败的更长 —— 要读的是原因，不是扫一眼结论。
     */
    val holdMs: Long
        get() = when {
            pending -> RUNNING_HOLD_MS
            output != null -> {
                val lines = output.lines().size
                (if (failed) FAILED_OUTPUT_HOLD_MS else LONG_HOLD_MS) + lines * PER_LINE_MS
            }
            value != null || failed -> LONG_HOLD_MS
            else -> SHORT_HOLD_MS
        }

    companion object {
        const val SHORT_HOLD_MS = 1_400L
        const val LONG_HOLD_MS = 2_400L
        const val RUNNING_HOLD_MS = 30_000L
        const val FAILED_OUTPUT_HOLD_MS = 4_000L
        const val PER_LINE_MS = 500L

        /** 结果卡里的输出行数：再多就该去通知或终端里看了。 */
        const val OUTPUT_LINES = 4
        const val OUTPUT_CHARS = 400
        private const val TERMUX_PACKAGE = "com.termux"

        /**
         * 后台命令跑完的那一张。输出原样给，不过 [translate] —— 那是命令自己的话，
         * 翻译表做的是子串替换，会把输出里碰巧出现的中文也换掉。
         */
        fun of(name: String, outcome: TermuxOutcome, translate: (String) -> String): TriggerFeedback {
            val took = outcome.durationMs?.let(::seconds)
            val text = when {
                !outcome.executed -> outcome.errmsg.ifBlank { translate("未知错误") }
                outcome.succeeded -> outcome.stdout.ifBlank { outcome.stderr }
                else -> outcome.stderr.ifBlank { outcome.stdout }
            }
            val meta = when {
                !outcome.executed -> translate("Termux 拒绝执行")
                outcome.succeeded -> took.orEmpty()
                else -> listOfNotNull(translate("退出码") + " " + (outcome.exitCode ?: "?"), took).joinToString(" · ")
            }
            return TriggerFeedback(
                title = name,
                packageName = TERMUX_PACKAGE,
                failed = !outcome.succeeded,
                succeeded = outcome.succeeded,
                meta = meta,
                output = TermuxOutcome.tail(text, OUTPUT_LINES, OUTPUT_CHARS).ifBlank { null },
            )
        }

        /**
         * 文件夹同步跑完的那一张结果卡。和 Termux 的同一种样子：结论靠右，明细在下，成败不只靠颜色。
         * 路径与失败原因是文件系统与 NAS 自己的话，原样给，不过 [translate]。
         */
        fun of(config: SyncConfig, report: SyncReport, translate: (String) -> String): TriggerFeedback {
            val failures = SyncSummary.failures(report, limit = 3, t = translate)
            val line = SyncSummary.line(report, translate)
            val text = if (failures.isEmpty()) line else line + "\n" + failures
            return TriggerFeedback(
                title = config.displayName,
                failed = !report.succeeded,
                succeeded = report.succeeded,
                meta = SyncSummary.meta(report).ifBlank { null },
                output = TermuxOutcome.tail(text, OUTPUT_LINES, OUTPUT_CHARS).ifBlank { null },
            )
        }

        /** 计时的写法：10 秒内看得出零点几秒，再往后只数整秒，一分钟以上带分。 */
        fun seconds(ms: Long): String = when {
            ms < 10_000 -> "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)
            ms < 60_000 -> "${ms / 1000}s"
            else -> "%dm%02ds".format(java.util.Locale.ROOT, ms / 60_000, ms / 1000 % 60)
        }

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
                // 带 Termux 的图标：按下去立刻知道命令发出去了。后台命令一直转着，结果回来原地换成结果卡
                is Action.Termux -> when (result) {
                    is ActionResult.Ok -> if (action.background) {
                        TriggerFeedback(
                            action.displayName,
                            packageName = TERMUX_PACKAGE,
                            pending = true,
                            then = TriggerFeedback(
                                action.displayName,
                                value = translate("仍在运行 · 结果见通知"),
                                packageName = TERMUX_PACKAGE,
                            ),
                        )
                    } else {
                        TriggerFeedback(action.displayName, packageName = TERMUX_PACKAGE)
                    }
                    is ActionResult.Failed -> TriggerFeedback(
                        action.displayName,
                        value = translate(result.reason.message),
                        packageName = TERMUX_PACKAGE,
                        failed = true,
                    )
                }
                // 同步在后台跑：按下去先转圈，结果回来原地换成结果卡
                is Action.FolderSync -> when (result) {
                    is ActionResult.Ok -> TriggerFeedback(
                        action.config.displayName,
                        pending = true,
                        then = TriggerFeedback(
                            action.config.displayName,
                            value = translate("仍在同步 · 进度见通知"),
                        ),
                    )
                    is ActionResult.Failed -> TriggerFeedback(
                        action.config.displayName,
                        value = translate(result.reason.message),
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
