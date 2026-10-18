package com.actionmental.core.remap

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyboardDevice
import com.actionmental.core.shortcut.DeviceScope
import kotlinx.serialization.Serializable

/**
 * 一条键位映射：按下 [from]，系统收到的是 [to]。
 *
 * 和快捷键是两件事：快捷键把一颗键换成一个**动作**，映射把一颗键换成**另一颗键**。
 * 前者由应用自己执行，后者要把事件重新发回系统，所以必须有 Shizuku。
 *
 * 替换是整颗的：源键的按下、连发、抬起都被拦下，前台应用与输入法根本收不到它，
 * 所以「短按左 Shift 切中英文」这类由**原键**触发的行为也会一并消失。
 *
 * 唯一性由 ([from], [deviceScope]) 决定 —— 一颗键（连同修饰键）在同一范围内只能有一个去向。
 *
 * [deviceScope] 限定只对某把键盘生效。同一颗源键上，指定键盘的规则压过「所有键盘」的规则；
 * 指定键盘上 [from] == [to] 表示「保持原键」：它不发出任何东西，只是挡住全局规则。
 */
@Serializable
data class KeyRemap(
    val id: String,
    val from: KeyCombo,
    val to: KeyCombo,
    val enabled: Boolean = true,
    val deviceScope: DeviceScope = DeviceScope.ALL,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /**
     * 映射成修饰键（左 Shift → 左 Alt、Caps → 左 Ctrl）。
     *
     * 这类映射不是「按一下发另一颗键」——那样只会发出一次孤零零的 Alt，
     * 而修饰键的意义恰恰在于**按住**。所以它改写的是修饰位：
     * 按住源键期间，其余每一次按键的修饰位都按目标修饰键计算，
     * 快捷键也用改写后的组合去匹配。源键自己不发出任何东西。
     */
    val isModifierRemap: Boolean get() = isModifierRemap(from, to)

    /** 这把键盘上不要映射：专属规则 + 两边相同。 */
    val isPassthrough: Boolean get() = !deviceScope.isAll && from == to

    val label: String get() = from.toString() + " → " + to.toString()
}

/** 编辑器还没保存时也要判断，所以判定条件放在外面。 */
fun isModifierRemap(from: KeyCombo?, to: KeyCombo?): Boolean =
    from != null && to != null && from != to &&
        from.modifiers == 0 && to.isModifierKey && to.modifiers == 0

/**
 * 按当前按住的键，把组合键的修饰位改写成映射之后的样子。
 *
 * 纯函数，没有状态：管线把「按下了哪些键」交进来，它算出「系统应该看到的组合」。
 * 只有当同族的另一颗键没被按住时才摘掉原来的位 —— 只映射了左 Shift 的话，
 * 右 Shift 按着时 SHIFT 仍然成立。
 */
fun rewriteModifiers(
    combo: KeyCombo,
    pressedKeyCodes: Set<Int>,
    rules: List<KeyRemap>,
): KeyCombo {
    if (rules.isEmpty()) return combo
    var removed = 0
    var added = 0
    for (rule in rules) {
        val source = rule.from.keyCode
        if (source !in pressedKeyCodes) continue
        // 源键自己按下的那一次不算：它是这条规则的主语，不是被它修饰的对象
        if (source == combo.keyCode) continue
        added = added or KeyCombo.selfModifierMask(rule.to.keyCode)
        val sibling = KeyCombo.siblingModifier(source)
        if (sibling == null || sibling !in pressedKeyCodes) {
            removed = removed or KeyCombo.selfModifierMask(source)
        }
    }
    if (removed == 0 && added == 0) return combo
    return combo.copy(modifiers = (combo.modifiers and removed.inv()) or added)
}

/**
 * 映射匹配器。
 *
 * 与 ShortcutMatcher 同构：按键线程只读一份不可变索引，仓库变化时整体换掉引用。
 *
 * 同一颗源键先查指定键盘的规则，再查「所有键盘」的规则；专属规则是「保持原键」时返回 null，
 * 等于这把键盘上这颗键不映射。同一把键盘上有多条专属规则（手工导入才会出现）取最近改的那条。
 */
class KeyRemapMatcher {

    private class Index(
        val global: Map<KeyCombo, KeyRemap>,
        val scoped: Map<KeyCombo, List<KeyRemap>>,
        /** 所有修饰键映射的源键。每一次按键都要问，所以空表要能一眼判断出来。 */
        val modifierSources: List<KeyCombo>,
    )

    @Volatile
    private var index = Index(emptyMap(), emptyMap(), emptyList())

    fun update(remaps: List<KeyRemap>) {
        val enabled = remaps.filter { it.enabled }
        val (all, scoped) = enabled.partition { it.deviceScope.isAll }
        index = Index(
            global = all.associateBy { it.from },
            scoped = scoped.groupBy { it.from },
            modifierSources = enabled.filter { it.isModifierRemap }.map { it.from }.distinct(),
        )
    }

    /** @return 这把键盘上这颗键该用的规则；没有，或被「保持原键」挡住时为 null。 */
    fun match(combo: KeyCombo, device: KeyboardDevice): KeyRemap? {
        val idx = index
        var best: KeyRemap? = null
        idx.scoped[combo]?.forEach { candidate ->
            val current = best
            if (candidate.deviceScope.matches(device.descriptor) &&
                (current == null || candidate.updatedAt > current.updatedAt)
            ) {
                best = candidate
            }
        }
        return (best ?: idx.global[combo])?.takeUnless { it.isPassthrough }
    }

    /** 按住的修饰键被映射时，返回系统应该看到的组合键。只看事件所在键盘适用的规则。 */
    fun rewrite(combo: KeyCombo, pressedKeyCodes: Set<Int>, device: KeyboardDevice): KeyCombo {
        val sources = index.modifierSources
        if (sources.isEmpty()) return combo
        val rules = sources.mapNotNull { source -> match(source, device)?.takeIf { it.isModifierRemap } }
        return rewriteModifiers(combo, pressedKeyCodes, rules)
    }
}

/** 空列表时给的几条常见改法，一键就能建。 */
object RemapPresets {

    data class Preset(val label: String, val from: KeyCombo, val to: KeyCombo)

    val common: List<Preset> = listOf(
        Preset("Caps → Esc", KeyCombo(KeyEvent.KEYCODE_CAPS_LOCK), KeyCombo(KeyEvent.KEYCODE_ESCAPE)),
        Preset("Caps → 左 Ctrl", KeyCombo(KeyEvent.KEYCODE_CAPS_LOCK), KeyCombo(KeyEvent.KEYCODE_CTRL_LEFT)),
        Preset(
            "左 Shift → 左 Alt",
            KeyCombo(KeyEvent.KEYCODE_SHIFT_LEFT),
            KeyCombo(KeyEvent.KEYCODE_ALT_LEFT),
        ),
        Preset("Caps → Backspace", KeyCombo(KeyEvent.KEYCODE_CAPS_LOCK), KeyCombo(KeyEvent.KEYCODE_DEL)),
        Preset(
            "Ctrl + H → Backspace",
            KeyCombo(KeyEvent.KEYCODE_H, KeyCombo.MOD_CTRL),
            KeyCombo(KeyEvent.KEYCODE_DEL),
        ),
        Preset("Insert → Delete", KeyCombo(KeyEvent.KEYCODE_INSERT), KeyCombo(KeyEvent.KEYCODE_FORWARD_DEL)),
    )
}
