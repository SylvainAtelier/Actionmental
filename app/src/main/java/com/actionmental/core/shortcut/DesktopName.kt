package com.actionmental.core.shortcut

import com.actionmental.core.action.Action
import java.text.BreakIterator

/**
 * 桌面按钮上的名字与图标字。
 *
 * 桌面只给十来个字的位置，[Shortcut.displayLabel] 的「启动应用 · 」「Termux · 」这类前缀
 * 在那里只会把真正的名字挤成省略号，所以这里取的是动作里最能认出它的那一段。
 * 用户填了名称就一律用名称 —— 名称字段同时也是桌面按钮的配置。
 */
object DesktopName {

    /**
     * [translate] 只作用于内置动作名（「切换屏幕常亮」这类）：用户起的名字、应用名、命令都原样保留，
     * 不拿去逐词翻译。
     */
    fun of(shortcut: Shortcut, translate: (String) -> String = { it }): String =
        shortcut.label.trim().ifBlank { of(shortcut.action).let { if (isBuiltIn(shortcut.action)) translate(it) else it } }

    fun isBuiltIn(action: Action): Boolean =
        action !is Action.LaunchApp && action !is Action.OpenUrl && action !is Action.Termux && action !is Action.Shell

    fun of(action: Action): String = when (action) {
        is Action.LaunchApp -> action.appLabel
        is Action.OpenUrl -> action.title.ifBlank { hostOf(action.url) }
        is Action.Termux -> action.displayName
        is Action.Shell -> action.title.ifBlank { action.command }
        else -> action.label
    }

    /**
     * 图标中央的那一个字：名字的第一个字形。
     *
     * 按字形而不是按 char 切，emoji、带声调的字母都是完整的一个；拉丁字母统一大写，
     * 让 `sync` 与 `Sync` 在桌面上是同一个样子。
     */
    fun glyphOf(name: String): String {
        val trimmed = name.trim()
        // 路径形式的命令（~/bin/sync.sh）认的是文件名，不是 bin
        val head = if (trimmed.startsWith('~') || trimmed.startsWith('/') || trimmed.startsWith('.')) {
            trimmed.substringBefore(' ').trimEnd('/').substringAfterLast('/')
        } else trimmed
        val text = head.trimStart('~', '/', '.', '-', '_')
        if (text.isEmpty()) return "·"
        val breaker = BreakIterator.getCharacterInstance().apply { setText(text) }
        val end = breaker.next().takeIf { it != BreakIterator.DONE } ?: text.length
        return text.substring(0, end).uppercase()
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { url }
}
