package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncConflict
import com.actionmental.core.sync.SyncDirection
import com.actionmental.core.sync.SyncFailure
import com.actionmental.core.sync.SyncReport
import com.actionmental.core.sync.SyncSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncActionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val config = SyncConfig(
        key = "abc",
        localPath = "/storage/emulated/0/Documents/notes",
        host = "192.168.1.10",
        share = "Public",
        remotePath = "Backup/notes",
        username = "me",
        direction = SyncDirection.BOTH,
        deletes = true,
        conflict = SyncConflict.KEEP_BOTH,
    )

    @Test
    fun actionSurvivesSerializationWithoutAnyPassword() {
        val action: Action = Action.FolderSync(config)
        val raw = json.encodeToString(Action.serializer(), action)

        assertEquals(action, json.decodeFromString(Action.serializer(), raw))
        assertTrue(raw.contains("\"type\":\"sync\""))
        assertFalse("配置里没有密码字段，导出与备份都不会带出密码", raw.contains("password", ignoreCase = true))
    }

    @Test
    fun oldConfigWithOnlyRequiredFieldsStillLoadsWithSafeDefaults() {
        val action = json.decodeFromString(
            Action.serializer(),
            """{"type":"sync","config":{"key":"k","localPath":"/a","host":"h","share":"s"}}""",
        ) as Action.FolderSync

        assertEquals(SyncDirection.UPLOAD, action.config.direction)
        assertFalse("默认不同步删除", action.config.deletes)
        assertTrue("默认仅 Wi-Fi", action.config.wifiOnly)
        assertEquals(445, action.config.port)
    }

    @Test
    fun labelsDescribeBothEndsAndDirection() {
        val action = Action.FolderSync(config)
        assertEquals("SYNC_BOTH", action.technical)
        assertEquals("notes ⇄ //192.168.1.10/Public/Backup/notes", action.detail)
        assertEquals("notes ⇄ 192.168.1.10", action.config.displayName)
        assertEquals("夜间备份", DesktopName.of(Action.FolderSync(config.copy(title = "夜间备份"))))
        assertFalse(DesktopName.isBuiltIn(action))
    }

    @Test
    fun overwriteMeansNewerWhenThereIsNoSourceSide() {
        assertEquals(SyncConflict.NEWER, config.copy(conflict = SyncConflict.OVERWRITE).effectiveConflict)
        assertEquals(
            SyncConflict.OVERWRITE,
            config.copy(direction = SyncDirection.UPLOAD, conflict = SyncConflict.OVERWRITE).effectiveConflict,
        )
    }

    @Test
    fun startingShowsASpinnerThatIsLaterReplacedByTheResultCard() {
        val feedback = TriggerFeedback.of(Action.FolderSync(config), ActionResult.OK, { it })
        assertNotNull(feedback)
        assertTrue(feedback!!.pending)
        assertNotNull(feedback.then)

        val failed = TriggerFeedback.of(
            Action.FolderSync(config),
            ActionResult.Failed(ActionResult.Reason.SYNC_RUNNING),
            { it },
        )!!
        assertTrue(failed.failed)
        assertNull(failed.then)
    }

    @Test
    fun summaryStatesWhatHappenedInOneLine() {
        val ok = SyncReport(uploaded = 12, downloaded = 3, deletedRemote = 1, conflicts = 2, bytes = 4_400_000, durationMs = 3_100)
        assertEquals("上传 12 · 下载 3 · 删除 1 · 冲突 2", SyncSummary.line(ok) { it })
        assertEquals("4.2 MB · 3.1s", SyncSummary.meta(ok))
        assertEquals("已是最新 · 40 个文件", SyncSummary.line(SyncReport(unchanged = 40)) { it })

        val failed = SyncReport(uploaded = 1, failures = List(7) { SyncFailure("f$it.txt", "没有权限") })
        assertEquals("上传 1 · 失败 7", SyncSummary.line(failed) { it })
        assertFalse(failed.succeeded)
        val detail = SyncSummary.failures(failed, limit = 5, t = { it })
        assertEquals(6, detail.lines().size)
        assertTrue(detail.endsWith("另有 2 条"))

        val aborted = SyncReport(aborted = "用户名或密码不对")
        assertEquals("用户名或密码不对", SyncSummary.line(aborted) { it })
        assertEquals("夜间 · 同步未完成", SyncSummary.title("夜间", aborted) { it })
    }
}
