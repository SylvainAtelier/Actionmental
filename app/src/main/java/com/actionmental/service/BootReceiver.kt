package com.actionmental.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionmental.AppGraph
import kotlinx.coroutines.launch

/**
 * 开机后把监听服务捞回来。
 *
 * 这是自愈唯一覆盖不到的场景：ROM 在重启时关掉无障碍开关，而此刻应用进程根本没起来 ——
 * 内存里的监视器、通知、历史全都不存在，用户要等到下次主动打开应用才知道键盘已经死了。
 * 只有开机广播能把进程拉起来，让那套机制有机会运行。
 *
 * 它不常驻、不起服务、不显示任何东西：拉起对象图，等 Shizuku 连上，试一次，然后退场。
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        /** 部分 ROM 的「快速开机」走这个私有 action，而不是标准的 BOOT_COMPLETED。 */
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != QUICKBOOT) return

        // Shizuku 开机后要几秒才起得来，同步返回等于必然失败；
        // goAsync 让进程在广播处理期间活着，够我们等一轮
        val pending = goAsync()
        val graph = AppGraph.get(context)
        graph.scope.launch {
            try {
                graph.healAfterBoot()
            } finally {
                pending.finish()
            }
        }
    }
}
