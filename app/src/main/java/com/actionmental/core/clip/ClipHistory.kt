package com.actionmental.core.clip

import java.security.MessageDigest

/**
 * 剪贴板历史里的一条。
 *
 * 同样的内容只存一行（按 [ClipPolicy.hash] 去重），再次复制或从面板里选用时
 * 只更新 [lastUsedAtMs] 与 [useCount] —— 于是列表按「最近用过」排序，常用的自然浮在上面。
 * [pinnedAtMs] 为 0 表示没置顶；置顶的永远排在最前，也不参与超量清理。
 */
data class ClipEntry(
    val id: Long,
    /** 列表里拿到的是开头 [ClipPolicy.PREVIEW_CHARS] 个字符；要全文请按 id 再取。 */
    val text: String,
    val sourcePackage: String?,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val useCount: Int,
    val pinnedAtMs: Long,
    /**
     * 全文的字符数，按码点计（SQLite 的 length / substr 就是这么数的，
     * 和 Kotlin 按 UTF-16 数的 String.length 在 emoji 上对不上）。
     */
    val length: Int = text.codePointCount(0, text.length),
) {
    val pinned: Boolean get() = pinnedAtMs > 0L

    /** [text] 就是全文，不必再查库。 */
    val complete: Boolean get() = length <= text.codePointCount(0, text.length)
}

/**
 * 剪贴板历史收什么、怎么查。纯函数，不碰 Android。
 *
 * 收录规则刻意保守：被来源应用标成敏感的（密码管理器复制的密码）一律不记，
 * 空白内容不记，超长内容截断 —— 这是一个本地历史，不是备份工具。
 */
object ClipPolicy {

    /** 单条上限。再长的东西从面板里也看不全，存全文只会让库膨胀。 */
    const val MAX_CHARS = 64 * 1024

    /**
     * 列表只取开头这么多字符。面板一行最多两行字，详情页另取全文 ——
     * 一条最长 64K 字符，整页都带全文的话，翻几百条就是几十 MB 的白搬。
     */
    const val PREVIEW_CHARS = 500

    /**
     * 未置顶条目的保留数，0 为不限。超出时插入那一刻顺手删掉最久没用过的。
     *
     * 默认一万条是设备实测定的：一万条时最坏的一次搜索（要扫全表的冷门词）约 17ms，
     * 五万条约 90ms，十万条起开始看得出停顿。置顶的不计入、也不会被挤掉。
     */
    const val DEFAULT_CAPACITY = 10_000
    val CAPACITY_PRESETS = listOf(1_000, 10_000, 50_000, 0)

    /**
     * 这段内容该不该进历史；该进就返回要存的文本。
     *
     * [sensitive] 来自 ClipDescription 的 EXTRA_IS_SENSITIVE（Android 13 起由来源应用标注）。
     */
    fun accept(text: String?, sensitive: Boolean): String? {
        if (text == null || sensitive || text.isBlank()) return null
        if (text.length <= MAX_CHARS) return text
        // 截断点落在代理对中间会留下半个字符，往前让一位
        val end = if (Character.isHighSurrogate(text[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return text.substring(0, end)
    }

    /** 去重键。存哈希而不是在长文本列上建唯一索引：索引体积跟着正文涨。 */
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 列表里的一行：换行与连续空白压成一个空格，过长的截掉。 */
    fun preview(text: String, max: Int = 200): String {
        val flat = text.trim().replace(WHITESPACE, " ")
        return if (flat.length <= max) flat else flat.substring(0, max) + "…"
    }

    /** 自动清理天数的上限：十年。再长就等于不清理，没必要让人填个天文数字。 */
    const val MAX_RETENTION_DAYS = 3650

    /** 设置页给的快捷选项；0 表示不清理（默认）。 */
    val RETENTION_PRESETS = listOf(0, 1, 7, 30, 90)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 早于这个时刻**没用过**的未置顶条目算过期；不清理时返回 null。
     *
     * 按最近使用而不是首次复制算：一条地址三个月前复制、昨天还在用，不该因为「老」被删掉。
     */
    fun expiryCutoff(nowMs: Long, days: Int): Long? =
        if (days <= 0) null else nowMs - days.coerceAtMost(MAX_RETENTION_DAYS) * DAY_MS

    /** 用户自己填的天数；不是 1…[MAX_RETENTION_DAYS] 的整数就返回 null。 */
    fun parseRetentionDays(input: String): Int? =
        input.trim().toIntOrNull()?.takeIf { it in 1..MAX_RETENTION_DAYS }

    /**
     * 把全文切成若干段交给懒加载列表：一段一个排版单元，64K 字符的长文也不会一次排完才显示。
     * 尽量在换行处切；一段里没有换行就硬切，但不切开代理对。
     */
    fun chunks(text: String, size: Int = 2_000): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>(text.length / size + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                end = when {
                    newline >= start + size / 2 -> newline + 1
                    Character.isHighSurrogate(text[end - 1]) -> end - 1
                    else -> end
                }
            }
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    /** 「多久以前」。面板只需要一个量级，不需要精确时间。 */
    fun age(nowMs: Long, thenMs: Long): String {
        val minutes = (nowMs - thenMs).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> minutes.toString() + " 分钟前"
            minutes < 60 * 24 -> (minutes / 60).toString() + " 小时前"
            else -> (minutes / (60 * 24)).toString() + " 天前"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * 连着两次同样的内容只记一次。
 *
 * ColorOS 上一次复制会连着回调好几次（中间还夹着系统组件的读写），
 * 解锁、重连时补读的也往往就是刚刚记过的那一条。这两种都不该把条目的「最近使用」顶上去。
 */
class ClipDeduper {
    private var lastHash: String? = null
    private var lastAtMs = 0L

    /**
     * @param cutoffMs 自动清理的过期线（见 [ClipPolicy.expiryCutoff]）。上一次记下它早于这条线的话，
     *   库里那一行已经算过期了 —— 同样的内容再来一次得重新记，不然它就从列表里消失了。
     */
    @Synchronized
    fun isRepeat(hash: String, nowMs: Long = 0L, cutoffMs: Long? = null): Boolean {
        if (hash == lastHash && (cutoffMs == null || lastAtMs >= cutoffMs)) return true
        lastHash = hash
        lastAtMs = nowMs
        return false
    }

    /** 历史被清空或删掉了这一条：同样的内容再来一次应该重新记。 */
    @Synchronized
    fun forget() {
        lastHash = null
    }
}
