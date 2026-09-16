package com.actionmental.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.actionmental.AppGraph
import com.actionmental.R
import com.actionmental.core.awake.ScreenAwakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 屏幕常亮磁贴。
 *
 * 和另外两块磁贴同一套规矩：自己不存任何状态，
 * 打开面板时读真实值 → 点击执行 → 控制器回读 → 状态流推回来更新显示。
 * 唯一的差别是它不依赖 Shizuku，所以「不可用」只可能是设备真的给不出唤醒锁。
 */
class ScreenAwakeTileService : TileService() {

    private var scope: CoroutineScope? = null
    private val graph by lazy { AppGraph.get(this) }

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                graph.screenAwake.refresh()
                graph.screenAwake.state.collect(::render)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        // 常亮不受全局暂停影响：暂停期间照样能开关
        s.launch { graph.screenAwake.toggle() }
    }

    private fun render(state: ScreenAwakeState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_screen_awake)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_screen_awake)
        if (!state.available) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = getString(R.string.tile_screen_awake_unavailable)
        } else {
            tile.state = if (state.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = getString(if (state.on) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
