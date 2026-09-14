package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.actionmental.core.awake.ScreenAwakeSwitch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 屏幕常亮的平台实现，两层：
 *
 * 1. **主路径：无障碍悬浮层 + `FLAG_KEEP_SCREEN_ON`。** 用服务当令牌挂一个 1px、全透明、
 *    不可触、不可聚焦的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口。它始终叠在所有应用之上、始终「可见」，
 *    所以不管前台是谁，WindowManager 都会替它压住熄屏超时 —— 这是窗口策略，不是电源策略，
 *    ROM 没有理由去拦。不需要悬浮窗权限，无障碍服务本身就够。
 * 2. **兜底：[PowerManager.SCREEN_DIM_WAKE_LOCK]。** 服务没连上时用它。
 *
 * 为什么唤醒锁不能再当主路径：实测开启后照样熄屏。屏幕类唤醒锁早已废弃，
 * 不少 ROM（尤其是带 `bgLimit` 温控清理的那类）对非前台应用的屏幕锁直接不理 ——
 * `isHeld` 仍然是 true，屏幕照熄，于是「回读事实」这条规矩在它身上是失效的。
 *
 * 为什么不是 Shizuku 改 `screen_off_timeout`：那要改一条全局系统设置并负责改回来，
 * 中途被杀就永久留下一个超长熄屏时间 —— 窗口和锁都随进程消失，不会在系统里留痕。
 *
 * 两层都不点亮屏幕：开机重建意图时不该把黑着的屏幕点亮。
 * 悬浮层不强制亮度，自动亮度照常工作，不会重演 `SCREEN_BRIGHT_WAKE_LOCK` 的发热问题。
 */
class ScreenAwakeBackend(
    context: Context,
    /** 当前绑定的无障碍服务；为 null 时只能退回唤醒锁。 */
    private val serviceProvider: () -> AccessibilityService?,
) : ScreenAwakeSwitch {

    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        power?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG)?.apply {
            // 不计数：重复开启不会攒出一堆需要同样次数才释放得掉的锁
            setReferenceCounted(false)
        }
    }.getOrNull()

    /** 挂着的悬浮层，以及挂它时用的是哪个服务实例。只在主线程读写。 */
    private var overlay: View? = null
    private var overlayOwner: AccessibilityService? = null

    override val supported: Boolean get() = wakeLock != null || serviceProvider() != null

    override fun acquire(): Result<Unit> = runCatching {
        val overlayOk = onMain { attachOverlay() }
        // 悬浮层挂上了就不再握锁：一层就够，多握一把只是在 dumpsys 里多一条噪声
        if (overlayOk) {
            wakeLock?.takeIf { it.isHeld }?.release()
        } else {
            val lock = wakeLock ?: error("无障碍服务未连接，且 SCREEN_DIM_WAKE_LOCK 不可用")
            if (!lock.isHeld) lock.acquire()
        }
    }

    override fun release(): Result<Unit> = runCatching {
        onMain { detachOverlay(); true }
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun isHeld(): Boolean = onMain { overlayAlive() } || wakeLock?.isHeld == true

    /** 悬浮层是否真的挂在**当前**服务上。服务重绑后旧窗口已随旧实例作废。 */
    private fun overlayAlive(): Boolean {
        val view = overlay ?: return false
        val current = serviceProvider()
        if (current == null || current !== overlayOwner || !view.isAttachedToWindow) {
            detachOverlay()
            return false
        }
        return true
    }

    private fun attachOverlay(): Boolean {
        if (overlayAlive()) return true
        val service = serviceProvider() ?: return false
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        val view = View(service)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = TAG
        }
        return runCatching { wm.addView(view, params) }
            .onSuccess {
                overlay = view
                overlayOwner = service
            }
            .isSuccess
    }

    private fun detachOverlay() {
        val view = overlay ?: return
        val owner = overlayOwner
        overlay = null
        overlayOwner = null
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    /**
     * WindowManager 只能在主线程动。控制器可能从 Default 调度器调进来，
     * 那就投递到主线程并等它做完 —— 主线程这边从不反过来等控制器，所以不会互锁。
     */
    private fun onMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result = false
        val done = CountDownLatch(1)
        main.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        return done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result
    }

    private companion object {
        /** 出现在 `dumpsys power` / `dumpsys window` 里，用户与我们排查时看到的就是这一串。 */
        const val TAG = "Actionmental:screen-awake"

        const val MAIN_TIMEOUT_MS = 2_000L
    }
}
