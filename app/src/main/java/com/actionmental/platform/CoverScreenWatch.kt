package com.actionmental.platform

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.actionmental.core.rotation.Panel
import com.actionmental.core.rotation.PanelSample
import com.actionmental.core.rotation.PanelSwitch
import com.actionmental.core.rotation.PanelSwitchDetector

/**
 * 盯着 display 0 背后换没换面板，在「换到另一块屏、并且用起来了」时叫一声。
 *
 * 只在开关打开时才挂监听：每一次转屏、亮灭屏都会让 display 0 报一次变化，
 * 而这个功能多数人用不上，不该为它多醒那么多次。
 *
 * 判定在 [PanelSwitchDetector] 里；这里只负责取样，并在认出之后等 [SETTLE_MS] 复核一次 ——
 * 合上那一瞬面板、亮灭、锁屏会连着变好几下，「上滑继续使用」的提示也还挂着。
 * 所有状态只在主线程读写。
 */
class CoverScreenWatch(
    context: Context,
    private val onSwitch: (PanelSwitch) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var detector: PanelSwitchDetector? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) check()
        }
    }

    private val confirm = Runnable {
        val d = detector ?: return@Runnable
        val sample = sample() ?: return@Runnable
        // 这一秒里又换回去了、熄了或锁了：等下一次真的用起来
        if (d.observe(sample)) d.take()?.let(onSwitch)
    }

    /** @param settled 上一次落地时的那块屏（盘上的记录），用来接续进程重启前的状态。 */
    fun setEnabled(on: Boolean, settled: Panel?) {
        main.post {
            if (on == (detector != null)) return@post
            val dm = displays ?: return@post
            if (on) {
                dm.registerDisplayListener(listener, main)
                detector = PanelSwitchDetector(settled)
                check()
            } else {
                dm.unregisterDisplayListener(listener)
                main.removeCallbacks(confirm)
                detector = null
            }
        }
    }

    /**
     * 再看一眼。解锁不会让 display 0 报变化，所以 USER_PRESENT 也要叫这里 ——
     * 合上后停在锁屏上的那一段，要到解锁才算「在外屏上用起来」。
     */
    fun check() {
        main.post {
            val d = detector ?: return@post
            val sample = sample() ?: return@post
            if (d.observe(sample)) {
                main.removeCallbacks(confirm)
                main.postDelayed(confirm, SETTLE_MS)
            }
        }
    }

    private fun sample(): PanelSample? = runCatching {
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        PanelSample(
            panel = Panel.of(mode.physicalWidth, mode.physicalHeight),
            interactive = display.state == Display.STATE_ON && keyguard?.isKeyguardLocked != true,
        )
    }.getOrNull()

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
