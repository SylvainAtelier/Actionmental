package com.actionmental.core.action

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.actionmental.core.awake.ScreenAwakeController
import com.actionmental.core.rotation.RotationController
import com.actionmental.platform.AccessibilityBridge
import com.actionmental.platform.AudioBackend
import com.actionmental.platform.ClipboardWriter
import com.actionmental.platform.KeyChordSender
import com.actionmental.platform.PackageBackend
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.TermuxBackend
import com.actionmental.platform.VoiceInputBackend
import com.actionmental.platform.sync.SyncBackend
import com.actionmental.platform.WirelessDebugBackend
import com.actionmental.platform.adb.AdbTcpipBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唯一的动作执行层（PRD 3.3 / 35.5）。
 *
 * 界面、快捷键、磁贴都只会走到这里；它按能力分层挑后端：
 * 普通 API 能做的绝不上 Shizuku，需要无障碍的走 AccessibilityBridge，
 * 只有真正需要 shell 的才落到 [privileged]（PRD 3.4 / 35.4）。
 */
class ActionExecutor(
    private val accessibility: AccessibilityBridge,
    private val audio: AudioBackend,
    private val packages: PackageBackend,
    private val rotation: RotationController,
    private val awake: ScreenAwakeController,
    private val wirelessDebug: WirelessDebugBackend,
    private val termux: TermuxBackend,
    private val sync: SyncBackend,
    private val adbTcpip: AdbTcpipBackend,
    private val voiceInput: VoiceInputBackend,
    private val keyChord: KeyChordSender,
    private val clipboard: ClipboardWriter,
    /** 剪贴板面板挂在无障碍服务的窗口上，开关它由平台层做；这里只转一手。 */
    private val toggleClipboardHistory: () -> ActionResult,
    private val privileged: () -> PrivilegedBackend,
) {

    suspend fun execute(action: Action): ActionResult = when (action) {
        is Action.Navigation -> navigate(action.target)
        is Action.Media -> audio.dispatchMediaKey(action.target.keyCode)
        is Action.Volume -> when (action.target) {
            Action.Volume.Target.UP -> audio.volumeUp()
            Action.Volume.Target.DOWN -> audio.volumeDown()
            Action.Volume.Target.MUTE -> audio.toggleMute()
        }
        is Action.LaunchApp -> launchApp(action)
        is Action.OpenUrl -> packages.openUrl(action.url)
        is Action.Rotation -> when (action.op) {
            Action.Rotation.Op.SET -> rotation.toggle(action.mode)
            Action.Rotation.Op.RESTORE -> rotation.setGlobal(com.actionmental.core.rotation.RotationMode.NORMAL)
            Action.Rotation.Op.TOGGLE_LANDSCAPE -> rotation.toggleLandscape()
            Action.Rotation.Op.CYCLE -> rotation.cycle()
        }
        is Action.Awake -> when (action.op) {
            Action.Awake.Op.ON -> awake.set(true)
            Action.Awake.Op.OFF -> awake.set(false)
            Action.Awake.Op.TOGGLE -> awake.toggle()
        }
        is Action.WirelessDebug -> copyWirelessDebug(action.target)
        is Action.AdbTcpip -> switchAdbTcpip(action)
        is Action.ClipboardHistory -> toggleClipboardHistory()
        is Action.VoiceInput -> voiceInput.trigger(action.mode)
        is Action.SendKey -> keyChord.send(action.combo)
        is Action.TypeSymbol -> typeSymbol(action.text)
        is Action.Termux -> termux.run(action, privileged())
        is Action.FolderSync -> sync.start(action.config)
        is Action.Shell -> shell(action.command)
    }

    /**
     * 复制无线调试的 IP / 端口。
     *
     * 开关明确是关的就直接报「未开启」；端口先试普通读取，读不到再走 Shizuku 的 getprop，
     * 两条都不通时如实说是缺 Shizuku，而不是含糊地报执行失败。
     */
    private suspend fun copyWirelessDebug(target: Action.WirelessDebug.Target): ActionResult {
        val needsPort = target != Action.WirelessDebug.Target.IP
        val needsIp = target != Action.WirelessDebug.Target.PORT
        if (needsPort && wirelessDebug.enabled() == false) {
            return ActionResult.Failed(ActionResult.Reason.WIRELESS_DEBUG_OFF)
        }

        val ip = if (needsIp) {
            wirelessDebug.wifiIpv4() ?: return ActionResult.Failed(ActionResult.Reason.WIFI_UNAVAILABLE)
        } else null

        val port = if (needsPort) {
            wirelessDebug.portWithoutPrivilege() ?: run {
                val backend = privileged()
                val availability = backend.availability()
                if (availability is ActionResult.Failed) {
                    return ActionResult.Failed(availability.reason, "读取端口需要 Shizuku")
                }
                val read = backend.exec(WirelessDebugBackend.PORT_COMMAND).getOrElse {
                    return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty())
                }
                WirelessDebugBackend.parsePortOutput(read.output)
                    ?: return ActionResult.Failed(ActionResult.Reason.WIRELESS_DEBUG_OFF)
            }
        } else null

        val text = when (target) {
            Action.WirelessDebug.Target.ADDRESS -> ip + ":" + port
            Action.WirelessDebug.Target.IP -> ip!!
            Action.WirelessDebug.Target.PORT -> port.toString()
        }
        return if (clipboard.write("adb", text)) ActionResult.Ok("已复制 " + text, copied = text)
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "剪贴板写入失败")
    }

    /**
     * 切 adbd 的 TCP 端口。开成功之后把 `IP:端口` 带回去：提示里要逐字核对，
     * 选了「复制」就顺手写进剪贴板 —— 电脑那边要的正是这一串。
     * 没连 Wi-Fi 时端口照样开着（USB 网络共享、热点下也能连），只是给不出地址。
     */
    private suspend fun switchAdbTcpip(action: Action.AdbTcpip): ActionResult {
        val result = adbTcpip.apply(action.enable, action.port)
        if (result !is ActionResult.Ok || !action.enable) return result
        val address = wirelessDebug.connectIpv4()?.let { it + ":" + action.port } ?: return result
        return if (action.copyAddress && clipboard.write("adb", address)) {
            ActionResult.Ok(result.detail + " · 已复制 " + address, copied = address)
        } else {
            ActionResult.Ok(result.detail + " · 地址 " + address)
        }
    }

    /**
     * 启动应用，冻结的也要能启动。
     *
     * 被冰箱类应用 `pm disable-user` 冻结之后，PackageManager 对这个包既不给
     * 启动 Intent 也不给启动 Activity —— 普通路径只能得到「目标应用不存在」，
     * 而这恰恰是最需要解冻的时候。所以这里不再要求快捷键必须配了具体 Activity：
     * 只要包还装着，就走特权路径解冻，再把入口现查出来启动。
     */
    private suspend fun launchApp(action: Action.LaunchApp): ActionResult {
        val directResult = packages.launch(action.packageName, action.activity)
        if (directResult.succeeded) return directResult

        // 包真的不在机器上，解冻也无从谈起。
        if (!packages.isInstalled(action.packageName)) {
            return ActionResult.Failed(ActionResult.Reason.TARGET_NOT_FOUND, action.packageName)
        }

        val frozen = packages.isFrozen(action.packageName)
        val backend = privileged()
        val availability = backend.availability()
        if (availability is ActionResult.Failed) {
            // 冻结时说清楚「要 Shizuku 才解得开」，比原样回报「不存在」有用得多。
            return if (frozen) availability else directResult
        }

        val packageArg = shellArg(action.packageName)
        // 冻结手段不止一种（disable-user / suspend / hide），逐条容错地都试一遍，
        // 任何一条失败都不该挡住后面的；成败只看最后真正的启动。
        val thaw = "pm enable --user current " + packageArg + " >/dev/null 2>&1;" +
            " pm unsuspend --user current " + packageArg + " >/dev/null 2>&1;" +
            " pm unhide " + packageArg + " >/dev/null 2>&1; true"
        val thawed = backend.exec(thaw)
        if (thawed.isFailure) {
            return ActionResult.Failed(
                ActionResult.Reason.EXECUTION_FAILED,
                thawed.exceptionOrNull()?.message.orEmpty(),
            )
        }

        // 解冻之后入口才重新可见：优先用快捷键里指定的 Activity，没有就现查默认入口。
        val activity = action.activity ?: packages.resolveLaunchActivity(action.packageName)
        val start = if (activity != null) {
            "am start --user current -n " + shellArg(action.packageName + "/" + activity)
        } else {
            // 连入口都查不出来（例如包可见性刚刚才恢复），交给 monkey 按 LAUNCHER 类别拉起。
            "monkey -p " + packageArg + " -c android.intent.category.LAUNCHER 1"
        }
        return backend.exec(start).fold(
            onSuccess = {
                val output = it.output.trim()
                // am / monkey 拒绝启动时照样退出码 0，只在输出里报 Error —— 只看退出码会把失败当成功。
                if (it.ok && !output.contains("Error:") && !output.contains("Exception")) {
                    ActionResult.Ok((if (frozen) "已解冻并启动 " else "已启动 ") + action.appLabel)
                } else {
                    ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, output.take(200))
                }
            },
            onFailure = { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) },
        )
    }

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * 经无障碍的输入连接把符号写进焦点输入框。
     *
     * 写不进去（Android 13 以下没有这条连接，或者此刻没有输入框）就放进剪贴板，
     * 失败原因照实说，详情里告诉用户已经复制、自己粘贴即可。
     */
    private suspend fun typeSymbol(text: String): ActionResult {
        if (!accessibility.connected.value) return ActionResult.Failed(ActionResult.Reason.ACCESSIBILITY_OFF)
        if (withContext(Dispatchers.Main) { accessibility.commitText(text) }) return ActionResult.OK
        val reason = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ActionResult.Reason.UNSUPPORTED
        else ActionResult.Reason.NO_INPUT_FIELD
        return ActionResult.Failed(reason, if (clipboard.write("symbol", text)) "已复制到剪贴板" else "")
    }

    private fun navigate(target: Action.Navigation.Target): ActionResult {
        if (!accessibility.connected.value) {
            return ActionResult.Failed(ActionResult.Reason.ACCESSIBILITY_OFF)
        }
        val code = when (target) {
            Action.Navigation.Target.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            Action.Navigation.Target.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            Action.Navigation.Target.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            Action.Navigation.Target.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            Action.Navigation.Target.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            Action.Navigation.Target.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        }
        return if (accessibility.performGlobalAction(code)) ActionResult.OK
        else ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, target.code)
    }

    private suspend fun shell(command: String): ActionResult {
        val backend = privileged()
        val availability = backend.availability()
        if (availability is ActionResult.Failed) return availability

        return backend.exec(command).fold(
            onSuccess = {
                if (it.ok) ActionResult.Ok(it.output.trim().take(200))
                else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.output.trim().take(200))
            },
            onFailure = { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) },
        )
    }
}
