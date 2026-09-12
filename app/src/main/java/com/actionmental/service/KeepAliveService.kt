package com.actionmental.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.actionmental.AppGraph
import com.actionmental.R

/**
 * 常驻前台服务（唯一目的：不被后台限制策略挑中）。
 *
 * 它什么都不做 —— 不轮询、不注册监听、不持锁，onStartCommand 之后就是一个空进程占位。
 * 存在的理由完全在进程之外：设备上记录到的死亡全是同一条
 * `bgLimit_level_thermal_10[(service)]`，那是温控升到 10 级之后对进程里
 * 带 service 的那一类做清理。前面几轮把 RSS 从 977MB 压到 104MB，照样被杀 ——
 * 那条策略挑的不是「占得多的」。
 *
 * 但它也不是只挑「在后台的」：后来记到的一次死亡写的是
 * `bgLimit_level_thermal_10[(service){fg-service}]` —— 前台服务开着，照样被同一条
 * 策略收走。所以这个开关只是把顺位往后挪一点，**不是**保命符；真正能改变结论的
 * 是别让机器热起来：见 RotationController 的写入去重与 execBatch，
 * 那里才是这个应用曾经烧掉的 CPU。
 *
 * 代价是通知栏里一条撤不掉的通知，所以它默认关闭，由用户在「后台加固」页明确打开。
 *
 * 无障碍服务自己不能兼任前台服务（它由系统绑定，没有 startForeground 的时机），
 * 因此必须单开这一个 —— 两者在同一个进程里，保住谁都是保住同一个进程。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val log = AppGraph.get(this).eventLog
        runCatching {
            ensureChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_screen_awake)
                .setContentTitle(getString(R.string.notification_keepalive_title))
                .setContentText(getString(R.string.notification_keepalive_text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp())
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification)
            }
        }.fold(
            onSuccess = { log.info("keepalive", "常驻前台服务已启动") },
            onFailure = {
                // 从后台启动前台服务在 Android 12 以后是受限的，失败必须留痕：
                // 否则用户打开了开关、通知栏什么都没有，还以为已经保住了
                log.error("keepalive", "常驻前台服务启动失败", it)
                stopSelf()
            },
        )
        return START_STICKY
    }

    override fun onDestroy() {
        AppGraph.get(this).eventLog.info("keepalive", "常驻前台服务已停止")
        super.onDestroy()
    }

    /** 渠道只在真的要用时才建，没开过这个开关的用户的通知设置里不该多出一项。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_keepalive),
            // 这条通知本身没有任何信息量，它只是前台服务的门票：能压多低压多低
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_keepalive_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun openApp(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "keep_alive"
        const val ID = 4103
    }
}
