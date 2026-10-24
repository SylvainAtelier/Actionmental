package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.SymbolCatalog
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.core.shortcut.Shortcut
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeSymbolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `输入符号是一级菜单里的直达项`() {
        val group = ActionCatalog.groups.first { it.id == ActionCatalog.GROUP_SYMBOL }
        assertTrue(group.direct)
        assertTrue(group.actions.isEmpty())
    }

    @Test
    fun `符号表每组非空、组内不重复、常用符号都在`() {
        assertTrue(SymbolCatalog.groups.size >= 15)
        SymbolCatalog.groups.forEach { group ->
            assertTrue(group.id, group.symbols.isNotEmpty())
            assertEquals(group.id, group.symbols.size, group.symbols.distinct().size)
            assertTrue(group.id, group.symbols.none { it.isEmpty() })
        }
        assertEquals(SymbolCatalog.groups.size, SymbolCatalog.groups.map { it.id }.distinct().size)
        listOf("，", "…", "\\", "\"", "\$", "≠", "∞", "→", "€", "₿", "℃", "©", "⌘", "①", "㊿", "Ω", "ǚ", "ㄅ", "ア", "👍", "\u3000")
            .forEach { assertNotNull(it, SymbolCatalog.groupOf(it)) }
        assertTrue(SymbolCatalog.groups.sumOf { it.symbols.size } > 1500)
    }

    @Test
    fun `圈号按码位区间连续生成`() {
        val numbers = SymbolCatalog.groups.first { it.id == "number" }.symbols
        assertEquals("①", numbers.first())
        assertTrue(numbers.containsAll(listOf("⑳", "㉑", "㉟", "㊱", "Ⅻ", "㈩", "Ⓩ", "⒵")))
    }

    @Test
    fun `看不见的字符有名字，普通符号没有`() {
        SymbolCatalog.groups.first { it.id == "space" }.symbols.forEach {
            assertNotNull(SymbolCatalog.codePoints(it), SymbolCatalog.nameOf(it))
        }
        assertNull(SymbolCatalog.nameOf("→"))
        assertEquals("全角空格", Action.TypeSymbol("\u3000").display)
    }

    @Test
    fun `码位显示，emoji 带变体选择符时逐个列出`() {
        assertEquals("U+00A9", SymbolCatalog.codePoints("©"))
        assertEquals("U+1F44D", SymbolCatalog.codePoints("👍"))
        assertEquals("U+2764 U+FE0F", SymbolCatalog.codePoints("❤️"))
    }

    @Test
    fun `标签不用间隔号，中文界面不会把符号丢掉`() {
        val action = Action.TypeSymbol("→")
        assertEquals("输入符号 →", action.label)
        assertFalse(action.label.contains(" · "))
        assertFalse(action.requiresPrivilege)
        assertEquals("箭头", Action.TypeSymbol("→", "箭头").display)
    }

    @Test
    fun `输入符号的绑定能原样存回来`() {
        val shortcut = Shortcut(
            id = "symbol",
            combo = KeyCombo(KeyEvent.KEYCODE_1, KeyCombo.MOD_ALT),
            action = Action.TypeSymbol("😀\u3000\\\"", "混合"),
        )
        val restored = json.decodeFromString(Shortcut.serializer(), json.encodeToString(Shortcut.serializer(), shortcut))
        assertEquals(shortcut, restored)
    }

    @Test
    fun `桌面按钮叫符号本身或自定的名字`() {
        assertFalse(DesktopName.isBuiltIn(Action.TypeSymbol("©")))
        assertEquals("©", DesktopName.of(Action.TypeSymbol("©")))
        assertEquals("版权", DesktopName.of(Action.TypeSymbol("©", "版权")))
    }

    @Test
    fun `写进去了不提示，没有输入框时说清原因与已复制`() {
        val action = Action.TypeSymbol("©")
        assertNull(TriggerFeedback.of(action, ActionResult.OK) { it })
        val failed = TriggerFeedback.of(
            action,
            ActionResult.Failed(ActionResult.Reason.NO_INPUT_FIELD, "已复制到剪贴板"),
        ) { it }
        assertNotNull(failed)
        assertTrue(failed!!.failed)
        assertEquals("©", failed.title)
        assertEquals("已复制到剪贴板", failed.meta)
    }
}
