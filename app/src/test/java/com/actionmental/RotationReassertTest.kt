package com.actionmental

import com.actionmental.core.action.ActionResult
import com.actionmental.core.diag.OrientationProbe
import com.actionmental.core.rotation.RotationController
import com.actionmental.core.rotation.RotationMode
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强制方向被 SystemUI 悄悄改掉之后能不能自己回来（ColorOS 平板 · 红果短剧）。
 *
 * 现场：强制 270° 时红果的竖屏请求被系统放行，屏幕转到 0°，SystemUI 的
 * RotationButtonController 随即把 user_rotation 改成 0。离开红果后整机停在竖屏。
 */
class RotationReassertTest {

    /** 只认旋转控制器会发的那几条命令，按批量脚本的格式回显退出码。 */
    private class FakeShell : PrivilegedBackend {
        val table = mutableMapOf("user_rotation" to 0, "accelerometer_rotation" to 1)
        var ignore = false
        var execs = 0

        override fun availability(): ActionResult = ActionResult.OK

        override suspend fun exec(command: String): Result<ShellResult> {
            execs++
            val out = StringBuilder()
            for (line in command.lines()) {
                when {
                    line.startsWith("echo ") -> out.append("__AM_RC__0\n")
                    line == "cmd window get-ignore-orientation-request" ->
                        out.append("ignoreOrientationRequest ").append(ignore).append(" for displayId=0\n")
                    line.startsWith("cmd window set-ignore-orientation-request ") ->
                        ignore = line.endsWith("true")
                    line.startsWith("cmd window user-rotation lock ") -> {
                        table["accelerometer_rotation"] = 0
                        table["user_rotation"] = line.substringAfterLast(' ').toInt()
                    }
                    line.startsWith("settings put system ") -> {
                        val (key, value) = line.removePrefix("settings put system ").split(' ')
                        table[key] = value.toInt()
                    }
                }
            }
            return Result.success(ShellResult(0, out.toString()))
        }

        override suspend fun injectKey(keyCode: Int, metaState: Int) = Result.success(Unit)
        override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean) = Result.success(Unit)
        override suspend fun getSetting(namespace: String, key: String) = Result.success<String?>(null)
        override suspend fun putSetting(namespace: String, key: String, value: String) = Result.success(Unit)
    }

    private fun controller(shell: FakeShell) = RotationController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        backend = { shell },
        settings = { key -> shell.table[key] },
        persistGlobalMode = {},
    ).also { runBlocking { it.setPaused(false) } }

    @Test
    fun `强制期间锁定角度被改掉 切应用时补写回来`() = runBlocking {
        val shell = FakeShell()
        val c = controller(shell)
        assertTrue(c.setGlobal(RotationMode.FORCE_REVERSE_LANDSCAPE).succeeded)
        assertEquals(3, shell.table["user_rotation"])

        // SystemUI 跟着被放行的竖屏请求改写锁定角度；ContentObserver 让缓存作废
        shell.table["user_rotation"] = 0
        c.invalidateObservedState()

        assertTrue(c.reassertForced().succeeded)
        assertEquals(3, shell.table["user_rotation"])
        assertEquals(RotationMode.FORCE_REVERSE_LANDSCAPE, c.state.value.mode)
    }

    @Test
    fun `系统没被动过就一条都不写`() = runBlocking {
        val shell = FakeShell()
        val c = controller(shell)
        c.setGlobal(RotationMode.FORCE_LANDSCAPE)
        val before = shell.execs

        c.reassertForced()
        assertEquals("缓存仍然有效时不该再 fork shell", before, shell.execs)
    }

    @Test
    fun `不强制时不和用户抢快捷设置里的开关`() = runBlocking {
        val shell = FakeShell()
        val c = controller(shell)
        c.setGlobal(RotationMode.NORMAL)

        // 用户在快捷设置里关掉了自动旋转
        shell.table["accelerometer_rotation"] = 0
        c.invalidateObservedState()

        c.reassertForced()
        assertEquals(0, shell.table["accelerometer_rotation"])
        assertFalse(c.forcing)
    }

    @Test
    fun `暂停期间不补写`() = runBlocking {
        val shell = FakeShell()
        val c = controller(shell)
        c.setGlobal(RotationMode.FORCE_LANDSCAPE)
        c.setPaused(true)

        shell.table["user_rotation"] = 0
        c.invalidateObservedState()
        c.reassertForced()
        assertEquals(0, shell.table["user_rotation"])
    }

    @Test
    fun `探测到方向被应用拉走`() {
        // 取自 PKH120 · ColorOS 16 的真实输出
        val probe = OrientationProbe.parse(
            """
              deepestLastOrientationSource=ActivityRecord{236119278 u0 com.phoenix.read/com.dragon.read.pages.main.MainFragmentActivity t31776}
              mHasSetIgnoreOrientationRequest=true ignoreOrientationRequest=true
                mCurrentAppOrientation=SCREEN_ORIENTATION_PORTRAIT
                mRotation=0 mDeferredRotationPauseCount=0
                mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_270 mCameraRotationMode=0 mAllowAllRotations=false
            """.trimIndent(),
        )
        assertEquals("com.phoenix.read", probe.sourcePackage)
        assertEquals("PORTRAIT", probe.requested)
        assertEquals(0, probe.rotation)
        assertEquals(3, probe.userRotation)
        assertTrue(probe.pulledAway(expected = 3))
        assertTrue("不知道意图时拿锁定角度比", probe.pulledAway(expected = null))
    }

    @Test
    fun `锁定角度被 SystemUI 跟过去之后仍然认得出`() {
        val probe = OrientationProbe.parse(
            """
              deepestLastOrientationSource=ActivityRecord{236119278 u0 com.phoenix.read/com.dragon.read.pages.main.MainFragmentActivity t31776}
              mHasSetIgnoreOrientationRequest=true ignoreOrientationRequest=true
                mCurrentAppOrientation=SCREEN_ORIENTATION_PORTRAIT
                mRotation=0 mDeferredRotationPauseCount=0
                mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_0 mCameraRotationMode=0
            """.trimIndent(),
        )
        assertFalse("只看锁定角度会漏掉", probe.pulledAway(expected = null))
        assertTrue(probe.pulledAway(expected = 3))
    }

    @Test
    fun `压住了就不算被拉走 系统窗口没有包名`() {
        val probe = OrientationProbe.parse(
            """
              deepestLastOrientationSource=Window{3df9b12 u0 NotificationShade}
              mHasSetIgnoreOrientationRequest=true ignoreOrientationRequest=true
                mCurrentAppOrientation=SCREEN_ORIENTATION_USER
                mRotation=3 mDeferredRotationPauseCount=0
                mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_270 mCameraRotationMode=0
            """.trimIndent(),
        )
        assertNull(probe.sourcePackage)
        assertFalse(probe.pulledAway(expected = 3))
    }
}
