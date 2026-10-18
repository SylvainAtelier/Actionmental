package com.actionmental.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.action.Action
import com.actionmental.ui.ShortcutEditorViewModel
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmComboField
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.theme.AmSpace

/** 确认过的输入法快捷键，点一下直接填进去。别家的没有公开说法，不往这里猜。 */
private val PRESETS = listOf(
    "Gboard 语音" to Action.SendKey.GBOARD_VOICE.combo,
)

/**
 * 「发送组合键」：按下快捷键，系统收到的是另一个组合键。
 *
 * 目标键和快捷键一样可以录、可以从列表挑；录制走同一条管线，录到的落在 [ShortcutEditorViewModel.sendKeyCombo]。
 * 主键从列表挑时要叠一层按键目录，所以弹层的开合由编辑页管：[onOpenCatalog] 回来后这里接着显示。
 */
@Composable
internal fun SendKeySheet(
    current: Action.SendKey?,
    vm: ShortcutEditorViewModel,
    onOpenCatalog: () -> Unit,
    onConfirm: (Action.SendKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val combo by vm.sendKeyCombo.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val recordingHere by vm.recordingSendKey.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    val ready = combo?.let { it.keyCode != KeyEvent.KEYCODE_UNKNOWN } == true

    InputSheet(
        title = "发送组合键",
        subtitle = "按下快捷键时，以实体键盘的身份发出另一个组合键，输入法也收得到。需要 Shizuku。",
        confirmText = "使用这个组合键",
        confirmEnabled = ready,
        footnote = "修饰键逐颗按下、倒序抬起，和手按的一样",
        onConfirm = { combo?.let { onConfirm(Action.SendKey(it, title.trim())) } },
        onDismiss = {
            vm.cancelRecording()
            onDismiss()
        },
    ) {
        AmComboField(
            label = "发出的组合键 · OUTPUT",
            combo = combo,
            recording = recording != null && recordingHere,
            onStartRecording = vm::recordSendKey,
            onCancelRecording = vm::cancelRecording,
            onOpenCatalog = onOpenCatalog,
            onToggleModifier = vm::toggleSendKeyModifier,
            modifier = Modifier.fillMaxWidth(),
            recordingLabel = "RECORDING · 按下要发出的组合键",
            recordHint = "按下要发出的组合键",
        )
        Spacer(Modifier.height(AmSpace.s2))
        AmLabel("常用 · PRESETS")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { (name, preset) ->
                AmChip(text = name, selected = combo == preset) {
                    vm.setSendKey(preset)
                    if (title.isBlank() && preset == Action.SendKey.GBOARD_VOICE.combo) {
                        title = Action.SendKey.GBOARD_VOICE.title
                    }
                }
            }
        }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("名称 · 可选", title, "列表里显示的名字") { title = it }
    }
}
