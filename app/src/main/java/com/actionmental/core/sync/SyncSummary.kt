package com.actionmental.core.sync

import java.util.Locale

/** 把 [SyncReport] 说成人话：通知、顶部提示、日志共用同一套措辞。 */
object SyncSummary {

    /** 一行结论。[t] 把文案换成界面语言（数字与单位不动）。 */
    fun line(r: SyncReport, t: (String) -> String): String {
        r.aborted?.let { return t(it) }
        val parts = buildList {
            if (r.uploaded > 0) add(t("上传") + " " + r.uploaded)
            if (r.downloaded > 0) add(t("下载") + " " + r.downloaded)
            val deleted = r.deletedLocal + r.deletedRemote
            if (deleted > 0) add(t("删除") + " " + deleted)
            if (r.conflicts > 0) add(t("冲突") + " " + r.conflicts)
            if (r.failures.isNotEmpty()) add(t("失败") + " " + r.failures.size)
        }
        val head = when {
            parts.isNotEmpty() -> parts.joinToString(" · ")
            r.cancelled -> t("已取消")
            else -> t("已是最新") + " · " + r.unchanged + " " + t("个文件")
        }
        return if (r.cancelled && parts.isNotEmpty()) head + " · " + t("已取消") else head
    }

    fun title(name: String, r: SyncReport, t: (String) -> String): String = when {
        r.aborted != null -> name + " · " + t("同步未完成")
        r.cancelled -> name + " · " + t("已取消")
        r.failures.isNotEmpty() -> name + " · " + t("部分失败")
        else -> name + " · " + t("同步完成")
    }

    /** 耗时与数据量，放在结论后面。 */
    fun meta(r: SyncReport): String =
        listOfNotNull(
            size(r.bytes).takeIf { r.bytes > 0 },
            seconds(r.durationMs),
        ).joinToString(" · ")

    /** 失败明细：最多列 [limit] 条，剩下的只说个数。路径与原因原样给，不翻译。 */
    fun failures(r: SyncReport, limit: Int = 5, t: (String) -> String): String {
        if (r.failures.isEmpty()) return ""
        val shown = r.failures.take(limit).joinToString("\n") { it.path + " · " + it.reason }
        val rest = r.failures.size - limit
        return if (rest > 0) shown + "\n… " + t("另有") + " " + rest + " " + t("条") else shown
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }

    fun seconds(ms: Long): String = when {
        ms < 10_000 -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
        ms < 60_000 -> "${ms / 1000}s"
        else -> String.format(Locale.ROOT, "%dm%02ds", ms / 60_000, ms / 1000 % 60)
    }
}
