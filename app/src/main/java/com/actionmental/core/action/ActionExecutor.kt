package com.actionmental.core.action

import android.accessibilityservice.AccessibilityService
import com.actionmental.core.awake.ScreenAwakeController
import com.actionmental.core.rotation.RotationController
import com.actionmental.platform.AccessibilityBridge
import com.actionmental.platform.AudioBackend
import com.actionmental.platform.PackageBackend
import com.actionmental.platform.PrivilegedBackend

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
        is Action.Shell -> shell(action.command)
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
