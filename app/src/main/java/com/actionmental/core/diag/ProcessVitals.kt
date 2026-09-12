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
    /** 上一次采样到这一次之间，本进程占了几个百分点的一颗 CPU。 */
    val cpuPercent: Int,
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
        val cpuPercent = if (lastTicks < 0 || elapsedMs <= 0L) {
            0
        } else {
            val cpuMs = (ticks - lastTicks) * 1000L / ticksPerSecond
            ((cpuMs * 100L) / elapsedMs).toInt()
        }
        lastTicks = ticks
        lastAtMs = nowMs

        return ProcessVitals(
            rssMb = rssPages * pageSizeKb / 1024L,
            threads = threads,
            cpuPercent = cpuPercent.coerceIn(0, 10_000),
            elapsedMs = elapsedMs,
        )
    }
}
