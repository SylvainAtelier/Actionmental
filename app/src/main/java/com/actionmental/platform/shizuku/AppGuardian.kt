package com.actionmental.platform.shizuku

import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 运行在特权进程里：主进程一死，立刻把它拉回来。
 *
 * ColorOS 的 Athena 在内存清理（o-kill 4008）时连 adj=100、前台服务、后台保护名单里的
 * 本应用都照杀，应用内没有任何手段躲得开；而 uid 2000 的进程不在它的查杀范围里。
 * 系统自己也会重启无障碍服务，但要 1–30 秒，连续被杀时还按退避越拉越长。
 *
 * 拉起的办法是经 shell 访问本应用导出的 ShizukuProvider（它要求的 INTERACT_ACROSS_USERS_FULL
 * shell 持有）：provider 一被访问，系统就起进程，挂着待重启的无障碍服务随进程一起绑回来。
 *
 * 用户的意图优先：无障碍里关掉了本应用、或应用被强行停止，就不拉。
 * 和清理策略对抗要有节制：短时间内反复死，按 [backoffMs] 退避。
 */
class AppGuardian(private val exec: (String) -> String) {

    private companion object {
        /** 拉起后等主进程重新 [guard] 的时限；超时说明这次没起来，再试一次。 */
        const val REATTACH_TIMEOUT_MS = 15_000L

        /** 两次死亡间隔短于它，算作连续被杀。 */
        const val STREAK_WINDOW_MS = 60_000L
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val lock = Object()

    private var client: IBinder? = null
    private var packageName: String? = null

    /** 每次 [guard] / [unguard] 加一：旧的一轮拉起看到它变了就收手。 */
    private var generation = 0
    private var lastDeathAt = 0L
    private var streak = 0

    private val recipient = IBinder.DeathRecipient { onClientDied() }

    fun guard(binder: IBinder, pkg: String) = synchronized(lock) {
        client?.unlinkToDeath(recipient, 0)
        binder.linkToDeath(recipient, 0)
        client = binder
        packageName = pkg
        generation++
        lock.notifyAll()
    }

    fun unguard() = synchronized(lock) {
        client?.unlinkToDeath(recipient, 0)
        client = null
        packageName = null
        generation++
        lock.notifyAll()
    }

    private fun onClientDied() {
        val (pkg, gen, attempt) = synchronized(lock) {
            val pkg = packageName ?: return
            client = null
            val now = System.currentTimeMillis()
            streak = if (now - lastDeathAt < STREAK_WINDOW_MS) streak + 1 else 0
            lastDeathAt = now
            Triple(pkg, generation, streak)
        }
        worker.schedule({ revive(pkg, gen, attempt) }, backoffMs(attempt), TimeUnit.MILLISECONDS)
    }

    /** 拉起，直到主进程重新 [guard] 回来、或者用户意图不再要求它活着。 */
    private fun revive(pkg: String, gen: Int, firstAttempt: Int) {
        var attempt = firstAttempt
        while (true) {
            if (!stillWanted(pkg)) return
            exec("content call --uri content://$pkg.shizuku --method wake")
            val reattached = synchronized(lock) {
                val deadline = System.currentTimeMillis() + REATTACH_TIMEOUT_MS
                while (generation == gen) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
                generation != gen
            }
            if (reattached) return
            attempt++
            Thread.sleep(backoffMs(attempt))
            if (synchronized(lock) { generation != gen }) return
        }
    }

    private fun stillWanted(pkg: String): Boolean {
        val services = exec("settings get secure enabled_accessibility_services").substringAfter('\n')
        val listed = services.split(':').any { it.trim().substringBefore('/') == pkg }
        val stopped = exec("dumpsys package $pkg | grep -m1 -o 'stopped=[a-z]*'").contains("stopped=true")
        return listed && !stopped
    }

    private fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BACKOFF_BASE_MS shl (2 * (attempt - 1)).coerceAtMost(20)).coerceAtMost(BACKOFF_MAX_MS)
}
