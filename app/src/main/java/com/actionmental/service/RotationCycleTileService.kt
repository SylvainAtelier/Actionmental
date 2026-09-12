package com.actionmental.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.actionmental.AppGraph
import com.actionmental.R
import com.actionmental.core.rotation.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 旋转模式循环磁贴：默认 → 横屏 → 竖屏。同样只显示真实状态。 */
class RotationCycleTileService : TileService() {

    private var scope: CoroutineScope? = null
    private val graph by lazy { AppGraph.get(this) }

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                graph.rotation.refresh()
                graph.rotation.state.collect(::render)
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
        s.launch {
            if (graph.paused.value) return@launch
            graph.rotation.cycle()
        }
    }

    private fun render(state: com.actionmental.core.rotation.RotationState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_rotation_cycle)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_rotation_cycle)
        if (graph.paused.value) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = getString(R.string.tile_paused)
        } else if (!state.available) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = getString(R.string.tile_unavailable)
        } else {
            tile.state = if (state.mode.isForced) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = state.mode.label
        }
        tile.updateTile()
    }
}
