package com.actionmental.core.diag

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 一次进程体征采样。
 *
 * [rssMb] 是**系统看到的那个数**：厂商的后台清理策略在日志里打印的就是 rss
 * （`pss=0kB rss=165736kB`），而应用自己一直在看的 pss 与它能差出十倍。
 * 两个数字对不上时，改错方向的代价是白干一整轮。
 */
data class ProcessVitals(
    val rssMb: Long,
    val threads: Int,
    /**
     * 上一次采样到这一次之间，本进程占了几个百分点的一颗 CPU。
     *
     * null 表示窗口太短、算不准（见 [VitalsReader.MIN_CPU_WINDOW_MS]）。
     */
    val cpuPercent: Int?,
    val elapsedMs: Long,
)

/**
 * 从 /proc 读体征。
 *
 * 刻意不用 [android.os.Debug.getMemoryInfo]：那一趟要遍历整个进程的内存映射
 * （smaps），是几十毫秒级的开销 —— 而这个应用是被**温控**策略杀掉的，
 * 每一次白烧的 CPU 都记在自己账上。statm 与 stat 各是一次几十字节的读取，
 * 便宜到可以常态采样。分项（java/native/graphics）只在真的要写一条日志时才去取。
 */
class VitalsReader {

    private val pageSizeKb: Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L
    }.getOrDefault(4L)

    /** 每秒多少个 jiffy。utime/stime 的单位。 */
    private val ticksPerSecond: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrDefault(100L).coerceAtLeast(1L)

    private var lastTicks = -1L
    private var lastAtMs = 0L

    /** 界面生命周期在主线程上采，定时采样在后台线程上采；基线只能有一份。 */
    @Synchronized
    fun read(): ProcessVitals? {
        val rssPages = runCatching {
            // statm: size resident shared text lib data dt（单位是页）
            File("/proc/self/statm").readText().trim().split(' ').getOrNull(1)?.toLongOrNull()
        }.getOrNull() ?: return null

        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull()
        // 第二个字段是进程名，里面可能有空格和括号 —— 从最后一个 ')' 之后开始切才安全
        val fields = stat?.substringAfterLast(')')?.trim()?.split(' ').orEmpty()
        // 去掉 pid 与 comm 之后，utime 是第 12 项、stime 第 13 项、线程数第 18 项（0 基）
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
        val threads = fields.getOrNull(17)?.toIntOrNull() ?: 0

        val nowMs = System.currentTimeMillis()
        val ticks = utime + stime
        val elapsedMs = if (lastAtMs == 0L) 0L else nowMs - lastAtMs
        val cpuPercent = when {
            lastTicks < 0 -> 0
            // 窗口太短就不算，也不挪基线，让下一次采样拿一个够长的窗口。
            // tick 是 10ms 的粒度：启动那一刻「启动」和「界面进入前台」两次采样只隔几毫秒，
            // 1 个 tick 除以 7ms 就成了 cpu=142%，还顺带触发了一条 WARN。
            elapsedMs < MIN_CPU_WINDOW_MS -> null
            else -> {
                val cpuMs = (ticks - lastTicks) * 1000L / ticksPerSecond
                ((cpuMs * 100L) / elapsedMs).toInt()
            }
        }
        if (cpuPercent != null) {
            lastTicks = ticks
            lastAtMs = nowMs
        }

        return ProcessVitals(
            rssMb = rssPages * pageSizeKb / 1024L,
            threads = threads,
            cpuPercent = cpuPercent?.coerceIn(0, 10_000),
            elapsedMs = elapsedMs,
        )
    }

    companion object {
        /** 短于这个窗口的 CPU 占用不报：几个 tick 的量化误差会被放大成几百个百分点。 */
        const val MIN_CPU_WINDOW_MS = 1_000L
    }
}
