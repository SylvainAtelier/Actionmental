package com.actionmental

import com.actionmental.core.action.Action
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.core.shortcut.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopNameTest {

    private fun shortcut(action: Action, label: String = "") =
        Shortcut(id = "x", combo = KeyCombo(29, 0), action = action, label = label)

    @Test
    fun `桌面名去掉动作前缀，只留认得出的那一段`() {
        assertEquals("笔记", DesktopName.of(shortcut(Action.LaunchApp("com.example.notes", appLabel = "笔记"))))
        assertEquals("同步", DesktopName.of(shortcut(Action.Termux("~/bin/sync.sh", title = "同步"))))
        assertEquals("~/bin/sync.sh", DesktopName.of(shortcut(Action.Termux("~/bin/sync.sh"))))
        assertEquals("example.com", DesktopName.of(shortcut(Action.OpenUrl("https://www.example.com/a/b"))))
    }

    @Test
    fun `用户起的名字优先，且不拿去翻译`() {
        val upper: (String) -> String = { "T:" + it }
        assertEquals("我的命令", DesktopName.of(shortcut(Action.Shell("ls"), label = " 我的命令 "), upper))
        assertEquals("ls", DesktopName.of(shortcut(Action.Shell("ls")), upper))
        assertEquals(
            "T:切换屏幕常亮",
            DesktopName.of(shortcut(Action.Awake(Action.Awake.Op.TOGGLE)), upper),
        )
    }

    @Test
    fun `图标字取第一个字形`() {
        assertEquals("同", DesktopName.glyphOf("同步"))
        assertEquals("S", DesktopName.glyphOf("~/bin/sync.sh"))
        assertEquals("👍🏽", DesktopName.glyphOf("👍🏽 点赞"))
        assertEquals("·", DesktopName.glyphOf("  "))
    }
}
