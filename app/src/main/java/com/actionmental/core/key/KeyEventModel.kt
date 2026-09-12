package com.actionmental.core.key

import android.view.KeyEvent

/** 归一化之后的按键事件。管线之外不允许再出现 android.view.KeyEvent。 */
data class NormalizedKeyEvent(
    val keyCode: Int,
    val scanCode: Int,
    val metaState: Int,
    val down: Boolean,
    val repeatCount: Int,
    val eventTimeMs: Long,
    val device: KeyboardDevice,
    /** 来自虚拟键盘。我们自己注入的按键就长这样，必须原样放行，否则会自己触发自己。 */
    val virtual: Boolean = false,
) {
    val isModifier: Boolean get() = KeyCombo.isModifier(keyCode)
    val modifiers: Int get() = KeyCombo.modifiersOf(metaState)
    val keyLabel: String get() = KeyCombo.keyLabel(keyCode)
    val rawKeyName: String get() = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    /** 除去这颗键自己点亮的那一位之后，还压着的修饰键。 */
    val otherModifiers: Int get() = modifiers and KeyCombo.selfModifierMask(keyCode).inv()

    /** 非修饰键（含 Caps / Num / Fn）的首次按下 —— 按下即触发，事件被拦截。 */
    fun asTrigger(): KeyCombo? =
        if (down && !isModifier && repeatCount == 0) KeyCombo(keyCode, modifiers) else null

    /**
     * 修饰键在已经压着别的修饰键时按下，它本身就是主键（例如 Ctrl + 右 Alt）。
     * 这种组合不含歧义，所以照常在按下时触发并拦截。
     */
    fun asModifierComboTrigger(): KeyCombo? {
        if (!down || repeatCount != 0 || !isModifier) return null
        return otherModifiers.takeIf { it != 0 }?.let { KeyCombo(keyCode, it) }
    }

    /** 轻点修饰键：抬起那一刻才成立，由 KeyPipeline 判断中途有没有按过别的键。 */
    fun asTapTrigger(): KeyCombo? =
        if (!down && isModifier) KeyCombo(keyCode, otherModifiers) else null
}

/** 当前按下状态的快照，供实时检测页显示。 */
data class KeyPressSnapshot(
    val pressedKeyCodes: Set<Int> = emptySet(),
    val modifiers: Int = 0,
    val last: NormalizedKeyEvent? = null,
) {
    val activeCombo: KeyCombo?
        get() {
            val main = pressedKeyCodes.firstOrNull { !KeyCombo.isModifier(it) }
            if (main != null) return KeyCombo(main, modifiers)
            // 只压着一颗修饰键：那它自己就是主键（轻点绑定），检测页据此也能直接保存
            val lone = pressedKeyCodes.singleOrNull() ?: return null
            return KeyCombo(lone, modifiers and KeyCombo.selfModifierMask(lone).inv())
        }

    /** 「Ctrl」「Ctrl + Alt」「Ctrl + Alt + L」三种中间态都要能显示。 */
    fun displayTokens(): List<String> = buildList {
        if (modifiers and KeyCombo.MOD_CTRL != 0) add("Ctrl")
        if (modifiers and KeyCombo.MOD_ALT != 0) add("Alt")
        if (modifiers and KeyCombo.MOD_SHIFT != 0) add("Shift")
        if (modifiers and KeyCombo.MOD_META != 0) add("Meta")
        pressedKeyCodes.firstOrNull { !KeyCombo.isModifier(it) }
            ?.let { add(KeyCombo.keyLabel(it)) }
    }
}

/** 事件流条目，仅内存保留，用于按键检测页（PRD 26：不落盘、不记录文本）。 */
data class KeyTrace(
    val id: Long,
    val timestampMs: Long,
    val kind: Kind,
    val text: String,
    val detail: String = "",
) {
    enum class Kind { DOWN, UP, MATCH, UNBOUND, VERIFIED, ERROR }
}
