package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.core.remap.KeyRemap
import com.actionmental.core.remap.KeyRemapMatcher
import com.actionmental.core.remap.RemapPresets
import com.actionmental.core.remap.isModifierRemap
import com.actionmental.core.remap.rewriteModifiers
import com.actionmental.core.shortcut.DeviceScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键位映射的三条约定：源键唯一、快捷键优先、长按要连发。
 * 前两条决定了映射永远不会绕成环，第三条决定了映射出来的键用起来和真键一样。
 */
class KeyRemapTest {

    private val caps = KeyCombo(KeyEvent.KEYCODE_CAPS_LOCK)
    private val esc = KeyCombo(KeyEvent.KEYCODE_ESCAPE)

    /**
     * 只发半边的修饰键要像实体键盘：按下带上自己的位，抬起清掉。
     * 按下带 0 时，TeamViewer 看不到 Shift 亮过，抬起就不往远端发，远端 Shift 卡住。
     */
    @Test
    fun `半边注入的修饰键按下点亮自己的位，抬起熄灭`() {
        val shiftL = KeyCombo(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, shiftL.metaState(true))
        assertEquals(0, shiftL.metaState(false))

        val altR = KeyCombo(KeyEvent.KEYCODE_ALT_RIGHT, KeyCombo.MOD_CTRL)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertEquals(ctrl or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, altR.metaState(true))
        assertEquals(ctrl, altR.metaState(false))

        // 普通键不受影响
        assertEquals(esc.metaState(), esc.metaState(true))
        assertEquals(esc.metaState(), esc.metaState(false))
    }
    @Test
    fun `源键唯一，禁用的映射不进索引`() {
        val matcher = KeyRemapMatcher()
        matcher.update(
            listOf(
                KeyRemap("a", caps, esc),
                KeyRemap("b", KeyCombo(KeyEvent.KEYCODE_INSERT), esc, enabled = false),
            )
        )

        assertEquals("a", matcher.match(caps, k380)?.id)
        assertNull(matcher.match(KeyCombo(KeyEvent.KEYCODE_INSERT), k380))
    }

    @Test
    fun `映射命中时源键整颗被拦下，换成目标键的按下与抬起`() {
        val emitted = mutableListOf<Pair<KeyCombo, Boolean>>()
        val pipeline = replacing(mapOf(caps to esc), emitted)

        assertTrue(pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK)))
        assertTrue(pipeline.dispatch(up(KeyEvent.KEYCODE_CAPS_LOCK)))

        // 前台应用与输入法一次都没收到 Caps —— 短按原键的行为也就不存在了
        assertEquals(listOf(esc to true, esc to false), emitted)
    }

    @Test
    fun `映射不成立时按键照常走快捷键`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onReplacedKey = { _, _, _, _ -> false }
            onTrigger = { combo, _ -> fired += combo; true }
        }

        assertTrue(pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK)))
        assertEquals(listOf(caps), fired)
    }

    @Test
    fun `长按被映射的键会连发目标键`() {
        val emitted = mutableListOf<Pair<KeyCombo, Boolean>>()
        val pipeline = replacing(mapOf(caps to esc), emitted)

        pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK))
        pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK, repeatCount = 1))
        pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK, repeatCount = 2))
        pipeline.dispatch(up(KeyEvent.KEYCODE_CAPS_LOCK))

        assertEquals(
            listOf(esc to true, esc to true, esc to true, esc to false),
            emitted,
        )
    }

    @Test
    fun `抬起之后不再补发`() {
        val emitted = mutableListOf<Pair<KeyCombo, Boolean>>()
        val pipeline = replacing(mapOf(caps to esc), emitted)

        pipeline.dispatch(down(KeyEvent.KEYCODE_CAPS_LOCK))
        pipeline.dispatch(up(KeyEvent.KEYCODE_CAPS_LOCK))
        // 迟到的重复抬起（热拔插时会出现）不该再发一次
        pipeline.dispatch(up(KeyEvent.KEYCODE_CAPS_LOCK))

        assertEquals(listOf(esc to true, esc to false), emitted)
    }

    @Test
    fun `键盘断开时会把按住的目标键放开`() {
        val emitted = mutableListOf<Pair<KeyCombo, Boolean>>()
        val pipeline = replacing(mapOf(shiftLeft to altLeft), emitted)

        pipeline.dispatch(down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        pipeline.reset()

        // 少了这次抬起，系统会一直以为 Alt 按着，整台设备的输入都会变样
        assertEquals(listOf(altLeft to true, altLeft to false), emitted)
    }

    // --- 修饰键映射 ------------------------------------------------------------

    private val shiftLeft = KeyCombo(KeyEvent.KEYCODE_SHIFT_LEFT)
    private val altLeft = KeyCombo(KeyEvent.KEYCODE_ALT_LEFT)
    private val shiftToAlt = KeyRemap("m", shiftLeft, altLeft)

    @Test
    fun `映射成修饰键时改写的是修饰位，不是发一颗孤零零的键`() {
        assertTrue(shiftToAlt.isModifierRemap)
        // 目标是普通键的才是「按一下发一颗」
        assertFalse(KeyRemap("k", caps, esc).isModifierRemap)
    }

    @Test
    fun `按住被映射的修饰键时，组合键按映射后的修饰键计算`() {
        val pressed = setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_L)
        val rewritten = rewriteModifiers(
            KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_SHIFT),
            pressed,
            listOf(shiftToAlt),
        )

        assertEquals(KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_ALT), rewritten)
    }

    @Test
    fun `只映射了左边时，右边那颗仍然算原来的修饰键`() {
        val pressed = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_L,
        )
        val rewritten = rewriteModifiers(
            KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_SHIFT),
            pressed,
            listOf(shiftToAlt),
        )

        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_SHIFT or KeyCombo.MOD_ALT),
            rewritten,
        )
    }

    @Test
    fun `源键自己按下的那一次不被改写`() {
        val pressed = setOf(KeyEvent.KEYCODE_CAPS_LOCK)
        val rule = KeyRemap("c", caps, KeyCombo(KeyEvent.KEYCODE_CTRL_LEFT))

        assertEquals(caps, rewriteModifiers(caps, pressed, listOf(rule)))
    }

    @Test
    fun `绑在映射后修饰键上的快捷键，用原来的键也能按出来`() {
        val fired = mutableListOf<KeyCombo>()
        val altL = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_ALT)
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { combo, _ -> fired += combo; combo == altL }
            rewriteCombo = { combo, pressed, _ -> rewriteModifiers(combo, pressed, listOf(shiftToAlt)) }
        }

        pipeline.dispatch(down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        val consumed = pipeline.dispatch(down(KeyEvent.KEYCODE_L, KeyEvent.META_SHIFT_ON))

        assertTrue(consumed)
        assertEquals(listOf(altL), fired)
    }

    @Test
    fun `没有快捷键时，改写后的组合被原样发出去`() {
        val emitted = mutableListOf<KeyCombo>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { _, _ -> false }
            rewriteCombo = { combo, pressed, _ -> rewriteModifiers(combo, pressed, listOf(shiftToAlt)) }
            onRemap = { original, effective, _ ->
                if (effective == original) false else { emitted += effective; true }
            }
        }

        pipeline.dispatch(down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        assertTrue(pipeline.dispatch(down(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)))

        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_TAB, KeyCombo.MOD_ALT)), emitted)
    }

    @Test
    fun `左 Shift 映射成 Alt 时，录下的 Shift + 斜杠 快捷键照样触发，不再被当成 Alt + 斜杠 发出去`() {
        val fired = mutableListOf<KeyCombo>()
        val emitted = mutableListOf<KeyCombo>()
        val shiftSlash = KeyCombo(KeyEvent.KEYCODE_SLASH, KeyCombo.MOD_SHIFT)
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { combo, _ -> fired += combo; combo == shiftSlash }
            rewriteCombo = { combo, pressed, _ -> rewriteModifiers(combo, pressed, listOf(shiftToAlt)) }
            onRemap = { _, effective, _ -> emitted += effective; true }
        }

        pipeline.dispatch(down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        assertTrue(pipeline.dispatch(down(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON)))

        // 先按改写后的 Alt + / 找，没有再按真按下的 Shift + / 找
        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_SLASH, KeyCombo.MOD_ALT), shiftSlash), fired)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `只添上修饰位的改写不退回原组合，Caps + 1 不会触发单独 1 的快捷键`() {
        val ctrlOne = KeyCombo(KeyEvent.KEYCODE_1, KeyCombo.MOD_CTRL)
        assertNull(KeyPipeline.physicalFallback(KeyCombo(KeyEvent.KEYCODE_1), ctrlOne))
        assertNull(KeyPipeline.physicalFallback(ctrlOne, ctrlOne))
        val shiftSlash = KeyCombo(KeyEvent.KEYCODE_SLASH, KeyCombo.MOD_SHIFT)
        assertEquals(
            shiftSlash,
            KeyPipeline.physicalFallback(shiftSlash, KeyCombo(KeyEvent.KEYCODE_SLASH, KeyCombo.MOD_ALT)),
        )
    }

    @Test
    fun `自己注入的按键原样放行，不会自己触发自己`() {
        val fired = mutableListOf<KeyCombo>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { combo, _ -> fired += combo; true }
        }

        val injected = down(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON).copy(virtual = true)
        assertFalse(pipeline.dispatch(injected))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `修饰键掩码能还原成 metaState`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL or KeyCombo.MOD_SHIFT)
        val state = combo.metaState()

        assertEquals(combo.modifiers, KeyCombo.modifiersOf(state))
        assertEquals(0, KeyCombo(KeyEvent.KEYCODE_L).metaState())
    }

    @Test
    fun `预设都是真的改动，而且源键都拦得住`() {
        assertTrue(RemapPresets.common.none { it.from == it.to })
        // 源键是修饰键、目标却是普通键的话只能轻点触发，不适合作为推荐项；
        // 映射成修饰键（左 Shift → 左 Alt）是另一回事，那条是推荐的
        assertTrue(
            RemapPresets.common.none {
                it.from.isModifierKey && !isModifierRemap(it.from, it.to)
            }
        )
        val labels = RemapPresets.common.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    // --- 按键盘生效 ------------------------------------------------------------

    private val k380 = KeyboardDevice(1, "K380", "desc-k380")
    private val hhkb = KeyboardDevice(2, "HHKB", "desc-hhkb")

    private fun only(device: KeyboardDevice) = DeviceScope.only(device)

    @Test
    fun `专属规则只管那一把键盘，其余键盘沿用全局规则`() {
        val matcher = KeyRemapMatcher()
        matcher.update(
            listOf(
                KeyRemap("global", caps, esc),
                KeyRemap("own", caps, KeyCombo(KeyEvent.KEYCODE_DEL), deviceScope = only(k380)),
            )
        )

        assertEquals("own", matcher.match(caps, k380)?.id)
        assertEquals("global", matcher.match(caps, hhkb)?.id)
    }

    @Test
    fun `没有全局规则时，专属规则对别的键盘不起作用`() {
        val matcher = KeyRemapMatcher()
        matcher.update(listOf(KeyRemap("own", caps, esc, deviceScope = only(k380))))

        assertEquals("own", matcher.match(caps, k380)?.id)
        assertNull(matcher.match(caps, hhkb))
    }

    @Test
    fun `保持原键挡住全局规则，而且什么都不发`() {
        val matcher = KeyRemapMatcher()
        val keep = KeyRemap("keep", caps, caps, deviceScope = only(hhkb))
        matcher.update(listOf(KeyRemap("global", caps, esc), keep))

        assertTrue(keep.isPassthrough)
        assertNull(matcher.match(caps, hhkb))
        assertEquals("global", matcher.match(caps, k380)?.id)
        // 所有键盘上的 from == to 不是「保持原键」，那是没有意义的映射
        assertFalse(KeyRemap("x", caps, caps).isPassthrough)
    }

    @Test
    fun `同一把键盘上有多条专属规则时取最近改的`() {
        val matcher = KeyRemapMatcher()
        matcher.update(
            listOf(
                KeyRemap("old", caps, esc, deviceScope = only(k380), updatedAt = 1L),
                KeyRemap("new", caps, KeyCombo(KeyEvent.KEYCODE_DEL), deviceScope = only(k380), updatedAt = 2L),
            )
        )

        assertEquals("new", matcher.match(caps, k380)?.id)
    }

    @Test
    fun `修饰键改写只看事件所在键盘适用的规则`() {
        val matcher = KeyRemapMatcher()
        matcher.update(listOf(KeyRemap("m", shiftLeft, altLeft, deviceScope = only(k380))))
        val pressed = setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_L)
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_SHIFT)

        assertEquals(KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_ALT), matcher.rewrite(combo, pressed, k380))
        assertEquals(combo, matcher.rewrite(combo, pressed, hhkb))
    }

    @Test
    fun `保持原键也挡住全局的修饰键映射`() {
        val matcher = KeyRemapMatcher()
        matcher.update(
            listOf(
                KeyRemap("m", shiftLeft, altLeft),
                KeyRemap("keep", shiftLeft, shiftLeft, deviceScope = only(hhkb)),
            )
        )
        val pressed = setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_L)
        val combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_SHIFT)

        assertEquals(combo, matcher.rewrite(combo, pressed, hhkb))
        assertEquals(KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_ALT), matcher.rewrite(combo, pressed, k380))
    }

    @Test
    fun `键盘断开补抬起时，仍然交给当初按下它的那把键盘`() {
        val released = mutableListOf<Pair<Boolean, KeyboardDevice>>()
        val pipeline = KeyPipeline(traceCapacity = 0).apply {
            onTrigger = { _, _ -> false }
            onReplacedKey = { _, _, down, device -> released += down to device; true }
        }

        pipeline.dispatch(NormalizedKeyEvent(KeyEvent.KEYCODE_CAPS_LOCK, 0, 0, true, 0, 1L, k380))
        pipeline.reset()

        // 规则只对 k380 生效：补抬起带着 UNKNOWN 的话匹配不到规则，目标键会一直卡着
        assertEquals(listOf(true to k380, false to k380), released)
    }

    @Test
    fun `范围比较只看键盘，名字快照不参与；旧数据读出来是所有键盘`() {
        val a = DeviceScope(listOf("d1"), mapOf("d1" to "K380"))
        val b = DeviceScope(listOf("d1"), mapOf("d1" to "K380 改名了"))
        assertTrue(a.sameDevices(b))
        assertFalse(a.sameDevices(DeviceScope.ALL))
        assertTrue(a.overlaps(DeviceScope.ALL))
        assertFalse(a.overlaps(DeviceScope(listOf("d2"))))

        // 没有 deviceScope 字段的旧配置（含旧版导出）读出来就是所有键盘
        val raw = Json.encodeToString(KeyRemap.serializer(), KeyRemap("a", caps, esc))
        assertFalse(raw.contains("deviceScope"))
        assertTrue(Json.decodeFromString(KeyRemap.serializer(), raw).deviceScope.isAll)
    }

    // --- 脚手架 ---------------------------------------------------------------

    /** 整颗替换：源键的每一半事件都换成目标键的同一半。 */
    private fun replacing(
        remaps: Map<KeyCombo, KeyCombo>,
        emitted: MutableList<Pair<KeyCombo, Boolean>>,
    ) = KeyPipeline(traceCapacity = 0).apply {
        onTrigger = { _, _ -> false }
        onReplacedKey = { keyCode, modifiers, down, _ ->
            val target = remaps[KeyCombo(keyCode, modifiers)]
            if (target == null) false else { emitted += target to down; true }
        }
    }

    private fun down(keyCode: Int, metaState: Int = 0, repeatCount: Int = 0) =
        NormalizedKeyEvent(keyCode, 0, metaState, true, repeatCount, 1L, KeyboardDevice.UNKNOWN)

    private fun up(keyCode: Int, metaState: Int = 0) =
        NormalizedKeyEvent(keyCode, 0, metaState, false, 0, 1L, KeyboardDevice.UNKNOWN)
}
