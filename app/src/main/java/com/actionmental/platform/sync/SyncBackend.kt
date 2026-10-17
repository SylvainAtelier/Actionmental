package com.actionmental.platform.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.actionmental.R
import com.actionmental.core.action.ActionResult
import com.actionmental.core.sync.SyncBaseline
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncEngine
import com.actionmental.core.sync.SyncProgress
import com.actionmental.core.sync.SyncReport
import com.actionmental.core.sync.SyncSummary
import com.actionmental.service.SyncCancelReceiver
import com.actionmental.service.SyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件夹同步的统筹：校验前提、在后台线程跑引擎、把进度与结果交给通知。
 *
 * 同一时刻只跑一个同步。这是有意的：两个同步抢同一条 Wi-Fi 与同一块闪存，总时间不会更短，
 * 却会让进度、取消、通知都变成要排队说清楚的事；连按两下快捷键也自然落在这一条规则里 ——
 * 第二下得到「已有同步正在进行」，而不是第二个任务。
 *
 * 引擎跑在自己的协程域里，不依赖前台服务是否起得来：Android 12 以后从后台拉起前台服务可能被拒，
 * 被拒时同步照常进行，只是通知退化为普通通知。前台服务只负责「让系统知道这个进程正在干活」。
 */
class SyncBackend(
    private val context: Context,
    private val credentials: SyncCredentials,
    private val translate: (String) -> String,
) {
    data class Running(val config: SyncConfig, val progress: SyncProgress = SyncProgress())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications = NotificationManagerCompat.from(context)

    private val _state = MutableStateFlow<Running?>(null)
    val state: StateFlow<Running?> = _state.asStateFlow()

    @Volatile private var engine: SyncEngine? = null
    @Volatile private var cancelRequested = false
    private var renderJob: kotlinx.coroutines.Job? = null

    /** 一次同步结束（含失败、取消）后回调。用来更新顶部提示与事件日志。 */
    @Volatile var onFinished: ((SyncConfig, SyncReport) -> Unit)? = null

    // --- 前提 ----------------------------------------------------------------

    /** 读写 `/storage/emulated/0` 下任意目录要这项权限；Android 10 及以下没有等价的免 SAF 办法。 */
    fun storageGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun storageSettingsIntent(): Intent {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (direct.resolveActivity(context.packageManager) != null) return direct
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun hasPassword(key: String): Boolean = credentials.has(key)

    fun savePassword(key: String, password: String) = credentials.save(key, password)

    private fun networkProblem(wifiOnly: Boolean): ActionResult.Reason? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return ActionResult.Reason.SYNC_NETWORK
        if (!wifiOnly) return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        // 经 VPN 回家的手机，底层是 Wi-Fi 时 VPN 网络也会标成不计费
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (local || unmetered) null else ActionResult.Reason.SYNC_WIFI_ONLY
    }

    // --- 启动 / 取消 ----------------------------------------------------------

    /** 快捷键回调所在的线程调用它：这里只做内存里的校验，网络与磁盘都在后台协程里。 */
    fun start(config: SyncConfig): ActionResult {
        if (!config.complete) return ActionResult.Failed(ActionResult.Reason.SYNC_INCOMPLETE)
        if (!storageGranted()) return ActionResult.Failed(ActionResult.Reason.SYNC_STORAGE_DENIED)
        if (config.username.isNotBlank() && !credentials.has(config.key)) {
            return ActionResult.Failed(ActionResult.Reason.SYNC_NO_PASSWORD)
        }
        networkProblem(config.wifiOnly)?.let { return ActionResult.Failed(it) }

        val running = Running(config)
        if (!_state.compareAndSet(null, running)) {
            return ActionResult.Failed(ActionResult.Reason.SYNC_RUNNING, _state.value?.config?.displayName.orEmpty())
        }
        cancelRequested = false
        promote()
        scope.launch { execute(config) }
        renderJob?.cancel()
        renderJob = scope.launch { renderLoop() }
        return ActionResult.Ok(translate("同步已开始"))
    }

    fun cancel() {
        cancelRequested = true
        engine?.cancel()
    }

    /** 测试连接：用户填完表单后点一下，不用等到快捷键按下才发现密码写错了。 */
    suspend fun test(config: SyncConfig, typedPassword: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val password = typedPassword.takeIf { it.isNotEmpty() }?.toCharArray()
                ?: credentials.load(config.key)
                ?: if (config.username.isBlank()) CharArray(0) else throw IllegalStateException("还没有保存密码")
            SmbConnection.open(config, password).use { conn ->
                val side = SmbSide(conn.share, config.remoteRoot)
                val note = if (side.exists("")) {
                    translate("已连接 · 共享目录里有 " + side.list("").size + " 个条目")
                } else {
                    translate("已连接 · 目录还不存在，同步时会自动创建")
                }
                note
            }
        }.recoverCatching { throw IllegalStateException(SmbConnection.describe(it), it) }
    }

    private fun promote() {
        try {
            context.startForegroundService(Intent(context, SyncService::class.java))
        } catch (_: Exception) {
            // 从后台拉前台服务可能被系统拒绝。同步不依赖它，照常进行，通知退化为普通通知
        }
    }

    // --- 执行 ----------------------------------------------------------------

    private suspend fun execute(config: SyncConfig) {
        var report: SyncReport
        try {
            report = runEngine(config)
        } catch (e: CancellationException) {
            _state.value = null
            throw e
        } catch (e: Throwable) {
            report = SyncReport(aborted = SmbConnection.describe(e))
        }
        engine = null
        _state.value = null
        notifications.cancel(PROGRESS_ID)
        notifyResult(config, report)
        onFinished?.invoke(config, report)
    }

    private suspend fun runEngine(config: SyncConfig): SyncReport {
        val password = if (config.username.isBlank()) CharArray(0)
        else credentials.load(config.key) ?: return SyncReport(aborted = "没有保存 NAS 密码 · 请在动作里重新输入")

        val connection = try {
            SmbConnection.open(config, password)
        } finally {
            password.fill('\u0000')
        }
        connection.use {
            if (cancelRequested) return SyncReport(cancelled = true)
            val file = baselineFile(config)
            val signature = SyncBaseline.signature(config)
            val engine = SyncEngine(
                local = LocalSide(File(config.localPath)),
                remote = SmbSide(connection.share, config.remoteRoot),
                config = config,
                baseline = SyncBaseline.load(file, signature),
                onProgress = { p -> _state.update { it?.copy(progress = p) } },
            )
            this.engine = engine
            if (cancelRequested) engine.cancel()
            val outcome = engine.run()
            // 无论是完整跑完、中途取消还是出错，已经做完的部分都要记账，
            // 否则下一次会把它们当成没同步过
            runCatching { SyncBaseline.save(file, signature, outcome.baseline) }
            return outcome.report
        }
    }

    private fun baselineFile(config: SyncConfig) = File(File(context.filesDir, "sync"), config.key + ".tsv")

    // --- 通知 ----------------------------------------------------------------

    /** 进度通知。限频：一个有成千上万个小文件的目录，每秒会产生很多次进度，通知不需要每次都动。 */
    private suspend fun renderLoop() {
        while (true) {
            val running = _state.value ?: return
            postProgress(running)
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    /** 前台服务启动时用它作为第一条通知，之后由 [renderLoop] 原地更新。 */
    fun progressNotification(running: Running? = _state.value): android.app.Notification {
        ensureChannels()
        val name = running?.config?.displayName ?: translate("文件夹同步")
        val p = running?.progress ?: SyncProgress()
        val text = when {
            p.phase == SyncProgress.Phase.CONNECTING && p.done == 0 && p.scanned == 0 -> translate("正在连接 NAS…")
            else -> listOfNotNull(
                p.done.takeIf { it > 0 }?.let { translate("已处理") + " " + it },
                p.scanned.takeIf { it > 0 }?.let { translate("已检查") + " " + it },
                SyncSummary.size(p.bytes).takeIf { p.bytes > 0 },
            ).joinToString(" · ").ifEmpty { translate("正在比对文件…") }
        }
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SyncCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_logo_mark)
            .setContentTitle(name)
            .setContentText(text)
            .setSubText(p.current.takeIf { it.isNotEmpty() }?.substringAfterLast('/'))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, translate("取消"), cancel)
            .setContentIntent(openApp())
            .build()
    }

    private fun postProgress(running: Running) {
        if (!notifications.areNotificationsEnabled()) return
        try {
            notifications.notify(PROGRESS_ID, progressNotification(running))
        } catch (_: SecurityException) {
        }
    }

    private fun notifyResult(config: SyncConfig, report: SyncReport) {
        if (!notifications.areNotificationsEnabled()) return
        ensureChannels()
        val t = translate
        val ok = report.succeeded
        val summary = SyncSummary.line(report, t)
        val body = buildString {
            append(summary)
            SyncSummary.meta(report).takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
            SyncSummary.failures(report, t = t).takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_logo_mark)
            .setContentTitle(SyncSummary.title(config.displayName, report, t))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 成功安静地躺着；有失败才值得响一下
            .setPriority(if (ok) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(ok)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            notifications.notify(RESULT_BASE + (config.key.hashCode() and 0xFFF), notification)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, translate("文件夹同步进度"), NotificationManager.IMPORTANCE_LOW).apply {
                description = translate("同步进行时显示进度，可以在这里取消。")
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, translate("文件夹同步结果"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = translate("每次文件夹同步结束后报告结果；失败时提醒。")
                setShowBadge(false)
            }
        )
    }

    private fun openApp(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val PROGRESS_ID = 4301
        private const val RESULT_BASE = 12000
        private const val CHANNEL_PROGRESS = "sync_progress"
        private const val CHANNEL_RESULT = "sync_result"
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
