package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TermuxOutcome
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.platform.TermuxBackend
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxActionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val identity: (String) -> String = { it }

    @Test
    fun `Termux 动作能原样存回来`() {
        val action: Action = Action.Termux("~/bin/sync.sh --quiet", title = "同步", background = false)
        val decoded = json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), action))
        assertEquals(action, decoded)
    }

    @Test
    fun `旧配置里没有 background 字段时默认后台执行`() {
        val decoded = json.decodeFromString(Action.serializer(), """{"type":"termux","command":"date"}""")
        assertEquals(Action.Termux("date"), decoded)
        assertTrue((decoded as Action.Termux).background)
    }

    @Test
    fun `命令先加载 bashrc 再执行，原文经参数传入不拼接`() {
        val command = "ncm \"a b\" | tail -n 1"
        val args = TermuxBackend.arguments(command)
        assertEquals("-c", args[0])
        assertTrue(args[1].contains(". ~/.bashrc"))
        assertTrue(args[1].contains("expand_aliases"))
        assertEquals(command, args.last())
        assertFalse(args[1].contains(command))
    }

    @Test
    fun `没有名称时用命令本身当名字`() {
        assertEquals("pkg upgrade -y", Action.Termux("pkg upgrade -y").displayName)
        assertEquals("升级", Action.Termux("pkg upgrade -y", title = "升级").displayName)
    }

    @Test
    fun `目录里有 Termux 一级入口`() {
        assertTrue(ActionCatalog.groups.any { it.id == ActionCatalog.GROUP_TERMUX && it.direct })
    }

    @Test
    fun `成功时通知给 stdout 的最后几行`() {
        val output = (1..20).joinToString("\n") { "line $it" }
        val outcome = TermuxOutcome(stdout = output + "\n", exitCode = 0)
        assertTrue(outcome.succeeded)
        assertEquals("同步 · 执行完成", outcome.title("同步", identity))
        val body = outcome.body(identity)
        assertEquals(TermuxOutcome.MAX_LINES, body.lines().size)
        assertEquals("line 20", outcome.summary(identity))
    }

    @Test
    fun `非零退出码优先给 stderr`() {
        val outcome = TermuxOutcome(stdout = "partial", stderr = "boom", exitCode = 2)
        assertFalse(outcome.succeeded)
        assertEquals("同步 · 退出码 2", outcome.title("同步", identity))
        assertEquals("boom", outcome.body(identity))
    }

    @Test
    fun `Termux 自己拒绝时转述它的原因`() {
        val outcome = TermuxOutcome(err = 2, errmsg = "allow-external-apps is not true")
        assertFalse(outcome.executed)
        assertEquals("同步 · Termux 拒绝执行", outcome.title("同步", identity))
        assertEquals("allow-external-apps is not true", outcome.body(identity))
    }

    @Test
    fun `没有输出时明说`() {
        assertEquals("没有输出", TermuxOutcome(exitCode = 0).body(identity))
    }

    @Test
    fun `超长单行被截断`() {
        val outcome = TermuxOutcome(stdout = "x".repeat(5_000), exitCode = 0)
        assertTrue(outcome.body(identity).length <= TermuxOutcome.MAX_CHARS + 1)
    }

    @Test
    fun `触发时顶部提示带 Termux 图标`() {
        val sent = TriggerFeedback.of(Action.Termux("date", title = "时间"), ActionResult.Ok("已交给 Termux 执行"), identity)!!
        assertEquals("时间", sent.title)
        assertEquals("com.termux", sent.packageName)

        val denied = TriggerFeedback.of(
            Action.Termux("date"),
            ActionResult.Failed(ActionResult.Reason.TERMUX_PERMISSION_DENIED),
            identity,
        )!!
        assertTrue(denied.failed)
        assertEquals("未授予 Termux 执行权限", denied.value)
    }
}
