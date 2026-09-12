package com.actionmental.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.actionmental.R

/**
 * 常亮期间在通知栏留下的那一条。
 *
 * 磁贴要下拉才看得见，而常亮是一个会一直耗电的状态 ——
 * 它必须在系统层面**持续**可见，并且在看见它的地方就能关掉，
 * 而不是让用户回想「我刚才是不是按到那个快捷键了」。
 *
 * 通知不响、不弹、不进角标（IMPORTANCE_LOW），只是常驻；
 * 常亮一关就撤掉，所以通知栏里出现它 = 屏幕此刻真的锁着不熄。
 */
class ScreenAwakeNotifier(
    private val context: Context,
    private val stopReceiver: Class<out BroadcastReceiver>,
) {
    companion object {
        const val CHANNEL_ID = "screen_awake"

        /** 通知上的「关闭常亮」按钮发出的广播。 */
        const val ACTION_STOP = "com.actionmental.action.STOP_SCREEN_AWAKE"

        private const val NOTIFICATION_ID = 4102
    }

    private val manager = NotificationManagerCompat.from(context)

    /** 跟着 [com.actionmental.core.awake.ScreenAwakeController] 的真实状态走。 */
    fun render(on: Boolean) {
        if (on) show() else hide()
    }

    fun hide() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /**
     * 发不出去（没给通知权限、渠道被关）就静默返回。
     * 常亮本身照常生效 —— 通知是多一层可见性，不是功能的前提。
     */
    private fun show(): Boolean {
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_screen_awake)
            .setContentTitle(context.getString(R.string.notification_awake_title))
            .setContentText(context.getString(R.string.notification_awake_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(
                R.drawable.ic_tile_screen_awake,
                context.getString(R.string.notification_awake_stop),
                stopIntent(),
            )
            .build()

        // 显式 catch 而不是 runCatching：lint 的 MissingPermission 只认前者。
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** 渠道只在真的要用时才建，没开过常亮的用户的通知设置里不该多出一项。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_awake),
            // 状态而不是事件：不该发声，也不该抢用户正在做的事
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_awake_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, stopReceiver).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
