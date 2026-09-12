package com.actionmental.platform

import android.content.Context
import android.os.PowerManager
import com.actionmental.core.awake.ScreenAwakeSwitch

/**
 * 屏幕常亮的平台实现：一把 [PowerManager.WakeLock]。
 *
 * 为什么不是 `FLAG_KEEP_SCREEN_ON`：那个标志只在本应用的窗口显示时有效，
 * 而这个功能的整个意义就是「我在用别的应用时屏幕也别熄」。
 * 为什么不是 Shizuku 改 `screen_off_timeout`：那要改一条全局系统设置并负责改回来，
 * 中途被杀就永久留下一个超长熄屏时间 —— 唤醒锁随进程消失，不会在系统里留痕。
 *
 * 锁本身不带 `ACQUIRE_CAUSES_WAKEUP`：开机重建意图时不该把黑着的屏幕点亮。
 *
 * 用 DIM 而不是 BRIGHT，是一条实测换来的结论：`SCREEN_BRIGHT_WAKE_LOCK` 会把
 * 屏幕锁在全亮并绕过自动亮度，长时间看书 / 看视频时它是整机最大的发热源 ——
 * 而这台设备被杀的原因恰恰是 `bgLimit_level_thermal_10`，等于自己的功能把自己热死。
 * DIM 让屏幕在系统的正常超时后暗下去、但不熄灭：功能（「用别的应用时别熄屏」）
 * 完整保留，代价是屏幕会变暗，这一点必须在界面上说清楚。
 */
class ScreenAwakeBackend(context: Context) : ScreenAwakeSwitch {

    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Suppress("DEPRECATION")
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        power?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG)?.apply {
            // 不计数：重复开启不会攒出一堆需要同样次数才释放得掉的锁
            setReferenceCounted(false)
        }
    }.getOrNull()

    override val supported: Boolean get() = wakeLock != null

    override fun acquire(): Result<Unit> = runCatching {
        val lock = wakeLock ?: error("SCREEN_DIM_WAKE_LOCK 不可用")
        if (!lock.isHeld) lock.acquire()
    }

    override fun release(): Result<Unit> = runCatching {
        val lock = wakeLock ?: return@runCatching
        if (lock.isHeld) lock.release()
    }

    override fun isHeld(): Boolean = wakeLock?.isHeld == true

    private companion object {
        /** 出现在 `dumpsys power` 里，用户与我们排查时看到的就是这一串。 */
        const val TAG = "Actionmental:screen-awake"
    }
}
