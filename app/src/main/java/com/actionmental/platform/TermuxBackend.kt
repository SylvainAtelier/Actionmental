package com.actionmental.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.actionmental.R
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TermuxOutcome
import com.actionmental.service.TermuxResultReceiver
import java.util.concurrent.atomic.AtomicInteger

/**
 * 经 Termux 的 `RUN_COMMAND` 接口执行命令，并把回传的结果发成通知。
 *
 * 前提有两条，都得用户自己做一次：
 * - 授予本应用 [PERMISSION]（运行时权限，编辑页里就能申请）；
 * - 在 Termux 里写 `allow-external-apps=true` 到 `~/.termux/termux.properties`。
 *   这一条从外面查不到，没开时 Termux 会在结果里报错，通知照实转述。
 *
 * [translate] 把通知文案换成当前界面语言 —— 结果回来时界面多半不在前台。
 */
class TermuxBackend(
    private val context: Context,
    private val translate: (String) -> String,
) {

    private val runIds = AtomicInteger(0)
    private val notifications = NotificationManagerCompat.from(context)

    fun installed(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Termux 自己有没有「显示在其他应用上层」。
     *
     * 前台执行要由 Termux 打开终端界面，而 Android 10 起后台应用只有拿着这项权限才能打开界面 ——
     * 缺它时 Termux 收到了命令却弹不出来，看上去就是「按了没反应」。
     * 查的是 Termux 的 appop；系统不让查时返回 null，界面照样给出去设置的入口。
     */
    fun overlayGranted(): Boolean? = runCatching {
        val uid = context.packageManager.getApplicationInfo(PACKAGE, 0).uid
        val ops = context.getSystemService(AppOpsManager::class.java)
        when (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, PACKAGE)) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            // DEFAULT：跟着权限本身走（预装或 adb 授予的情况）
            else -> context.packageManager.checkPermission(
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                PACKAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }.getOrNull()

    /** 直达 Termux 的悬浮窗授权页；个别 ROM 不认带包名的入口，退回 Termux 的应用详情页。 */
    fun overlaySettingsIntent(): Intent {
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (direct.resolveActivity(context.packageManager) != null) return direct
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun run(action: Action.Termux): ActionResult {
        if (!installed()) return ActionResult.Failed(ActionResult.Reason.TERMUX_NOT_INSTALLED)
        if (!permissionGranted()) return ActionResult.Failed(ActionResult.Reason.TERMUX_PERMISSION_DENIED)
        // 只拦「确定没有」：查不到时照发，交给 Termux 自己去试
        if (!action.background && overlayGranted() == false) {
            return ActionResult.Failed(ActionResult.Reason.TERMUX_OVERLAY_DENIED)
        }

        val intent = Intent(ACTION_RUN_COMMAND)
            .setComponent(ComponentName(PACKAGE, SERVICE))
            .putExtra(EXTRA_PATH, BASH)
            .putExtra(EXTRA_ARGUMENTS, arguments(action.command))
            .putExtra(EXTRA_WORKDIR, HOME)
            .putExtra(EXTRA_BACKGROUND, action.background)
            .putExtra(EXTRA_COMMAND_LABEL, action.displayName)
        if (action.background) {
            intent.putExtra(EXTRA_PENDING_INTENT, resultIntent(action))
        } else {
            // 新开会话并切到 Termux：用户要看的就是那块终端
            intent.putExtra(EXTRA_SESSION_ACTION, "0")
        }

        // Termux 的 RunCommandService 会自己转成前台服务；后台发起时只能走 startForegroundService
        return try {
            context.startForegroundService(intent)
            ActionResult.Ok(if (action.background) "已交给 Termux 执行" else "已在 Termux 中打开")
        } catch (e: SecurityException) {
            ActionResult.Failed(ActionResult.Reason.TERMUX_PERMISSION_DENIED, e.message.orEmpty())
        } catch (e: IllegalStateException) {
            // 包括 ForegroundServiceStartNotAllowedException：部分 ROM 不认无障碍服务的后台豁免
            ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * 回传结果的 PendingIntent。必须是 MUTABLE：Termux 要把结果包填进来。
     * 组件是显式的本应用接收器，所以可变也不会被别的应用截走。
     * requestCode 每次不同，连按两次的结果不会互相覆盖。
     */
    private fun resultIntent(action: Action.Termux): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(EXTRA_NAME, action.displayName)
            .putExtra(EXTRA_COMMAND, action.command)
        return PendingIntent.getBroadcast(
            context,
            runIds.incrementAndGet(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** 从回传的 Intent 里取出结果。字段缺失时按「没执行」处理，而不是当成成功。 */
    fun parse(intent: Intent): Pair<String, TermuxOutcome> {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Termux" }
        val bundle: Bundle? = intent.getBundleExtra(RESULT_BUNDLE)
        val outcome = if (bundle == null) {
            TermuxOutcome(err = 1, errmsg = translate("Termux 没有回传结果"))
        } else {
            TermuxOutcome(
                stdout = bundle.getString(RESULT_STDOUT).orEmpty(),
                stderr = bundle.getString(RESULT_STDERR).orEmpty(),
                exitCode = if (bundle.containsKey(RESULT_EXIT_CODE)) bundle.getInt(RESULT_EXIT_CODE) else null,
                err = bundle.getInt(RESULT_ERR, TermuxOutcome.ERR_OK),
                errmsg = bundle.getString(RESULT_ERRMSG).orEmpty(),
            )
        }
        return name to outcome
    }

    /**
     * 发结果通知。同一个名字复用同一个通知 id：连跑几次只留最新一条，
     * 不同命令各占一条。发不出去（没给通知权限）就返回 false，事件日志里仍有记录。
     */
    fun notify(name: String, outcome: TermuxOutcome): Boolean {
        if (!notifications.areNotificationsEnabled()) return false
        ensureChannel()
        val body = outcome.body(translate)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_mark)
            .setContentTitle(outcome.title(name, translate))
            .setContentText(outcome.summary(translate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 成功安静地躺着就好；失败才值得响一下
            .setPriority(if (outcome.succeeded) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(outcome.succeeded)
            .setAutoCancel(true)
            .setContentIntent(openTermux())
            .build()
        return try {
            notifications.notify(NOTIFICATION_BASE + (name.hashCode() and 0xFFF), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            translate("Termux 命令结果"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = translate("快捷键在后台执行的 Termux 命令跑完后，在这里报告结果与输出。")
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 点通知回到 Termux：输出不够看时，用户下一步多半就是去终端里接着查。 */
    private fun openTermux(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 路径写死是 Termux 接口的要求：它只认自己私有目录下的绝对路径。 */
    @SuppressLint("SdCardPath")
    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        /**
         * `bash -c` 是非交互 shell，不读 `~/.bashrc` —— 用户在那里定义的函数、别名、PATH
         * 一概不存在，终端里能跑的 `ncm` 到这里就成了 command not found。
         * 所以先显式加载 `~/.bashrc`（它的输出丢掉，免得混进结果通知），再 eval 用户的命令。
         * 别名要在解析前打开 expand_aliases；eval 的那一行是加载之后才解析的，所以别名也生效。
         * 命令经 `$1` 传入而不是拼进脚本，引号、`$`、换行都不用转义。
         */
        const val WRAPPER =
            "shopt -s expand_aliases; [ -r ~/.bashrc ] && . ~/.bashrc >/dev/null 2>&1; eval \"\$1\""

        /** 交给 bash 的参数：`bash -c WRAPPER actionmental <command>`，`$0` 只是个名字。 */
        fun arguments(command: String): Array<String> = arrayOf("-c", WRAPPER, "actionmental", command)

        const val PROPERTIES_HINT = "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"

        private const val SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val PREFIX = "/data/data/com.termux/files/usr"
        private const val BASH = "$PREFIX/bin/bash"
        private const val HOME = "/data/data/com.termux/files/home"

        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"

        private const val EXTRA_NAME = "com.actionmental.termux.NAME"
        const val EXTRA_COMMAND = "com.actionmental.termux.COMMAND"

        const val CHANNEL_ID = "termux"
        private const val NOTIFICATION_BASE = 5000
    }
}
