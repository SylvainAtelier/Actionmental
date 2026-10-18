package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.data.UserSettings
import com.actionmental.ui.i18n.AppTranslations
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTcpipActionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val identity: (String) -> String = { it }

    @Test
    fun `ADB 网络调试动作能原样存回来`() {
        val action: Action = Action.AdbTcpip(port = 7001, enable = false, copyAddress = false)
        val decoded = json.decodeFromString(Action.serializer(), json.encodeToString(Action.serializer(), action))
        assertEquals(action, decoded)
    }

    @Test
    fun `只写了类型时默认开启 6677 并复制地址`() {
        val decoded = json.decodeFromString(Action.serializer(), """{"type":"adb_tcpip"}""")
        assertEquals(Action.AdbTcpip(6677, enable = true, copyAddress = true), decoded)
    }

    @Test
    fun `端口只认 1024 到 65535 的整数`() {
        assertEquals(6677, Action.AdbTcpip.parsePort(" 6677 "))
        assertEquals(1024, Action.AdbTcpip.parsePort("1024"))
        assertEquals(65535, Action.AdbTcpip.parsePort("65535"))
        assertNull(Action.AdbTcpip.parsePort("1023"))
        assertNull(Action.AdbTcpip.parsePort("65536"))
        assertNull(Action.AdbTcpip.parsePort(""))
        assertNull(Action.AdbTcpip.parsePort("66a"))
    }

    @Test
    fun `中文界面下标签里的端口号不会被吞掉`() {
        // 界面在中文下会丢掉 ` · ` 后面不含汉字的那一段，端口必须跟着「端口」二字
        val label = AppTranslations.translate(Action.AdbTcpip(6677).label, UserSettings.Language.CHINESE)
        assertTrue(label, label.contains("6677"))
        val english = AppTranslations.translate(Action.AdbTcpip(6677).label, UserSettings.Language.ENGLISH)
        assertFalse(english, english.contains(Regex("[\\p{IsHan}]")))
        assertTrue(english, english.contains("6677"))
    }

    @Test
    fun `目录里有一级入口且另开编辑流程`() {
        assertTrue(ActionCatalog.groups.any { it.id == ActionCatalog.GROUP_ADB_TCPIP && it.direct })
    }

    @Test
    fun `按下先转圈，复制了地址就逐字显示并注明`() {
        val action = Action.AdbTcpip(6677)
        assertTrue(TriggerFeedback.starting(action, identity)!!.pending)

        val copied = TriggerFeedback.of(
            action,
            ActionResult.Ok("已开启 · 端口 6677 · 已复制 192.168.1.5:6677", copied = "192.168.1.5:6677"),
            identity,
        )!!
        assertEquals("192.168.1.5:6677", copied.value)
        assertEquals("已复制", copied.meta)
        assertTrue(copied.succeeded)
    }

    @Test
    fun `失败时原因与细节分开`() {
        val feedback = TriggerFeedback.of(
            Action.AdbTcpip(6677),
            ActionResult.Failed(ActionResult.Reason.ADB_PAIRING_FAILED, "Connection refused"),
            identity,
        )!!
        assertTrue(feedback.failed)
        assertEquals(ActionResult.Reason.ADB_PAIRING_FAILED.message, feedback.value)
        assertEquals("Connection refused", feedback.output)
    }

    @Test
    fun `其余动作按下时不先挂提示`() {
        assertNull(TriggerFeedback.starting(Action.ClipboardHistory, identity))
    }
}
