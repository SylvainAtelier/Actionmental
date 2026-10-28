package com.actionmental.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.AppGraph
import com.actionmental.BuildConfig
import com.actionmental.data.UserSettings
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSegmented
import com.actionmental.ui.components.ProvideSettingsTypography
import com.actionmental.ui.components.SettingBlock
import com.actionmental.ui.components.SettingDetails
import com.actionmental.ui.components.SettingDivider
import com.actionmental.ui.components.SettingItem
import com.actionmental.ui.components.SettingNote
import com.actionmental.ui.components.SettingSwitch
import com.actionmental.ui.components.SettingsSection
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.components.amFocusRing
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.i18n.localize
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.DarkAmColors
import com.actionmental.ui.theme.amColors
import com.actionmental.ui.theme.amColorsFor

/** 设置（PRD 36 · 设计稿 2b 屏 3）。 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    translationState: TranslationSettingsUiState? = null,
    translationActions: TranslationSettingsActions? = null,
) {
    val c = amColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val pauseReason by vm.pauseReason.collectAsStateWithLifecycle()
    var clearShortcutsConfirmation by remember { mutableStateOf(false) }

    // 备份走系统文件选择器：不申请存储权限，拿到的也只是这一个文件的一次性许可。
    // 用户取消（uri 为 null）什么都不做 —— 那不是失败，不该弹提示。
    val backupFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::backupConfigToFile) }
    val restoreFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::restoreConfigFromFile) }

    ProvideSettingsTypography {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AmSpace.screen),
            verticalArrangement = Arrangement.spacedBy(AmSpace.s3),
        ) {
            ScreenTitle("设置", "SETTINGS · v" + BuildConfig.VERSION_NAME)

            // 暂停排在最前面。它不是一项偏好，是「现在这个应用还算不算在运行」——
            // 用户来找它的时候，多半是因为键盘正在出问题，翻不动列表。
            SettingsSection("运行 · RUNTIME", alert = paused) {
                // 这颗开关只管手动暂停。自动暂停期间它仍然显示「开」——
                // 它表示的是「用户没有按下暂停」，而不是「现在正在跑」，
                // 标题那行状态才是结论。
                SettingSwitch(
                    title = when (pauseReason) {
                        AppGraph.PauseReason.MANUAL -> "已暂停 · 手动"
                        AppGraph.PauseReason.NO_KEYBOARD -> "已暂停 · 未检测到键盘"
                        AppGraph.PauseReason.NONE -> "运行中"
                    },
                    description = if (paused) {
                        "除屏幕常亮外全部停止，占用降到最低"
                    } else {
                        "快捷键、键位映射、旋转规则、屏幕常亮都在生效"
                    },
                    titleColor = if (paused) c.warn else c.ink,
                    leading = { StatusDot(if (paused) c.warn else c.ok) },
                    checked = !settings.paused,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(paused = !on) } },
                )
                SettingDivider()
                SettingSwitch(
                    title = "没有键盘时自动暂停",
                    description = when {
                        !settings.autoPauseWithoutKeyboard -> "未启用 · 拔掉键盘后应用照常运行"
                        status.keyboardConnected ->
                            "已启用 · 当前 " + status.keyboards.size + " 个键盘，正常运行"
                        else -> "已启用 · 键盘不在，已自动停下"
                    },
                    checked = settings.autoPauseWithoutKeyboard,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(autoPauseWithoutKeyboard = on) } },
                )
                SettingDivider()
                // 长说明收起来：来这里的人多半只想拨开关
                SettingDetails("暂停后会发生什么") {
                    SettingNote(
                        "暂停后：按键原样交给前台应用（不再匹配、不再改写），前台应用不再被识别，" +
                            "屏幕旋转不再被修改，常驻前台服务与自动恢复一并停止，" +
                            "体征采样也不再进行。屏幕常亮不受暂停影响，照你的开关保持。",
                    )
                    SettingNote(
                        "无障碍授权保持原样，不需要去系统设置里关。进程仍由系统绑定着，" +
                            "但它此刻是个空壳 —— 这是不撤授权就能做到的最低占用。" +
                            "关掉这个开关，一切按你原来的设置恢复。",
                    )
                    if (settings.autoPauseWithoutKeyboard) {
                        SettingNote(
                            "自动暂停会连旋转规则一起停掉 —— 它本来不需要键盘；屏幕常亮不受影响。" +
                                "键盘掉线后等 8 秒才真的停，蓝牙键盘短暂重连不会来回折腾；" +
                                "插回来则立刻恢复。",
                        )
                    }
                }
            }

            SettingsSection("外观 · APPEARANCE") {
                SettingBlock("界面语言") {
                    val languages = listOf(UserSettings.Language.CHINESE, UserSettings.Language.ENGLISH)
                    AmSegmented(
                        options = listOf("中文", "英文"),
                        selectedIndex = languages.indexOf(settings.language).coerceAtLeast(0),
                    ) { index -> vm.updateSettings { it.copy(language = languages[index]) } }
                }
                SettingDivider()
                SettingBlock("主题") {
                    val themes = UserSettings.Theme.entries
                    AmSegmented(
                        options = themes.map { it.label },
                        selectedIndex = themes.indexOf(settings.theme),
                    ) { index -> vm.updateSettings { it.copy(theme = themes[index]) } }
                }
                SettingDivider()
                SettingBlock("强调色", description = settings.accent.label + " · 深色、浅色各用一档，自动换") {
                    AccentPicker(settings.accent) { accent -> vm.updateSettings { it.copy(accent = accent) } }
                }
                SettingDivider()
                SettingSwitch(
                    title = "显示原始键值",
                    description = "列表中常驻 keyCode / scanCode",
                    checked = settings.showRawKeyCodes,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(showRawKeyCodes = on) } },
                )
                SettingDivider()
                SettingSwitch(
                    title = "触发提示",
                    description = "快捷键启动应用时显示应用名，复制 IP / 端口时显示内容",
                    checked = settings.triggerHud,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(triggerHud = on) } },
                )
            }

            if (translationState != null && translationActions != null) {
                TranslationSettings(translationState, translationActions)
            }

            SettingsSection("服务与权限 · SERVICES") {
                SettingItem(
                    title = "无障碍键盘服务",
                    description = when {
                        status.accessibilityConnected -> "运行中 · 已授权"
                        status.accessibilityMasterSwitchOff ->
                            "系统无障碍总开关是关的 · 服务不会被绑定"
                        status.accessibilityWaitingForBind ->
                            "已授权但未连接 · 系统没有重新绑定服务"
                        else -> "未开启 · service_state = OFF"
                    },
                    stackBelow = ACTION_STACK_WIDTH,
                    leading = { StatusDot(if (status.accessibilityConnected) c.ok else c.accent) },
                    trailing = {
                        AmSecondaryButton("管理", onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        })
                    },
                )
                // 服务连不上时，这一颗替用户完成「去系统设置里关掉再打开」那一趟。
                // 需要 Shizuku：没有 shell 身份就改不了 secure 设置，那时仍然只能手动去设置里做。
                if (!status.accessibilityConnected &&
                    (status.accessibilityWaitingForBind || status.accessibilityMasterSwitchOff)
                ) {
                    SettingDivider()
                    SettingItem(
                        title = "重新连接服务",
                        description = if (status.shizuku.usable) {
                            "可以在这里直接重连，不用去系统设置"
                        } else {
                            "Shizuku 不可用，只能到系统设置里把开关关掉再打开"
                        },
                        stackBelow = ACTION_STACK_WIDTH,
                        trailing = { AmSecondaryButton("重新连接", onClick = vm::rebindAccessibility) },
                    )
                }
                SettingDivider()
                SettingItem(
                    title = "Shizuku",
                    description = status.shizuku.conclusion + " · " + status.shizuku.identity,
                    stackBelow = ACTION_STACK_WIDTH,
                    leading = { StatusDot(if (status.shizuku.usable) c.ok else c.warn) },
                    trailing = { AmSecondaryButton("重新检测", vm::refreshAll) },
                )
            }

            SettingsSection("数据 · DATA") {
                SettingItem(
                    title = "导出配置",
                    description = "快捷键与键位映射 · 复制 JSON 到剪贴板",
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton("复制", onClick = {
                            clipboard.setText(AnnotatedString(vm.exportConfig()))
                        })
                    },
                )
                SettingDivider()
                SettingItem(
                    title = "导入配置",
                    description = "快捷键与键位映射 · 从剪贴板读取 JSON",
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton("粘贴导入", onClick = {
                            clipboard.getText()?.text?.let(vm::importConfig)
                        })
                    },
                )
                SettingDivider()
                SettingItem(
                    title = "备份到文件",
                    description = "快捷键与键位映射 · 存成 JSON 文件，卸载也不会丢",
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton("备份", onClick = {
                            backupFile.launch(vm.suggestedBackupName())
                        })
                    },
                )
                SettingDivider()
                SettingItem(
                    title = "从文件恢复",
                    description = "读回之前备份的 JSON 文件",
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton("选择文件", onClick = {
                            // 放开到所有类型，不是偷懒。
                            //
                            // 有些 ROM 的文件管理器把 .json 记成 application/octet-stream 甚至
                            // text/plain，按 application/json 过滤会让用户的备份在选择器里
                            // 直接不出现 —— 而这是一条恢复路径，看不见自己的文件是最糟的结果。
                            // 选错了也不会有损失：解析不过就提示失败，一个字节都不会写进去。
                            restoreFile.launch(arrayOf("*/*"))
                        })
                    },
                )
                SettingDivider()
                SettingItem(
                    title = "重新运行引导",
                    description = "重新检查三项能力",
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton("重新运行", onClick = {
                            vm.updateSettings { it.copy(onboardingDone = false) }
                        })
                    },
                )
                SettingDivider()
                // 破坏性操作排在最后、离常用按钮远一点，并且要再确认一次
                SettingItem(
                    title = "清空全部快捷键",
                    description = status.shortcutCount.toString() + " 条",
                    titleColor = c.accent,
                    stackBelow = ACTION_STACK_WIDTH,
                    trailing = {
                        AmSecondaryButton(
                            "清空",
                            onClick = { clearShortcutsConfirmation = true },
                            enabled = status.shortcutCount > 0,
                            accent = true,
                        )
                    },
                )
            }

            SettingsSection("隐私 · PRIVACY") {
                SettingNote(
                    "按键仅在内存中处理，不记录文本输入。剪贴板历史默认关闭，开启后保存在本机。主动翻译时，原文与目标语言提示发送至 MiniMax 中国站；连接测试也会联网。语音转文字仅在点击开始后使用麦克风，点击完成并转写才上传录音至 MiniMax 中国站，不后台录音，不保存转写历史。临时录音在转写成功、取消录音或关闭语音页后清理。Token 在本机加密保存，不包含在配置导出或备份中。应用不申请通讯录或位置权限；共享存储权限仅供文件夹同步使用。",
                    color = c.inkMid,
                )
            }
            Spacer(Modifier.height(AmSpace.s4))
        }

        if (clearShortcutsConfirmation) {
            AlertDialog(
                onDismissRequest = { clearShortcutsConfirmation = false },
                title = { Text("清空全部快捷键？") },
                text = { Text("将删除 " + status.shortcutCount + " 条快捷键，无法撤销；需要的话先备份到文件。") },
                confirmButton = {
                    AmPrimaryButton("清空", onClick = {
                        clearShortcutsConfirmation = false
                        vm.clearShortcuts()
                    }, destructive = true)
                },
                dismissButton = { AmSecondaryButton("取消", onClick = { clearShortcutsConfirmation = false }) },
            )
        }
    }
}

/** 右侧是按钮的条目：可用宽度低于这个值（按字号放大折算）时按钮落到说明下面。 */
private val ACTION_STACK_WIDTH = 320.dp

/**
 * 强调色色板：每个圆点画的是它在当前深浅主题下真正会用的那一档，所见即所得。
 * 选中的那个外面套一圈墨色环、里面一个白点；不只靠颜色区分，色弱也认得出。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: UserSettings.Accent, onSelect: (UserSettings.Accent) -> Unit) {
    val c = amColors
    val dark = c.bgScreen == DarkAmColors.bgScreen
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UserSettings.Accent.entries.forEach { accent ->
            val on = accent == selected
            val label = localize(accent.label)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .amFocusRing(CircleShape)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(accent) }
                    .semantics { contentDescription = label }
                    .border(2.dp, if (on) c.ink else Color.Transparent, CircleShape)
                    .padding(6.dp)
                    .background(amColorsFor(dark, accent).accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Box(Modifier.size(10.dp).background(Color.White, CircleShape))
            }
        }
    }
}
