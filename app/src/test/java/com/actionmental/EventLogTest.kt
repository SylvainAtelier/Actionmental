package com.actionmental

import com.actionmental.core.diag.EventLog
import com.actionmental.core.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EventLogTest {

    private fun tempDir(): File = Files.createTempDirectory("eventlog").toFile()

    private fun newLog(dir: File?, capacity: Int = 500, maxFileBytes: Long = 256 * 1024): EventLog =
        EventLog(CoroutineScope(Dispatchers.Unconfined), dir, capacity, maxFileBytes)

    /**
     * 等盘上那一份追上来。
     *
     * 非 ERROR 的条目走异步队列，写盘发生在另一个线程里 —— 记完就读必然是场
     * 竞态：本机赢，CI 上的慢机器输。这里等的是「落盘完成」这个事实本身，
     * 而不是某个碰巧够用的睡眠时长。
     */
    private fun awaitPersisted(log: EventLog, timeoutMs: Long = 5_000, done: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var persisted = log.readPersisted()
        while (!done(persisted) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            persisted = log.readPersisted()
        }
        return persisted
    }

    /** 崩溃那一条必须当场落盘：进程紧接着就没了，异步队列来不及消费。 */
    @Test
    fun crashEntryIsOnDiskImmediately() {
        val dir = tempDir()
        val log = newLog(dir)

        log.logBlocking(LogLevel.ERROR, "crash", "进程因未捕获异常终止", "java.lang.IllegalStateException")

        val persisted = log.readPersisted()
        assertTrue(persisted.contains("进程因未捕获异常终止"))
        assertTrue(persisted.contains("java.lang.IllegalStateException"))
    }

    /** 内存是环形的：旧条目让位给新的，不能无限增长。 */
    @Test
    fun memoryBufferKeepsOnlyTheNewest() {
        val log = newLog(null, capacity = 3)

        repeat(5) { log.info("t", "第 " + it + " 条") }

        val messages = log.entries.value.map { it.message }
        assertEquals(listOf("第 2 条", "第 3 条", "第 4 条"), messages)
    }

    /**
     * 文件写满就轮转：留住最近的两轮，且总量有上限。
     *
     * 两轮是刻意的 —— 一轮覆盖「这次掉线」，上一轮覆盖「上次掉线」，
     * 再早的记录对排查没有帮助，却会让文件无限长下去。
     */
    @Test
    fun fileRotationKeepsRecentRoundsAndStaysBounded() {
        val dir = tempDir()
        val log = newLog(dir, maxFileBytes = 200)

        repeat(40) { log.logBlocking(LogLevel.INFO, "t", "填充第 " + it + " 条", "") }

        assertTrue(File(dir, "events.prev.log").exists())
        // 最新的一条必须在，否则日志等于没记
        assertTrue(log.readPersisted().contains("填充第 39 条"))
        // 最早的已经让位，文件不会无限增长
        assertFalse(log.readPersisted().contains("填充第 0 条"))
        assertTrue(log.readPersisted().length < 200 * 4)
    }

    /** 没有目录时只留内存，不能因此崩掉。 */
    @Test
    fun worksWithoutADirectory() {
        val log = newLog(null)

        log.logBlocking(LogLevel.WARN, "t", "无盘也要活着", "")

        assertEquals(1, log.entries.value.size)
        assertEquals("", log.readPersisted())
    }

    /** 一行里要同时有时间、级别、标签与正文，否则导出的日志没法读。 */
    @Test
    fun formatCarriesTimeLevelAndTag() {
        val log = newLog(null)
        log.error("a11y", "监听服务未连接")

        val line = log.entries.value.single().format()

        assertTrue(line.contains("ERROR"))
        assertTrue(line.contains("[a11y]"))
        assertTrue(line.contains("监听服务未连接"))
        assertFalse(line.contains("\n"))
    }

    /**
     * 错误必须当场落盘。
     *
     * 异步队列要等一次线程调度，而崩溃常常就发生在那之前 ——
     * 最该保住的一条恰好是最容易丢的一条。
     */
    @Test
    fun errorsReachDiskWithoutWaitingForTheQueue() {
        val dir = tempDir()
        val log = newLog(dir)

        log.error("a11y", "监听服务掉线")

        assertTrue(File(dir, "events.log").readText().contains("监听服务掉线"))
    }

    /**
     * 内存告急时让出的只是内存里那一份，盘上一个字节都不能少。
     *
     * 这条界限就是 [EventLog.trimMemory] 存在的全部理由：能砍的是界面往回翻的
     * 那几百条，不能砍的是排查现场 —— 而那个现场恰好只在盘上。
     */
    @Test
    fun trimMemoryKeepsDiskIntact() {
        val dir = tempDir()
        val log = newLog(dir)

        repeat(20) { log.logBlocking(LogLevel.INFO, "t", "第 " + it + " 条", "") }
        log.trimMemory(keep = 5)

        assertEquals(5, log.entries.value.size)
        assertEquals("第 19 条", log.entries.value.last().message)
        val persisted = log.readPersisted()
        assertTrue(persisted.contains("第 0 条"))
        assertTrue(persisted.contains("第 19 条"))
    }

    /**
     * 句柄一直开着，落盘也必须是一条不少。
     *
     * 改成常开句柄是为了省掉每条日志的 open/close —— 那笔开销在连按时会变成
     * 一串系统调用，而这台设备的死因正是温控。省下来的前提是内容不能因此丢。
     */
    @Test
    fun batchedEntriesAllReachDisk() {
        val dir = tempDir()
        val log = newLog(dir)

        repeat(50) { log.info("action", "启动应用 · 第 " + it + " 个") }

        val persisted = awaitPersisted(log) { text ->
            (0 until 50).all { text.contains("启动应用 · 第 " + it + " 个") }
        }
        repeat(50) { assertTrue("缺第 " + it + " 条", persisted.contains("启动应用 · 第 " + it + " 个")) }
    }

    /** 写盘失败不能静默：否则「日志是空的」分不清是没事，还是没记下来。 */
    @Test
    fun writeFailureIsReported() {
        // 用一个已存在的**文件**当日志目录，mkdirs 与写入都必然失败
        val notADir = Files.createTempFile("notadir", ".txt").toFile()
        val log = newLog(notADir)

        log.error("t", "写不进去")

        assertTrue(log.writeError.value != null)
    }
}
