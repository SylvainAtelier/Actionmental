package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.SystemKeyPolicy
import com.actionmental.ui.i18n.AppTranslations
import com.actionmental.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meta 被系统抢先执行，这是 Android 15 起的框架行为，不是本应用的 bug。
 * 这里只钉住「哪些组合要出提示」，免得以后有人把 Meta 悄悄当成普通修饰键。
 */
class SystemKeyPolicyTest {

    @Test
    fun `带 Meta 的组合会给出提示`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_A, KeyCombo.MOD_META)

        assertEquals(SystemKeyPolicy.META_COMBO, SystemKeyPolicy.reservation(combo))
        assertTrue(SystemKeyPolicy.usesMeta(combo))
    }

    @Test
    fun `单独一颗 Meta 是另一条提示`() {
        assertEquals(
            SystemKeyPolicy.LONE_META,
            SystemKeyPolicy.reservation(KeyCombo(KeyEvent.KEYCODE_META_LEFT)),
        )
        // 已经压着别的修饰键时它就是个普通主键，走的是组合那条
        assertEquals(
            SystemKeyPolicy.META_COMBO,
            SystemKeyPolicy.reservation(KeyCombo(KeyEvent.KEYCODE_META_RIGHT, KeyCombo.MOD_META)),
        )
    }

    @Test
    fun `其余修饰键照常，不该跟着报警`() {
        assertNull(SystemKeyPolicy.reservation(KeyCombo(KeyEvent.KEYCODE_A, KeyCombo.MOD_CTRL)))
        assertNull(SystemKeyPolicy.reservation(KeyCombo(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertNull(SystemKeyPolicy.reservation(null))
        assertFalse(SystemKeyPolicy.usesMeta(KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_ALT)))
    }

    @Test
    fun `两条提示都有英文`() {
        listOf(SystemKeyPolicy.META_COMBO, SystemKeyPolicy.LONE_META).forEach { note ->
            val english = AppTranslations.translate(note, UserSettings.Language.ENGLISH)
            assertNotNull(english)
            assertFalse(english.contains(Regex("[\\p{IsHan}]")))
        }
    }
}
