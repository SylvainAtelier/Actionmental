package com.actionmental.platform

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.actionmental.core.rotation.CoverScreenDetector
import com.actionmental.core.rotation.PanelSample

/**
 * 盯着 display 0 背后换没换面板，在「合上折叠屏、在外屏上继续用」时叫一声。
 *
 * 只在开关打开时才挂监听：每一次转屏、亮灭屏都会让 display 0 报一次变化，
 * 而这个功能多数人用不上，不该为它多醒那么多次。
 *
 * 判定在 [CoverScreenDetector] 里；这里只负责取样，并在报出之后等 [SETTLE_MS] 复核一次 ——
 * 合上那一瞬面板、亮灭、锁屏会连着变好几下，「上滑继续使用」的提示也还挂着。
 * 所有状态只在主线程读写。
 */
class CoverScreenWatch(
    context: Context,
    private val onEnterCover: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val detector = CoverScreenDetector()
    private var registered = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) check()
        }
    }

    private val confirm = Runnable {
        val sample = sample() ?: return@Runnable
        detector.observe(sample)
        when {
            !detector.onCover -> Unit           // 这一秒里又展开了
            sample.interactive -> onEnterCover()
            else -> detector.rearm()            // 熄了 / 锁了：等下一次真的用起来
        }
    }

    fun setEnabled(on: Boolean) {
        main.post {
            if (on == registered) return@post
            val dm = displays ?: return@post
            if (on) {
                dm.registerDisplayListener(listener, main)
                registered = true
                // 先记下当前面板，下一次变化才有得比
                sample()?.let(detector::observe)
            } else {
                dm.unregisterDisplayListener(listener)
                main.removeCallbacks(confirm)
                registered = false
            }
        }
    }

    /**
     * 再看一眼。解锁不会让 display 0 报变化，所以 USER_PRESENT 也要叫这里 ——
     * 合上后停在锁屏上的那一段，要到解锁才算「在外屏上用起来」。
     */
    fun check() {
        main.post {
            if (!registered) return@post
            val sample = sample() ?: return@post
            if (detector.observe(sample)) {
                main.removeCallbacks(confirm)
                main.postDelayed(confirm, SETTLE_MS)
            }
        }
    }

    private fun sample(): PanelSample? = runCatching {
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        PanelSample(
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            interactive = display.state == Display.STATE_ON && keyguard?.isKeyguardLocked != true,
        )
    }.getOrNull()

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
