package com.actionmental.core.key

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/**
 * 标准化的组合键。
 *
 * 这是全应用唯一的快捷键身份：录制、匹配、冲突检测、展示都使用它。
 * 修饰键存为位掩码而不是有序列表，因此「先 Ctrl 后 Alt」与「先 Alt 后 Ctrl」
 * 天然是同一个值（PRD 4.4）。
 */
@Serializable
data class KeyCombo(
    val keyCode: Int,
    val modifiers: Int = 0,
) {
    val ctrl: Boolean get() = modifiers and MOD_CTRL != 0
    val alt: Boolean get() = modifiers and MOD_ALT != 0
    val shift: Boolean get() = modifiers and MOD_SHIFT != 0
    val meta: Boolean get() = modifiers and MOD_META != 0

    /** 主键本身就是一颗修饰键（轻点绑定）。 */
    val isModifierKey: Boolean get() = isModifier(keyCode)

    /** 展示用的分段列表，修饰键顺序恒定为 Ctrl → Alt → Shift → Meta → 主键。 */
    fun tokens(): List<String> = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(keyLabel(keyCode))
    }

    /** 掩码还原成 Android 的 metaState —— 注入按键时要把修饰键一起带上。 */
    fun metaState(): Int {
        var state = 0
        if (ctrl) state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) state = state or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return state
    }

    override fun toString(): String = tokens().joinToString(" + ")

    companion object {
        const val MOD_CTRL = 1 shl 0
        const val MOD_ALT = 1 shl 1
        const val MOD_SHIFT = 1 shl 2
        const val MOD_META = 1 shl 3

        /**
         * 真正参与掩码的修饰键。
         *
         * 它们按下的那一刻还不知道用户要按什么，所以不能立刻当主键用 ——
         * 绑定这几颗键走的是「轻点」语义（见 KeyPipeline）。
         */
        val MODIFIER_KEYCODES = intArrayOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        )

        /**
         * 锁定键与 Fn。
         *
         * 它们不进修饰键掩码（Ctrl + Caps 这种组合现实里没人用），
         * 因此可以当成普通主键：按下即触发、事件被拦截，
         * 绑定了 Caps Lock 之后系统也就不会再切换大小写了。
         */
        val LOCK_KEYCODES = intArrayOf(
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION,
        )

        fun isModifier(keyCode: Int): Boolean = MODIFIER_KEYCODES.any { it == keyCode }

        fun isLock(keyCode: Int): Boolean = LOCK_KEYCODES.any { it == keyCode }

        /**
         * 同一族的另一颗修饰键（左 ←→ 右）。
         *
         * 改写修饰位时要用：只映射了左 Shift 的话，右 Shift 还按着就不能把 SHIFT 位摘掉。
         */
        fun siblingModifier(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.KEYCODE_CTRL_RIGHT
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.KEYCODE_ALT_RIGHT
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.KEYCODE_SHIFT_RIGHT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
            KeyEvent.KEYCODE_META_LEFT -> KeyEvent.KEYCODE_META_RIGHT
            KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
            else -> null
        }

        /** 这颗修饰键自己在 metaState 里点亮的那一位。用来把它从「其余修饰键」里剔除。 */
        fun selfModifierMask(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> MOD_CTRL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> MOD_ALT
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> MOD_SHIFT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> MOD_META
            else -> 0
        }

        /** 由 Android metaState 解析出标准修饰键掩码。 */
        fun modifiersOf(metaState: Int): Int {
            var m = 0
            if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or MOD_CTRL
            if (metaState and KeyEvent.META_ALT_ON != 0) m = m or MOD_ALT
            if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or MOD_SHIFT
            if (metaState and KeyEvent.META_META_ON != 0) m = m or MOD_META
            return m
        }

        fun keyLabel(keyCode: Int): String {
            val raw = KeyEvent.keyCodeToString(keyCode)          // KEYCODE_L
            val name = raw.removePrefix("KEYCODE_")
            return FRIENDLY[name] ?: when {
                name.length == 1 -> name
                else -> name.split('_').joinToString(" ") { part ->
                    part.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
        }

        private val FRIENDLY = mapOf(
            "DPAD_LEFT" to "←", "DPAD_RIGHT" to "→", "DPAD_UP" to "↑", "DPAD_DOWN" to "↓",
            "SPACE" to "Space", "ENTER" to "Enter", "TAB" to "Tab", "ESCAPE" to "Esc",
            "DEL" to "Backspace", "FORWARD_DEL" to "Delete", "GRAVE" to "`",
            "MOVE_HOME" to "Home", "MOVE_END" to "End", "PAGE_UP" to "PgUp", "PAGE_DOWN" to "PgDn",
            "MEDIA_PLAY_PAUSE" to "Play/Pause", "MEDIA_NEXT" to "Next", "MEDIA_PREVIOUS" to "Prev",
            "SLASH" to "/", "BACKSLASH" to "\\", "COMMA" to ",", "PERIOD" to ".",
            "SEMICOLON" to ";", "APOSTROPHE" to "'", "MINUS" to "-", "EQUALS" to "=",
            "LEFT_BRACKET" to "[", "RIGHT_BRACKET" to "]",
            "CTRL_LEFT" to "Ctrl L", "CTRL_RIGHT" to "Ctrl R",
            "ALT_LEFT" to "Alt L", "ALT_RIGHT" to "Alt R",
            "SHIFT_LEFT" to "Shift L", "SHIFT_RIGHT" to "Shift R",
            "META_LEFT" to "Meta L", "META_RIGHT" to "Meta R",
            "CAPS_LOCK" to "Caps", "NUM_LOCK" to "Num", "SCROLL_LOCK" to "Scroll",
            "FUNCTION" to "Fn",
        )
    }
}
