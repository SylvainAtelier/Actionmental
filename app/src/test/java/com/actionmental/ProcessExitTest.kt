package com.actionmental

import android.app.ApplicationExitInfo
import com.actionmental.core.diag.ProcessExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定规则的回归测试。
 *
 * 起因是一条真实记录：进程被 ROM 以 `bgLimit_level_thermal_10` 结束，
 * reason 是 REASON_OTHER —— 当时被记成了一条 INFO，混在重装应用的噪音里，
 * 而它恰恰是唯一说明了「为什么快捷键忽然不灵」的那一条。
 */
class ProcessExitTest {

    private fun exit(reason: Int, description: String? = null, rss: Long = 0L) = ProcessExit(
        timestampMs = 1_000L,
        reason = reason,
        reasonLabel = "label",
        status = 0,
        description = description,
        pssKb = 0L,
        rssKb = rss,
        trace = null,
    )

    @Test
    fun vendorPolicyKillCountsAsAbnormal() {
        val killed = exit(ApplicationExitInfo.REASON_OTHER, "[UNKNOWN] bgLimit_level_thermal_10[(service)]")

        assertTrue(killed.abnormal)
    }

    @Test
    fun crashAnrAndLowMemoryAreAbnormal() {
        assertTrue(exit(ApplicationExitInfo.REASON_CRASH).abnormal)
        assertTrue(exit(ApplicationExitInfo.REASON_CRASH_NATIVE).abnormal)
        assertTrue(exit(ApplicationExitInfo.REASON_ANR).abnormal)
        assertTrue(exit(ApplicationExitInfo.REASON_LOW_MEMORY).abnormal)
    }

    /** 重装与用户主动结束是预期之内的，不该报成问题 —— 否则真的问题会被淹掉。 */
    @Test
    fun packageUpdateAndUserActionAreNotAbnormal() {
        val reinstall = exit(16, "stop com.actionmental.debug due to installPackageLI")

        assertFalse(reinstall.abnormal)
        assertFalse(exit(ApplicationExitInfo.REASON_USER_REQUESTED).abnormal)
        assertFalse(exit(ApplicationExitInfo.REASON_EXIT_SELF).abnormal)
    }

    /** 摘要一行里就要能看到真正的原因和当时的占用，不必展开详情。 */
    @Test
    fun summaryCarriesDescriptionAndFootprint() {
        val killed = exit(
            ApplicationExitInfo.REASON_OTHER,
            "[UNKNOWN] bgLimit_level_thermal_10[(service)]",
            rss = 1_000_704L,
        )

        val summary = killed.summary()

        assertTrue(summary.contains("bgLimit_level_thermal_10"))
        assertTrue(summary.contains("977MB"))
    }
}
