package com.actionmental

import com.actionmental.core.hardening.HardeningState
import com.actionmental.core.hardening.HardeningStep
import com.actionmental.core.hardening.appendComponent
import com.actionmental.core.hardening.containsComponent
import com.actionmental.core.hardening.normalizeComponent
import com.actionmental.core.hardening.parseAppOp
import com.actionmental.core.hardening.parseDozeWhitelist
import com.actionmental.core.hardening.removeComponent
import com.actionmental.core.action.ActionResult
import com.actionmental.core.hardening.HardeningController
import com.actionmental.core.hardening.HealOutcome
import com.actionmental.core.hardening.HealTrigger
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import com.actionmental.platform.shizuku.settingsWriteCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PKG = "com.actionmental"
private const val COMPONENT = PKG + "/com.actionmental.service.KeyboardAccessibilityService"
private const val OTHER = "com.other/.OtherService"

class HardeningTest {

    // --- 无障碍开关串的解析与追加 --------------------------------------------

    @Test
    fun shortFormComponentMatchesFullForm() {
        val short = PKG + "/.service.KeyboardAccessibilityService"

        assertEquals(normalizeComponent(COMPONENT), normalizeComponent(short))
        assertTrue(containsComponent(short, COMPONENT))
    }

    @Test
    fun otherServicesSurviveTheAppend() {
        val existing = "com.other/.A11yService:com.third/com.third.Service"

        val next = appendComponent(existing, COMPONENT)

        assertEquals(existing + ":" + COMPONENT, next)
        assertTrue(containsComponent(next, "com.other/.A11yService"))
    }

    @Test
    fun appendIsIdempotent() {
        val once = appendComponent("com.other/.A11yService", COMPONENT)

        assertEquals(once, appendComponent(once, COMPONENT))
    }

    @Test
    fun emptySwitchYieldsJustOurComponent() {
        assertEquals(COMPONENT, appendComponent(null, COMPONENT))
        assertEquals(COMPONENT, appendComponent("", COMPONENT))
    }

    @Test
    fun similarPackageNamesDoNotMatch() {
        val other = "com.actionmental.pro/com.actionmental.pro.service.KeyboardAccessibilityService"

        assertFalse(containsComponent(other, COMPONENT))
    }

    @Test
    fun removeTakesOnlyOurComponentOut() {
        val existing = "com.other/.A11yService:" + COMPONENT + ":com.third/com.third.Service"

        val without = removeComponent(existing, COMPONENT)

        assertFalse(containsComponent(without, COMPONENT))
        assertTrue(containsComponent(without, "com.other/.A11yService"))
        assertTrue(containsComponent(without, "com.third/com.third.Service"))
    }

    /** 摘掉再写回就是「关掉再打开」；除了顺序，那一串必须和原来一模一样。 */
    @Test
    fun removeThenAppendKeepsEveryOtherService() {
        val existing = "com.other/.A11yService:" + COMPONENT + ":com.third/com.third.Service"

        val cycled = appendComponent(removeComponent(existing, COMPONENT), COMPONENT)

        assertEquals(
            existing.split(':').sorted(),
            cycled.split(':').sorted(),
        )
    }

    @Test
    fun removeIsSafeOnEmptyAndAbsent() {
        assertEquals("", removeComponent(null, COMPONENT))
        assertEquals("com.other/.A11yService", removeComponent("com.other/.A11yService", COMPONENT))
    }

    // --- shell 输出解析 -------------------------------------------------------

    @Test
    fun dozeWhitelistMatchesWholePackageOnly() {
        val output = "system-excidle,com.android.providers.downloads,10011\n" +
            "user,com.actionmental,10234"

        assertTrue(parseDozeWhitelist(output, PKG))
        assertFalse(parseDozeWhitelist("user,com.actionmental.pro,10235", PKG))
    }

    @Test
    fun appOpDefaultsToAllowedWhenSystemSaysNothing() {
        // 这个 op 的系统默认就是 allow；把「查不到」当成没生效会让界面长期误报
        assertTrue(parseAppOp("No operations."))
        assertTrue(parseAppOp("RUN_IN_BACKGROUND: allow"))
        assertFalse(parseAppOp("RUN_IN_BACKGROUND: deny"))
        assertFalse(parseAppOp("RUN_ANY_IN_BACKGROUND: ignore"))
    }

    // --- 状态三态 -------------------------------------------------------------

    @Test
    fun unknownIsNotTheSameAsNotHardened() {
        val unknown = HardeningState()

        assertFalse(unknown.checked)
        assertFalse(unknown.hardened)
        assertNull(unknown.valueOf(HardeningStep.DOZE_WHITELIST))
    }

    @Test
    fun hardenedRequiresAllThreeConfirmed() {
        val partial = HardeningState(
            checkedAtMs = 1L,
            dozeWhitelisted = true,
            runInBackground = true,
            runAnyInBackground = null,
        )

        assertTrue(partial.checked)
        assertFalse(partial.hardened)
        assertTrue(partial.copy(runAnyInBackground = true).hardened)
    }

    // --- 写回系统设置的命令 ----------------------------------------------------

    /**
     * 这一条是「熄屏后失灵、自动恢复毫无作用」的根因。
     *
     * 重绑的第一步是把组件摘掉写回去，而设备上只装了这一个无障碍服务时，
     * 摘完就是空串。空串直接拼进命令会少一个参数，系统回 `Bad arguments`，
     * 于是重绑每次都倒在第一步 —— 用户只能自己去系统设置里关掉再打开。
     */
    @Test
    fun emptyValueDeletesInsteadOfWritingAnIncompleteCommand() {
        val command = settingsWriteCommand("secure", "enabled_accessibility_services", "")

        assertEquals("settings delete secure enabled_accessibility_services", command)
    }

    @Test
    fun valuesAreQuotedSoSpacesCannotSplitTheCommand() {
        assertEquals(
            "settings put secure enabled_accessibility_services '" + COMPONENT + "'",
            settingsWriteCommand("secure", "enabled_accessibility_services", COMPONENT),
        )
        assertEquals("settings put secure k 'a b'", settingsWriteCommand("secure", "k", "a b"))
        // 值里的单引号得先闭合、转义、再开一个新的：'it'\''s'
        assertEquals("""settings put secure k 'it'\''s'""", settingsWriteCommand("secure", "k", "it's"))
    }

    // --- 重绑的冷却 ------------------------------------------------------------

    /** 摘了又装回去是有代价的，真动过服务就得等满长冷却，否则键盘会反复失灵。 */
    @Test
    fun aSuccessfulRebindHoldsOffTheNextOneForTheFullCooldown() = runBlocking {
        val clock = FakeClock()
        val controller = controller(FakeBackend(), clock)

        assertTrue(controller.rebindAccessibilityAuto() is HealOutcome.Attempted)

        clock.advance(HardeningController.REBIND_COOLDOWN_MS - 1)
        assertTrue(controller.rebindAccessibilityAuto() is HealOutcome.Skipped)
        clock.advance(2)
        assertTrue(controller.rebindAccessibilityAuto() is HealOutcome.Attempted)
    }

    /**
     * 写都没写进去的失败没碰过服务，不该占满三分钟。
     *
     * 补救的时机本来就稀疏（亮屏、解除暂停、进程重启），让一次 Shizuku 抖动
     * 把后面每一个时机都堵在冷却上，等于把自动恢复整段废掉。
     */
    @Test
    fun aFailedWriteDoesNotBurnTheLongCooldown() = runBlocking {
        val clock = FakeClock()
        val backend = FakeBackend(writeFails = true)
        val controller = controller(backend, clock)

        assertFalse((controller.rebindAccessibilityAuto() as HealOutcome.Attempted).result.succeeded)

        clock.advance(HardeningController.REBIND_RETRY_MS + 1)
        assertTrue(controller.rebindAccessibilityAuto() is HealOutcome.Attempted)
    }

    private fun controller(backend: PrivilegedBackend, clock: FakeClock) = HardeningController(
        packageName = PKG,
        accessibilityComponent = COMPONENT,
        backend = { backend },
        journal = { _, _, _, _ -> },
        now = clock::now,
    )

    private class FakeClock {
        private var value = 1_000L
        fun now() = value
        fun advance(ms: Long) { value += ms }
    }

    private class FakeBackend(
        private val writeFails: Boolean = false,
        private val readFails: Boolean = false,
    ) : PrivilegedBackend {
        val writes = mutableListOf<Pair<String, String>>()
        override fun availability() = ActionResult.OK
        override suspend fun exec(command: String) = Result.success(ShellResult(0, ""))
        override suspend fun injectKey(keyCode: Int, metaState: Int) = Result.success(Unit)
        override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean) =
            Result.success(Unit)

        override suspend fun getSetting(namespace: String, key: String): Result<String?> =
            if (readFails) Result.failure(IllegalStateException("特权服务未连接"))
            else Result.success(if (key == "accessibility_enabled") "1" else OTHER + ":" + COMPONENT)

        override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
            writes += key to value
            return if (writeFails) Result.failure(IllegalStateException("Bad arguments")) else Result.success(Unit)
        }
    }

    /**
     * 读失败绝不能当成空串写回去。
     *
     * 重绑是「读出整串 → 摘掉自己 → 写回 → 再加上自己」。读失败时若按空串算，
     * 写回去的就只剩这一个组件，用户别的无障碍服务被悄悄关掉。
     */
    @Test
    fun rebindNeverWritesWhenTheReadFails() = runBlocking {
        val backend = FakeBackend(readFails = true)
        val result = controller(backend, FakeClock()).rebindAccessibility()

        assertFalse(result.succeeded)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun healNeverWritesWhenTheReadFails() = runBlocking {
        val backend = FakeBackend(readFails = true)
        val outcome = controller(backend, FakeClock()).healAccessibility(HealTrigger.MANUAL)

        assertFalse(outcome.succeeded)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun rebindKeepsOtherServices() = runBlocking {
        val backend = FakeBackend()
        controller(backend, FakeClock()).rebindAccessibility()

        val lastList = backend.writes.last { it.first == "enabled_accessibility_services" }.second
        assertTrue(lastList.contains(OTHER))
    }
}
