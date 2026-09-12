package com.actionmental.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.actionmental.core.key.KeyCatalog
import com.actionmental.core.key.KeyCombo
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

private val MODIFIERS = listOf(
    "Ctrl" to KeyCombo.MOD_CTRL,
    "Alt" to KeyCombo.MOD_ALT,
    "Shift" to KeyCombo.MOD_SHIFT,
    "Meta" to KeyCombo.MOD_META,
)

/**
 * 组合键输入卡。快捷键编辑与键位映射共用一个。
 *
 * 两条等价的输入路径并排放着：录制（按下就是它）与目录选择（手上这把键盘没有的键也能选）。
 * 修饰键是勾出来的，不是按出来的 —— 这样「Ctrl 单独绑定」与「Ctrl 作为修饰键」
 * 在界面上就是两件不会混淆的事。
 */
@Composable
fun AmComboField(
    label: String,
    combo: KeyCombo?,
    recording: Boolean,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenCatalog: () -> Unit,
    onToggleModifier: (Int) -> Unit,
    modifier: Modifier = Modifier,
    recordingLabel: String = "RECORDING · 正在录制，原快捷键已暂停",
    recordHint: String = "按下要绑定的组合键",
    showRaw: Boolean = false,
) {
    val c = amColors
    val hasMainKey = combo != null && combo.keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN

    AmCard(modifier, alert = recording) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                StatusDot(c.accent)
                AmLabel(recordingLabel, color = c.accent)
            } else {
                AmLabel(label)
            }
        }
        Spacer(Modifier.height(AmSpace.s2))

        if (hasMainKey) {
            KeyComboRow(combo, large = true)
            if (showRaw) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "keyCode " + combo.keyCode + " · modifiers 0x" + combo.modifiers.toString(16),
                    style = AmType.data,
                    color = c.inkFaint,
                )
            }
            // 修饰键必须让位给组合键，触发方式和普通键不一样，这里说清楚
            val mode = KeyCatalog.triggerMode(combo.keyCode)
            if (mode == KeyCatalog.TriggerMode.TAP) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmLabel(mode.label, color = c.warn)
                    Spacer(Modifier.width(6.dp))
                    Text(mode.hint, style = AmType.secondary, color = c.inkMid)
                }
            }
        } else {
            val pending = remember(combo) {
                (combo?.modifierTokens() ?: emptyList()) + if (recording) recordHint else "选择主键"
            }
            KeyTokensRow(pending, large = true, highlightLast = false)
        }

        Spacer(Modifier.height(AmSpace.s2))
        AmLabel("修饰键 · MODIFIERS")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 主键就是这颗修饰键时，再把它当修饰键勾上就成了永远按不出来的组合
            val selfMask = combo?.let { KeyCombo.selfModifierMask(it.keyCode) } ?: 0
            MODIFIERS.forEach { (name, mask) ->
                AmChip(
                    text = name,
                    selected = combo != null && combo.modifiers and mask != 0,
                    enabled = mask != selfMask,
                ) { onToggleModifier(mask) }
            }
        }

        Spacer(Modifier.height(AmSpace.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            if (recording) {
                AmSecondaryButton("取消录制", onCancelRecording)
            } else {
                AmSecondaryButton(
                    if (hasMainKey) "重新录制" else "开始录制",
                    onStartRecording,
                    accent = true,
                )
                AmSecondaryButton("从列表选择", onOpenCatalog)
            }
        }
    }
}

/** 只取修饰键部分，用于「还没定主键」的中间态。 */
fun KeyCombo.modifierTokens(): List<String> = buildList {
    if (ctrl) add("Ctrl")
    if (alt) add("Alt")
    if (shift) add("Shift")
    if (meta) add("Meta")
}

/**
 * 按键目录弹层。走的是全应用统一的二级菜单，一级是分组，二级是具体的键。
 */
@Composable
fun AmKeyPickerSheet(
    selectedKeyCode: Int?,
    openGroupId: String?,
    onOpenGroup: (String?) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择主键",
    subtitle: String = "修饰键在上一层勾选；Ctrl / Alt / Shift / Meta 单独绑定时改为轻点触发",
) {
    val groups = remember(selectedKeyCode) {
        KeyCatalog.groups.map { group ->
            PickerGroup(
                id = group.id,
                title = group.label,
                subtitle = group.technical,
                badge = group.keyCodes.size.toString(),
                items = group.keyCodes.map { keyCode ->
                    val name = KeyCombo.keyLabel(keyCode)
                    val tap = KeyCatalog.triggerMode(keyCode) == KeyCatalog.TriggerMode.TAP
                    PickerItem(
                        id = keyCode.toString(),
                        title = name,
                        subtitle = "keyCode " + keyCode,
                        badge = if (tap) "轻点" else null,
                        tone = PickerTone.WARN,
                        selected = keyCode == selectedKeyCode,
                        keyTokens = listOf(name),
                    )
                },
            )
        }
    }

    AmPickerSheet(
        title = title,
        subtitle = subtitle,
        groups = groups,
        openGroupId = openGroupId,
        onOpenGroup = onOpenGroup,
        searchPlaceholder = "搜索键名或 keyCode…",
        emptyHint = "没有匹配的按键",
        onPick = { _, itemId -> itemId?.toIntOrNull()?.let(onPick) },
        onDismiss = onDismiss,
    )
}

/** 一个只读的组合键展示块，用于列表行。 */
@Composable
fun ComboChip(combo: KeyCombo, modifier: Modifier = Modifier) {
    Column(modifier) { KeyComboRow(combo) }
}
