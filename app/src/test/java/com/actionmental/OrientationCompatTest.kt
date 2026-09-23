package com.actionmental

import com.actionmental.core.action.ActionResult
import com.actionmental.core.rotation.OrientationCompatKeeper
import com.actionmental.core.rotation.OrientationCompatTarget
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import com.actionmental.platform.UnavailableBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationCompatTest {

    /** 模拟 platform_compat：记着哪些包上挂着 OVERRIDE_ANY_ORIENTATION_TO_USER。 */
    private class FakeCompat(vararg on: String) : PrivilegedBackend {
        val overridden = on.toMutableSet()
        val commands = mutableListOf<String>()

        override fun availability(): ActionResult = ActionResult.OK

        override suspend fun exec(command: String): Result<ShellResult> {
            commands += command
            val change = OrientationCompatKeeper.CHANGE
            val out = when {
                command.startsWith("dumpsys platform_compat") ->
                    "ChangeId(310816437; name=$change; disabled; packageOverrides={" +
                        overridden.joinToString(", ") { "$it=true" } + ", com.off=false}; rawOverrides={}; overridable)"
                command.startsWith("am compat enable $change ") -> {
                    overridden += command.substringAfterLast(' ')
                    "Enabled change 310816437 for " + command.substringAfterLast(' ') + "."
                }
                command.startsWith("am compat reset $change ") -> {
                    overridden -= command.substringAfterLast(' ')
                    "Reset change 310816437 for " + command.substringAfterLast(' ') + " to default value."
                }
                else -> ""
            }
            return Result.success(ShellResult(0, out))
        }

        override suspend fun injectKey(keyCode: Int, metaState: Int) = Result.success(Unit)
        override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean) = Result.success(Unit)
        override suspend fun getSetting(namespace: String, key: String) = Result.success<String?>(null)
        override suspend fun putSetting(namespace: String, key: String, value: String) = Result.success(Unit)
    }

    private fun target(pkg: String, enabled: Boolean = true) = OrientationCompatTarget(pkg, pkg, enabled)

    @Test
    fun `解析出覆盖为 true 的包`() {
        // 取自 PKH120 · ColorOS 16
        val line = "ChangeId(310816437; name=OVERRIDE_ANY_ORIENTATION_TO_USER; disabled; " +
            "packageOverrides={com.phoenix.read=true, com.foo=false}; rawOverrides={com.phoenix.read=true}; overridable)"
        assertEquals(setOf("com.phoenix.read"), OrientationCompatKeeper.parseOverridden(line))
        assertEquals(emptySet<String>(), OrientationCompatKeeper.parseOverridden(""))
    }

    @Test
    fun `缺了才施加 已在的不重复写`() = runBlocking {
        val shell = FakeCompat("com.phoenix.read")
        val steps = OrientationCompatKeeper { shell }.sync(listOf(target("com.phoenix.read"), target("com.other")))!!

        assertEquals(listOf("com.other"), steps.map { it.packageName })
        assertTrue(steps.single().ok)
        assertEquals("一次读 + 一次写", 2, shell.commands.size)
        assertTrue("com.other" in shell.overridden)
    }

    @Test
    fun `停用的撤掉 名单外的不碰`() = runBlocking {
        // com.manual 是诊断页按钮手动施加的，不归名单管
        val shell = FakeCompat("com.phoenix.read", "com.manual")
        OrientationCompatKeeper { shell }.sync(listOf(target("com.phoenix.read", enabled = false)))

        assertEquals(setOf("com.manual"), shell.overridden)
    }

    @Test
    fun `名单为空时一条 shell 都不跑`() = runBlocking {
        val shell = FakeCompat()
        OrientationCompatKeeper { shell }.sync(emptyList())
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `没有 Shizuku 时什么都不做`() = runBlocking {
        val keeper = OrientationCompatKeeper { UnavailableBackend(ActionResult.Reason.SHIZUKU_NOT_RUNNING) }
        assertNull(keeper.sync(listOf(target("com.phoenix.read"))))
    }
}
