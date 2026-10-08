package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.action.ActionResult
import com.actionmental.core.action.TriggerFeedback
import com.actionmental.core.clip.BubbleGeometry
import com.actionmental.core.clip.ClipDeduper
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.core.clip.ClipRecorder
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.clip.PickKeys
import com.actionmental.core.clip.Polyphones
import com.actionmental.core.clip.SearchKey
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.key.NormalizedKeyEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipHistoryTest {

    // --- 收录规则 -------------------------------------------------------------

    @Test
    fun `空白与敏感内容不收`() {
        assertNull(ClipPolicy.accept(null, false))
        assertNull(ClipPolicy.accept("  \n\t", false))
        assertNull(ClipPolicy.accept("hunter2", sensitive = true))
        assertEquals("192.168.1.8:37215", ClipPolicy.accept("192.168.1.8:37215", false))
    }

    @Test
    fun `超长内容截断且不切开代理对`() {
        val long = "a".repeat(ClipPolicy.MAX_CHARS + 10)
        assertEquals(ClipPolicy.MAX_CHARS, ClipPolicy.accept(long, false)!!.length)

        // 截断点正好落在一个 emoji 的高位代理上：整颗让出去，不留半个字符
        val emojiAtEdge = "a".repeat(ClipPolicy.MAX_CHARS - 1) + "😀" + "tail"
        val kept = ClipPolicy.accept(emojiAtEdge, false)!!
        assertEquals(ClipPolicy.MAX_CHARS - 1, kept.length)
        assertFalse(Character.isHighSurrogate(kept.last()))
    }

    @Test
    fun `哈希稳定且区分内容`() {
        assertEquals(ClipPolicy.hash("abc"), ClipPolicy.hash("abc"))
        assertNotEquals(ClipPolicy.hash("abc"), ClipPolicy.hash("abd"))
        assertEquals(64, ClipPolicy.hash("任意内容").length)
    }

    // --- 搜索键 ---------------------------------------------------------------

    /** 假的读音表：只认测试里用到的字，第二个读音模拟多音字。 */
    private val fakeReadings: (Int) -> List<String> = { cp ->
        when (cp.toChar()) {
            '中' -> listOf("zhong")
            '文' -> listOf("wen")
            '设' -> listOf("she")
            '置' -> listOf("zhi")
            '银' -> listOf("yin")
            '行' -> listOf("xing", "hang")
            '绿' -> listOf("lv", "lu")
            '打' -> listOf("da")
            '开' -> listOf("kai")
            else -> emptyList()
        }
    }

    private fun matches(text: String, query: String): Boolean {
        val key = SearchKey.build(text, fakeReadings)
        return SearchKey.terms(query).all { key.contains(it) }
    }

    @Test
    fun `全拼与首字母都能找到中文`() {
        assertTrue(matches("中文设置", "zhongwen"))
        assertTrue(matches("中文设置", "zwsz"))
        assertTrue(matches("中文设置", "zhongw"))
        assertTrue(matches("中文设置", "文设"))
        assertFalse(matches("中文设置", "yinhang"))
    }

    @Test
    fun `多音字两个读音都找得到`() {
        assertTrue(matches("银行卡", "yinxing"))
        assertTrue(matches("银行卡", "yinhang"))
        assertTrue(matches("银行卡", "yh"))
        assertTrue(matches("绿色", "lv"))
        assertTrue(matches("绿色", "lu"))
    }

    @Test
    fun `夹在中文里的英文数字进拼音段，大小写不敏感`() {
        assertTrue(matches("打开Chrome 3", "dkchrome3"))
        assertTrue(matches("Release Build", "release build"))
        assertTrue(matches("Release Build", "BUILD"))
        assertTrue(matches("adb connect 192.168.1.8", "  adb   192.168 "))
    }

    @Test
    fun `没有汉字时键就是小写原文，搜索只看开头一段`() {
        assertEquals("abc def", SearchKey.build("ABC def", fakeReadings))
        val long = "x".repeat(SearchKey.KEY_CHARS) + "needle"
        assertFalse(matches(long, "needle"))
        assertTrue(SearchKey.terms("   ").isEmpty())
    }

    @Test
    fun `截断不切开代理对`() {
        val text = "a".repeat(SearchKey.KEY_CHARS - 1) + "😀"
        val key = SearchKey.build(text, fakeReadings)
        assertFalse(Character.isHighSurrogate(key.last()))
    }

    @Test
    fun `补充表把常见多音字的两个读音都并进来`() {
        assertEquals(listOf("xing", "hang"), Polyphones.merge('行'.code, listOf("xing")))
        assertEquals(listOf("chang", "zhang"), Polyphones.merge('长'.code, listOf("zhang")))
        assertEquals(listOf("wen"), Polyphones.merge('文'.code, listOf("wen")))
    }

    @Test
    fun `容量默认一万且可选不限`() {
        assertEquals(10_000, ClipPolicy.DEFAULT_CAPACITY)
        assertTrue(0 in ClipPolicy.CAPACITY_PRESETS)
        assertTrue(ClipPolicy.DEFAULT_CAPACITY in ClipPolicy.CAPACITY_PRESETS)
    }

    @Test
    fun `预览压平换行并截断`() {
        assertEquals("line one line two", ClipPolicy.preview("  line one\n\n  line two  "))
        assertEquals("abc…", ClipPolicy.preview("abcdef", max = 3))
    }

    @Test
    fun `长文按换行切段，拼回去和原文一样`() {
        assertEquals(listOf("short"), ClipPolicy.chunks("short", size = 10))

        val lines = (1..50).joinToString("\n") { "line $it" }
        val parts = ClipPolicy.chunks(lines, size = 40)
        assertEquals(lines, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 40 })
        // 有换行可切时就在换行后切
        assertTrue(parts.dropLast(1).all { it.endsWith("\n") })

        // 没有换行时硬切，但不切开代理对
        val emoji = "a".repeat(9) + "😀".repeat(10)
        val hard = ClipPolicy.chunks(emoji, size = 10)
        assertEquals(emoji, hard.joinToString(""))
        assertTrue(hard.none { Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun `预览是否就是全文按码点判断`() {
        assertTrue(ClipEntry(1, "abc", null, 0, 0, 1, 0).complete)
        assertFalse(ClipEntry(1, "abc", null, 0, 0, 1, 0, length = 900).complete)
        // emoji 在 UTF-16 里占两位：按码点数才对得上 SQLite 的 length()
        assertTrue(ClipEntry(1, "😀😀", null, 0, 0, 1, 0, length = 2).complete)
        assertFalse(ClipEntry(1, "😀😀", null, 0, 0, 1, 0, length = 3).complete)
    }

    @Test
    fun `时间只给量级`() {
        val now = 10_000_000_000L
        assertEquals("刚刚", ClipPolicy.age(now, now - 30_000))
        assertEquals("5 分钟前", ClipPolicy.age(now, now - 5 * 60_000))
        assertEquals("3 小时前", ClipPolicy.age(now, now - 3 * 3_600_000))
        assertEquals("2 天前", ClipPolicy.age(now, now - 2 * 86_400_000))
        // 时钟回拨不出负数
        assertEquals("刚刚", ClipPolicy.age(now, now + 60_000))
    }

    @Test
    fun `连续相同只记一次，忘掉之后重新记`() {
        val deduper = ClipDeduper()
        assertFalse(deduper.isRepeat("h1"))
        assertTrue(deduper.isRepeat("h1"))
        assertFalse(deduper.isRepeat("h2"))
        assertFalse(deduper.isRepeat("h1"))
        deduper.forget()
        assertFalse(deduper.isRepeat("h1"))
    }

    // --- 自动清理 -------------------------------------------------------------

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `默认不清理，天数换算成过期线`() {
        val now = 100 * day
        assertNull(ClipPolicy.expiryCutoff(now, 0))
        assertNull(ClipPolicy.expiryCutoff(now, -3))
        assertEquals(now - 7 * day, ClipPolicy.expiryCutoff(now, 7))
        // 超过上限按上限算，不会溢出成未来的时刻
        assertEquals(
            now - ClipPolicy.MAX_RETENTION_DAYS * day,
            ClipPolicy.expiryCutoff(now, Int.MAX_VALUE),
        )
        assertTrue(0 in ClipPolicy.RETENTION_PRESETS)
    }

    @Test
    fun `自定义天数只收 1 到上限的整数`() {
        assertEquals(15, ClipPolicy.parseRetentionDays(" 15 "))
        assertEquals(ClipPolicy.MAX_RETENTION_DAYS, ClipPolicy.parseRetentionDays(ClipPolicy.MAX_RETENTION_DAYS.toString()))
        assertNull(ClipPolicy.parseRetentionDays("0"))
        assertNull(ClipPolicy.parseRetentionDays((ClipPolicy.MAX_RETENTION_DAYS + 1).toString()))
        assertNull(ClipPolicy.parseRetentionDays(""))
        assertNull(ClipPolicy.parseRetentionDays("7.5"))
    }

    @Test
    fun `上一条已过期时，同样的内容重新记`() {
        val deduper = ClipDeduper()
        assertFalse(deduper.isRepeat("h", nowMs = 0L, cutoffMs = null))
        // 过期线还在它之前：仍是重复
        assertTrue(deduper.isRepeat("h", nowMs = 5 * day, cutoffMs = -2 * day))
        // 过期线越过了上次记录的时刻：库里那行已过期，得重新记
        assertFalse(deduper.isRepeat("h", nowMs = 10 * day, cutoffMs = 3 * day))
        // 重新记过之后又是新的起点
        assertTrue(deduper.isRepeat("h", nowMs = 11 * day, cutoffMs = 4 * day))
    }

    @Test
    fun `记录器按清理天数判断重复`() = runBlocking {
        var now = 0L
        val stored = mutableListOf<String>()
        val recorder = ClipRecorder(
            enabled = { true },
            excluded = { emptySet() },
            foreground = { null },
            store = { text, _ -> stored += text },
            retentionDays = { 7 },
            clock = { now },
        )
        assertTrue(recorder.offer("addr", false))
        now = 3 * day
        assertFalse(recorder.offer("addr", false))
        now = 8 * day
        assertTrue(recorder.offer("addr", false))
        assertEquals(listOf("addr", "addr"), stored)
    }

    // --- 记录器 ---------------------------------------------------------------

    private class Harness(
        var enabled: Boolean = true,
        var excluded: Set<String> = emptySet(),
        var foreground: String? = "com.example.notes",
    ) {
        val stored = mutableListOf<Pair<String, String?>>()
        val recorder = ClipRecorder(
            enabled = { enabled },
            excluded = { excluded },
            foreground = { foreground },
            store = { text, source -> stored += text to source },
        )
    }

    @Test
    fun `关闭时什么都不存`() = runBlocking {
        val h = Harness(enabled = false)
        assertFalse(h.recorder.offer("text", false))
        assertTrue(h.stored.isEmpty())
    }

    @Test
    fun `来源取前台应用，排除名单里的不存`() = runBlocking {
        val h = Harness(excluded = setOf("com.example.vault"))
        assertTrue(h.recorder.offer("note", false))
        assertEquals("note" to "com.example.notes", h.stored.single())

        h.foreground = "com.example.vault"
        assertFalse(h.recorder.offer("secret", false))
        assertEquals(1, h.stored.size)
    }

    @Test
    fun `补读时来源记为未知，不受前台影响`() = runBlocking {
        val h = Harness(excluded = setOf("com.example.notes"))
        assertTrue(h.recorder.offer("from unlock", false, source = null))
        assertEquals("from unlock" to null, h.stored.single())
    }

    @Test
    fun `同一次复制的多次回调只存一条`() = runBlocking {
        val h = Harness()
        assertTrue(h.recorder.offer("same", false))
        assertFalse(h.recorder.offer("same", false))
        assertFalse(h.recorder.offer("same", false, source = null))
        h.recorder.forget()
        assertTrue(h.recorder.offer("same", false))
        assertEquals(2, h.stored.size)
    }

    // --- 面板状态机 -----------------------------------------------------------

    private fun entry(id: Long, text: String = "item $id", pinned: Boolean = false) =
        ClipEntry(id, text, null, 0L, 0L, 1, if (pinned) 1L else 0L)

    private val five = (1L..5L).map { entry(it) }

    @Test
    fun `上下移动夹在范围内，到边不再重画`() {
        var s = ClipPanelState().withEntries(five)
        val (down, effect) = s.reduce(PanelKey.Down)
        assertEquals(1, down.selected)
        assertEquals(PanelEffect.Render, effect)

        s = down.reduce(PanelKey.PageDown).first
        assertEquals(4, s.selected)
        assertEquals(PanelEffect.None, s.reduce(PanelKey.Down).second)
        assertEquals(0, s.reduce(PanelKey.PageUp).first.selected)
    }

    @Test
    fun `打字触发重查并把光标拉回第一条`() {
        val s = ClipPanelState(selected = 3).withEntries(five)
        val (typed, effect) = s.reduce(PanelKey.Type('a'))
        assertEquals("a", typed.query)
        assertEquals(0, typed.selected)
        assertEquals(PanelEffect.Requery("a"), effect)

        val (erased, again) = typed.reduce(PanelKey.Backspace)
        assertEquals("", erased.query)
        assertEquals(PanelEffect.Requery(""), again)
        assertEquals(PanelEffect.None, erased.reduce(PanelKey.Backspace).second)
    }

    @Test
    fun `退格整颗删掉 emoji`() {
        val s = ClipPanelState(query = "a😀")
        assertEquals("a", s.reduce(PanelKey.Backspace).first.query)
    }

    @Test
    fun `回车选用当前条，空列表什么都不做`() {
        val s = ClipPanelState(selected = 2).withEntries(five)
        assertEquals(PanelEffect.Insert(five[2]), s.reduce(PanelKey.Enter).second)
        assertEquals(PanelEffect.TogglePin(five[2]), s.reduce(PanelKey.TogglePin).second)
        assertEquals(PanelEffect.Delete(five[2]), s.reduce(PanelKey.Delete).second)
        assertEquals(PanelEffect.Copy(five[2]), s.reduce(PanelKey.Copy).second)

        val empty = ClipPanelState()
        assertEquals(PanelEffect.None, empty.reduce(PanelKey.Enter).second)
        assertEquals(PanelEffect.None, empty.reduce(PanelKey.Down).second)
        assertEquals(PanelEffect.Close, empty.reduce(PanelKey.Escape).second)
    }

    @Test
    fun `数字直选越界不动`() {
        val s = ClipPanelState().withEntries(five)
        val (picked, effect) = s.reduce(PanelKey.Pick(3))
        assertEquals(3, picked.selected)
        assertEquals(PanelEffect.Insert(five[3]), effect)
        assertEquals(PanelEffect.None, s.reduce(PanelKey.Pick(7)).second)
    }

    @Test
    fun `新结果变短时光标夹回范围`() {
        val s = ClipPanelState(selected = 4).withEntries(five)
        assertEquals(1, s.withEntries(five.take(2)).selected)
        assertEquals(0, s.withEntries(emptyList()).selected)
    }

    @Test
    fun `面板键位`() {
        val ctrl = KeyCombo.MOD_CTRL
        val alt = KeyCombo.MOD_ALT
        val shift = KeyCombo.MOD_SHIFT
        assertEquals(PanelKey.Up, PanelKeys.map(KeyEvent.KEYCODE_DPAD_UP, 0, null))
        assertEquals(PanelKey.Down, PanelKeys.map(KeyEvent.KEYCODE_TAB, 0, null))
        assertEquals(PanelKey.Up, PanelKeys.map(KeyEvent.KEYCODE_TAB, shift, null))
        assertEquals(PanelKey.Enter, PanelKeys.map(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, null))
        assertEquals(PanelKey.Backspace, PanelKeys.map(KeyEvent.KEYCODE_DEL, 0, null))
        assertEquals(PanelKey.ClearQuery, PanelKeys.map(KeyEvent.KEYCODE_DEL, ctrl, null))
        assertEquals(PanelKey.Delete, PanelKeys.map(KeyEvent.KEYCODE_FORWARD_DEL, 0, null))
        assertEquals(PanelKey.TogglePin, PanelKeys.map(KeyEvent.KEYCODE_P, ctrl, null))
        assertEquals(PanelKey.Copy, PanelKeys.map(KeyEvent.KEYCODE_C, ctrl, null))
        assertEquals(PanelKey.Pick(0), PanelKeys.map(KeyEvent.KEYCODE_1, ctrl, null))
        assertEquals(PanelKey.Pick(8), PanelKeys.map(KeyEvent.KEYCODE_9, ctrl, null))
        assertEquals(PanelKey.Pick(2), PanelKeys.map(KeyEvent.KEYCODE_NUMPAD_3, ctrl, null))
        // Alt + 数字不再是直选，也不当成字符输入
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_1, alt, '1'))
        // 不带修饰的数字就是搜索词
        assertEquals(PanelKey.Type('1'), PanelKeys.map(KeyEvent.KEYCODE_1, 0, '1'))
        assertEquals(PanelKey.Type('P'), PanelKeys.map(KeyEvent.KEYCODE_P, shift, 'P'))
        // 没定义的 Ctrl 组合不当成字符输入
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_Q, ctrl, 'q'))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_F5, 0, null))
    }

    @Test
    fun `直选键三选一，没选中的那几组都不直选`() {
        val ctrl = KeyCombo.MOD_CTRL
        val alt = KeyCombo.MOD_ALT
        val altKeys = PickKeys.ALT_DIGITS
        assertEquals(PanelKey.Pick(0), PanelKeys.map(KeyEvent.KEYCODE_1, alt, null, altKeys))
        assertEquals(PanelKey.Pick(4), PanelKeys.map(KeyEvent.KEYCODE_NUMPAD_5, alt, null, altKeys))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_1, ctrl, null, altKeys))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_1, ctrl or alt, null, altKeys))

        val fn = PickKeys.FUNCTION_KEYS
        assertEquals(PanelKey.Pick(0), PanelKeys.map(KeyEvent.KEYCODE_F1, 0, null, fn))
        assertEquals(PanelKey.Pick(8), PanelKeys.map(KeyEvent.KEYCODE_F9, 0, null, fn))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_F10, 0, null, fn))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_F1, ctrl, null, fn))
        assertNull(PanelKeys.map(KeyEvent.KEYCODE_1, ctrl, null, fn))
        // 不管选哪组，光按数字都是搜索
        PickKeys.entries.forEach { assertEquals(PanelKey.Type('1'), PanelKeys.map(KeyEvent.KEYCODE_1, 0, '1', it)) }

        assertEquals("F3", fn.badge(3))
        assertEquals("3", altKeys.badge(3))
        assertEquals(listOf("Alt", "3"), altKeys.tokens(3))
        assertEquals(listOf("F3"), fn.tokens(3))
    }

    // --- 悬浮按钮的位置 -------------------------------------------------------

    @Test
    fun `松手时贴近的那一侧`() {
        assertFalse(BubbleGeometry.snapToRight(centerX = 100, screenWidth = 1000))
        assertTrue(BubbleGeometry.snapToRight(centerX = 500, screenWidth = 1000))
        assertTrue(BubbleGeometry.snapToRight(centerX = 900, screenWidth = 1000))
    }

    @Test
    fun `竖直位置按比例存，换屏幕尺寸也落在可用区内`() {
        // 竖屏 2000 高、上让 100、下让 100、按钮 100：可动范围 100…1800
        assertEquals(0f, BubbleGeometry.fractionOf(50, 2000, 100, 100, 100))
        assertEquals(1f, BubbleGeometry.fractionOf(1950, 2000, 100, 100, 100))
        val half = BubbleGeometry.fractionOf(950, 2000, 100, 100, 100)
        assertEquals(0.5f, half, 0.001f)
        assertEquals(950, BubbleGeometry.yOf(half, 2000, 100, 100, 100))
        // 转成横屏只剩 1000 高：同样的比例落在新范围的中间
        assertEquals(450, BubbleGeometry.yOf(half, 1000, 100, 100, 100))
        // 窗口小到放不下按钮也不出负数
        assertEquals(100, BubbleGeometry.yOf(0.7f, 250, 100, 100, 100))
        assertEquals(0f, BubbleGeometry.fractionOf(10, 250, 100, 100, 100))
    }

    // --- 管线的模态接管 -------------------------------------------------------

    private fun key(down: Boolean, keyCode: Int, metaState: Int = 0, repeat: Int = 0) = NormalizedKeyEvent(
        keyCode = keyCode,
        scanCode = 0,
        metaState = metaState,
        down = down,
        repeatCount = repeat,
        eventTimeMs = 1L,
        device = KeyboardDevice.UNKNOWN,
    )

    @Test
    fun `面板开着时按键交给面板，快捷键不触发`() {
        val pipeline = KeyPipeline()
        var triggered = 0
        pipeline.onTrigger = { _, _ -> triggered++; true }
        val seen = mutableListOf<Pair<Int, Char?>>()
        pipeline.modal = { e, _, typed ->
            if (e.down) seen += e.keyCode to typed
            !e.isModifier
        }

        assertTrue(pipeline.dispatch(key(true, KeyEvent.KEYCODE_A), 'a'))
        assertTrue(pipeline.dispatch(key(false, KeyEvent.KEYCODE_A)))
        assertEquals(0, triggered)
        assertEquals(listOf(KeyEvent.KEYCODE_A to 'a'), seen)

        // 修饰键放行给前台应用
        assertFalse(pipeline.dispatch(key(true, KeyEvent.KEYCODE_CTRL_LEFT)))
        assertFalse(pipeline.dispatch(key(false, KeyEvent.KEYCODE_CTRL_LEFT)))
    }

    @Test
    fun `唤出面板的快捷键，抬起照样被吞`() {
        val pipeline = KeyPipeline()
        pipeline.onTrigger = { _, _ -> true }
        val ctrlV = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertTrue(pipeline.dispatch(key(true, KeyEvent.KEYCODE_V, ctrlV)))

        // 快捷键执行后面板才打开：抬起到的时候已经是模态了
        var modalSawUp = false
        pipeline.modal = { e, _, _ -> if (!e.down) modalSawUp = true; true }
        assertTrue(pipeline.dispatch(key(false, KeyEvent.KEYCODE_V, ctrlV)))
        assertFalse(modalSawUp)

        // 关掉面板之后 V 是一颗普通键，不再被当成「已吞掉」
        pipeline.modal = null
        pipeline.onTrigger = { _, _ -> false }
        assertFalse(pipeline.dispatch(key(true, KeyEvent.KEYCODE_V)))
    }

    @Test
    fun `面板在按下时关掉，Enter 的抬起不能漏给底下的输入框`() {
        val pipeline = KeyPipeline()
        pipeline.onTrigger = { _, _ -> false }
        pipeline.modal = { e, _, _ ->
            if (e.down && e.keyCode == KeyEvent.KEYCODE_ENTER) pipeline.modal = null // 选用即关闭
            true
        }
        assertTrue(pipeline.dispatch(key(true, KeyEvent.KEYCODE_ENTER)))
        // 单行输入框在 Enter 抬起时触发「发送」—— 这一下必须拦住
        assertTrue(pipeline.dispatch(key(false, KeyEvent.KEYCODE_ENTER)))
        // 再按一次 Enter 就是普通的 Enter 了
        assertFalse(pipeline.dispatch(key(true, KeyEvent.KEYCODE_ENTER)))
        assertFalse(pipeline.dispatch(key(false, KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun `长按方向键的连发照样交给面板`() {
        val pipeline = KeyPipeline()
        var moves = 0
        pipeline.modal = { e, _, _ -> if (e.down && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) moves++; true }
        pipeline.dispatch(key(true, KeyEvent.KEYCODE_DPAD_DOWN))
        pipeline.dispatch(key(true, KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1))
        pipeline.dispatch(key(true, KeyEvent.KEYCODE_DPAD_DOWN, repeat = 2))
        assertEquals(3, moves)
    }

    @Test
    fun `修饰键映射在面板里同样生效：Caps 映射成 Ctrl 时 Caps + 1 是直选`() {
        val pipeline = KeyPipeline()
        val caps = KeyEvent.KEYCODE_CAPS_LOCK
        pipeline.rewriteCombo = { combo, pressed ->
            if (caps in pressed && combo.keyCode != caps) combo.copy(modifiers = combo.modifiers or KeyCombo.MOD_CTRL)
            else combo
        }
        val picks = mutableListOf<PanelKey?>()
        pipeline.modal = { e, modifiers, typed ->
            if (e.down) picks += PanelKeys.map(e.keyCode, modifiers, typed)
            true
        }
        pipeline.dispatch(key(true, caps))
        // 映射出来的 Ctrl 是注入的，实体键盘这颗 1 的 metaState 里没有它，打出来的字照样是 '1'
        pipeline.dispatch(key(true, KeyEvent.KEYCODE_1), '1')
        pipeline.dispatch(key(false, KeyEvent.KEYCODE_1))
        pipeline.dispatch(key(false, caps))
        // 松开 Caps 之后，1 回到搜索输入
        pipeline.dispatch(key(true, KeyEvent.KEYCODE_1), '1')
        assertEquals(listOf(null, PanelKey.Pick(0), PanelKey.Type('1')), picks)
    }

    // --- 动作 -----------------------------------------------------------------

    @Test
    fun `剪贴板历史动作在目录里且能序列化`() {
        val group = ActionCatalog.groups.first { it.id == "clipboard" }
        assertEquals(listOf<Action>(Action.ClipboardHistory), group.actions)
        assertFalse(Action.ClipboardHistory.requiresPrivilege)

        val json = Json.encodeToString(Action.serializer(), Action.ClipboardHistory)
        assertTrue(json.contains("clip_history"))
        assertEquals(Action.ClipboardHistory, Json.decodeFromString(Action.serializer(), json))
    }

    @Test
    fun `面板打开不提示，打不开时说清原因`() {
        val identity: (String) -> String = { it }
        assertNull(TriggerFeedback.of(Action.ClipboardHistory, ActionResult.OK, identity))
        val failed = TriggerFeedback.of(
            Action.ClipboardHistory,
            ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "锁屏时不显示剪贴板历史"),
            identity,
        )!!
        assertTrue(failed.failed)
        assertEquals("剪贴板历史", failed.title)
        assertTrue(failed.value!!.contains("锁屏"))
    }
}
