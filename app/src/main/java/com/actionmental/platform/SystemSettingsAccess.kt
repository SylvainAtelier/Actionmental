package com.actionmental.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.actionmental.core.action.ActionResult

/**
 * 应用自己（不经 shell）写系统设置的能力。
 *
 * 两条来源，彼此独立：
 *  - `WRITE_SETTINGS`：用户在系统的「修改系统设置」页里给一次。只能写 system 表的公开项，
 *    旋转那两项（accelerometer_rotation / user_rotation）正在其中；
 *  - `WRITE_SECURE_SETTINGS`：只能用 adb 授一次（`pm grant`），重启不丢。
 *    有了它 secure / global / system 三张表都写得了 —— 无障碍自愈要改的
 *    enabled_accessibility_services 就在 secure 表里。
 *
 * 它们替代的是 Shizuku 里「改设置」的那一部分，`cmd window`、`appops`、按键注入仍然只有 shell 做得到。
 */
class SystemSettingsAccess(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** adb 授过 WRITE_SECURE_SETTINGS。每次现查：授予和撤销都可能在进程活着时发生。 */
    fun secureWritable(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 用户在「修改系统设置」页里开了开关。 */
    fun systemGranted(): Boolean = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * 写得了 system 表。
     *
     * SettingsProvider 对持有 WRITE_SECURE_SETTINGS 的调用方直接放行 system 表的写入，
     * 而 [Settings.System.canWrite] 只看 WRITE_SETTINGS 那个 appop，两者必须或起来。
     */
    fun systemWritable(): Boolean = secureWritable() || systemGranted()

    /** 打开系统的「修改系统设置」授权页，直达本应用那一行。 */
    fun manageWriteSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 用户要在电脑上执行的那一行。界面上原样给出，可复制。 */
    fun grantSecureCommand(): String =
        "adb shell pm grant " + context.packageName + " " + Manifest.permission.WRITE_SECURE_SETTINGS

    fun putSystemInt(key: String, value: Int): Result<Unit> = runCatching {
        check(Settings.System.putInt(resolver, key, value)) { "系统拒绝写入 " + key }
    }

    fun getString(namespace: String, key: String): Result<String?> = runCatching {
        when (namespace) {
            SECURE -> Settings.Secure.getString(resolver, key)
            GLOBAL -> Settings.Global.getString(resolver, key)
            SYSTEM -> Settings.System.getString(resolver, key)
            else -> throw IllegalArgumentException("未知的设置表 " + namespace)
        }?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** 空串照写：与 shell 不同，API 写空值不会少一个参数。 */
    fun putString(namespace: String, key: String, value: String): Result<Unit> = runCatching {
        val ok = when (namespace) {
            SECURE -> Settings.Secure.putString(resolver, key, value)
            GLOBAL -> Settings.Global.putString(resolver, key, value)
            SYSTEM -> Settings.System.putString(resolver, key, value)
            else -> throw IllegalArgumentException("未知的设置表 " + namespace)
        }
        check(ok) { "系统拒绝写入 " + namespace + "/" + key }
    }

    companion object {
        const val SECURE = "secure"
        const val GLOBAL = "global"
        const val SYSTEM = "system"
    }
}

/**
 * 读写设置优先走应用自己的 WRITE_SECURE_SETTINGS，其余一切照旧交给 [shell]。
 *
 * 给无障碍自愈用：它只读写 secure 表，而这件事一次 adb 授权就能永久做到，
 * 不必每次开机都等 Shizuku 起来 —— 开机那一刻恰恰是 ROM 最常关掉无障碍、Shizuku 又还没起来的时候。
 */
class SettingsFirstBackend(
    private val settings: SystemSettingsAccess,
    private val shell: PrivilegedBackend,
) : PrivilegedBackend {

    private val direct: Boolean get() = settings.secureWritable()

    override fun availability(): ActionResult =
        if (direct) ActionResult.OK else shell.availability()

    override suspend fun exec(command: String): Result<ShellResult> = shell.exec(command)

    override suspend fun injectKey(keyCode: Int, metaState: Int): Result<Unit> = shell.injectKey(keyCode, metaState)

    override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean): Result<Unit> =
        shell.injectKeyState(keyCode, metaState, down)

    override suspend fun getSetting(namespace: String, key: String): Result<String?> =
        if (direct) settings.getString(namespace, key) else shell.getSetting(namespace, key)

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> =
        if (direct) settings.putString(namespace, key, value) else shell.putSetting(namespace, key, value)
}
