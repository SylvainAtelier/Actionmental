package com.actionmental.core.clip

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo

/** 面板认得的按键，由平台层从 KeyEvent 翻译过来。 */
sealed interface PanelKey {
    data object Up : PanelKey
    data object Down : PanelKey

    /** ← / →：在「置顶」与「最近」两段之间来回，回到上次在那一段停的那一条。 */
    data object Left : PanelKey
    data object Right : PanelKey
    data object PageUp : PanelKey
    data object PageDown : PanelKey
    data object Enter : PanelKey
    data object Escape : PanelKey
    data object Backspace : PanelKey
    data object ClearQuery : PanelKey
    data object Delete : PanelKey
    data object TogglePin : PanelKey

    /** Ctrl+C：把光标所在那一条放回系统剪贴板，不上屏。 */
    data object Copy : PanelKey

    /** Ctrl+E：打开光标所在那一条的编辑页。 */
    data object Edit : PanelKey

    /** 直选键（默认 Ctrl + 1…9，见 [PickKeys]）：直接选用屏幕上第几条，不必先挪光标。 */
    data class Pick(val index: Int) : PanelKey

    /** 普通字符：追加到搜索词。 */
    data class Type(val char: Char) : PanelKey
}

/**
 * 面板里直选第几条用哪组键。三组只选其一：角标、提示与实际生效的键永远是同一套，不用记「哪个也行」。
 *
 * - Ctrl + 数字：和 Raycast、Alfred 的剪贴板一个手感，默认；
 * - Alt + 数字：Ctrl 被映射占了、或者键盘上 Ctrl 离数字太远时换它；
 * - F1…F9：单键直达，不用组合。代价是部分笔记本 / 平板键盘的 F 行默认是媒体键，要按住 Fn。
 *
 * 枚举名会落盘，改名就是改存档格式。
 */
enum class PickKeys(
    /** 提示文案里的写法，替换文案模板中的 [TEMPLATE]。 */
    val hint: String,
) {
    CTRL_DIGITS("Ctrl+1…9"),
    ALT_DIGITS("Alt+1…9"),
    FUNCTION_KEYS("F1…F9"),
    ;

    /** 第 [number] 条（1 起）的角标上写什么。 */
    fun badge(number: Int): String = if (this == FUNCTION_KEYS) "F$number" else number.toString()

    /** 按第 [number] 条要按的键，从修饰键到主键。设置页画键帽用。 */
    fun tokens(number: Int): List<String> = when (this) {
        CTRL_DIGITS -> listOf("Ctrl", number.toString())
        ALT_DIGITS -> listOf("Alt", number.toString())
        FUNCTION_KEYS -> listOf("F$number")
    }

    /** 这颗键按下时选第几条（0 起）；不是直选键就是 null。 */
    fun indexOf(keyCode: Int, ctrl: Boolean, alt: Boolean): Int? = when (this) {
        CTRL_DIGITS -> if (ctrl) digitIndex(keyCode) else null
        ALT_DIGITS -> if (alt && !ctrl) digitIndex(keyCode) else null
        // 带着 Ctrl / Alt 的 F 键留给别的组合，免得一手按住修饰键时误选
        FUNCTION_KEYS -> if (!ctrl && !alt && keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F9) {
            keyCode - KeyEvent.KEYCODE_F1
        } else {
            null
        }
    }

    companion object {
        /** 文案模板里代表直选键的占位：翻译词表按默认写法收录，翻译完再换成当前这一组。 */
        const val TEMPLATE = "Ctrl+1…9"

        private fun digitIndex(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1
            else -> null
        }
    }
}

/**
 * 面板的键位。Atuin 用户的肌肉记忆优先：方向键 / Tab 移动、直接打字就是搜索、Enter 选用、Esc 退出。
 *
 * 返回 null 表示这颗键面板不认 —— 平台层照样吞掉它，面板开着时按键不该漏进底下的应用。
 *
 * 直选键（[PickKeys]）只在面板开着时是「直选」：面板经管线的模态接管拿按键，排在快捷键匹配之前，
 * 所以同名的全局快捷键这时被临时盖住；面板一关，接管撤掉，全局绑定原样回来，不需要额外的登记与归还。
 *
 * 光按数字不做直选：数字要留给搜索 —— 验证码、手机号、单号正是剪贴板里最常翻找的东西。
 * 「搜索词为空时数字直选」也不要：同一颗键随状态换意思，想搜「123」却当场把第一条上了屏，撤不回来。
 */
object PanelKeys {
    fun map(keyCode: Int, modifiers: Int, typed: Char?, pick: PickKeys = PickKeys.CTRL_DIGITS): PanelKey? {
        val ctrl = modifiers and KeyCombo.MOD_CTRL != 0
        val alt = modifiers and KeyCombo.MOD_ALT != 0
        val shift = modifiers and KeyCombo.MOD_SHIFT != 0
        pick.indexOf(keyCode, ctrl, alt)?.let { return PanelKey.Pick(it) }
        return when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> PanelKey.Up
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> PanelKey.Down
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> PanelKey.Left
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> PanelKey.Right
            keyCode == KeyEvent.KEYCODE_TAB -> if (shift) PanelKey.Up else PanelKey.Down
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> PanelKey.PageUp
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> PanelKey.PageDown
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> PanelKey.Enter
            keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK -> PanelKey.Escape
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> PanelKey.Delete
            keyCode == KeyEvent.KEYCODE_DEL -> if (ctrl) PanelKey.ClearQuery else PanelKey.Backspace
            ctrl && keyCode == KeyEvent.KEYCODE_P -> PanelKey.TogglePin
            ctrl && keyCode == KeyEvent.KEYCODE_C -> PanelKey.Copy
            ctrl && keyCode == KeyEvent.KEYCODE_D -> PanelKey.Delete
            ctrl && keyCode == KeyEvent.KEYCODE_E -> PanelKey.Edit
            ctrl && keyCode == KeyEvent.KEYCODE_U -> PanelKey.ClearQuery
            ctrl || alt -> null
            typed != null -> PanelKey.Type(typed)
            else -> null
        }
    }
}

/** 一次按键之后平台层要做的事。 */
sealed interface PanelEffect {
    /** 只是光标动了，重画即可。 */
    data object Render : PanelEffect
    data object Close : PanelEffect
    data class Requery(val query: String) : PanelEffect
    data class Insert(val entry: ClipEntry) : PanelEffect
    data class TogglePin(val entry: ClipEntry) : PanelEffect
    data class Delete(val entry: ClipEntry) : PanelEffect
    /** 只放回系统剪贴板，不上屏。Ctrl+C 与长按菜单发出。 */
    data class Copy(val entry: ClipEntry) : PanelEffect
    /** 打开编辑页改这一条的正文。Ctrl+E 与长按菜单发出。 */
    data class Edit(val entry: ClipEntry) : PanelEffect
    data object None : PanelEffect
}

/**
 * 剪贴板面板的状态机。纯数据，按键线程上直接跑，单元测试不需要设备。
 *
 * 结果列表由平台层异步查回来再塞进 [withEntries]：查询期间的按键照样能动光标，
 * 光标在新结果到达时夹回合法范围。
 *
 * 列表分两段：开头 [pinned] 条是置顶的，其余是最近的。←→ 在两段之间跳；
 * 两段并排成两栏时（[columns]）↑↓ 与翻页不越过栏界 —— 往下按到左栏底，光标不该忽然跑到右栏顶上。
 */
data class ClipPanelState(
    val query: String = "",
    val entries: List<ClipEntry> = emptyList(),
    val selected: Int = 0,
    val columns: Boolean = false,
    /** 另一段上次停在哪一条（按 id 记，重查之后照样认得）。 */
    val awayId: Long? = null,
) {
    val current: ClipEntry? get() = entries.getOrNull(selected)

    /** 开头有几条是置顶的。 */
    val pinned: Int = entries.indexOfFirst { !it.pinned }.let { if (it < 0) entries.size else it }

    /**
     * 换上新结果。[follow] 为 true 时光标跟着原来那一条走（置顶之后它挪到了另一段、
     * 展开收起之后位置变了），找不到了才按位置夹回范围；换了搜索词时不跟，回到第一条。
     */
    fun withEntries(list: List<ClipEntry>, follow: Boolean = false): ClipPanelState {
        val id = if (follow) current?.id else null
        val kept = if (id == null) -1 else list.indexOfFirst { it.id == id }
        return copy(entries = list, selected = if (kept >= 0) kept else selected.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
    }

    fun reduce(key: PanelKey): Pair<ClipPanelState, PanelEffect> = when (key) {
        PanelKey.Up -> move(-1)
        PanelKey.Down -> move(1)
        PanelKey.Left -> switchTo(0 until pinned)
        PanelKey.Right -> switchTo(pinned until entries.size)
        PanelKey.PageUp -> move(-PAGE)
        PanelKey.PageDown -> move(PAGE)
        PanelKey.Escape -> this to PanelEffect.Close
        PanelKey.Enter -> this to (current?.let { PanelEffect.Insert(it) } ?: PanelEffect.None)
        PanelKey.Delete -> this to (current?.let { PanelEffect.Delete(it) } ?: PanelEffect.None)
        PanelKey.TogglePin -> this to (current?.let { PanelEffect.TogglePin(it) } ?: PanelEffect.None)
        PanelKey.Copy -> this to (current?.let { PanelEffect.Copy(it) } ?: PanelEffect.None)
        PanelKey.Edit -> this to (current?.let { PanelEffect.Edit(it) } ?: PanelEffect.None)
        is PanelKey.Pick -> {
            val entry = entries.getOrNull(key.index)
            if (entry == null) this to PanelEffect.None
            else copy(selected = key.index) to PanelEffect.Insert(entry)
        }
        PanelKey.Backspace ->
            if (query.isEmpty()) this to PanelEffect.None
            else requery(query.dropLast(if (query.length >= 2 && Character.isLowSurrogate(query.last())) 2 else 1))
        PanelKey.ClearQuery -> if (query.isEmpty()) this to PanelEffect.None else requery("")
        is PanelKey.Type -> requery(query + key.char)
    }

    private fun move(delta: Int): Pair<ClipPanelState, PanelEffect> {
        if (entries.isEmpty()) return this to PanelEffect.None
        val range = if (columns) sectionOf(selected) else entries.indices
        val next = (selected + delta).coerceIn(range.first, range.last)
        return if (next == selected) this to PanelEffect.None else copy(selected = next) to PanelEffect.Render
    }

    private fun sectionOf(index: Int): IntRange = if (index < pinned) 0 until pinned else pinned until entries.size

    /** 跳到另一段：回到上次在那里停的那一条，没有就是那一段的第一条。 */
    private fun switchTo(range: IntRange): Pair<ClipPanelState, PanelEffect> {
        if (range.isEmpty() || selected in range) return this to PanelEffect.None
        val back = entries.indexOfFirst { it.id == awayId }
        val next = if (back in range) back else range.first
        return copy(selected = next, awayId = current?.id) to PanelEffect.Render
    }

    /** 搜索词一变，光标回到第一条：新结果的第一条才是最相关的。 */
    private fun requery(next: String): Pair<ClipPanelState, PanelEffect> =
        copy(query = next, selected = 0) to PanelEffect.Requery(next)

    companion object {
        /** PageUp / PageDown 一次跳几条。 */
        const val PAGE = 8

        /** 懒加载：一页查多少条。滚到离末尾不足 [PREFETCH] 条时再要下一页。 */
        const val PAGE_SIZE = 60
        const val PREFETCH = 15
    }
}
