package com.actionmental.core.key

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.view.KeyEvent

/**
 * 映射出来的键从哪条路发回系统。
 *
 * 只有 [INJECT] 是「向系统注入任意按键」，它要 Shizuku。其余三条都不要特权，
 * 各自只覆盖一部分目标键 —— 按目标键分流，而不是 Shizuku 一掉整个映射就失效。
 */
enum class KeyChannel(val label: String, val technical: String) {
    /** InputManager 注入。任何键、任何窗口、系统级组合键也认。 */
    INJECT("Shizuku 注入", "INJECT"),

    /** 无障碍全局动作：返回、主页、最近任务、截屏、锁屏、方向键…… */
    GLOBAL_ACTION("系统全局动作", "GLOBAL_ACTION"),

    /** AudioManager：媒体键与音量键。 */
    MEDIA("媒体 / 音量", "MEDIA"),

    /**
     * Android 13+ 无障碍服务自带的输入通道（`AccessibilityService.getInputMethod()`）。
     * 和用户自己的输入法并存；只有输入框（或自己实现了输入连接的视图，如终端）获得焦点时才有。
     */
    INPUT_CONNECTION("无障碍输入通道", "INPUT_CONNECTION"),
}

/**
 * 映射的输出口。
 *
 * [route] 在按键线程上同步回答「这颗键此刻发得出去吗」—— 发不出去就绝不拦下源键，
 * 宁可映射不生效也不吞键（PRD 25）。真正的发送在 [send] 里异步做，届时按那一刻重新挑路。
 */
interface KeyOutput {

    /** 这颗目标键此刻走哪条路；null 表示哪条都走不通。必须非阻塞。 */
    fun route(combo: KeyCombo): KeyChannel?

    /**
     * 按住一颗被映射成修饰键的源键时能不能拦下它。
     *
     * 修饰键映射自己不发任何东西，作用体现在按住期间的**别的**键上 ——
     * 那些键要带着改写后的修饰位发出去，所以得有一条能发任意键的路。
     */
    fun canCarryModifiers(): Boolean

    /** 哪条路都走不通时给用户看的原因。 */
    fun unavailableReason(): String

    /** @param down null = 按下 + 抬起成对；true / false = 只发半边 */
    suspend fun send(combo: KeyCombo, down: Boolean?): Result<Unit>
}

/** 分流规则。纯函数，不碰任何系统对象，单测直接覆盖。 */
object KeyRouting {

    /**
     * 目标键对应的无障碍全局动作。带修饰键的一律不算 —— 「Ctrl + 返回」没有全局动作可对应，
     * 硬按「返回」发出去就改了用户的意思。
     *
     * 13 以后才有的几个常量是编译期内联的整数，只在 [sdk] 够的时候才会交给系统。
     */
    @SuppressLint("InlinedApi")
    fun globalActionFor(combo: KeyCombo, sdk: Int): Int? {
        if (combo.modifiers != 0) return null
        return when (combo.keyCode) {
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            KeyEvent.KEYCODE_NOTIFICATION -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            KeyEvent.KEYCODE_SYSRQ -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            KeyEvent.KEYCODE_HEADSETHOOK ->
                if (sdk >= 31) AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK else null
            // 方向键的全局动作是系统替我们注入一颗真的方向键，列表、网格里也有效，
            // 比输入通道（只在输入框里有）覆盖得更广，所以排在它前面
            KeyEvent.KEYCODE_DPAD_UP -> if (sdk >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_UP else null
            KeyEvent.KEYCODE_DPAD_DOWN -> if (sdk >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else null
            KeyEvent.KEYCODE_DPAD_LEFT -> if (sdk >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_LEFT else null
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (sdk >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT else null
            KeyEvent.KEYCODE_DPAD_CENTER -> if (sdk >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_CENTER else null
            else -> null
        }
    }

    /** AudioManager 能直接处理的键。修饰键对它们没有意义，忽略即可。 */
    fun isAudioKey(keyCode: Int): Boolean = keyCode in AUDIO_KEYS

    /**
     * 挑路。
     *
     * 能注入就注入：那是唯一保真的一条，系统级组合键、非输入框的窗口都认。
     * 注入不通时，系统键与媒体键各有专门的出口；剩下的普通键只能交给输入通道。
     */
    fun route(combo: KeyCombo, sdk: Int, injectReady: Boolean, inputConnectionReady: Boolean): KeyChannel? = when {
        injectReady -> KeyChannel.INJECT
        globalActionFor(combo, sdk) != null -> KeyChannel.GLOBAL_ACTION
        isAudioKey(combo.keyCode) -> KeyChannel.MEDIA
        sdk >= 33 && inputConnectionReady -> KeyChannel.INPUT_CONNECTION
        else -> null
    }

    /**
     * 没有 Shizuku 时这颗目标键最好能走到哪条路。映射页据此提前告诉用户：
     * 这一条降级后是「照常」、「只在输入框里」还是「失效」。
     */
    fun fallbackChannel(target: KeyCombo, sdk: Int): KeyChannel? =
        route(target, sdk, injectReady = false, inputConnectionReady = true)

    private val AUDIO_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        // Android 12 以前没有 HEADSETHOOK 的全局动作，退回当媒体键发
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
    )
}
