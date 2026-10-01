package com.actionmental.core.clip

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo

/** 面板认得的按键，由平台层从 KeyEvent 翻译过来。 */
sealed interface PanelKey {
    data object Up : PanelKey
    data object Down : PanelKey
    data object PageUp : PanelKey
    data object PageDown : PanelKey
    data object Enter : PanelKey
    data object Escape : PanelKey
    data object Backspace : PanelKey
    data object ClearQuery : PanelKey
    data object Delete : PanelKey
    data object TogglePin : PanelKey

    /** Ctrl + 1…9：直接选用屏幕上第几条，不必先挪光标。 */
    data class Pick(val index: Int) : PanelKey

    /** 普通字符：追加到搜索词。 */
    data class Type(val char: Char) : PanelKey
}

/**
 * 面板的键位。Atuin 用户的肌肉记忆优先：方向键 / Tab 移动、直接打字就是搜索、Enter 选用、Esc 退出。
 *
 * 返回 null 表示这颗键面板不认 —— 平台层照样吞掉它，面板开着时按键不该漏进底下的应用。
 *
 * Ctrl + 数字只在面板开着时是「直选」：面板经管线的模态接管拿按键，排在快捷键匹配之前，
 * 所以同名的全局快捷键这时被临时盖住；面板一关，接管撤掉，全局绑定原样回来，不需要额外的登记与归还。
 */
object PanelKeys {
    fun map(keyCode: Int, modifiers: Int, typed: Char?): PanelKey? {
        val ctrl = modifiers and KeyCombo.MOD_CTRL != 0
        val alt = modifiers and KeyCombo.MOD_ALT != 0
        val shift = modifiers and KeyCombo.MOD_SHIFT != 0
        return when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> PanelKey.Up
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> PanelKey.Down
            keyCode == KeyEvent.KEYCODE_TAB -> if (shift) PanelKey.Up else PanelKey.Down
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> PanelKey.PageUp
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> PanelKey.PageDown
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> PanelKey.Enter
            keyCode == KeyEvent.KEYCODE_ESCAPE -> PanelKey.Escape
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> PanelKey.Delete
            keyCode == KeyEvent.KEYCODE_DEL -> if (ctrl) PanelKey.ClearQuery else PanelKey.Backspace
            ctrl && keyCode == KeyEvent.KEYCODE_P -> PanelKey.TogglePin
            ctrl && keyCode == KeyEvent.KEYCODE_D -> PanelKey.Delete
            ctrl && keyCode == KeyEvent.KEYCODE_U -> PanelKey.ClearQuery
            ctrl && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> PanelKey.Pick(keyCode - KeyEvent.KEYCODE_1)
            ctrl && keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 ->
                PanelKey.Pick(keyCode - KeyEvent.KEYCODE_NUMPAD_1)
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
    data object None : PanelEffect
}

/**
 * 剪贴板面板的状态机。纯数据，按键线程上直接跑，单元测试不需要设备。
 *
 * 结果列表由平台层异步查回来再塞进 [withEntries]：查询期间的按键照样能动光标，
 * 光标在新结果到达时夹回合法范围。
 */
data class ClipPanelState(
    val query: String = "",
    val entries: List<ClipEntry> = emptyList(),
    val selected: Int = 0,
) {
    val current: ClipEntry? get() = entries.getOrNull(selected)

    fun withEntries(list: List<ClipEntry>): ClipPanelState =
        copy(entries = list, selected = selected.coerceIn(0, (list.size - 1).coerceAtLeast(0)))

    fun reduce(key: PanelKey): Pair<ClipPanelState, PanelEffect> = when (key) {
        PanelKey.Up -> move(-1)
        PanelKey.Down -> move(1)
        PanelKey.PageUp -> move(-PAGE)
        PanelKey.PageDown -> move(PAGE)
        PanelKey.Escape -> this to PanelEffect.Close
        PanelKey.Enter -> this to (current?.let { PanelEffect.Insert(it) } ?: PanelEffect.None)
        PanelKey.Delete -> this to (current?.let { PanelEffect.Delete(it) } ?: PanelEffect.None)
        PanelKey.TogglePin -> this to (current?.let { PanelEffect.TogglePin(it) } ?: PanelEffect.None)
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
        val next = (selected + delta).coerceIn(0, entries.size - 1)
        return if (next == selected) this to PanelEffect.None else copy(selected = next) to PanelEffect.Render
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
