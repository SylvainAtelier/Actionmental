package com.actionmental.core.key

import android.view.KeyEvent

/**
 * 把一个组合键拆成实体键盘上真实会出现的那一串事件：修饰键逐个按下，主键按下、抬起，修饰键倒序抬起。
 *
 * 键位映射只发一颗带 metaState 的主键就够了，应用看 metaState 认组合。
 * 输入法不一样：Gboard 的 Alt + D 这类快捷键要先看到 Alt 本身按下，
 * 只给一颗「带着 Alt 位的 D」它不认。所以发给输入法的组合键必须整串发。
 */
object KeyChord {

    data class Step(val keyCode: Int, val down: Boolean, val metaState: Int)

    /** 发修饰键时用左边那一颗：实体键盘上最常见，输入法的快捷键表也按它写。 */
    private val modifierKeys = listOf(
        KeyCombo.MOD_CTRL to KeyEvent.KEYCODE_CTRL_LEFT,
        KeyCombo.MOD_ALT to KeyEvent.KEYCODE_ALT_LEFT,
        KeyCombo.MOD_SHIFT to KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyCombo.MOD_META to KeyEvent.KEYCODE_META_LEFT,
    )

    fun steps(combo: KeyCombo): List<Step> {
        // 主键自己就是修饰键时（Ctrl + 右 Alt），它那一族不再另按一颗左键
        val own = KeyCombo.selfModifierMask(combo.keyCode)
        val held = modifierKeys.filter { (mask, _) -> combo.modifiers and mask != 0 && mask != own }
            .map { it.second }

        val pressed = ArrayList<Int>(held.size + 1)
        fun meta(): Int = pressed.fold(0) { acc, key -> acc or KeyCombo.selfMetaState(key) }

        return buildList {
            for (key in held) {
                pressed += key
                add(Step(key, true, meta()))
            }
            pressed += combo.keyCode
            add(Step(combo.keyCode, true, meta()))
            // 抬起时自己那一位先熄灭，和实体键盘一致
            pressed.removeAt(pressed.lastIndex)
            add(Step(combo.keyCode, false, meta()))
            for (key in held.asReversed()) {
                pressed.remove(key)
                add(Step(key, false, meta()))
            }
        }
    }
}
