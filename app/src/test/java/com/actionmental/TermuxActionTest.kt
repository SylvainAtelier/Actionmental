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
import org.junit.Assert.assertNull
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

    @Test
    fun `后台命令发出后一直转着，等太久才换成仍在运行`() {
        val sent = TriggerFeedback.of(Action.Termux("sleep 60", title = "等"), ActionResult.Ok(), identity)!!
        assertTrue(sent.pending)
        assertEquals(TriggerFeedback.RUNNING_HOLD_MS, sent.holdMs)
        val later = sent.then!!
        assertFalse(later.pending)
        assertEquals("仍在运行 · 结果见通知", later.value)

        val session = TriggerFeedback.of(Action.Termux("htop", background = false), ActionResult.Ok(), identity)!!
        assertFalse(session.pending)
        assertNull(session.then)
    }

    @Test
    fun `成功的结果卡给耗时和输出末尾几行`() {
        val output = (1..9).joinToString("\n") { "line $it" }
        val card = TriggerFeedback.of("同步", TermuxOutcome(stdout = output, exitCode = 0, durationMs = 1_234), identity)
        assertTrue(card.succeeded)
        assertFalse(card.failed)
        assertEquals("1.2s", card.meta)
        assertEquals((6..9).joinToString("\n") { "line $it" }, card.output)
        assertEquals(TriggerFeedback.LONG_HOLD_MS + 4 * TriggerFeedback.PER_LINE_MS, card.holdMs)
    }

    @Test
    fun `成功但没有输出时不展开，只留结论`() {
        val card = TriggerFeedback.of("清理", TermuxOutcome(exitCode = 0, durationMs = 300), identity)
        assertNull(card.output)
        assertEquals("0.3s", card.meta)
        assertEquals(TriggerFeedback.SHORT_HOLD_MS, card.holdMs)
    }

    @Test
    fun `失败的结果卡给退出码和 stderr，停得更久`() {
        val card = TriggerFeedback.of(
            "同步",
            TermuxOutcome(stdout = "partial", stderr = "fatal: boom", exitCode = 128, durationMs = 4_800),
            identity,
        )
        assertTrue(card.failed)
        assertFalse(card.succeeded)
        assertEquals("退出码 128 · 4.8s", card.meta)
        assertEquals("fatal: boom", card.output)
        assertEquals(TriggerFeedback.FAILED_OUTPUT_HOLD_MS + TriggerFeedback.PER_LINE_MS, card.holdMs)

        val long = TriggerFeedback.of("同步", TermuxOutcome(stderr = "x\n".repeat(50), exitCode = 1), identity)
        assertEquals(TriggerFeedback.FAILED_OUTPUT_HOLD_MS + TriggerFeedback.OUTPUT_LINES * TriggerFeedback.PER_LINE_MS, long.holdMs)
        assertEquals("退出码 1", long.meta)
    }

    @Test
    fun `Termux 拒绝时结果卡转述原因`() {
        val card = TriggerFeedback.of("同步", TermuxOutcome(err = 2, errmsg = "allow-external-apps is not true"), identity)
        assertTrue(card.failed)
        assertEquals("Termux 拒绝执行", card.meta)
        assertEquals("allow-external-apps is not true", card.output)
    }

    @Test
    fun `命令输出原样显示，不过翻译表`() {
        val shout: (String) -> String = { "EN:$it" }
        val card = TriggerFeedback.of("同步", TermuxOutcome(stdout = "执行完成", exitCode = 0), shout)
        assertEquals("执行完成", card.output)
    }

    @Test
    fun `计时写法随长度换档`() {
        assertEquals("0.0s", TriggerFeedback.seconds(0))
        assertEquals("9.9s", TriggerFeedback.seconds(9_949))
        assertEquals("12s", TriggerFeedback.seconds(12_900))
        assertEquals("1m05s", TriggerFeedback.seconds(65_000))
    }
}
