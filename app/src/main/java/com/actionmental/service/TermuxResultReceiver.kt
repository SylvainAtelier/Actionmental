package com.actionmental.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionmental.AppGraph

/**
 * 接 Termux 回传的执行结果。只由本应用发出的 PendingIntent 触发（exported=false）。
 *
 * 和监听服务一样保持极薄：解析、记日志、发通知都在对象图里。
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { AppGraph.get(context).onTermuxResult(intent) }
    }
}
