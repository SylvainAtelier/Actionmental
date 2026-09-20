package com.actionmental.core.diag

import android.os.Debug

/**
 * 进程体征的定期采样（原来的 MemoryWatch）。
 *
 * 换掉的不只是名字。上一轮留下的记录是这样的：
 *
 *   00:51:14 内存水位 11MB（启动）
 *   00:54:14 被系统结束 · rss=165736kB
 *
 * 三分钟，中间一条采样都没有 —— 因为那时的最密采样间隔是五分钟，而且只在
 * 跨 50MB 档时才写。于是「从 11MB 到 165MB 之间发生了什么」在日志里是一段空白，
 * 任何结论都只能靠猜。这个类存在的全部意义就是把那段空白填上：
 *
 *  - 采 rss 而不只是 pss：系统写死因时打印的是 rss，两者能差出十倍；
 *  - 采 CPU 占用：杀掉它的策略叫 `bgLimit_level_thermal`，看的是发热；
 *  - 采活动计数：shell 调用、切窗口、按键，把「谁在忙」落到具体的量上；
 *  - 采样本身足够便宜（两次 /proc 小读），所以间隔可以做到 30 秒起。
 *
 * 便宜的是采样，不是写日志。所以采得密、写得少：只有跨档、CPU 明显、温控不正常，
 * 或者太久没写过，才落一条。
 */
class VitalsWatch(
    private val log: EventLog,
    val counters: DiagCounters,
    /** 取一次体征。默认从 /proc 读；测试可以直接给数。 */
    private val read: () -> ProcessVitals? = VitalsReader()::read,
    /** 运行画像：常驻服务开没开、屏幕常亮着没有、界面在不在前台、温控几档。 */
    private val profile: () -> String = { "" },
) {
    private var lastBucket = -1L
    private var lastLogAtMs = 0L
    private var delayMs = MIN_DELAY_MS

    /** 下一次采样该等多久。活动多就采得密，长期不动就退到最疏。 */
    val nextDelayMs: Long get() = delayMs

    /**
     * @param force 无论如何都写一条。启动、界面隐藏、系统喊内存吃紧这类
     *              明确的时间点，本身就是后面回看时最需要的锚点。
     * @return 本次采到的 rss（MB）。
     */
    @Synchronized
    fun sample(reason: String, force: Boolean = false): Long {
        val vitals = read() ?: return 0L
        val now = System.currentTimeMillis()
        val bucket = vitals.rssMb / BUCKET_MB
        val cpu = vitals.cpuPercent ?: 0

        val notable = force ||
            bucket != lastBucket ||
            cpu >= CPU_NOTABLE_PERCENT ||
            (lastLogAtMs != 0L && now - lastLogAtMs >= HEARTBEAT_MS)

        if (!notable) {
            // 平稳就把间隔翻倍。有活动时不退太远：真正需要看清楚的正是忙的那几分钟
            val ceiling = if (counters.pending()) BUSY_DELAY_MS else MAX_DELAY_MS
            delayMs = (delayMs * 2).coerceAtMost(ceiling)
            return vitals.rssMb
        }

        lastBucket = bucket
        delayMs = MIN_DELAY_MS
        val windowMs = if (lastLogAtMs == 0L) vitals.elapsedMs else now - lastLogAtMs
        lastLogAtMs = now

        val counts = counters.drain()
        val head = "体征 · rss=" + vitals.rssMb + "MB cpu=" +
            (vitals.cpuPercent?.let { "$it%" } ?: "—")
        val detail = buildString {
            append("rss=").append(vitals.rssMb).append("MB ")
            append(memoryBreakdown())
            append(" · 线程=").append(vitals.threads)
            append(" · 最近").append(windowMs / 1000L).append("s ").append(counts.format())
            profile().takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append(" · ").append(reason)
        }

        if (vitals.rssMb >= WARN_RSS_MB || cpu >= CPU_WARN_PERCENT) {
            log.warn(TAG, head, detail)
        } else {
            log.debug(TAG, head, detail)
        }
        return vitals.rssMb
    }

    /**
     * 分项。
     *
     * 这一趟要遍历整个进程的内存映射，是几十毫秒级的开销 —— 所以只在**确定要写
     * 一条日志**的时候才去取，而不是每次采样都取。上一版把它放在采样路径上，
     * 等于为了测量发热而发热。
     */
    private fun memoryBreakdown(): String {
        val info = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } }.getOrNull()
            ?: return "分项不可用"
        val parts = SUMMARY_KEYS.mapNotNull { (key, label) ->
            val kb = runCatching { info.getMemoryStat(key)?.toLongOrNull() }.getOrNull() ?: return@mapNotNull null
            if (kb < 1024L) null else label + "=" + (kb / 1024L) + "MB"
        }
        // totalPss 把换出到 zram 的那部分也算进去了，rss 却不算 —— 于是日志里出现过
        // pss 比 rss 还大、rss 一路降而 pss 一路涨的样子。单列出来，两个数才对得上
        val swapMb = runCatching { info.getMemoryStat("summary.total-swap")?.toLongOrNull() }
            .getOrNull()?.div(1024L) ?: 0L
        return "pss=" + (info.totalPss / 1024L) + "MB" +
            (if (swapMb >= 1L) "(含swap=" + swapMb + "MB)" else "") +
            if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
    }

    companion object {
        private const val TAG = "vitals"

        /** 最密的采样间隔。 */
        const val MIN_DELAY_MS = 30_000L

        /** 有活动时最疏也就到这里 —— 忙的那几分钟正是需要看清楚的。 */
        const val BUSY_DELAY_MS = 120_000L

        /** 长期不动时的上限。 */
        const val MAX_DELAY_MS = 900_000L

        /** 再平稳也要隔一段时间落一条：被杀之前的最后一条必须是新鲜的。 */
        const val HEARTBEAT_MS = 900_000L

        /** rss 跨这么多就值得记一条。50MB 的档太粗，从 11 到 165 只跨了三次。 */
        const val BUCKET_MB = 25L

        /** 占到一颗 CPU 的这个比例就该记下来。 */
        const val CPU_NOTABLE_PERCENT = 5

        /** 到这个比例已经是在给机器加热了。 */
        const val CPU_WARN_PERCENT = 25

        /** rss 高到这个数就用 WARN。够一个键盘工具用很多倍了。 */
        const val WARN_RSS_MB = 350L

        private val SUMMARY_KEYS = listOf(
            "summary.java-heap" to "java",
            "summary.native-heap" to "native",
            "summary.code" to "code",
            "summary.graphics" to "graphics",
            "summary.stack" to "stack",
            "summary.private-other" to "other",
        )
    }
}
