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

    /**
     * 符号目录里的一项：打出来的字符、中文名，以及在美式布局（Generic.kcm）上按出它的组合键。
     *
     * 上档符号（! @ # …）本身没有独立的键，是 Shift + 数字 / 标点；目录里直接列成组合，
     * 选「?」就得到 Shift + /，不用先选 / 再去勾 Shift。换了别的键盘布局，字符会跟着布局变。
     */
    data class SymbolKey(val char: String, val name: String, val combo: KeyCombo) {
        val shifted: Boolean get() = combo.modifiers and KeyCombo.MOD_SHIFT != 0
    }

    private fun plain(char: String, name: String, keyCode: Int) = SymbolKey(char, name, KeyCombo(keyCode))
    private fun shift(char: String, name: String, keyCode: Int) =
        SymbolKey(char, name, KeyCombo(keyCode, KeyCombo.MOD_SHIFT))

    /** 主键区的全部符号：先是不按 Shift 的，再是独立的符号键，最后是上档符号。 */
    val symbolKeys: List<SymbolKey> = listOf(
        plain("`", "反引号", KeyEvent.KEYCODE_GRAVE),
        plain("-", "减号", KeyEvent.KEYCODE_MINUS),
        plain("=", "等号", KeyEvent.KEYCODE_EQUALS),
        plain("[", "左方括号", KeyEvent.KEYCODE_LEFT_BRACKET),
        plain("]", "右方括号", KeyEvent.KEYCODE_RIGHT_BRACKET),
        plain("\\", "反斜杠", KeyEvent.KEYCODE_BACKSLASH),
        plain(";", "分号", KeyEvent.KEYCODE_SEMICOLON),
        plain("'", "单引号", KeyEvent.KEYCODE_APOSTROPHE),
        plain(",", "逗号", KeyEvent.KEYCODE_COMMA),
        plain(".", "句点", KeyEvent.KEYCODE_PERIOD),
        plain("/", "斜杠", KeyEvent.KEYCODE_SLASH),
        // 独立的符号键：部分键盘、手机按键与遥控器上才有
        plain("@", "At 键", KeyEvent.KEYCODE_AT),
        plain("#", "井号键", KeyEvent.KEYCODE_POUND),
        plain("*", "星号键", KeyEvent.KEYCODE_STAR),
        plain("+", "加号键", KeyEvent.KEYCODE_PLUS),
        plain("¥", "日元键（日文键盘）", KeyEvent.KEYCODE_YEN),
        plain("ろ", "Ro 键（日文键盘）", KeyEvent.KEYCODE_RO),
        // 上档符号
        shift("~", "波浪号", KeyEvent.KEYCODE_GRAVE),
        shift("!", "感叹号", KeyEvent.KEYCODE_1),
        shift("@", "At 符号", KeyEvent.KEYCODE_2),
        shift("#", "井号", KeyEvent.KEYCODE_3),
        shift("\$", "美元符号", KeyEvent.KEYCODE_4),
        shift("%", "百分号", KeyEvent.KEYCODE_5),
        shift("^", "脱字符", KeyEvent.KEYCODE_6),
        shift("&", "和号", KeyEvent.KEYCODE_7),
        shift("*", "星号", KeyEvent.KEYCODE_8),
        shift("(", "左圆括号", KeyEvent.KEYCODE_9),
        shift(")", "右圆括号", KeyEvent.KEYCODE_0),
        shift("_", "下划线", KeyEvent.KEYCODE_MINUS),
        shift("+", "加号", KeyEvent.KEYCODE_EQUALS),
        shift("{", "左花括号", KeyEvent.KEYCODE_LEFT_BRACKET),
        shift("}", "右花括号", KeyEvent.KEYCODE_RIGHT_BRACKET),
        shift("|", "竖线", KeyEvent.KEYCODE_BACKSLASH),
        shift(":", "冒号", KeyEvent.KEYCODE_SEMICOLON),
        shift("\"", "双引号", KeyEvent.KEYCODE_APOSTROPHE),
        shift("<", "小于号", KeyEvent.KEYCODE_COMMA),
        shift(">", "大于号", KeyEvent.KEYCODE_PERIOD),
        shift("?", "问号", KeyEvent.KEYCODE_SLASH),
    )

    /** 这个组合在符号目录里对应哪一项；修饰键里多出的 Ctrl / Alt / Meta 不影响认字符。 */
    fun symbolOf(combo: KeyCombo?): SymbolKey? {
        if (combo == null) return null
        val shiftOnly = combo.modifiers and KeyCombo.MOD_SHIFT
        return symbolKeys.firstOrNull { it.combo.keyCode == combo.keyCode && it.combo.modifiers == shiftOnly }
    }

    /**
     * 从目录里选了一个符号：Shift 由符号决定（选「?」带上、选「/」摘掉），
     * 别的修饰键沿用当前草稿 —— 选完「?」再勾 Ctrl 就是 Ctrl + Shift + /。
     */
    fun applySymbol(current: KeyCombo?, symbol: SymbolKey): KeyCombo {
        val kept = (current?.modifiers ?: 0) and KeyCombo.MOD_SHIFT.inv()
        val keyCode = symbol.combo.keyCode
        return KeyCombo(keyCode, (kept or symbol.combo.modifiers) and KeyCombo.selfModifierMask(keyCode).inv())
    }

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
        Group("symbol", "符号", "SYMBOLS", symbolKeys.filter { it.combo.modifiers == 0 }.map { it.combo.keyCode }),
        Group(
            "numpad", "小键盘", "NUMPAD",
            range(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_9) + listOf(
                KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_NUMPAD_ADD,
                KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_NUMPAD_EQUALS, KeyEvent.KEYCODE_NUMPAD_COMMA,
                KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN, KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
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

    /** 打开目录时先展开哪一组：Shift + 1 这类上档符号在「符号」里，而不是在「数字」里。 */
    fun openGroupFor(combo: KeyCombo?): String? = when {
        combo == null -> null
        symbolOf(combo)?.shifted == true -> "symbol"
        else -> groupOf(combo.keyCode)?.id
    }

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
