package com.actionmental

import com.actionmental.core.diag.DiagCounters
import com.actionmental.core.diag.EventLog
import com.actionmental.core.diag.ProcessVitals
import com.actionmental.core.diag.VitalsWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体征采样的取舍：采得密、写得少。
 *
 * 这几条约束是从一次真实的死亡里长出来的 —— 进程在启动三分钟后被温控策略结束，
 * 而那三分钟里日志一条采样都没有。所以「什么时候必须写下一条」不是风格问题。
 */
class VitalsWatchTest {

    private fun newWatch(
        counters: DiagCounters = DiagCounters(),
        vitals: () -> ProcessVitals?,
    ): Pair<VitalsWatch, EventLog> {
        val log = EventLog(CoroutineScope(Dispatchers.Unconfined), dir = null)
        return VitalsWatch(log = log, counters = counters, read = vitals) to log
    }

    private fun vitals(rssMb: Long, cpuPercent: Int = 0) =
        ProcessVitals(rssMb = rssMb, threads = 30, cpuPercent = cpuPercent, elapsedMs = 30_000)

    @Test
    fun `水位平稳时不重复写`() {
        var current = vitals(120)
        val (watch, log) = newWatch { current }

        watch.sample("启动", force = true)
        repeat(5) { watch.sample("常规采样") }

        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun `跨档就落一条`() {
        var current = vitals(120)
        val (watch, log) = newWatch { current }

        watch.sample("启动", force = true)
        current = vitals(160)      // 跨过 25MB 的档
        watch.sample("常规采样")

        assertEquals(2, log.entries.value.size)
        assertTrue(log.entries.value.last().message.contains("160MB"))
    }

    @Test
    fun `CPU 明显时即使水位不动也要落一条`() {
        var current = vitals(120)
        val (watch, log) = newWatch { current }

        watch.sample("启动", force = true)
        current = vitals(120, cpuPercent = 40)
        watch.sample("常规采样")

        // 杀掉这个进程的策略看的是发热，所以 CPU 必须能单独触发一条记录
        assertEquals(2, log.entries.value.size)
        val last = log.entries.value.last()
        assertEquals(com.actionmental.core.diag.LogLevel.WARN, last.level)
        assertTrue(last.message.contains("cpu=40%"))
    }

    @Test
    fun `活动计数跟着日志一起写出去并清零`() {
        val counters = DiagCounters()
        var current = vitals(120)
        val (watch, log) = newWatch(counters) { current }
        watch.sample("启动", force = true)

        counters.shell.addAndGet(7)
        counters.windowChange.addAndGet(31)
        current = vitals(160)
        watch.sample("常规采样")

        val detail = log.entries.value.last().detail
        assertTrue(detail, detail.contains("shell=7"))
        assertTrue(detail, detail.contains("切窗口=31"))
        // 写出去就清零：下一条覆盖的是下一段时间，不是从头累计
        assertEquals(0L, counters.shell.get())
    }

    @Test
    fun `平稳时采样间隔翻倍，有活动时不退太远`() {
        val counters = DiagCounters()
        val (watch, _) = newWatch(counters) { vitals(120) }
        watch.sample("启动", force = true)
        assertEquals(VitalsWatch.MIN_DELAY_MS, watch.nextDelayMs)

        counters.keyEvent.addAndGet(1)
        repeat(10) { watch.sample("常规采样") }
        assertEquals(VitalsWatch.BUSY_DELAY_MS, watch.nextDelayMs)
    }

    @Test
    fun `读不到体征时不写日志也不报错`() {
        val (watch, log) = newWatch { null }

        assertEquals(0L, watch.sample("启动", force = true))
        assertTrue(log.entries.value.isEmpty())
    }
}
