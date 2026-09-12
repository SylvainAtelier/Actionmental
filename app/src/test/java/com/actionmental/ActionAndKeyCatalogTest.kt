package com.actionmental

import android.view.KeyEvent
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.key.KeyCatalog
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.platform.PackageBackend
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖这次改动引入的三条约定：
 * 方向一律用角度表述、按键目录可以脱离键盘选键、新动作能原样存回来。
 */
class ActionAndKeyCatalogTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `强制方向按角度升序且带角度前缀`() {
        assertEquals(listOf(0, 90, 180, 270), RotationMode.forced.map { it.degrees })
        RotationMode.forced.forEach { mode ->
            assertTrue(mode.label.startsWith(mode.degrees.toString() + "°"))
            assertEquals(mode, RotationMode.fromDegrees(mode.degrees!!))
        }
    }

    @Test
    fun `非强制模式没有角度`() {
        assertNull(RotationMode.NORMAL.degrees)
        assertNull(RotationMode.CUSTOM.degrees)
        assertEquals("系统默认", RotationMode.NORMAL.label)
    }

    @Test
    fun `旋转动作全部收在一个分组里`() {
        val rotation = ActionCatalog.groups.first { it.id == "rotation" }
        assertEquals(
            RotationMode.forced.size + 3,
            rotation.actions.size,
        )
        assertTrue(rotation.actions.all { it.category == com.actionmental.core.action.ActionCategory.ROTATION })
    }

    @Test
    fun `屏幕常亮是三条无需特权的动作`() {
        val awake = ActionCatalog.groups.first { it.id == "awake" }
        assertEquals(Action.Awake.Op.entries.size, awake.actions.size)
        assertTrue(awake.actions.none { it.requiresPrivilege })
        assertEquals(
            listOf("AWAKE_ON", "AWAKE_OFF", "AWAKE_TOGGLE"),
            awake.actions.map { it.technical },
        )
    }

    @Test
    fun `屏幕常亮的绑定能原样存回来`() {
        val shortcut = binding("awake", Action.Awake(Action.Awake.Op.TOGGLE))
        val restored = json.decodeFromString(
            Shortcut.serializer(),
            json.encodeToString(Shortcut.serializer(), shortcut),
        )

        assertEquals(shortcut, restored)
    }

    @Test
    fun `要另开编辑流程的动作不带固定清单`() {
        val direct = ActionCatalog.groups.filter { it.direct }.map { it.id }
        assertEquals(
            listOf(ActionCatalog.GROUP_APP, ActionCatalog.GROUP_URL, ActionCatalog.GROUP_SHELL),
            direct,
        )
        assertTrue(ActionCatalog.groups.filter { it.direct }.all { it.actions.isEmpty() })
    }

    @Test
    fun `按键目录不重复且能反查分组`() {
        val all = KeyCatalog.allKeyCodes
        assertEquals(all.size, all.distinct().size)
        assertEquals("letter", KeyCatalog.groupOf(KeyEvent.KEYCODE_A)?.id)
        assertEquals("function", KeyCatalog.groupOf(KeyEvent.KEYCODE_F5)?.id)
        assertEquals("modifier", KeyCatalog.groupOf(KeyEvent.KEYCODE_CTRL_LEFT)?.id)
        assertNull(KeyCatalog.groupOf(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `修饰键与锁定键都能作为主键选出来`() {
        val modifiers = KeyCatalog.groups.first { it.id == "modifier" }.keyCodes
        assertTrue(KeyCombo.MODIFIER_KEYCODES.all { it in modifiers })
        assertTrue(KeyCombo.LOCK_KEYCODES.all { it in modifiers })
        assertEquals("modifier", KeyCatalog.groupOf(KeyEvent.KEYCODE_CAPS_LOCK)?.id)
        assertEquals("modifier", KeyCatalog.groupOf(KeyEvent.KEYCODE_META_LEFT)?.id)
    }

    @Test
    fun `锁定键不再算修饰键，所以按下即可触发`() {
        assertTrue(!KeyCombo.isModifier(KeyEvent.KEYCODE_CAPS_LOCK))
        assertTrue(KeyCombo.isLock(KeyEvent.KEYCODE_CAPS_LOCK))
        assertEquals(KeyCatalog.TriggerMode.PRESS, KeyCatalog.triggerMode(KeyEvent.KEYCODE_CAPS_LOCK))
        assertEquals(KeyCatalog.TriggerMode.TAP, KeyCatalog.triggerMode(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test
    fun `修饰键自己那一位会被剔除`() {
        assertEquals(KeyCombo.MOD_CTRL, KeyCombo.selfModifierMask(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(KeyCombo.MOD_META, KeyCombo.selfModifierMask(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(0, KeyCombo.selfModifierMask(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    @Test
    fun `打开链接与指定 Activity 的绑定能原样存回来`() {
        val shortcuts = listOf(
            binding("url", Action.OpenUrl("https://example.com", "示例")),
            binding(
                "activity",
                Action.LaunchApp(
                    packageName = "com.example",
                    activity = "com.example.DeepActivity",
                    appLabel = "示例应用",
                    activityLabel = "DeepActivity",
                ),
            ),
        )

        val restored = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Shortcut.serializer()),
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Shortcut.serializer()),
                shortcuts,
            ),
        )

        assertEquals(shortcuts, restored)
        assertNotNull((restored[1].action as Action.LaunchApp).activity)
        assertTrue(!(restored[1].action as Action.LaunchApp).isDefaultEntry)
    }

    @Test
    fun `链接补全 scheme 并挡掉不是链接的输入`() {
        assertEquals("https://example.com/page", PackageBackend.normalizeUrl("example.com/page"))
        assertEquals("http://a.test", PackageBackend.normalizeUrl(" http://a.test "))
        assertEquals("mailto:user@example.com", PackageBackend.normalizeUrl("mailto:user@example.com"))
        assertNull(PackageBackend.normalizeUrl("这不是链接"))
        assertNull(PackageBackend.normalizeUrl("two words.com"))
        assertNull(PackageBackend.normalizeUrl("   "))
    }

    private fun binding(id: String, action: Action) = Shortcut(
        id = id,
        combo = KeyCombo(KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL),
        action = action,
    )
}
