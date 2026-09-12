package com.actionmental.core.key

import android.view.KeyEvent

/**
 * 可手动指定的主键清单。
 *
 * 录制解决「这把键盘上的这颗键是什么」，目录解决「我知道要哪颗键，但不想去按它」——
 * 手上这把键盘没有的键（没有小键盘、没有 Scroll Lock）、
 * 被系统抢走录不到的键，以及 Ctrl / Alt / Shift / Meta / Caps 这类
 * 录制时只会被当成修饰键的键，都只能从这里选。
 *
 * 分组即二级菜单的一级项；顺序按使用频率排，字母永远在最前面。
 */
object KeyCatalog {

    data class Group(val id: String, val label: String, val technical: String, val keyCodes: List<Int>)

    private fun range(from: Int, to: Int): List<Int> = (from..to).toList()

    val groups: List<Group> = listOf(
        Group("letter", "字母", "A – Z", range(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z)),
        Group("digit", "数字", "0 – 9", range(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_9)),
        Group("function", "功能键", "F1 – F12", range(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12)),
        Group(
            "modifier", "修饰与锁定键", "MODIFIERS & LOCKS",
            KeyCombo.MODIFIER_KEYCODES.toList() + KeyCombo.LOCK_KEYCODES.toList(),
        ),
        Group(
            "navigation", "方向与编辑", "NAVIGATION",
            listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_INSERT,
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            ),
        ),
        Group(
            "symbol", "符号", "SYMBOLS",
            listOf(
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
                KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_GRAVE,
                KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
            ),
        ),
        Group(
            "numpad", "小键盘", "NUMPAD",
            range(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_9) + listOf(
                KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_NUMPAD_ADD,
                KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_NUMPAD_ENTER,
            ),
        ),
        Group(
            "media", "媒体与设备", "MEDIA & DEVICE",
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BRIGHTNESS_UP,
                KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_SYSRQ,
            ),
        ),
    )

    val allKeyCodes: List<Int> = groups.flatMap { it.keyCodes }

    fun groupOf(keyCode: Int): Group? = groups.firstOrNull { keyCode in it.keyCodes }

    /**
     * 这颗键绑定后怎么触发。
     *
     * 修饰键必须让位给组合键，所以只能轻点；其余的键（包括 Caps Lock、Num Lock、Fn）
     * 按下即触发，事件也就不再传给前台应用。
     */
    fun triggerMode(keyCode: Int): TriggerMode =
        if (KeyCombo.isModifier(keyCode)) TriggerMode.TAP else TriggerMode.PRESS

    enum class TriggerMode(val label: String, val hint: String) {
        PRESS("按下即触发", "按下时触发，事件不再传给前台应用"),
        TAP("轻点触发", "抬起时触发；按住不放或与别的键同按都不会触发，按键本身照常传给应用"),
    }
}
