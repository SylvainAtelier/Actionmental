package com.actionmental.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionmental.core.key.KeyCatalog
import com.actionmental.core.key.SystemKeyPolicy
import android.os.Build
import com.actionmental.core.key.KeyChannel
import com.actionmental.core.key.KeyRouting
import com.actionmental.core.remap.KeyRemap
import com.actionmental.ui.components.DataRow
import com.actionmental.core.remap.isModifierRemap
import com.actionmental.ui.RemapViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmComboField
import com.actionmental.ui.components.AmKeyPickerSheet
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.KeyComboRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.components.combinedClickableCompat
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 键位映射（与快捷键并列的第二个模块）。
 *
 * 快捷键把一颗键换成一个动作，映射把一颗键换成另一颗键 —— 两件事分开放，
 * 是因为它们的失败方式完全不同：快捷键由应用自己执行，映射必须把事件发回系统。
 * 只有 Shizuku 能把任意键发回任意窗口；它不在时按目标键分流到几条无特权的出口，
 * 各自只覆盖一部分。所以这一页最上面永远先回答「现在能不能用、用到什么程度」。
 */
@Composable
fun RemapScreen(modifier: Modifier = Modifier) {
    val c = amColors
    val vm: RemapViewModel = viewModel()
    val remaps by vm.remaps.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val injectError by vm.injectError.collectAsStateWithLifecycle()
    val selfTest by vm.selfTest.collectAsStateWithLifecycle()
    val injectReady = status.shizuku.usable && status.shizuku.serviceBound

    // 被快捷键占用的源键：快捷键优先，映射不会生效，必须让用户看见
    val shadowed = remember(remaps, shortcuts) {
        val bound = shortcuts.filter { it.enabled }.map { it.combo }.toSet()
        remaps.filter { it.from in bound }.map { it.id }.toSet()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 480.dp

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = AmSpace.screen, end = AmSpace.screen, top = AmSpace.screen)) {
                ScreenTitle("键位映射", "KEY REMAP · " + remaps.size) {
                    AmSecondaryButton("新建映射", vm::create, accent = true)
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(AmSpace.screen),
                verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
            ) {
                item {
                    InjectionStatusCard(
                        usable = status.shizuku.usable,
                        bound = status.shizuku.serviceBound,
                        conclusion = status.shizuku.conclusion,
                        accessibilityConnected = status.accessibilityConnected,
                        injectError = injectError,
                        selfTest = selfTest,
                        onSelfTest = vm::runSelfTest,
                    )
                }

                items(remaps, key = { it.id }) { remap ->
                    RemapRow(
                        remap = remap,
                        compact = compact,
                        shadowed = remap.id in shadowed,
                        degradedHint = if (injectReady) null else degradedHint(remap),
                        onClick = { vm.edit(remap.id) },
                        onToggle = { vm.setEnabled(remap.id, it) },
                        onDelete = { vm.delete(remap.id) },
                    )
                }

                if (remaps.isEmpty()) {
                    item {
                        AmCard(Modifier.fillMaxWidth()) {
                            Text(
                                "还没有映射。挑一条常见的直接建，或者点右上角自己配。",
                                style = AmType.secondary,
                                color = c.inkMid,
                            )
                            Spacer(Modifier.height(AmSpace.s2))
                            AmLabel("常见改法 · PRESETS")
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                vm.presets.forEach { preset ->
                                    AmChip(preset.label, selected = false) { vm.addPreset(preset) }
                                }
                            }
                        }
                    }
                }

                item { HowItWorksCard() }
            }
        }
    }

    draft?.let { RemapEditorSheet(vm, it) }
}

// --- 列表 -------------------------------------------------------------------

@Composable
private fun RemapRow(
    remap: KeyRemap,
    compact: Boolean,
    shadowed: Boolean,
    /** 没有注入时这一条会怎样；null 表示照常（或 Shizuku 就绪）。 */
    degradedHint: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = amColors
    var menuOpen by remember { mutableStateOf(false) }

    AmCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick, onLongClick = { menuOpen = true }),
        alert = shadowed,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (compact) {
                    KeyComboRow(remap.from)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "映射为",
                            tint = c.inkFaint,
                            modifier = Modifier.height(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        KeyComboRow(remap.to)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeyComboRow(remap.from)
                        Spacer(Modifier.width(AmSpace.s1))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "映射为",
                            tint = c.inkFaint,
                            modifier = Modifier.height(14.dp),
                        )
                        Spacer(Modifier.width(AmSpace.s1))
                        KeyComboRow(remap.to)
                    }
                }
            }
            if (remap.isModifierRemap) {
                AmLabel("修饰键", color = c.accent)
                Spacer(Modifier.width(6.dp))
            }
            AmSwitch(checked = remap.enabled, onCheckedChange = onToggle)
        }

        if (remap.isModifierRemap) {
            Spacer(Modifier.height(4.dp))
            Text(
                "按住时所有组合键都按 " + remap.to.toString() + " 计算",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }

        if (degradedHint != null) {
            Spacer(Modifier.height(4.dp))
            Text(degradedHint, style = AmType.secondary, color = c.inkMid)
        }

        if (shadowed) {
            Spacer(Modifier.height(4.dp))
            Text(
                "这颗键已经绑给快捷键了，快捷键优先，映射不会生效。",
                style = AmType.secondary,
                color = c.accent,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (remap.enabled) "禁用" else "启用") },
                onClick = { onToggle(!remap.enabled); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("删除", color = c.accent) },
                onClick = { onDelete(); menuOpen = false },
            )
        }
    }
}

/** 没有 Shizuku 注入时，这一条映射落到哪条路上。 */
private fun degradedHint(remap: KeyRemap): String {
    val target = if (remap.isModifierRemap) null else remap.to
    val channel = if (target == null) {
        if (Build.VERSION.SDK_INT >= 33) KeyChannel.INPUT_CONNECTION else null
    } else {
        KeyRouting.fallbackChannel(target, Build.VERSION.SDK_INT)
    }
    return when (channel) {
        KeyChannel.GLOBAL_ACTION, KeyChannel.MEDIA -> "无 Shizuku · 经" + channel.label + "照常生效"
        KeyChannel.INPUT_CONNECTION -> "无 Shizuku · 只在输入框获得焦点时生效"
        else -> "无 Shizuku · 这一条不生效（原键照常）"
    }
}

@Composable
private fun InjectionStatusCard(
    usable: Boolean,
    bound: Boolean,
    conclusion: String,
    accessibilityConnected: Boolean,
    injectError: String?,
    selfTest: String?,
    onSelfTest: () -> Unit,
) {
    val c = amColors
    val injectReady = usable && bound
    val inputChannel = Build.VERSION.SDK_INT >= 33
    val healthy = accessibilityConnected && injectReady && injectError == null
    AmCard(Modifier.fillMaxWidth(), alert = !healthy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    healthy -> c.ok
                    accessibilityConnected -> c.warn
                    else -> c.accent
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !accessibilityConnected -> "映射不可用：无障碍服务未连接"
                        injectError != null -> "上次发送失败"
                        injectReady -> "注入链路就绪"
                        else -> "降级运行 · 按目标键分流"
                    },
                    style = AmType.body,
                    color = if (healthy) c.ink else c.accent,
                )
                Text(
                    injectError ?: if (injectReady) {
                        "任意键都经 Shizuku 发回系统，系统级组合键也认。"
                    } else {
                        "Shizuku " + (if (usable) "特权服务未连接" else conclusion) +
                            "。发不出去的键不会被拦下，原键照常工作。"
                    },
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
            AmSecondaryButton("自检", onSelfTest, enabled = usable)
        }
        Spacer(Modifier.height(AmSpace.s1))
        DataRow("Shizuku 注入", if (injectReady) "就绪" else if (usable) "特权服务未连接" else conclusion)
        DataRow("系统键 / 媒体键", if (accessibilityConnected) "可用 · 返回、主页、截屏、锁屏、方向键、音量…" else "不可用")
        DataRow(
            "无障碍输入通道",
            when {
                !inputChannel -> "需要 Android 13"
                !accessibilityConnected -> "不可用"
                else -> "可用 · 仅输入框获得焦点时"
            },
        )
        if (selfTest != null) {
            Spacer(Modifier.height(6.dp))
            Text(selfTest, style = AmType.data, color = c.inkMid)
        }
    }
}

@Composable
private fun HowItWorksCard() {
    val c = amColors
    AmCard(Modifier.fillMaxWidth()) {
        AmLabel("怎么工作的 · HOW IT WORKS")
        Spacer(Modifier.height(6.dp))
        Text(
            "源键的按下、连发、抬起整个被拦下，换成目标键发回系统。前台应用与输入法收不到原键，所以短按原键的行为（比如切中英文）也一并消失。",
            style = AmType.secondary,
            color = c.inkMid,
        )
        Spacer(Modifier.height(4.dp))
        Text("同一颗键同时有快捷键和映射时，快捷键优先，映射不生效。", style = AmType.secondary, color = c.inkMid)
        Spacer(Modifier.height(4.dp))
        Text(
            "目标是修饰键时（左 Shift → 左 Alt、Caps → 左 Ctrl）走的是另一条路：源键变成一颗修饰键，按住期间所有组合键的修饰位都被改写，绑在 Alt + X 上的快捷键会被 左 Shift + X 触发。",
            style = AmType.secondary,
            color = c.inkMid,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "没有 Shizuku 时，系统键（返回、主页、最近任务、截屏、锁屏、方向键）与媒体、音量键经系统接口照常生效；其余的键在 Android 13 以上经无障碍输入通道发给当前输入框，没有输入框获得焦点时不生效。任何一条路都走不通时映射不生效，但也绝不吞键 —— 原键照常工作，事件流里会写明原因。",
            style = AmType.secondary,
            color = c.inkMid,
        )
    }
}

// --- 编辑器 -----------------------------------------------------------------

@Composable
private fun RemapEditorSheet(vm: RemapViewModel, draft: RemapViewModel.Draft) {
    val c = amColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recording by vm.recording.collectAsStateWithLifecycle()
    val recordingField by vm.recordingField.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<RemapViewModel.Field?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recording?.combo) { if (recording?.combo != null) vm.acceptRecorded() }

    val replacing = vm.replacing()

    ModalBottomSheet(
        onDismissRequest = vm::close,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = AmShape.sheet, topEnd = AmShape.sheet),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 620.dp
            Column(
                Modifier
                    // 宽屏上不要把两张卡拉成横幅，读起来会散
                    .widthIn(max = 760.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AmSpace.screen),
            ) {
                Text(
                    if (draft.isNew) "新建映射" else "编辑映射",
                    style = AmType.cardTitle,
                    color = c.ink,
                )
                Text("按下左边这颗键，系统收到的是右边那颗。", style = AmType.secondary, color = c.inkMid)
                Spacer(Modifier.height(AmSpace.s3))

                val source: @Composable (Modifier) -> Unit = { m ->
                    AmComboField(
                        label = "按下的键 · FROM",
                        combo = draft.from,
                        recording = recording != null && recordingField == RemapViewModel.Field.FROM,
                        onStartRecording = { vm.startRecording(RemapViewModel.Field.FROM) },
                        onCancelRecording = vm::cancelRecording,
                        onOpenCatalog = {
                            openGroup = draft.from?.let { KeyCatalog.groupOf(it.keyCode)?.id }
                            picking = RemapViewModel.Field.FROM
                        },
                        onToggleModifier = { vm.toggleModifier(RemapViewModel.Field.FROM, it) },
                        modifier = m,
                        recordingLabel = "RECORDING · 按下要映射的键",
                        recordHint = "按下要映射的键",
                    )
                }
                val target: @Composable (Modifier) -> Unit = { m ->
                    AmComboField(
                        label = "实际发出的键 · TO",
                        combo = draft.to,
                        recording = recording != null && recordingField == RemapViewModel.Field.TO,
                        onStartRecording = { vm.startRecording(RemapViewModel.Field.TO) },
                        onCancelRecording = vm::cancelRecording,
                        onOpenCatalog = {
                            openGroup = draft.to?.let { KeyCatalog.groupOf(it.keyCode)?.id }
                            picking = RemapViewModel.Field.TO
                        },
                        onToggleModifier = { vm.toggleModifier(RemapViewModel.Field.TO, it) },
                        modifier = m,
                        recordingLabel = "RECORDING · 按下要变成的键",
                        recordHint = "按下要变成的键",
                    )
                }

                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        source(Modifier.weight(1f))
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = c.inkFaint,
                            )
                        }
                        target(Modifier.weight(1f))
                    }
                } else {
                    source(Modifier.fillMaxWidth())
                    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = c.inkFaint)
                    }
                    target(Modifier.fillMaxWidth())
                }

                if (isModifierRemap(draft.from, draft.to)) {
                    Spacer(Modifier.height(AmSpace.s2))
                    AmCard(Modifier.fillMaxWidth()) {
                        AmLabel("修饰键映射 · MODIFIER REMAP", color = c.accent)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "按住 " + draft.from.toString() + " 期间，所有组合键都按 " +
                                draft.to.toString() + " 计算：绑在它上面的快捷键会被触发，" +
                                "没有绑定的组合会按改写后的样子发回系统。",
                            style = AmType.secondary,
                            color = c.inkMid,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "源键整个被替换：短按它发出的也是 " + draft.to.toString() +
                                "，输入法收不到原键。",
                            style = AmType.secondary,
                            color = c.inkFaint,
                        )
                    }
                }

                // 源键带 Meta：系统在事件入队时就把它处理掉了，映射拦得住原键，拦不住系统那份动作
                SystemKeyPolicy.reservation(draft.from)?.let { note ->
                    Spacer(Modifier.height(AmSpace.s2))
                    AmCard(Modifier.fillMaxWidth(), alert = true) {
                        AmLabel("系统会抢先执行 · SYSTEM RESERVED", color = c.warn)
                        Spacer(Modifier.height(4.dp))
                        Text(note, style = AmType.secondary, color = c.inkMid)
                    }
                }

                if (replacing != null) {
                    Spacer(Modifier.height(AmSpace.s2))
                    AmCard(Modifier.fillMaxWidth(), alert = true) {
                        AmLabel("会替换现有映射", color = c.accent)
                        Text(replacing.label, style = AmType.data, color = c.inkMid)
                    }
                }
                if (draft.from != null && draft.to != null && draft.from == draft.to) {
                    Spacer(Modifier.height(AmSpace.s2))
                    Text("两边是同一颗键，映射没有意义。", style = AmType.secondary, color = c.accent)
                }

                Spacer(Modifier.height(AmSpace.s3))
                Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                    AmPrimaryButton(
                        "保存",
                        onClick = vm::save,
                        enabled = draft.complete,
                        modifier = Modifier.weight(1f),
                    )
                    if (!draft.isNew) {
                        AmSecondaryButton("删除", { vm.delete(draft.id); vm.close() }, accent = true)
                    }
                    AmSecondaryButton("取消", vm::close)
                }
                Spacer(Modifier.height(AmSpace.s4))
            }
        }
    }

    picking?.let { field ->
        AmKeyPickerSheet(
            selectedKeyCode = vm.comboOf(field)?.keyCode,
            openGroupId = openGroup,
            onOpenGroup = { openGroup = it },
            onPick = { keyCode -> vm.setMainKey(field, keyCode); picking = null },
            onDismiss = { picking = null },
            title = if (field == RemapViewModel.Field.FROM) "选择要映射的键" else "选择要变成的键",
            subtitle = if (field == RemapViewModel.Field.FROM) {
                "Ctrl / Alt / Shift / Meta 作为源键时只能轻点触发，原键仍会到达应用"
            } else {
                "发回系统的键，可以是任意一颗，包括修饰键"
            },
        )
    }
}
