package com.actionmental.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.actionmental.R

/**
 * 系统通知栏里的自愈提醒。
 *
 * 只为一件事存在：自愈发生在应用不在前台的时候，应用内的 snackbar 与仪表盘卡片
 * 都要等用户下次打开才看得到。「系统悄悄关了你的键盘服务，我又把它打开了」
 * 属于必须当场知会的事，所以它得走通知栏。
 *
 * 反过来，用户自己按下「一键加固」时不发通知 —— 他正看着屏幕，
 * 结果就在眼前，再推一条只是噪音。
 */
class HardeningNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "hardening"
        private const val NOTIFICATION_ID = 4101

        /** 只有 13 以上需要运行时授权；更早的版本装上就有。 */
        val requiresRuntimePermission: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val permission: String get() = Manifest.permission.POST_NOTIFICATIONS
    }

    private val manager = NotificationManagerCompat.from(context)

    /** 系统设置里的开关也算数：给了权限但用户在系统里关掉了渠道，一样发不出去。 */
    fun canNotify(): Boolean = granted() && manager.areNotificationsEnabled()

    fun granted(): Boolean = !requiresRuntimePermission ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 渠道只需建一次，重复调用是幂等的。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_hardening),
            // 这类事件不该只静静躺在通知栏：键盘刚刚失灵过
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_hardening_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * 推一条自愈结果。发不出去就静默返回 —— 应用内的历史与卡片仍在，通知只是额外一层。
     *
     * @return 是否真的发出去了。
     */
    fun notifyHeal(succeeded: Boolean, detail: String): Boolean {
        if (!canNotify()) return false
        ensureChannel()

        val title = context.getString(
            if (succeeded) R.string.notification_heal_ok_title else R.string.notification_heal_failed_title
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_mark)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

        // 权限已由 canNotify() 查过，但可能在这一刻之间被撤销 —— SecurityException
        // 不该把自愈流程带崩。写成显式 catch 而不是 runCatching，是因为 lint 的
        // MissingPermission 只认得出前者是「已经处理过了」。
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

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
