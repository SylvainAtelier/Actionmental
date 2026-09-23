package com.actionmental.platform.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Shizuku 对外可见的状态。UI 只消费这个，不直接碰 Shizuku API。 */
data class ShizukuStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val granted: Boolean = false,
    val uid: Int = -1,
    val versionCode: Int = -1,
    val serviceBound: Boolean = false,
) {
    val identity: String
        get() = when {
            uid == 2000 -> "uid 2000 · shell (adb)"
            uid == 0 -> "uid 0 · root"
            uid >= 0 -> "uid " + uid
            else -> "未知身份"
        }

    val conclusion: String
        get() = when {
            !installed -> "未安装"
            !running -> "未运行"
            !granted -> "未授权"
            else -> "已授权"
        }

    val reason: ActionResult.Reason?
        get() = when {
            !installed -> ActionResult.Reason.SHIZUKU_NOT_INSTALLED
            !running -> ActionResult.Reason.SHIZUKU_NOT_RUNNING
            !granted -> ActionResult.Reason.SHIZUKU_DENIED
            else -> null
        }

    val usable: Boolean get() = reason == null
}

/**
 * Shizuku 生命周期与特权服务的唯一持有者（PRD 21 · ShizukuManager）。
 *
 * 它同时实现 [PrivilegedBackend]：不可用时返回带原因的失败，而不是抛异常或静默返回。
 */
class ShizukuManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * 生命周期里的每一步：binder 到没到、特权服务绑没绑上、什么时候掉的。
     *
     * 盘上记录里有过进程启动 20 秒后重连仍失败于「特权服务未连接」的情况 ——
     * 这句话既可能是 Shizuku 自己没起来，也可能是 user service 迟迟绑不上，
     * 而原来的日志里一点痕迹都没有，只能猜。
     */
    private val onEvent: (warn: Boolean, message: String, detail: String) -> Unit = { _, _, _ -> },
) : PrivilegedBackend {

    companion object {
        const val PERMISSION_REQUEST_CODE = 1101
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val BIND_NUDGE_INTERVAL_MS = 5_000L
    }

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    /** 按键线程也会读（[injectReady]），所以必须 @Volatile。 */
    @Volatile
    private var service: IPrivilegedService? = null

    /** 最近一次请求绑定特权服务的时刻；0 表示还没请求过。 */
    @Volatile
    private var bindRequestedAtMs = 0L

    /** 特权服务最近一次连上的时刻；0 表示这个进程里还没连上过。 */
    @Volatile
    private var boundAtMs = 0L

    /** 等特权服务超时这件事只记一次，连上后再重新计。按键注入每一颗都会走到这里。 */
    @Volatile
    private var awaitTimeoutLogged = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedUserService::class.java.name)
    ).daemon(false).processNameSuffix("privileged").debuggable(false).version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IPrivilegedService.Stub.asInterface(it) }
            _status.value = _status.value.copy(serviceBound = service != null)
            if (service != null) {
                boundAtMs = System.currentTimeMillis()
                awaitTimeoutLogged = false
                onEvent(false, "特权服务已连接", "距请求绑定 " + sinceMs(bindRequestedAtMs) + "ms")
            } else {
                onEvent(true, "特权服务回调了连接，但 binder 为空", describe())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _status.value = _status.value.copy(serviceBound = false)
            onEvent(true, "特权服务断开", "已连 " + sinceMs(boundAtMs) + "ms")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        onEvent(false, "Shizuku binder 已到位", describe())
        bindIfPossible()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
        onEvent(true, "Shizuku binder 死亡", describe())
    }

    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            refresh()
            bindIfPossible()
        }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    /** 重新查询真实状态。任何时刻 UI 想知道状态，都应该先调它。 */
    fun refresh() {
        val installed = isInstalled()
        val running = installed && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _status.value = ShizukuStatus(
            installed = installed,
            running = running,
            granted = granted,
            uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1,
            versionCode = if (running) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1,
            serviceBound = service != null,
        )
    }

    fun requestPermission() {
        if (!_status.value.running) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        scope.launch { refresh() }
    }

    fun reconnect() {
        service = null
        refresh()
        bindIfPossible()
    }

    private fun bindIfPossible() {
        if (!_status.value.granted || service != null) return
        bindRequestedAtMs = System.currentTimeMillis()
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { onEvent(true, "请求绑定特权服务失败", it.javaClass.simpleName + " · " + it.message.orEmpty()) }
    }

    /** 一行说清楚此刻的 Shizuku：给「特权服务未连接」这类失败配上下文。 */
    fun describe(): String {
        val s = _status.value
        return s.conclusion + " · " + s.identity +
            " · 特权服务=" + (if (service != null) "已连 " + sinceMs(boundAtMs) + "ms" else "未连") +
            " · 上次请求绑定=" + (if (bindRequestedAtMs == 0L) "从未" else sinceMs(bindRequestedAtMs).toString() + "ms 前")
    }

    private fun sinceMs(at: Long): Long = if (at == 0L) -1L else System.currentTimeMillis() - at

    private fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    // --- PrivilegedBackend ---------------------------------------------------

    override fun availability(): ActionResult {
        val reason = _status.value.reason
        return if (reason == null) ActionResult.OK else ActionResult.Failed(reason)
    }

    /**
     * 按键注入此刻能不能**立刻**用上。按键线程专用。
     *
     * 与 [availability] 不同：那一条只看 Shizuku 授没授权，特权服务没连上时照样说「可用」，
     * 于是映射拦下源键、注入再在 [awaitService] 里空等 1 秒后失败 —— 原键发不出去，
     * 目标键也没有，这颗键就哑了。按键路径不能等，也不能赌：没连上就是不可用，
     * 同时在后台催一次绑定，下一颗键多半就用得上了。
     */
    fun injectReady(): Boolean {
        if (_status.value.reason != null) return false
        if (service != null) return true
        requestBindAsync()
        return false
    }

    /** [injectReady] 为假时的原因，一句话。 */
    fun injectUnavailableReason(): String =
        _status.value.reason?.message ?: if (service == null) "特权服务未连接" else "可用"

    @Volatile
    private var lastBindNudgeMs = 0L

    /** 按键线程上不能做 binder 调用，绑定丢到后台；几秒内只催一次，免得打字时每颗键都催。 */
    private fun requestBindAsync() {
        val now = System.currentTimeMillis()
        if (now - lastBindNudgeMs < BIND_NUDGE_INTERVAL_MS) return
        lastBindNudgeMs = now
        scope.launch(Dispatchers.IO) { bindIfPossible() }
    }

    /** 拿到特权服务；不可用或绑不上时返回 null。 */
    private suspend fun awaitService(): IPrivilegedService? {
        if (_status.value.reason != null) return null
        if (service == null) {
            bindIfPossible()
            // 绑定是异步的，最多等 1s，避免第一次调用必然失败
            var waited = 0
            while (service == null && waited < 1000) {
                delay(50)
                waited += 50
            }
            if (service == null && !awaitTimeoutLogged) {
                awaitTimeoutLogged = true
                onEvent(true, "等了 1s 特权服务仍未连上", describe())
            }
        }
        return service
    }

    private fun unavailableMessage(): String =
        _status.value.reason?.message ?: "特权服务未连接"

    override suspend fun exec(command: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        val svc = awaitService()
            ?: return@withContext Result.failure(IllegalStateException(unavailableMessage()))

        runCatching {
            val raw = svc.exec(command)
            val newline = raw.indexOf('\n')
            if (newline < 0) ShellResult(raw.trim().toIntOrNull() ?: -1, "")
            else ShellResult(raw.substring(0, newline).trim().toIntOrNull() ?: -1, raw.substring(newline + 1))
        }
    }

    override suspend fun injectKey(keyCode: Int, metaState: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val svc = awaitService()
                ?: return@withContext Result.failure<Unit>(IllegalStateException(unavailableMessage()))
            runCatching {
                val error = svc.injectKey(keyCode, metaState)
                if (error.isNotEmpty()) throw IllegalStateException(error)
            }
        }

    override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val svc = awaitService()
                ?: return@withContext Result.failure<Unit>(IllegalStateException(unavailableMessage()))
            runCatching {
                val error = svc.injectKeyState(keyCode, metaState, down)
                if (error.isNotEmpty()) throw IllegalStateException(error)
            }
        }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> =
        exec("settings get " + namespace + " " + key).mapCatching { result ->
            if (!result.ok) throw IllegalStateException("exit " + result.exitCode + " · " + result.output.trim())
            val value = result.output.trim()
            if (value.isEmpty() || value == "null") null else value
        }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> =
        exec(settingsWriteCommand(namespace, key, value)).mapCatching {
            if (!it.ok) throw IllegalStateException(it.output.trim()) else Unit
        }
}

/**
 * 写一个 settings 值该发什么命令。
 *
 * 空值不能直接拼进去：`settings put secure key ` 少一个参数，系统回的是
 * `Bad arguments`，这次写入失败。重绑监听服务时「先把组件摘掉」那一步正好写空值 ——
 * 设备上只装了这一个无障碍服务时，摘掉之后那一串就是空的 —— 于是自动重连
 * 每一次都倒在第一步，用户只能自己去系统设置里关掉再打开。
 *
 * 空值改走 `settings delete`：对读取方来说「删掉」和「空字符串」是同一件事
 * （[ShizukuManager.getSetting] 两种都读成 null，系统读这一串时也一样）。
 *
 * 非空值一律引起来：命令最终交给 `sh -c`，值里只要有一个空格就会被拆成两个参数。
 */
fun settingsWriteCommand(namespace: String, key: String, value: String): String =
    if (value.isEmpty()) {
        "settings delete " + namespace + " " + key
    } else {
        "settings put " + namespace + " " + key + " " + shellQuote(value)
    }

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
