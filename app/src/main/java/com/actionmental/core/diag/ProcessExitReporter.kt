package com.actionmental.core.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** API 32 起才有的 REASON_FREEZER，用字面值以兼容更低的版本。 */
private const val REASON_FREEZER = 14

/** API 34 起才有的两个，同样用字面值。 */
private const val REASON_PACKAGE_STATE_CHANGE = 15
private const val REASON_PACKAGE_UPDATED = 16

/** 上一次进程是怎么没的。字段全部来自系统，不依赖应用自己有没有来得及记录。 */
data class ProcessExit(
    val timestampMs: Long,
    val reason: Int,
    val reasonLabel: String,
    val status: Int,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
    /** ANR 才有：系统抓的线程堆栈，已截断。 */
    val trace: String?,
) {
    /**
     * 这次退出是不是「出事了」，而不是用户或系统正常收走进程。
     *
     * REASON_OTHER 也算在内：厂商 ROM 的省电、后台限制与热控策略几乎都归到这一类，
     * 真正的信息藏在 [description] 里（例如 bgLimit_level_thermal）。
     * 把它当成「其它原因」记一条 INFO，等于把唯一一条有用的记录降级成噪音。
     * 重装应用（PACKAGE_UPDATED）与用户主动结束则明确排除 —— 那是预期之内的。
     */
    val abnormal: Boolean
        get() = reason in setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
            REASON_FREEZER,
        )

    /**
     * 被杀时占了多少内存。
     *
     * 后台限制类的策略往往按内存排序挑目标，所以这个数字和 [description] 一样，
     * 是判断「为什么偏偏是我们」的直接依据。
     */
    val footprintKb: Long get() = maxOf(pssKb, rssKb)

    /**
     * 一行摘要。
     *
     * description 与内存占用必须出现在这里而不是只在详情里：
     * 「被系统策略结束」本身什么也没说，`bgLimit_level_thermal` 才是结论。
     */
    fun summary(): String = buildString {
        append(reasonLabel)
        if (!description.isNullOrBlank()) append(" · ").append(description.take(80))
        if (footprintKb > 0) append(" · 占用 ").append(footprintKb / 1024).append("MB")
        append(" · ").append(TIME.format(Date(timestampMs)))
    }

    fun detail(): String = buildString {
        appendLine("reason=" + reason + " (" + reasonLabel + ")")
        appendLine("status=" + status)
        if (!description.isNullOrBlank()) appendLine("description=" + description)
        appendLine("pss=" + pssKb + "kB rss=" + rssKb + "kB")
        if (!trace.isNullOrBlank()) {
            appendLine("--- 系统抓取的堆栈 ---")
            append(trace)
        }
    }.trim()

    private companion object {
        val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    }
}

/**
 * 上一次进程的死因，问系统要。
 *
 * 应用自己的未捕获异常处理器只能抓到 Java 异常：native 崩溃、ANR 被杀、
 * 内存不足被回收、被 SIGKILL —— 这些死法进程根本没有机会写任何东西，
 * 而「频繁触发快捷键后应用退出、日志里什么都没有」正是这种情况。
 * 系统这份记录不依赖进程还活着，因此是唯一可靠的来源。
 *
 * 需要 API 30；更低的版本上返回空列表，调用方照常工作。
 */
class ProcessExitReporter(private val context: Context) {

    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun recentExits(limit: Int = 5, maxTraceChars: Int = 8_000): List<ProcessExit> {
        if (!available()) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()

        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, limit).map { info ->
                ProcessExit(
                    timestampMs = info.timestamp,
                    reason = info.reason,
                    reasonLabel = labelOf(info.reason),
                    status = info.status,
                    description = info.description,
                    pssKb = info.pss,
                    rssKb = info.rss,
                    trace = traceOf(info, maxTraceChars),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** ANR 的堆栈可能有几百 KB，日志里只留开头 —— 死锁与主线程卡点都在最前面。 */
    // 只从 recentExits() 里调用，那里已经用 available() 挡住了低版本。
    @RequiresApi(Build.VERSION_CODES.R)
    private fun traceOf(info: ApplicationExitInfo, maxChars: Int): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR) return null
        return runCatching {
            info.traceInputStream?.bufferedReader()?.use { reader ->
                val text = reader.readText()
                if (text.length > maxChars) text.take(maxChars) + "\n…（已截断）" else text
            }
        }.getOrNull()
    }

    private fun labelOf(reason: Int): String = when (reason) {
        // 这三个常量的最低 API 高于本应用，用字面值避免在低版本上取不到
        REASON_FREEZER -> "冻结状态下被杀"
        REASON_PACKAGE_STATE_CHANGE -> "应用状态变更"
        REASON_PACKAGE_UPDATED -> "应用被重新安装"
        ApplicationExitInfo.REASON_CRASH -> "Java 异常崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native 崩溃"
        ApplicationExitInfo.REASON_ANR -> "无响应被系统杀掉（ANR）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被回收"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_SIGNALED -> "收到信号被杀"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束"
        ApplicationExitInfo.REASON_USER_STOPPED -> "用户停用了应用"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程死亡"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更导致重启"
        ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行退出"
        // 厂商的省电 / 后台 / 热控策略基本都落在这一类，具体原因只在 description 里
        ApplicationExitInfo.REASON_OTHER -> "被系统策略结束"
        else -> "未知原因 " + reason
    }
}
