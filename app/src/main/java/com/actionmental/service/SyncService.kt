package com.actionmental.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.actionmental.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 文件夹同步期间的前台服务。它**不运行**同步，只做一件事：告诉系统「这个进程正在传数据」。
 *
 * 同步本身在 [com.actionmental.platform.sync.SyncBackend] 的协程里跑，所以前台服务起不来
 * （Android 12 以后从后台启动可能被拒）时同步照样进行。有了它则多一层保护：
 * 进度通知变成不可划掉的前台通知，进程在这段时间里被回收的优先级更低。
 *
 * 类型是 dataSync。Android 15 起这个类型每 24 小时合计最多 6 小时，到点系统会回调 [onTimeout]，
 * 必须在回调里停掉 —— 我们取消同步，已经做完的部分仍然记进基线，下次接着来。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val backend = AppGraph.get(this).syncBackend
        // 一进来就要 startForeground，哪怕同步已经结束了 —— 见 KeepAliveService 里的说明
        runCatching {
            ServiceCompat.startForeground(
                this,
                com.actionmental.platform.sync.SyncBackend.PROGRESS_ID,
                backend.progressNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 同步一结束（状态回到 null）就收工；中间的进度刷新由后端原地更新同一条通知
        scope.launch {
            backend.state.first { it == null }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppGraph.get(this).syncBackend.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** 进度通知上的「取消」按钮。只有本应用发得出这条广播。 */
class SyncCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppGraph.get(context).syncBackend.cancel()
    }
}
