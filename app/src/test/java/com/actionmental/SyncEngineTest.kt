package com.actionmental

import com.actionmental.core.sync.BaselineEntry
import com.actionmental.core.sync.FileMeta
import com.actionmental.core.sync.SyncBaseline
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncConflict
import com.actionmental.core.sync.SyncDirection
import com.actionmental.core.sync.SyncEngine
import com.actionmental.core.sync.SyncEntry
import com.actionmental.core.sync.SyncPlanner
import com.actionmental.core.sync.SyncReport
import com.actionmental.core.sync.SyncSide
import com.actionmental.core.sync.SyncSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 引擎的行为测试：两端都是内存里的假文件系统，所以不需要手机，也不需要 NAS。
 * 真正容易出错的是「该不该动这个文件」，而不是搬字节 —— 这正是这里测的。
 */
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存文件系统。目录显式记录，方便验证「删完文件后空目录被收掉」。 */
    class FakeSide : SyncSide {
        class Node(var data: ByteArray, var mtime: Long)

        val files = ConcurrentHashMap<String, Node>()
        val dirs = ConcurrentHashMap.newKeySet<String>().apply { add("") }
        val failOpen = ConcurrentHashMap.newKeySet<String>()
        val failList = ConcurrentHashMap.newKeySet<String>()
        var opens = 0

        fun put(path: String, text: String, mtime: Long) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty()) {
                dirs.add(dir)
                dir = dir.substringBeforeLast('/', "")
            }
            files[path] = Node(text.toByteArray(), mtime)
        }

        fun text(path: String): String? = files[path]?.data?.toString(Charsets.UTF_8)

        override fun exists(dir: String) = dir in dirs

        override fun mkdirs(dir: String) {
            var d = dir
            while (d.isNotEmpty()) {
                dirs.add(d)
                d = d.substringBeforeLast('/', "")
            }
        }

        override fun list(dir: String): List<SyncEntry> {
            if (dir in failList) throw IOException("读不了 $dir")
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val out = ArrayList<SyncEntry>()
            files.forEach { (p, n) ->
                if (p.startsWith(prefix) && '/' !in p.substring(prefix.length)) {
                    out.add(SyncEntry(p.substring(prefix.length), false, n.data.size.toLong(), n.mtime))
                }
            }
            dirs.forEach { d ->
                if (d.isNotEmpty() && d.startsWith(prefix) && '/' !in d.substring(prefix.length)) {
                    out.add(SyncEntry(d.substring(prefix.length), true))
                }
            }
            return out
        }

        override fun stat(path: String) = files[path]?.let { FileMeta(it.data.size.toLong(), it.mtime) }

        override fun open(path: String): InputStream {
            opens++
            if (path in failOpen) throw IOException("打不开 $path")
            return ByteArrayInputStream(files[path]!!.data)
        }

        override fun create(path: String): SyncSink {
            val buffer = java.io.ByteArrayOutputStream()
            return object : SyncSink {
                override fun write(buf: ByteArray, off: Int, len: Int) = buffer.write(buf, off, len)
                override fun commit(mtime: Long) {
                    files[path] = Node(buffer.toByteArray(), mtime)
                }

                override fun close() = Unit
            }
        }

        override fun delete(path: String) {
            files.remove(path) ?: throw IOException("没有这个文件 $path")
        }

        override fun rename(from: String, to: String) {
            val node = files.remove(from) ?: throw IOException("没有这个文件 $from")
            files[to] = node
        }

        override fun rmdir(dir: String): Boolean {
            val prefix = "$dir/"
            if (files.keys.any { it.startsWith(prefix) } || dirs.any { it.startsWith(prefix) }) return false
            return dirs.remove(dir)
        }
    }

    private fun cfg(
        direction: SyncDirection = SyncDirection.UPLOAD,
        conflict: SyncConflict = SyncConflict.NEWER,
        deletes: Boolean = false,
    ) = SyncConfig(
        key = "k", localPath = "/l", host = "nas", share = "s",
        direction = direction, conflict = conflict, deletes = deletes,
    )

    private class Result(val report: SyncReport, val baseline: Map<String, BaselineEntry>)

    private fun run(
        local: FakeSide,
        remote: FakeSide,
        config: SyncConfig,
        baseline: Map<String, BaselineEntry> = emptyMap(),
    ): Result = runBlocking {
        val outcome = SyncEngine(local, remote, config, baseline, clock = { 1_800_000_000_000L }).run()
        Result(outcome.report, outcome.baseline)
    }

    // --- 单向 ---------------------------------------------------------------

    @Test
    fun uploadCopiesNewFilesAndPreservesModificationTime() {
        val l = FakeSide().apply { put("a.txt", "A", 5_000); put("sub/dir/b.txt", "BB", 6_000) }
        val r = FakeSide()
        val result = run(l, r, cfg())

        assertEquals(2, result.report.uploaded)
        assertTrue(result.report.succeeded)
        assertEquals("A", r.text("a.txt"))
        assertEquals("BB", r.text("sub/dir/b.txt"))
        assertEquals(6_000L, r.files["sub/dir/b.txt"]!!.mtime)
        assertEquals(2, result.baseline.size)
    }

    @Test
    fun secondRunWithNothingChangedTransfersNothing() {
        val l = FakeSide().apply { put("a.txt", "A", 5_000); put("x/b.txt", "B", 6_000) }
        val r = FakeSide()
        val first = run(l, r, cfg())
        val opensBefore = l.opens + r.opens

        val second = run(l, r, cfg(), first.baseline)
        assertEquals(0, second.report.changed)
        assertEquals(2, second.report.unchanged)
        assertEquals("没变的文件不该再读一个字节", opensBefore, l.opens + r.opens)
    }

    @Test
    fun uploadKeepsExtraRemoteFilesUnlessDeletesEnabled() {
        val l = FakeSide().apply { put("a.txt", "A", 5_000) }
        val r = FakeSide().apply { put("extra.txt", "E", 1_000); put("old/z.txt", "Z", 1_000) }

        val kept = run(l, r, cfg(deletes = false))
        assertNotNull(r.files["extra.txt"])
        assertEquals(0, kept.report.deletedRemote)

        val removed = run(l, r, cfg(deletes = true), kept.baseline)
        assertNull(r.files["extra.txt"])
        assertNull(r.files["old/z.txt"])
        assertFalse("删光文件后留下的空目录一并收掉", "old" in r.dirs)
        assertEquals(2, removed.report.deletedRemote)
    }

    @Test
    fun emptySourceWithDeletesAbortsInsteadOfWipingTheDestination() {
        val l = FakeSide() // 比如存储没挂好、读不到
        val r = FakeSide().apply { put("a.txt", "A", 1_000) }
        val history = mapOf("a.txt" to BaselineEntry(FileMeta(1, 1_000), FileMeta(1, 1_000)))

        val result = run(l, r, cfg(deletes = true), history)

        assertNotNull(result.report.aborted)
        assertNotNull("NAS 上的文件必须原样保留", r.files["a.txt"])
        assertEquals(history, result.baseline)
    }

    @Test
    fun unreadableDirectoryIsNotMistakenForEmptyAndNeverTriggersDeletes() {
        val l = FakeSide().apply { put("keep.txt", "K", 1_000); put("docs/a.txt", "A", 1_000) }
        val r = FakeSide().apply { put("keep.txt", "K", 1_000); put("docs/a.txt", "A", 1_000) }
        val history = mapOf(
            "keep.txt" to BaselineEntry(FileMeta(1, 1_000), FileMeta(1, 1_000)),
            "docs/a.txt" to BaselineEntry(FileMeta(1, 1_000), FileMeta(1, 1_000)),
        )
        l.failList.add("docs")

        val result = run(l, r, cfg(deletes = true), history)

        assertNotNull("读不出的目录里的文件不能被当成已删除", r.files["docs/a.txt"])
        assertEquals(1, result.report.failures.size)
        assertTrue("没走完的同步保留旧记录", result.baseline.containsKey("docs/a.txt"))
    }

    @Test
    fun oneFailureDoesNotStopTheRest() {
        val l = FakeSide().apply { put("good.txt", "G", 1_000); put("bad.txt", "B", 1_000); put("ok.txt", "O", 1_000) }
        val r = FakeSide()
        l.failOpen.add("bad.txt")

        val result = run(l, r, cfg())

        assertEquals(2, result.report.uploaded)
        assertEquals(listOf("bad.txt"), result.report.failures.map { it.path })
        assertFalse(result.report.succeeded)
        assertNull("失败的文件不进基线，下次会再试", result.baseline["bad.txt"])
        assertNotNull(result.baseline["good.txt"])
    }

    @Test
    fun ignoredNamesAreNeverTransferredOrDeleted() {
        val l = FakeSide().apply { put("a.txt", "A", 1_000); put("a.txt" + SyncPlanner.PART_SUFFIX, "half", 1_000) }
        val r = FakeSide().apply { put("@Recycle/old.txt", "x", 1_000) }

        run(l, r, cfg(deletes = true))

        assertNull(r.files["a.txt" + SyncPlanner.PART_SUFFIX])
        assertNotNull("NAS 的回收站不归我们管", r.files["@Recycle/old.txt"])
    }

    @Test
    fun keepBothOneWayRenamesTheOverwrittenFileInsteadOfLosingIt() {
        val l = FakeSide().apply { put("n.txt", "local-new", 9_000) }
        val r = FakeSide().apply { put("n.txt", "nas-edited", 5_000) }
        val history = mapOf("n.txt" to BaselineEntry(FileMeta(3, 1_000), FileMeta(3, 1_000)))

        val result = run(l, r, cfg(conflict = SyncConflict.KEEP_BOTH), history)

        assertEquals("local-new", r.text("n.txt"))
        val backups = r.files.keys.filter { it.startsWith("n (冲突 ") }
        assertEquals(1, backups.size)
        assertEquals("nas-edited", r.text(backups.single()))
        assertEquals(1, result.report.uploaded)
    }

    @Test
    fun skipPolicyLeavesBothSidesAndCountsTheConflict() {
        val l = FakeSide().apply { put("n.txt", "L", 9_000) }
        val r = FakeSide().apply { put("n.txt", "R!", 5_000) }

        val result = run(l, r, cfg(conflict = SyncConflict.SKIP))

        assertEquals(1, result.report.conflicts)
        assertEquals("R!", r.text("n.txt"))
        assertEquals("L", l.text("n.txt"))
        assertEquals(0, result.report.changed)
    }

    @Test
    fun downloadMirrorsRemoteIntoLocal() {
        val l = FakeSide().apply { put("gone.txt", "g", 1_000) }
        val r = FakeSide().apply { put("a/b.txt", "B", 7_000) }

        val result = run(l, r, cfg(SyncDirection.DOWNLOAD, deletes = true))

        assertEquals("B", l.text("a/b.txt"))
        assertEquals(7_000L, l.files["a/b.txt"]!!.mtime)
        assertNull(l.files["gone.txt"])
        assertEquals(1, result.report.downloaded)
        assertEquals(1, result.report.deletedLocal)
    }

    // --- 双向 ---------------------------------------------------------------

    @Test
    fun twoWayMergesDisjointChangesFromBothSides() {
        val l = FakeSide().apply { put("shared.txt", "v1", 1_000) }
        val r = FakeSide().apply { put("shared.txt", "v1", 1_000) }
        val first = run(l, r, cfg(SyncDirection.BOTH))
        assertEquals(0, first.report.changed)

        l.put("from-phone.txt", "P", 5_000)
        r.put("from-nas.txt", "N", 6_000)
        l.put("shared.txt", "v2-phone", 7_000)

        val second = run(l, r, cfg(SyncDirection.BOTH), first.baseline)

        assertEquals("P", r.text("from-phone.txt"))
        assertEquals("N", l.text("from-nas.txt"))
        assertEquals("v2-phone", r.text("shared.txt"))
        assertEquals(2, second.report.uploaded)
        assertEquals(1, second.report.downloaded)
        assertTrue(second.report.succeeded)
    }

    @Test
    fun twoWayDeletePropagatesOnlyWhenEnabled() {
        // 另有一个文件陪着：整个目录清空会触发「疑似误删」闸门，那是另一条测试的事
        val l = FakeSide().apply { put("a.txt", "A", 1_000); put("keep.txt", "K", 1_000) }
        val r = FakeSide().apply { put("a.txt", "A", 1_000); put("keep.txt", "K", 1_000) }
        val synced = run(l, r, cfg(SyncDirection.BOTH, deletes = true))

        l.files.remove("a.txt")
        val off = run(l, r, cfg(SyncDirection.BOTH, deletes = false), synced.baseline)
        assertEquals("没开同步删除：本地删了，远端的补回来", "A", l.text("a.txt"))
        assertEquals(1, off.report.downloaded)

        l.files.remove("a.txt")
        val on = run(l, r, cfg(SyncDirection.BOTH, deletes = true), synced.baseline)
        assertNull(r.files["a.txt"])
        assertEquals(1, on.report.deletedRemote)
        assertNull("删干净之后基线里也不留", on.baseline["a.txt"])
    }

    @Test
    fun twoWayDeleteDoesNotDestroyAnEditMadeAfterTheOtherSideDeleted() {
        val l = FakeSide().apply { put("a.txt", "A", 1_000); put("keep.txt", "K", 1_000) }
        val r = FakeSide().apply { put("a.txt", "A", 1_000); put("keep.txt", "K", 1_000) }
        val synced = run(l, r, cfg(SyncDirection.BOTH, deletes = true))

        r.files.remove("a.txt")
        l.put("a.txt", "A-edited", 8_000)

        run(l, r, cfg(SyncDirection.BOTH, deletes = true), synced.baseline)

        assertEquals("改动比删除值钱", "A-edited", r.text("a.txt"))
        assertEquals("A-edited", l.text("a.txt"))
    }

    @Test
    fun twoWayKeepBothLeavesBothVersionsOnBothSides() {
        val l = FakeSide().apply { put("n.txt", "v0", 1_000) }
        val r = FakeSide().apply { put("n.txt", "v0", 1_000) }
        val synced = run(l, r, cfg(SyncDirection.BOTH, SyncConflict.KEEP_BOTH))

        l.put("n.txt", "phone", 5_000)
        r.put("n.txt", "nas", 6_000)
        val result = run(l, r, cfg(SyncDirection.BOTH, SyncConflict.KEEP_BOTH), synced.baseline)

        assertEquals("phone", l.text("n.txt"))
        assertEquals("phone", r.text("n.txt"))
        val copyL = l.files.keys.single { it.startsWith("n (冲突 ") }
        val copyR = r.files.keys.single { it.startsWith("n (冲突 ") }
        assertEquals("nas", l.text(copyL))
        assertEquals("nas", r.text(copyR))
        assertEquals(1, result.report.conflicts)

        // 下一轮：冲突副本是正常文件，不再引起任何动作；开了同步删除也不会被当成多余文件清掉
        val again = run(l, r, cfg(SyncDirection.BOTH, SyncConflict.KEEP_BOTH, deletes = true), result.baseline)
        assertEquals(0, again.report.changed)
    }

    // --- 基线文件 -----------------------------------------------------------

    @Test
    fun baselineRoundTripsAwkwardPathsAndRejectsAForeignSignature() {
        val file = tmp.newFile("b.tsv")
        val entries = mapOf(
            "plain.txt" to BaselineEntry(FileMeta(1, 2), FileMeta(3, 4)),
            "tab\tname\\and\nnewline.txt" to BaselineEntry(FileMeta(5, 6), FileMeta(7, 8)),
            "中文/目录/文件 (1).md" to BaselineEntry(FileMeta(9, 10), FileMeta(11, 12)),
        )
        SyncBaseline.save(file, "sig1", entries)

        assertEquals(entries, SyncBaseline.load(file, "sig1"))
        assertTrue("换了目录，旧记录一概作废", SyncBaseline.load(file, "sig2").isEmpty())
        assertTrue(SyncBaseline.load(tmp.newFile("missing").also { it.delete() }, "sig1").isEmpty())
    }

    @Test
    fun baselineSignatureChangesWhenEitherFolderChanges() {
        val a = cfg()
        assertEquals(SyncBaseline.signature(a), SyncBaseline.signature(a.copy(title = "x", deletes = true)))
        assertTrue(SyncBaseline.signature(a) != SyncBaseline.signature(a.copy(localPath = "/other")))
        assertTrue(SyncBaseline.signature(a) != SyncBaseline.signature(a.copy(remotePath = "sub")))
    }
}
