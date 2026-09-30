package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.rotation.RotationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerFeedbackTest {

    private val identity: (String) -> String = { it }

    @Test
    fun `启动应用只显示应用名并带包名取图标`() {
        val feedback = TriggerFeedback.of(
            Action.LaunchApp("com.example.notes", appLabel = "笔记"),
            ActionResult.Ok("已启动 笔记"),
            identity,
        )!!
        assertEquals("笔记", feedback.title)
        assertNull(feedback.value)
        assertEquals("com.example.notes", feedback.packageName)
        assertEquals(TriggerFeedback.SHORT_HOLD_MS, feedback.holdMs)
    }

    @Test
    fun `复制地址显示剪贴板里的原文`() {
        val feedback = TriggerFeedback.of(
            Action.WirelessDebug(Action.WirelessDebug.Target.ADDRESS),
            ActionResult.Ok("已复制 192.168.1.8:37215", copied = "192.168.1.8:37215"),
            identity,
        )!!
        assertEquals("已复制", feedback.title)
        assertEquals("192.168.1.8:37215", feedback.value)
        assertEquals(TriggerFeedback.LONG_HOLD_MS, feedback.holdMs)
    }

    @Test
    fun `复制失败如实给出原因`() {
        val feedback = TriggerFeedback.of(
            Action.WirelessDebug(Action.WirelessDebug.Target.PORT),
            ActionResult.Failed(ActionResult.Reason.WIRELESS_DEBUG_OFF),
            identity,
        )!!
        assertTrue(feedback.failed)
        assertEquals("无线调试未开启", feedback.title)
    }

    @Test
    fun `自带反馈的动作不提示`() {
        assertNull(TriggerFeedback.of(Action.Rotation(Action.Rotation.Op.SET, RotationMode.forced.first()), ActionResult.OK, identity))
        assertNull(TriggerFeedback.of(Action.Volume(Action.Volume.Target.UP), ActionResult.OK, identity))
    }
}
