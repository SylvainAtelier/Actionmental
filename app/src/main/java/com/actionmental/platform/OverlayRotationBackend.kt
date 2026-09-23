package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.actionmental.core.rotation.LocalRotationBackend
import com.actionmental.core.rotation.OverlayOutcome
import com.actionmental.core.rotation.screenOrientationFor
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 不在时的旋转写入：无障碍悬浮层 + 系统设置。
 *
 * 悬浮层与屏幕常亮那一个是同一种窗口（1px、透明、不可触、不可聚焦的
 * `TYPE_ACCESSIBILITY_OVERLAY`），区别只在带了 `screenOrientation`。
 * WindowManager 定方向时先问应用上层的窗口（AOSP `DisplayArea.Tokens.getOrientation`），
 * 于是它压得住应用自己在清单里写死的方向 —— 这正是 Rotation Control 一类应用多年来的做法，
 * 不需要悬浮窗权限，也不改任何系统设置。窗口随服务实例、随进程消失，不在系统里留痕。
 *
 * 它不是万能的：系统在显示屏级别打开了 ignore-orientation-request
 * （部分大屏 / 折叠屏的默认策略，或以前经 Shizuku 设过而现在撤不掉），这个请求会被整个忽略。
 * 所以每次下发都要等屏幕真的转过去，转不过去就如实报 [OverlayOutcome.IGNORED]。
 */
class OverlayRotationBackend(
    context: Context,
    private val serviceProvider: () -> AccessibilityService?,
    private val settings: SystemSettingsAccess,
) : LocalRotationBackend {

    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)

    /** 挂着的窗口、挂它的服务实例、它要求的 Surface 角度。只在主线程读写。 */
    private var overlay: View? = null
    private var overlayOwner: AccessibilityService? = null
    private var overlayRotation: Int? = null

    /**
     * 这台设备把 90° / 270° 对调了（`config_reverseDefaultRotation`）。
     * 读不到那个配置，只能下发一次看屏幕转到哪边，反了就记下来。进程内有效。
     */
    @Volatile
    private var reversed = false

    override fun overlayAvailable(): Boolean = serviceProvider() != null

    override fun settingsWritable(): Boolean = settings.systemWritable()

    override fun overlayRotation(): Int? = onMain(null) { if (overlayAlive()) overlayRotation else null }

    override fun writeSettings(autoRotate: Boolean, userRotation: Int?): Result<Unit> = runCatching {
        // 先锁角度再关自动旋转：反过来的话，关掉的那一瞬屏幕会先落在旧的 user_rotation 上
        if (userRotation != null) settings.putSystemInt(KEY_USER_ROTATION, userRotation).getOrThrow()
        settings.putSystemInt(KEY_ACCELEROMETER, if (autoRotate) 1 else 0).getOrThrow()
    }

    override suspend fun forceOverlay(surfaceRotation: Int?): OverlayOutcome {
        if (surfaceRotation == null) {
            onMain(Unit) { detach() }
            return OverlayOutcome.REMOVED
        }
        if (!onMain(false) { attach(surfaceRotation) }) return OverlayOutcome.FAILED
        if (awaitRotation(surfaceRotation)) return OverlayOutcome.ADOPTED

        // 90° / 270° 转反了：这台设备的横屏角度与默认分配相反，对调一次再试。
        // 0° / 180° 不受那个配置影响，转不过去就是真的被忽略了。
        val actual = displayRotation()
        if (surfaceRotation % 2 == 1 && actual == (surfaceRotation + 2) % 4) {
            reversed = !reversed
            if (onMain(false) { attach(surfaceRotation) } && awaitRotation(surfaceRotation)) {
                return OverlayOutcome.ADOPTED
            }
        }
        // 转不过去就撤掉：留着它，回读会报「已强制 90°」，而屏幕根本没转 ——
        // 那是这个应用最不该说的一种谎（PRD 3.2）
        onMain(Unit) { detach() }
        return OverlayOutcome.IGNORED
    }

    /** 屏幕转动是异步的（带动画），轮询等它落定。 */
    private suspend fun awaitRotation(target: Int): Boolean {
        var waited = 0L
        while (true) {
            if (displayRotation() == target) return true
            if (waited >= ROTATE_TIMEOUT_MS) return false
            delay(ROTATE_POLL_MS)
            waited += ROTATE_POLL_MS
        }
    }

    private fun defaultDisplay(): Display? = displays?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun displayRotation(): Int? = runCatching { defaultDisplay()?.rotation }.getOrNull()

    /** Display.Mode 的物理尺寸按自然方向给出，与此刻转没转无关。 */
    private fun naturalLandscape(): Boolean = runCatching {
        val mode = defaultDisplay()?.mode ?: return false
        mode.physicalWidth > mode.physicalHeight
    }.getOrDefault(false)

    /** 与屏幕常亮那一层同一个判据：`parent` 同步可见，且仍挂在当前服务实例上。 */
    private fun overlayAlive(): Boolean {
        val view = overlay ?: return false
        val current = serviceProvider()
        if (current == null || current !== overlayOwner || view.parent == null) {
            detach()
            return false
        }
        return true
    }

    private fun attach(surfaceRotation: Int): Boolean {
        val orientation = screenOrientationFor(surfaceRotation, naturalLandscape(), reversed)
        if (overlayAlive()) {
            val view = overlay!!
            val params = view.layoutParams as WindowManager.LayoutParams
            if (params.screenOrientation != orientation) {
                params.screenOrientation = orientation
                val updated = runCatching {
                    overlayOwner?.getSystemService(WindowManager::class.java)?.updateViewLayout(view, params)
                }.isSuccess
                if (!updated) return false
            }
            overlayRotation = surfaceRotation
            return true
        }

        val service = serviceProvider() ?: return false
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        val view = View(service)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = TAG
            screenOrientation = orientation
        }
        return runCatching { wm.addView(view, params) }
            .onSuccess {
                overlay = view
                overlayOwner = service
                overlayRotation = surfaceRotation
            }
            .isSuccess
    }

    private fun detach() {
        val view = overlay ?: return
        val owner = overlayOwner
        overlay = null
        overlayOwner = null
        overlayRotation = null
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    /** WindowManager 只能在主线程动；调用方可能在任何调度器上。主线程从不反过来等这里。 */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result = fallback
        val done = CountDownLatch(1)
        main.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        return if (done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else fallback
    }

    private companion object {
        /** 出现在 `dumpsys window` 里，排查「是谁把屏幕压成横屏的」时看到的就是这一串。 */
        const val TAG = "Actionmental:rotation"

        const val KEY_USER_ROTATION = "user_rotation"
        const val KEY_ACCELEROMETER = "accelerometer_rotation"

        const val MAIN_TIMEOUT_MS = 2_000L
        const val ROTATE_POLL_MS = 100L

        /** 一次转屏动画大约 300~500ms，给足余量；超过这个还没转就是被忽略了。 */
        const val ROTATE_TIMEOUT_MS = 1_500L
    }
}
