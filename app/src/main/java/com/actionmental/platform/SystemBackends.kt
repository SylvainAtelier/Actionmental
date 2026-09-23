package com.actionmental.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import com.actionmental.core.action.ActionResult
import com.actionmental.core.key.KeyboardDevice

/** 音量 / 媒体：普通 API 就能做到的事，绝不走 Shizuku（PRD 3.4）。 */
class AudioBackend(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeUp(): ActionResult = adjust(AudioManager.ADJUST_RAISE)

    fun volumeDown(): ActionResult = adjust(AudioManager.ADJUST_LOWER)

    fun toggleMute(): ActionResult = adjust(AudioManager.ADJUST_TOGGLE_MUTE)

    private fun adjust(direction: Int): ActionResult = runCatching {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        ActionResult.OK
    }.getOrElse { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) }

    fun dispatchMediaKey(keyCode: Int): ActionResult = runCatching {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        ActionResult.OK
    }.getOrElse { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) }
}

/** 应用启动、应用清单与链接打开。 */
class PackageBackend(private val context: Context) {

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val launchActivity: String,
        val frozen: Boolean,
        val systemApp: Boolean,
    )

    /** 一个可以被直接启动的 Activity 入口。 */
    data class ActivityEntry(
        val packageName: String,
        val className: String,
        val label: String,
        val exported: Boolean,
        val isDefaultEntry: Boolean,
    ) {
        /** 未导出的入口普通 startActivity 会被拒绝，只能退回 Shizuku 的 am start。 */
        val requiresPrivilege: Boolean get() = !exported
    }

    fun launch(packageName: String, activity: String?): ActionResult {
        val intent = if (activity.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } else {
            Intent(Intent.ACTION_MAIN).setClassName(packageName, activity)
        } ?: return ActionResult.Failed(ActionResult.Reason.TARGET_NOT_FOUND, packageName)

        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.OK
        }.getOrElse { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) }
    }

    /** 交给系统默认浏览器。应用自己不申请网络权限，也不做任何抓取。 */
    fun openUrl(url: String): ActionResult {
        val normalized = normalizeUrl(url)
            ?: return ActionResult.Failed(ActionResult.Reason.TARGET_NOT_FOUND, url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult.OK
        }.getOrElse {
            ActionResult.Failed(ActionResult.Reason.TARGET_NOT_FOUND, normalized)
        }
    }

    /**
     * 包含被 disable-user 冻结的应用。普通的 launcher 查询会把它们漏掉，
     * 因而应用选择器必须显式带 MATCH_DISABLED_COMPONENTS。
     *
     * 这里只用 queryIntentActivities 一次返回的数据判断冻结状态：
     * 逐包再问一次 getApplicationEnabledSetting 会变成几百次 IPC，
     * 是选择器打开时唯一会让人等的地方。
     */
    @Suppress("DEPRECATION")
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
            .map {
                val applicationInfo = it.activityInfo.applicationInfo
                InstalledApp(
                    packageName = it.activityInfo.packageName,
                    // 查询与逐条取名之间包可能刚被更新或卸载，取不到名字就用包名顶上
                    label = runCatching { it.loadLabel(context.packageManager).toString() }
                        .getOrDefault(it.activityInfo.packageName),
                    launchActivity = it.activityInfo.name,
                    frozen = !applicationInfo.enabled ||
                        applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0,
                    systemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * 单个应用里所有可作为启动目标的 Activity。
     *
     * 只查一个包，所以随点随开；刻意不调 loadLabel —— 那会为每一条去解析一次资源，
     * 几百条 Activity 的应用（微信、系统设置）能卡上一秒，而返回的多半还是应用名本身。
     */
    @Suppress("DEPRECATION")
    fun activitiesOf(packageName: String): List<ActivityEntry> = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val defaultEntry = pm.getLaunchIntentForPackage(packageName)?.component?.className
        info.activities.orEmpty()
            .map { activity ->
                ActivityEntry(
                    packageName = packageName,
                    className = activity.name,
                    label = activity.name.substringAfterLast('.').removePrefix("$"),
                    exported = activity.exported,
                    isDefaultEntry = activity.name == defaultEntry,
                )
            }
            .distinctBy { it.className }
            .sortedWith(
                compareByDescending<ActivityEntry> { it.isDefaultEntry }
                    .thenByDescending { it.exported }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
    }.getOrDefault(emptyList())

    /** 包是否装在机器上 —— 被冻结、被停用的也算装着。 */
    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        true
    }.getOrDefault(false)

    /**
     * 找出这个包的启动入口，包括被冻结的。
     *
     * getLaunchIntentForPackage 对 `pm disable-user` 过的包一律返回 null ——
     * 这正是冰箱类应用冻结后「目标应用不存在」的由来。
     * 只有显式带上 MATCH_DISABLED_COMPONENTS 的查询才还看得见它的 LAUNCHER Activity，
     * 解冻命令也才有 `am start` 的落点。
     */
    @Suppress("DEPRECATION")
    fun resolveLaunchActivity(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(packageName)?.component?.className
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            ).firstOrNull()?.activityInfo?.name
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun isFrozen(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        !info.enabled ||
            info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
            isExplicitlyDisabled(packageName)
    }.getOrDefault(false)

    private fun isExplicitlyDisabled(packageName: String): Boolean =
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }

    fun labelOf(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)

    companion object {
        /** 用户不会输入 scheme。补 https，并挡掉明显不是链接的输入。 */
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            if (trimmed.contains("://") || trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")) {
                return trimmed
            }
            if (!trimmed.contains('.')) return null
            return "https://" + trimmed
        }
    }
}

/** 实体键盘枚举。deviceId 会变，descriptor 才是稳定标识。 */
class InputDeviceBackend(context: Context) {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    fun physicalKeyboards(): List<KeyboardDevice> = inputManager.inputDeviceIds.toList()
        .mapNotNull { inputManager.getInputDevice(it) }
        .filter { it.isPhysicalKeyboard() }
        .map { KeyboardDevice.from(it) }

    private fun InputDevice.isPhysicalKeyboard(): Boolean =
        !isVirtual &&
            sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    fun registerListener(listener: InputManager.InputDeviceListener) {
        inputManager.registerInputDeviceListener(listener, null)
    }

    fun unregisterListener(listener: InputManager.InputDeviceListener) {
        runCatching { inputManager.unregisterInputDeviceListener(listener) }
    }
}

/** 读 system 表里的一个整数。旋转控制器只要这一条，单测里换成一张表即可。 */
fun interface SystemIntSource {
    fun systemInt(key: String): Int?
}

/** 无需特权即可读取的系统设置。写入才需要 Shizuku。 */
class SettingsReader(private val context: Context) : SystemIntSource {

    override fun systemInt(key: String): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()

    fun globalInt(key: String): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()

    /**
     * 盯住几个 system 表的键，变了就回调一次。
     *
     * 用来替代「隔一会儿去读一遍」：这些值一天也变不了几次，而每一次轮询都要
     * 跨进程查一趟。观察者既更省，也更快 —— 用户在快捷设置里刚拨了自动旋转，
     * 当场就知道了。
     */
    fun observeSystem(keys: List<String>, onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        keys.forEach { key ->
            runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    observer,
                )
            }
        }
        return observer
    }
}
