package com.actionmental.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionmental.AppGraph
import com.actionmental.platform.ScreenAwakeNotifier
import kotlinx.coroutines.launch

/**
 * 常亮通知上「关闭常亮」按钮的落点。
 *
 * 走的是和快捷键、磁贴完全相同的那一个入口，
 * 所以关掉之后意图会被记住，通知也由状态流自己撤掉 —— 这里不碰通知。
 */
class ScreenAwakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScreenAwakeNotifier.ACTION_STOP) return

        val graph = AppGraph.get(context)
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.screenAwake.set(false)
            } finally {
                pending.finish()
            }
        }
    }
}
