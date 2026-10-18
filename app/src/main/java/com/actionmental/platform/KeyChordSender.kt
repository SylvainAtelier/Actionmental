package com.actionmental.platform

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import com.actionmental.core.action.ActionResult
import com.actionmental.core.key.KeyChord
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline

/**
 * 「发送组合键」动作的出口：整串注入，冒用实体键盘的设备号。
 *
 * 和键位映射的 [KeyOutputRouter] 是两回事 —— 映射只发一颗带修饰位的主键、标成虚拟键盘就够了；
 * 这里的听众是输入法，它的快捷键要先看到修饰键自己按下，而且只认实体键盘。
 * 只有 Shizuku 能把事件送到输入法前面，无障碍的输入通道直接进应用、输入法看不见。
 */
class KeyChordSender(
    private val pipeline: KeyPipeline,
    private val privileged: () -> PrivilegedBackend,
) {

    fun ready(): Boolean = privileged().availability() !is ActionResult.Failed

    suspend fun send(combo: KeyCombo): ActionResult {
        val backend = privileged()
        val availability = backend.availability()
        if (availability is ActionResult.Failed) {
            return ActionResult.Failed(availability.reason, "发送组合键需要 Shizuku")
        }
        val steps = KeyChord.steps(combo)
        val deviceId = sourceKeyboard()
        if (deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD) {
            pipeline.expectInjected(steps.map { it.keyCode }, SystemClock.uptimeMillis())
        }
        return backend.injectSequence(steps, deviceId).fold(
            onSuccess = { ActionResult.Ok("已发送 " + combo) },
            onFailure = { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) },
        )
    }

    /**
     * 借谁的设备号：刚按下快捷键的那把键盘还连着就用它，否则挑一把连着的外接全键盘，
     * 都没有（从桌面按钮触发、键盘已断开）才退回虚拟键盘。
     */
    private fun sourceKeyboard(): Int {
        val last = pipeline.lastDevice.value
        if (last.id > 0 && isFullKeyboard(InputDevice.getDevice(last.id))) return last.id
        return InputDevice.getDeviceIds()
            .firstOrNull { isFullKeyboard(InputDevice.getDevice(it)) }
            ?: KeyCharacterMap.VIRTUAL_KEYBOARD
    }

    private fun isFullKeyboard(device: InputDevice?): Boolean =
        device != null && !device.isVirtual && device.isExternal &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
}
