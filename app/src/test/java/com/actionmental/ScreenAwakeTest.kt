package com.actionmental

import com.actionmental.core.action.ActionResult
import com.actionmental.core.awake.ScreenAwakeController
import com.actionmental.core.awake.ScreenAwakeSwitch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕常亮和旋转遵守同一条规矩：显示出来的永远是回读到的事实，
 * 而不是「我点过开关」。这几条测试盯的就是这个分界。
 */
class ScreenAwakeTest {

    /** 一把听话的锁。 */
    private class FakeSwitch(
        override val supported: Boolean = true,
        /** 系统「假装接受了」的情形：调用成功，但锁根本没握住。 */
        private val silentlyIgnores: Boolean = false,
    ) : ScreenAwakeSwitch {
        var held = false
            private set

        override fun acquire(): Result<Unit> = runCatching { if (!silentlyIgnores) held = true }

        override fun release(): Result<Unit> = runCatching { held = false }

        override fun isHeld(): Boolean = held
    }

    @Test
    fun `开启会握住锁并记下意图`() = runBlocking {
        val switch = FakeSwitch()
        var persisted: Boolean? = null
        val controller = ScreenAwakeController(switch) { persisted = it }

        val result = controller.set(true)

        assertTrue(result.succeeded)
        assertTrue(switch.held)
        assertTrue(controller.state.value.on)
        assertEquals(true, persisted)
    }

    @Test
    fun `切换看的是回读到的事实`() = runBlocking {
        val switch = FakeSwitch()
        val controller = ScreenAwakeController(switch) {}

        controller.toggle()
        assertTrue(controller.state.value.on)

        controller.toggle()
        assertFalse(controller.state.value.on)
        assertFalse(switch.held)
    }

    @Test
    fun `系统没真的接受时如实报失败`() = runBlocking {
        val switch = FakeSwitch(silentlyIgnores = true)
        val controller = ScreenAwakeController(switch) {}

        val result = controller.set(true)

        assertFalse(result.succeeded)
        assertEquals(
            ActionResult.Reason.EXECUTION_FAILED,
            (result as ActionResult.Failed).reason,
        )
        assertFalse(controller.state.value.on)
    }

    @Test
    fun `拿不到唤醒锁时显示不可用而不是已关闭`() = runBlocking {
        val controller = ScreenAwakeController(FakeSwitch(supported = false)) {}

        val result = controller.set(true)

        assertEquals(
            ActionResult.Reason.UNSUPPORTED,
            (result as ActionResult.Failed).reason,
        )
        assertFalse(controller.state.value.available)
        assertEquals("不可用", controller.state.value.label)
    }

    @Test
    fun `重建意图只在上次开着时动手，且不再写一遍设置`() = runBlocking {
        val switch = FakeSwitch()
        var persisted = 0
        val controller = ScreenAwakeController(switch) { persisted++ }

        controller.restore(false)
        assertFalse(switch.held)

        controller.restore(true)
        assertTrue(switch.held)
        assertEquals(0, persisted)
    }
}
