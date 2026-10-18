package com.actionmental.platform.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.WirelessDebugBackend
import com.actionmental.platform.shizuku.ShizukuManager
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 「ADB 网络调试」：让 adbd 在固定 TCP 端口上监听（`adb tcpip`），或者收回（`adb usb`）。
 *
 * 改端口要写 `service.adb.tcp.port` 再重启 adbd，这两步 shell 身份都做不了（SELinux 不让），所以分两条路：
 * - Shizuku 以 root 运行：直接 setprop + `ctl.restart`，和 `adb root` 之后的做法一样。
 * - 其余情况：本应用自己当一次 adb 客户端，经无线调试的 TLS 端口连上本机 adbd，发 `tcpip:端口` ——
 *   和电脑上那条命令发的是同一个请求，由 adbd 自己改属性、自己重启。
 *   连 adbd 要先配对：经 Shizuku 以 shell 身份调 IAdbManager 开一次二维码式配对，
 *   名称与配对码都由这里定，于是不用用户去抄六位数字。配对一次长期有效。
 *
 * adbd 重启会断开此刻所有的 adb 连接（包括无线调试），这和电脑上执行 `adb tcpip` 一样；
 * Shizuku 的 starter 会把服务端移出 adbd 的 cgroup，按设计不会随 adbd 重启被一起杀掉。
 */
class AdbTcpipBackend(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val wirelessDebug: WirelessDebugBackend,
    /** 走 shell 日志的那条特权通道：root 改端口、读无线调试端口都经它，可查。配对不经它。 */
    private val privileged: () -> PrivilegedBackend,
    private val onEvent: (warn: Boolean, message: String, detail: String) -> Unit = { _, _, _ -> },
) {
    private val identity = AdbIdentity(context)

    /** 连按两下不会叠两次 adbd 重启：第二下直接说「还没结束」。 */
    private val busy = Mutex()

    /** 编辑页据此说明「首次执行会自动配对」还是「已配对」。 */
    fun paired(): Boolean = identity.paired

    /** Shizuku 以 root 运行时根本用不着配对与无线调试。 */
    fun rootAvailable(): Boolean = shizuku.isRoot

    /** 无线调试开没开；读不到时为 null（老系统或改过的 ROM），不当成「没开」。 */
    fun wirelessEnabled(): Boolean? = wirelessDebug.enabled()

    fun shizukuUsable(): Boolean = shizuku.status.value.usable

    suspend fun apply(enable: Boolean, port: Int): ActionResult {
        if (!busy.tryLock()) return ActionResult.Failed(ActionResult.Reason.ADB_BUSY)
        return try {
            withContext(Dispatchers.IO) {
                if (enable && listening(port)) {
                    ActionResult.Ok("已在监听 · 端口 " + port)
                } else if (shizuku.isRoot) {
                    viaRoot(enable, port)
                } else {
                    viaClient(if (enable) "tcpip:" + port else "usb:").let { failure ->
                        failure ?: if (enable) awaitListening(port) else ActionResult.Ok("已收回 TCP 端口")
                    }
                }
            }
        } finally {
            busy.unlock()
        }
    }

    /**
     * 编辑页的「测试」：配对（需要的话）并连上 adbd，但不发任何请求 —— 不重启 adbd，不断开谁。
     * 首次配对放在这里做完，按下快捷键那一刻就只剩切端口这一步。
     */
    suspend fun check(): Result<String> = withContext(Dispatchers.IO) {
        if (shizuku.isRoot) return@withContext Result.success("Shizuku 以 root 运行 · 直接改端口，不需要配对")
        if (!busy.tryLock()) return@withContext Result.failure(IllegalStateException(ActionResult.Reason.ADB_BUSY.message))
        try {
            when (val failure = viaClient(null)) {
                null -> Result.success("已连上本机 adbd · 可以切换端口")
                else -> Result.failure(IllegalStateException(failure.message))
            }
        } finally {
            busy.unlock()
        }
    }

    // --- root ----------------------------------------------------------------

    private suspend fun viaRoot(enable: Boolean, port: Int): ActionResult {
        // `adb usb` 写的就是 0：adbd 启动时读到 0 不开 TCP
        val value = if (enable) port else 0
        val command = "setprop " + TCP_PORT_PROPERTY + " " + value + " && setprop ctl.restart adbd"
        val result = privileged().exec(command).getOrElse {
            return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty())
        }
        if (!result.ok) return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, result.output.trim().take(200))
        return if (enable) awaitListening(port) else ActionResult.Ok("已收回 TCP 端口")
    }

    // --- 本机 adb 客户端 -------------------------------------------------------

    /**
     * 连上本机 adbd，发 [service]（为 null 时只连不发）。成功返回 null，失败返回原因。
     *
     * 第一次连不上而且像是没配对（TLS 握手被拒），就配对一次再连 ——
     * 本地记着「配对过」也照样重来：用户可能在开发者选项里撤销过。
     */
    private suspend fun viaClient(service: String?): ActionResult.Failed? {
        if (wirelessDebug.enabled() == false) return ActionResult.Failed(ActionResult.Reason.ADB_NEEDS_WIRELESS)
        val tlsPort = tlsPort()
            ?: return ActionResult.Failed(ActionResult.Reason.ADB_NEEDS_WIRELESS, "读不到无线调试端口")
        val (key, cert) = identity.load()

        val first = runCatching { talk(key, cert, tlsPort, service) }
        val error = first.exceptionOrNull() ?: return null
        if (!needsPairing(error)) {
            return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, describe(error))
        }
        identity.paired = false
        pair(key, cert)?.let { return it }
        return runCatching { talk(key, cert, tlsPort, service) }.exceptionOrNull()?.let {
            ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, describe(it))
        }
    }

    /** 连接、发请求、读完 adbd 的回话。tcpip / usb 发出去之后 adbd 会自己退出，连接随之断开，这是预期。 */
    private suspend fun talk(key: PrivateKey, cert: Certificate, tlsPort: Int, service: String?) {
        val client = LocalClient(key, cert)
        try {
            val connected = runInterruptible { client.connect(LOOPBACK, tlsPort) }
            if (!connected) throw IOException("连接本机 adbd 超时")
            if (service == null) return
            val stream = runInterruptible { client.openStream(service) }
            // 回的是一行 "restarting in TCP mode port: 6677"；读不完整也无妨，成败看端口有没有开
            val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                runCatching { runInterruptible { stream.openInputStream().bufferedReader().readLine() } }.getOrNull()
            }
            onEvent(false, "已向 adbd 发送 " + service, reply.orEmpty().trim())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * 像不像「没配对」。libadb 认得出的会抛 [AdbPairingRequiredException]，认不出时只给一句不带原因的
     * `Connection failed` —— 所以从没配对过时，除了端口根本连不上，一律先配对再说。
     */
    private fun needsPairing(error: Throwable): Boolean =
        error is AdbPairingRequiredException || error is AdbAuthenticationFailedException ||
            error is SSLException || error.cause is SSLException ||
            (!identity.paired && error !is java.net.ConnectException)

    /**
     * 经 Shizuku 开一次配对，自己当对端配上。名称带随机后缀，好在 mDNS 里只认自己开的这一个；
     * 配对码只在内存里，用完即弃，不进任何日志。成功返回 null。
     */
    private suspend fun pair(key: PrivateKey, cert: Certificate): ActionResult.Failed? {
        if (!shizuku.status.value.usable) return ActionResult.Failed(ActionResult.Reason.ADB_NOT_PAIRED)
        val random = SecureRandom()
        val name = PAIRING_NAME_PREFIX + (1..6).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
        val password = (1..PASSWORD_LENGTH).map { ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)] }.joinToString("")

        // 先开始监听再开配对：mDNS 广播在配对服务起来的那一刻就发出去了
        val watch = MdnsWatch(context, PAIRING_SERVICE) { it.serviceName == name }
        watch.start()
        return try {
            shizuku.startAdbPairing(name, password).exceptionOrNull()?.let {
                return ActionResult.Failed(ActionResult.Reason.ADB_PAIRING_FAILED, describe(it))
            }
            val pairingPort = watch.await(PAIRING_DISCOVERY_TIMEOUT_MS)
                ?: return ActionResult.Failed(ActionResult.Reason.ADB_PAIRING_FAILED, "没等到系统的配对服务")
            runInterruptible { LocalClient(key, cert).pair(LOOPBACK, pairingPort, password) }
            identity.paired = true
            onEvent(false, "已与本机 adbd 配对", "开发者选项 · 无线调试 · 已配对的设备里显示为 " + AdbIdentity.CLIENT_NAME)
            null
        } catch (e: Exception) {
            ActionResult.Failed(ActionResult.Reason.ADB_PAIRING_FAILED, describe(e))
        } finally {
            watch.stop()
            shizuku.stopAdbPairing()
        }
    }

    /**
     * 无线调试此刻的端口：先不经特权读，再经 Shizuku 读，最后在 mDNS 里找本机广播的那一个 ——
     * 最后这条不要任何权限，配对过、Shizuku 又不在时就靠它。
     */
    private suspend fun tlsPort(): Int? {
        wirelessDebug.portWithoutPrivilege()?.let { return it }
        if (shizuku.status.value.usable) {
            privileged().exec(WirelessDebugBackend.PORT_COMMAND).getOrNull()
                ?.let { WirelessDebugBackend.parsePortOutput(it.output) }
                ?.let { return it }
        }
        val watch = MdnsWatch(context, CONNECT_SERVICE) { true }
        watch.start()
        return try {
            watch.await(CONNECT_DISCOVERY_TIMEOUT_MS)
        } finally {
            watch.stop()
        }
    }

    /** adbd 重启要一两秒；端口真的能连上才算成功，不凭那一句回话。 */
    private suspend fun awaitListening(port: Int): ActionResult {
        val deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(LISTEN_POLL_MS)
            if (listening(port)) return ActionResult.Ok("已开启 · 端口 " + port)
        }
        return ActionResult.Failed(ActionResult.Reason.ADB_NOT_LISTENING, "端口 " + port)
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return (root.message ?: t.message ?: root.javaClass.simpleName).take(200)
    }

    /** libadb 的连接管理器：身份由 [AdbIdentity] 给，只连本机。 */
    private class LocalClient(private val key: PrivateKey, private val cert: Certificate) : AbsAdbConnectionManager() {
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = AdbIdentity.CLIENT_NAME
    }

    companion object {
        const val TCP_PORT_PROPERTY = "service.adb.tcp.port"
        private const val LOOPBACK = "127.0.0.1"
        private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
        private const val PAIRING_NAME_PREFIX = "actionmental-"
        private const val HEX = "0123456789abcdef"
        private const val ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private const val PASSWORD_LENGTH = 16
        private const val CONNECT_TIMEOUT_S = 5L
        private const val REPLY_TIMEOUT_MS = 2_000L
        private const val PAIRING_DISCOVERY_TIMEOUT_MS = 8_000L
        private const val CONNECT_DISCOVERY_TIMEOUT_MS = 3_000L
        private const val LISTEN_TIMEOUT_MS = 8_000L
        private const val LISTEN_POLL_MS = 300L
        private const val PROBE_TIMEOUT_MS = 300
    }
}

/**
 * 在 mDNS 里等本机广播的某一类 adb 服务，拿到它的端口。
 *
 * 只认解析出来的地址落在本机网卡上的：同一 Wi-Fi 下别的手机也在广播同类服务。
 */
private class MdnsWatch(
    context: Context,
    private val type: String,
    private val accept: (NsdServiceInfo) -> Boolean,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = CompletableDeferred<Int>()
    @Volatile private var started = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!accept(info) || found.isCompleted) return
            @Suppress("DEPRECATION")
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (isLocal(info)) found.complete(info.port)
                }
            })
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            started = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        val manager = nsd ?: return
        started = runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
    }

    suspend fun await(timeoutMs: Long): Int? = if (nsd == null) null else withTimeoutOrNull(timeoutMs) { found.await() }

    fun stop() {
        if (started) runCatching { nsd?.stopServiceDiscovery(listener) }
        started = false
    }

    private fun isLocal(info: NsdServiceInfo): Boolean {
        val hosts: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        val local = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
        }.getOrDefault(emptySet())
        return hosts.any { it in local || it.isLoopbackAddress }
    }
}
