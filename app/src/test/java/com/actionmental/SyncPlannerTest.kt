package com.actionmental

import com.actionmental.core.sync.BaselineEntry
import com.actionmental.core.sync.FileMeta
import com.actionmental.core.sync.StoragePaths
import com.actionmental.core.sync.SyncAct
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncConflict
import com.actionmental.core.sync.SyncDirection
import com.actionmental.core.sync.SyncPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun cfg(
        direction: SyncDirection = SyncDirection.UPLOAD,
        conflict: SyncConflict = SyncConflict.NEWER,
        deletes: Boolean = false,
    ) = SyncConfig(
        key = "k", localPath = "/storage/emulated/0/x", host = "nas", share = "s",
        direction = direction, conflict = conflict, deletes = deletes,
    )

    private fun m(size: Long, mtime: Long) = FileMeta(size, mtime)
    private fun base(l: FileMeta, r: FileMeta = l) = BaselineEntry(l, r)

    private fun act(l: FileMeta?, r: FileMeta?, b: BaselineEntry?, c: SyncConfig) =
        SyncPlanner.decide(l, r, b, c).act

    // --- 一致 / 缺失 ------------------------------------------------------------

    @Test
    fun identicalFilesDoNothing() {
        assertEquals(SyncAct.NONE, act(m(10, 1000), m(10, 1000), null, cfg()))
    }

    @Test
    fun timestampsWithinTwoSecondsCountAsSame() {
        assertEquals(SyncAct.NONE, act(m(10, 1000), m(10, 2900), null, cfg()))
        assertEquals(SyncAct.UPLOAD, act(m(10, 1000), m(10, 3500), null, cfg(conflict = SyncConflict.OVERWRITE)))
    }

    @Test
    fun serverThatIgnoresMtimeDoesNotLoopForever() {
        // 服务端没肯保留修改时间：两边互相对不上，但各自都和上次记录一致，就是没动过
        val b = base(m(10, 1000), m(10, 9_000_000))
        assertEquals(SyncAct.NONE, act(m(10, 1000), m(10, 9_000_000), b, cfg()))
    }

    @Test
    fun newSourceFileIsCopiedOverInBothOneWayDirections() {
        assertEquals(SyncAct.UPLOAD, act(m(1, 1), null, null, cfg(SyncDirection.UPLOAD)))
        assertEquals(SyncAct.DOWNLOAD, act(null, m(1, 1), null, cfg(SyncDirection.DOWNLOAD)))
    }

    @Test
    fun extraFileOnDestinationIsKeptUnlessDeletesEnabled() {
        assertEquals(SyncAct.NONE, act(null, m(1, 1), null, cfg(SyncDirection.UPLOAD)))
        assertEquals(SyncAct.DELETE_REMOTE, act(null, m(1, 1), null, cfg(SyncDirection.UPLOAD, deletes = true)))
        assertEquals(SyncAct.DELETE_LOCAL, act(m(1, 1), null, null, cfg(SyncDirection.DOWNLOAD, deletes = true)))
    }

    // --- 单向的同名差异 ----------------------------------------------------------

    @Test
    fun plainUpdateDoesNotConsultConflictPolicy() {
        // 目的端自上次以来没人动过：就是普通更新，哪怕策略是「跳过」也要更新
        val b = base(m(10, 1000))
        val decision = SyncPlanner.decide(m(20, 5000), m(10, 1000), b, cfg(conflict = SyncConflict.SKIP))
        assertEquals(SyncAct.UPLOAD, decision.act)
        assertFalse(decision.backupDest)
    }

    @Test
    fun destinationEditedIndependentlyFollowsPolicy() {
        val b = base(m(10, 1000))
        val src = m(20, 5000)
        val dst = m(30, 9000)
        assertEquals(SyncAct.CONFLICT, act(src, dst, b, cfg(conflict = SyncConflict.SKIP)))
        assertEquals(SyncAct.UPLOAD, act(src, dst, b, cfg(conflict = SyncConflict.OVERWRITE)))
        // 目的端更新：较新者优先时不覆盖
        assertEquals(SyncAct.CONFLICT, act(src, dst, b, cfg(conflict = SyncConflict.NEWER)))
        // 源端更新：覆盖
        assertEquals(SyncAct.UPLOAD, act(m(20, 20_000), dst, b, cfg(conflict = SyncConflict.NEWER)))
        val keep = SyncPlanner.decide(src, dst, b, cfg(conflict = SyncConflict.KEEP_BOTH))
        assertEquals(SyncAct.UPLOAD, keep.act)
        assertTrue(keep.backupDest)
    }

    @Test
    fun firstRunWithDifferingFilesIsAConflictNotASilentOverwrite() {
        assertEquals(SyncAct.CONFLICT, act(m(1, 5000), m(2, 9000), null, cfg(conflict = SyncConflict.SKIP)))
    }

    // --- 双向 -------------------------------------------------------------------

    @Test
    fun twoWayPropagatesWhicheverSideChanged() {
        val c = cfg(SyncDirection.BOTH)
        val b = base(m(10, 1000))
        assertEquals(SyncAct.UPLOAD, act(m(11, 5000), m(10, 1000), b, c))
        assertEquals(SyncAct.DOWNLOAD, act(m(10, 1000), m(11, 5000), b, c))
    }

    @Test
    fun twoWayBothChangedFollowsPolicy() {
        val b = base(m(10, 1000))
        val l = m(11, 5000)
        val r = m(12, 9000)
        assertEquals(SyncAct.DOWNLOAD, act(l, r, b, cfg(SyncDirection.BOTH, SyncConflict.NEWER)))
        assertEquals(SyncAct.UPLOAD, act(m(11, 9000), m(12, 5000), b, cfg(SyncDirection.BOTH, SyncConflict.NEWER)))
        assertEquals(SyncAct.KEEP_BOTH, act(l, r, b, cfg(SyncDirection.BOTH, SyncConflict.KEEP_BOTH)))
        assertEquals(SyncAct.CONFLICT, act(l, r, b, cfg(SyncDirection.BOTH, SyncConflict.SKIP)))
        // 双向没有「源端」：以源端为准按较新者优先处理
        assertEquals(SyncAct.DOWNLOAD, act(l, r, b, cfg(SyncDirection.BOTH, SyncConflict.OVERWRITE)))
    }

    @Test
    fun twoWayTieGoesToLocal() {
        val b = base(m(10, 1000))
        assertEquals(SyncAct.UPLOAD, act(m(11, 5000), m(12, 5000), b, cfg(SyncDirection.BOTH)))
    }

    @Test
    fun twoWayDeletionOnlyPropagatesWhenEnabledAndUnmodified() {
        val b = base(m(10, 1000))
        val on = cfg(SyncDirection.BOTH, deletes = true)
        val off = cfg(SyncDirection.BOTH, deletes = false)
        // 远端删了，本地没动 → 删本地
        assertEquals(SyncAct.DELETE_LOCAL, act(m(10, 1000), null, b, on))
        // 远端删了，但本地删除之后又改过 → 改动比删除值钱，传回去
        assertEquals(SyncAct.UPLOAD, act(m(99, 8000), null, b, on))
        // 没开同步删除：绝不删，反而把文件补回去
        assertEquals(SyncAct.UPLOAD, act(m(10, 1000), null, b, off))
        // 对称
        assertEquals(SyncAct.DELETE_REMOTE, act(null, m(10, 1000), b, on))
        assertEquals(SyncAct.DOWNLOAD, act(null, m(10, 1000), b, off))
    }

    @Test
    fun twoWayFileMissingOneSideWithoutHistoryIsNew() {
        val on = cfg(SyncDirection.BOTH, deletes = true)
        assertEquals(SyncAct.UPLOAD, act(m(1, 1), null, null, on))
        assertEquals(SyncAct.DOWNLOAD, act(null, m(1, 1), null, on))
    }

    // --- 杂项 -------------------------------------------------------------------

    @Test
    fun ignoredNamesCoverOurPartialsAndNasInternals() {
        assertTrue(SyncPlanner.ignored("a.txt" + SyncPlanner.PART_SUFFIX))
        assertTrue(SyncPlanner.ignored("@Recycle"))
        assertTrue(SyncPlanner.ignored(".@__thumb"))
        assertFalse(SyncPlanner.ignored("notes.md"))
        assertFalse(SyncPlanner.ignored(".nomedia"))
    }

    @Test
    fun conflictNameKeepsExtensionAndDirectory() {
        assertEquals("a/b/note (冲突 20261018-153012).txt", SyncPlanner.conflictName("a/b/note.txt", "20261018-153012"))
        assertEquals("README (冲突 s)", SyncPlanner.conflictName("README", "s"))
        assertEquals(".env (冲突 s)", SyncPlanner.conflictName(".env", "s"))
    }

    @Test
    fun treeDocumentIdsMapToPaths() {
        val root = "/storage/emulated/0"
        assertEquals("/storage/emulated/0/Documents/notes", StoragePaths.fromDocumentId("primary:Documents/notes", root))
        assertEquals("/storage/emulated/0", StoragePaths.fromDocumentId("primary:", root))
        assertEquals("/storage/1A2B-3C4D/Backup", StoragePaths.fromDocumentId("1A2B-3C4D:Backup", root))
        assertNull(StoragePaths.fromDocumentId("raw:/data/x", root))
        assertNull(StoragePaths.fromDocumentId("nonsense", root))
    }
}
