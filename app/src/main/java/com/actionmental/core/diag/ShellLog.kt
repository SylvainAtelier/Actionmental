package com.actionmental.core.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条特权 shell 调用的完整记录。
 *
 * 命令、退出码、原始输出三样缺一不可 —— 「为什么这个应用没转」这类问题，
 * 答案通常就藏在某一条命令的 stderr 里，只报成败等于把它扔掉。
 */
data class ShellEntry(
    val id: Long,
    val timestampMs: Long,
    val source: String,
    val command: String,
    val exitCode: Int,
    val output: String,
    val durationMs: Long,
) {
    val ok: Boolean get() = exitCode == 0

    /** 单行摘要，输出折起来时显示。 */
    val summary: String
        get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            ?: if (ok) "（无输出）" else "exit " + exitCode
}

/**
 * 内存环形缓冲，与 KeyPipeline 的事件流同一套路：不落盘、有上限、UI 直接订阅。
 *
 * 这里记录的是 shell 命令与系统回显，不含任何用户输入内容，
 * 因此和 PRD 26 对按键事件流的约束是一致的。
 */
class ShellLog(
    private val capacity: Int = 300,
    /**
     * 单条输出的上限。
     *
     * 没有上限时，一条 `dumpsys` 或一次失败的 `pm` 就能带回几百 KB，
     * 300 条乘起来足以把进程推向内存不足 —— 而那种死法连崩溃日志都留不下。
     * 诊断需要的信息都在开头，超出的部分留一个明确的截断标记就够了。
     */
    private val maxOutputChars: Int = 4_000,
) {

    private val ids = AtomicLong(0)
    private val _entries = MutableStateFlow<List<ShellEntry>>(emptyList())
    val entries: StateFlow<List<ShellEntry>> = _entries.asStateFlow()

    fun record(source: String, command: String, exitCode: Int, output: String, durationMs: Long) {
        val entry = ShellEntry(
            id = ids.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            source = source,
            command = command,
            exitCode = exitCode,
            output = output.trim().let {
                if (it.length > maxOutputChars) it.take(maxOutputChars) + TRUNCATED else it
            },
            durationMs = durationMs,
        )
        _entries.update { list ->
            val next = list + entry
            if (next.size > capacity) next.takeLast(capacity) else next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * 只留最近这些条。
     *
     * 这份记录纯粹是给诊断页看的，界面一走就没人读；而进程要以无障碍服务的身份
     * 长期活着，留着的每一条都只是在抬高自己在后台清理名单上的位置。
     * 留一小段是为了「刚才那次失败」还能翻得到。
     */
    fun trim(keep: Int = 50) {
        _entries.update { if (it.size > keep) it.takeLast(keep) else it }
    }

    /** 导出成纯文本，供用户复制去提 issue。 */
    fun export(): String = buildString {
        appendLine("# Actionmental shell log · " + _entries.value.size + " 条")
        _entries.value.forEach { e ->
            appendLine()
            appendLine("[" + stamp.format(e.timestampMs) + "] " + e.source + " · exit " + e.exitCode + " · " + e.durationMs + "ms")
            appendLine("$ " + e.command)
            if (e.output.isNotBlank()) appendLine(e.output)
        }
    }

    private companion object {
        const val TRUNCATED = "\n…（已截断）"
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
