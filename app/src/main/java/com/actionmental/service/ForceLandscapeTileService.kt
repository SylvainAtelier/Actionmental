package com.actionmental.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.actionmental.AppGraph
import com.actionmental.R
import com.actionmental.core.rotation.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 强制横屏磁贴（PRD 10–12 / 35.7）。
 *
 * 磁贴自己不保存任何状态：打开时读真实状态 → 点击执行 → 再读一次 → 更新显示。
 * 读不到就显示 UNAVAILABLE，绝不伪装成「已关闭」。
 */
class ForceLandscapeTileService : TileService() {

    private var scope: CoroutineScope? = null

    private val graph by lazy { AppGraph.get(this) }

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + graph.coroutineFailures).also { s ->
            s.launch {
                render()
                graph.rotation.state.collect { renderFrom(it.mode, it.writable) }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main + graph.coroutineFailures).also { scope = it }
        s.launch {
            // 暂停期间这块磁贴显示的是 UNAVAILABLE，系统照理不会送点击过来；
            // 真送过来也不动手 —— 暂停的承诺是「什么都不做」，不是「悄悄做一半」
            if (graph.paused.value) return@launch
            setBusy()
            graph.rotation.toggleLandscape()
            render()                       // 执行后重新读取真实状态
        }
    }

    private suspend fun render() {
        val state = graph.rotation.refresh()
        renderFrom(state.mode, state.writable)
    }

    private fun setBusy() {
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            subtitle = "处理中"
            updateTile()
        }
    }

    private fun renderFrom(mode: RotationMode, available: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_force_landscape)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_force_landscape)
        when {
            graph.paused.value -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.tile_paused)
            }
            !available -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.tile_unavailable)
            }
            mode == RotationMode.FORCE_LANDSCAPE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_on)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }
}
