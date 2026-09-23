package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.actionmental.core.key.KeyChannel
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyOutput
import com.actionmental.core.key.KeyRouting
import com.actionmental.platform.shizuku.ShizukuManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 映射输出的四条路，按 [KeyRouting.route] 分流。
 *
 * Shizuku 在且特权服务已连上时一律注入；否则系统键走全局动作、媒体与音量走 AudioManager、
 * 其余的键在 Android 13+ 交给无障碍服务自带的输入通道。四条都走不通才算不可用。
 */
class KeyOutputRouter(
    private val shizuku: ShizukuManager,
    private val accessibility: AccessibilityBridge,
    private val audio: AudioBackend,
) : KeyOutput {

    /** 输入通道上按住中的键的按下时刻。抬起要带同一个 downTime，应用的长按判定才对。 */
    private val downTimes = ConcurrentHashMap<Int, Long>()

    override fun route(combo: KeyCombo): KeyChannel? =
        KeyRouting.route(combo, Build.VERSION.SDK_INT, shizuku.injectReady(), inputConnectionReady())

    override fun canCarryModifiers(): Boolean = shizuku.injectReady() || inputConnectionReady()

    override fun unavailableReason(): String = buildString {
        append("Shizuku ").append(shizuku.injectUnavailableReason())
        append(" · ")
        append(
            when {
                Build.VERSION.SDK_INT < 33 -> "无障碍输入通道需要 Android 13"
                accessibility.boundService() == null -> "无障碍服务未连接"
                else -> "当前没有输入框获得焦点"
            },
        )
    }

    override suspend fun send(combo: KeyCombo, down: Boolean?): Result<Unit> {
        // 发送时按那一刻重新挑路：从按下到真正发出之间焦点可能换了、Shizuku 可能刚连上
        return when (route(combo)) {
            KeyChannel.INJECT -> when (down) {
                null -> shizuku.injectKey(combo.keyCode, combo.metaState())
                else -> shizuku.injectKeyState(combo.keyCode, combo.metaState(), down)
            }
            KeyChannel.GLOBAL_ACTION -> onPress(down) {
                val action = KeyRouting.globalActionFor(combo, Build.VERSION.SDK_INT)
                    ?: error("没有对应的全局动作")
                check(accessibility.performGlobalAction(action)) { "系统拒绝了全局动作 " + action }
            }
            KeyChannel.MEDIA -> onPress(down) { sendAudio(combo.keyCode) }
            KeyChannel.INPUT_CONNECTION ->
                if (Build.VERSION.SDK_INT >= 33) sendToInputConnection(combo, down)
                else Result.failure(IllegalStateException("无障碍输入通道需要 Android 13"))
            null -> Result.failure(IllegalStateException(unavailableReason()))
        }
    }

    /**
     * 全局动作与媒体键只有「一下」，没有按住的概念：在按下时做一次，抬起什么都不发。
     * 长按时的连发照样会走到这里，音量键因此能按住连调。
     */
    private inline fun onPress(down: Boolean?, block: () -> Unit): Result<Unit> =
        if (down == false) Result.success(Unit) else runCatching { block() }

    private fun sendAudio(keyCode: Int) {
        val result = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> audio.volumeUp()
            KeyEvent.KEYCODE_VOLUME_DOWN -> audio.volumeDown()
            KeyEvent.KEYCODE_VOLUME_MUTE -> audio.toggleMute()
            else -> audio.dispatchMediaKey(keyCode)
        }
        check(result.succeeded) { result.message }
    }

    private fun inputConnectionReady(): Boolean =
        Build.VERSION.SDK_INT >= 33 && inputConnection(accessibility.boundService()) != null

    @RequiresApi(33)
    private fun sendToInputConnection(combo: KeyCombo, down: Boolean?): Result<Unit> = runCatching {
        val connection = inputConnection(accessibility.boundService())
            ?: error("当前没有输入框获得焦点")
        val keyCode = combo.keyCode
        val meta = combo.metaState()
        when (down) {
            null -> {
                val now = SystemClock.uptimeMillis()
                connection.sendKeyEvent(event(now, KeyEvent.ACTION_DOWN, keyCode, meta))
                connection.sendKeyEvent(event(now, KeyEvent.ACTION_UP, keyCode, meta))
            }
            true -> {
                val downTime = downTimes[keyCode] ?: SystemClock.uptimeMillis().also { downTimes[keyCode] = it }
                connection.sendKeyEvent(event(downTime, KeyEvent.ACTION_DOWN, keyCode, meta))
            }
            false -> {
                val downTime = downTimes.remove(keyCode) ?: SystemClock.uptimeMillis()
                connection.sendKeyEvent(event(downTime, KeyEvent.ACTION_UP, keyCode, meta))
            }
        }
    }

    /**
     * 只在系统替这个服务绑了输入连接时才有（配置里的 flagInputMethodEditor，且有输入框获得焦点）。
     * 按键线程上会被调用：只是读两个字段，没有跨进程调用。
     */
    private fun inputConnection(service: AccessibilityService?) =
        if (Build.VERSION.SDK_INT >= 33) service?.inputMethod?.currentInputConnection else null

    /**
     * 标成虚拟键盘：输入通道的事件直接进前台应用的视图树，不经过 InputDispatcher，
     * 本来就不会再绕回无障碍服务；标记只是让它在应用那边也不冒充任何一把实体键盘。
     */
    private fun event(downTime: Long, action: Int, keyCode: Int, metaState: Int) = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        InputDevice.SOURCE_KEYBOARD,
    )
}
