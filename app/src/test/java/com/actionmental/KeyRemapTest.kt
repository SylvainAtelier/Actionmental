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

    @Test
    fun `源键唯一，禁用的映射不进索引`() {
        val matcher = KeyRemapMatcher()
        matcher.update(
            listOf(
                KeyRemap("a", caps, esc),
                KeyRemap("b", KeyCombo(KeyEvent.KEYCODE_INSERT), esc, enabled = false),
            )
        )

        assertEquals("a", matcher.match(caps)?.id)
        assertNull(matcher.match(KeyCombo(KeyEvent.KEYCODE_INSERT)))
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
            rewriteCombo = { combo, pressed -> rewriteModifiers(combo, pressed, listOf(shiftToAlt)) }
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
            rewriteCombo = { combo, pressed -> rewriteModifiers(combo, pressed, listOf(shiftToAlt)) }
            onRemap = { original, effective, _ ->
                if (effective == original) false else { emitted += effective; true }
            }
        }

        pipeline.dispatch(down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON))
        assertTrue(pipeline.dispatch(down(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)))

        assertEquals(listOf(KeyCombo(KeyEvent.KEYCODE_TAB, KeyCombo.MOD_ALT)), emitted)
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
