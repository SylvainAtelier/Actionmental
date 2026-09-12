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
) : PrivilegedBackend {

    companion object {
        const val PERMISSION_REQUEST_CODE = 1101
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private var service: IPrivilegedService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedUserService::class.java.name)
    ).daemon(false).processNameSuffix("privileged").debuggable(false).version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IPrivilegedService.Stub.asInterface(it) }
            _status.value = _status.value.copy(serviceBound = service != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _status.value = _status.value.copy(serviceBound = false)
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        bindIfPossible()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
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
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
    }

    private fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    // --- PrivilegedBackend ---------------------------------------------------

    override fun availability(): ActionResult {
        val reason = _status.value.reason
        return if (reason == null) ActionResult.OK else ActionResult.Failed(reason)
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

    override suspend fun getSetting(namespace: String, key: String): String? {
        val result = exec("settings get " + namespace + " " + key).getOrNull() ?: return null
        if (!result.ok) return null
        val value = result.output.trim()
        return if (value.isEmpty() || value == "null") null else value
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
