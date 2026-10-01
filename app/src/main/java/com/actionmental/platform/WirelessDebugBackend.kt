package com.actionmental.platform

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无线调试的地址读取与剪贴板写入。
 *
 * 这里只做不需要特权的部分；端口读不到时由 ActionExecutor 决定是否退回 Shizuku。
 */
class WirelessDebugBackend(private val context: Context) {

    /**
     * 开发者选项里的「无线调试」开关。
     *
     * 键名是隐藏常量 Settings.Global.ADB_WIFI_ENABLED；老系统或改过的 ROM 上可能没有，
     * 读不到时返回 null，交给端口去判断，而不是一口咬定没开。
     */
    fun enabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    /**
     * Wi-Fi 网卡上的 IPv4。
     *
     * 用 NetworkInterface 而不是 ConnectivityManager：后者要多申请网络状态权限。
     * 优先 wlan*，没有就退到任意一块在线、非回环的局域网地址（部分 ROM 网卡名不同）。
     */
    fun wifiIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nic.name to it.hostAddress }
            }
        (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second
    }.getOrNull()

    /**
     * 不经特权读端口。多数系统的 SELinux 策略不让普通应用读 adbd 的属性，
     * 读到空串就当读不到，不当成「没开」。
     */
    fun portWithoutPrivilege(): Int? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java).invoke(null, PORT_PROPERTY) as String
        parsePort(value)
    }.getOrNull()

    /**
     * 此刻直接写剪贴板会不会被放行。
     *
     * ColorOS 把所有应用的 WRITE_CLIPBOARD 默认设成 foreground，快捷键又总在后台触发：
     * 这时 setPrimaryClip 会被静默丢弃。unsafeCheckOpNoThrow 返回的是按当前进程状态
     * 折算后的模式，后台时会是 IGNORED。查不了就当放行，退回原来的直接写。
     */
    fun clipboardWritable(): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(OPSTR_WRITE_CLIPBOARD, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(true)

    /** 经特权把 WRITE_CLIPBOARD 永久放开。uid 级和包级都要改：uid 级一旦设了就优先生效。 */
    val grantClipboardCommand: String
        get() = "cmd appops set --uid " + context.packageName + " WRITE_CLIPBOARD allow; " +
            "cmd appops set " + context.packageName + " WRITE_CLIPBOARD allow"

    /**
     * 写剪贴板。复制了什么由快捷键的触发提示（TriggerHud）或界面的 snackbar 负责显示。
     *
     * 后台写不进去时借透明跳板 Activity 短暂进前台再写，见 [ClipboardTrampolineActivity]。
     */
    suspend fun copy(text: String): Boolean {
        if (!clipboardWritable()) return ClipboardTrampolineActivity.write(context, CLIP_LABEL, text)
        return withContext(Dispatchers.Main) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                true
            }.getOrDefault(false)
        }
    }

    companion object {
        const val PORT_PROPERTY = "service.adb.tls.port"
        private const val CLIP_LABEL = "adb"

        /** AppOpsManager.OPSTR_WRITE_CLIPBOARD 是隐藏常量，按字面量写。 */
        private const val OPSTR_WRITE_CLIPBOARD = "android:write_clipboard"

        /**
         * 特权读端口：先 getprop，再看 AdbService 自己报的 tls_port。
         *
         * Android 16 起系统不再写 [PORT_PROPERTY]，端口只剩 `dumpsys adb` 里的
         * `adb_wifi.tls_port` 一处。那段输出里还有所有已授权电脑的公钥，
         * 所以只 grep 出这一行 —— shell 日志会记下输出，公钥不该落进去。
         */
        const val PORT_COMMAND = "getprop " + PORT_PROPERTY +
            "; dumpsys adb 2>/dev/null | grep -m1 -o 'tls_port=[0-9-]*'"

        /** 关掉无线调试后属性可能残留成 0 或 -1，只认真正的端口号。 */
        fun parsePort(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

        /** [PORT_COMMAND] 的输出：逐行取第一个有效端口，属性空行与 `tls_port=` 前缀都容得下。 */
        fun parsePortOutput(output: String): Int? = output.lineSequence()
            .map { it.trim().removePrefix("tls_port=") }
            .firstNotNullOfOrNull { parsePort(it) }
    }
}
