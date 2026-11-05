package com.actionmental.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.actionmental.core.rotation.Panel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 盯着 display 0 背后是哪块物理面板，换了就立刻叫一声。
 *
 * 只在折叠屏上挂监听（有铰链角度传感器）：每一次转屏、亮灭屏都会让 display 0 报一次变化，
 * 直板机上永远只有一块屏，不该为它多醒那么多次。
 *
 * 换屏就是面板变了这一个事实，不再看亮没亮、锁没锁：每块屏有自己的配置，
 * 合上后熄着放进口袋时先换成外屏的方向，展开时照样换回内屏的，结果不会错。
 * 所有状态只在主线程读写。
 */
class ScreenWatch(
    context: Context,
    private val onPanel: (Panel, inches: Float?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)

    /** 这台设备是不是折叠屏。 */
    val foldable: Boolean = context.getSystemService(SensorManager::class.java)
        ?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null

    private val _panel = MutableStateFlow<Panel?>(null)

    /** 此刻 display 0 背后的面板；不是折叠屏、或还没取到样时为 null。 */
    val panel: StateFlow<Panel?> = _panel.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) sample()
        }
    }

    fun start() {
        if (!foldable) return
        main.post {
            val dm = displays ?: return@post
            dm.registerDisplayListener(listener, main)
            sample()
        }
    }

    private fun sample() {
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val mode = runCatching { display.mode }.getOrNull() ?: return
        val panel = Panel.of(mode.physicalWidth, mode.physicalHeight)
        if (panel == _panel.value) return
        _panel.value = panel
        onPanel(panel, inches(display, mode))
    }

    /** 按 dpi 算对角线，取一位小数。只为让人认得出是哪块屏，差一点无所谓。 */
    @Suppress("DEPRECATION")
    private fun inches(display: Display, mode: Display.Mode): Float? = runCatching {
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val dpi = (metrics.xdpi + metrics.ydpi) / 2
        if (dpi <= 0f) return null
        (hypot(mode.physicalWidth.toFloat(), mode.physicalHeight.toFloat()) / dpi * 10).roundToInt() / 10f
    }.getOrNull()
}
