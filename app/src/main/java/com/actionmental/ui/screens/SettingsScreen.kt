package com.actionmental.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.AppGraph
import com.actionmental.BuildConfig
import com.actionmental.data.UserSettings
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 设置（PRD 36 · 设计稿 2b 屏 3）。 */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = amColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val pauseReason by vm.pauseReason.collectAsStateWithLifecycle()

    // 备份走系统文件选择器：不申请存储权限，拿到的也只是这一个文件的一次性许可。
    // 用户取消（uri 为 null）什么都不做 —— 那不是失败，不该弹提示。
    val backupFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::backupConfigToFile) }
    val restoreFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::restoreConfigFromFile) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        ScreenTitle("设置", "SETTINGS · v" + BuildConfig.VERSION_NAME)

        // 暂停排在最前面。它不是一项偏好，是「现在这个应用还算不算在运行」——
        // 用户来找它的时候，多半是因为键盘正在出问题，翻不动列表。
        AmCard(Modifier.fillMaxWidth(), alert = paused) {
            AmLabel("运行 · RUNTIME")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (paused) c.warn else c.ok)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (pauseReason) {
                            AppGraph.PauseReason.MANUAL -> "已暂停 · 手动"
                            AppGraph.PauseReason.NO_KEYBOARD -> "已暂停 · 未检测到键盘"
                            AppGraph.PauseReason.NONE -> "运行中"
                        },
                        style = AmType.body,
                        color = if (paused) c.warn else c.ink,
                    )
                    Text(
                        if (paused) {
                            "除屏幕常亮外全部停止，占用降到最低"
                        } else {
                            "快捷键、键位映射、旋转规则、屏幕常亮都在生效"
                        },
                        style = AmType.data,
                        color = c.inkFaint,
                    )
                }
                // 这颗开关只管手动暂停。自动暂停期间它仍然显示「开」——
                // 它表示的是「用户没有按下暂停」，而不是「现在正在跑」，
                // 上面那行状态才是结论。
                AmSwitch(
                    checked = !settings.paused,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(paused = !on) } },
                )
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("没有键盘时自动暂停", style = AmType.body, color = c.ink)
                    Text(
                        when {
                            !settings.autoPauseWithoutKeyboard -> "未启用 · 拔掉键盘后应用照常运行"
                            status.keyboardConnected ->
                                "已启用 · 当前 " + status.keyboards.size + " 个键盘，正常运行"
                            else -> "已启用 · 键盘不在，已自动停下"
                        },
                        style = AmType.data,
                        color = c.inkFaint,
                    )
                }
                AmSwitch(
                    settings.autoPauseWithoutKeyboard,
                    onCheckedChange = { on ->
                        vm.updateSettings { it.copy(autoPauseWithoutKeyboard = on) }
                    },
                )
            }
            Spacer(Modifier.height(AmSpace.s2))
            Text(
                "暂停后：按键原样交给前台应用（不再匹配、不再改写），前台应用不再被识别，" +
                    "屏幕旋转不再被修改，常驻前台服务与自动恢复一并停止，" +
                    "体征采样也不再进行。屏幕常亮不受暂停影响，照你的开关保持。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "无障碍授权保持原样，不需要去系统设置里关。进程仍由系统绑定着，" +
                    "但它此刻是个空壳 —— 这是不撤授权就能做到的最低占用。" +
                    "关掉这个开关，一切按你原来的设置恢复。",
                style = AmType.secondary,
                color = c.inkFaint,
            )
            if (settings.autoPauseWithoutKeyboard) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "自动暂停会连旋转规则一起停掉 —— 它本来不需要键盘；屏幕常亮不受影响。" +
                        "键盘掉线后等 8 秒才真的停，蓝牙键盘短暂重连不会来回折腾；" +
                        "插回来则立刻恢复。",
                    style = AmType.secondary,
                    color = c.inkFaint,
                )
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("语言 · LANGUAGE")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AmChip("中文", settings.language == UserSettings.Language.CHINESE) {
                    vm.updateSettings { it.copy(language = UserSettings.Language.CHINESE) }
                }
                AmChip("英文", settings.language == UserSettings.Language.ENGLISH) {
                    vm.updateSettings { it.copy(language = UserSettings.Language.ENGLISH) }
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("外观 · APPEARANCE")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UserSettings.Theme.entries.forEach { theme ->
                    AmChip(theme.label, settings.theme == theme) {
                        vm.updateSettings { it.copy(theme = theme) }
                    }
                }
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示原始键值", style = AmType.body, color = c.ink)
                    Text("列表中常驻 keyCode / scanCode", style = AmType.data, color = c.inkFaint)
                }
                AmSwitch(settings.showRawKeyCodes, onCheckedChange = { on -> vm.updateSettings { it.copy(showRawKeyCodes = on) } })
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("服务与权限 · SERVICES")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (status.accessibilityConnected) c.ok else c.accent)
                Column(Modifier.weight(1f)) {
                    Text("无障碍键盘服务", style = AmType.body, color = c.ink)
                    Text(
                        when {
                            status.accessibilityConnected -> "运行中 · 已授权"
                            status.accessibilityMasterSwitchOff ->
                                "系统无障碍总开关是关的 · 服务不会被绑定"
                            status.accessibilityWaitingForBind ->
                                "已授权但未连接 · 系统没有重新绑定服务"
                            else -> "未开启 · service_state = OFF"
                        },
                        style = AmType.data,
                        color = c.inkFaint,
                    )
                }
                AmSecondaryButton("管理", onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                })
            }
            // 服务连不上时，这一颗替用户完成「去系统设置里关掉再打开」那一趟。
            // 需要 Shizuku：没有 shell 身份就改不了 secure 设置，那时仍然只能手动去设置里做。
            if (!status.accessibilityConnected &&
                (status.accessibilityWaitingForBind || status.accessibilityMasterSwitchOff)
            ) {
                Spacer(Modifier.height(AmSpace.s2))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status.shizuku.usable) {
                                "可以在这里直接重连，不用去系统设置"
                            } else {
                                "Shizuku 不可用，只能到系统设置里把开关关掉再打开"
                            },
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                    }
                    AmSecondaryButton("重新连接", onClick = vm::rebindAccessibility)
                }
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (status.shizuku.usable) c.ok else c.warn)
                Column(Modifier.weight(1f)) {
                    Text("Shizuku", style = AmType.body, color = c.ink)
                    Text(status.shizuku.conclusion + " · " + status.shizuku.identity,
                        style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("重新检测", vm::refreshAll)
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("数据 · DATA")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出配置", style = AmType.body, color = c.ink)
                    Text("快捷键与键位映射 · 复制 JSON 到剪贴板", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("复制", onClick = {
                    clipboard.setText(AnnotatedString(vm.exportConfig()))
                })
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导入配置", style = AmType.body, color = c.ink)
                    Text("快捷键与键位映射 · 从剪贴板读取 JSON", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("粘贴导入", onClick = {
                    clipboard.getText()?.text?.let(vm::importConfig)
                })
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("备份到文件", style = AmType.body, color = c.ink)
                    Text("快捷键与键位映射 · 存成 JSON 文件，卸载也不会丢",
                        style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("备份", onClick = {
                    backupFile.launch(vm.suggestedBackupName())
                })
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("从文件恢复", style = AmType.body, color = c.ink)
                    Text("读回之前备份的 JSON 文件", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("选择文件", onClick = {
                    // 放开到所有类型，不是偷懒。
                    //
                    // 有些 ROM 的文件管理器把 .json 记成 application/octet-stream 甚至
                    // text/plain，按 application/json 过滤会让用户的备份在选择器里
                    // 直接不出现 —— 而这是一条恢复路径，看不见自己的文件是最糟的结果。
                    // 选错了也不会有损失：解析不过就提示失败，一个字节都不会写进去。
                    restoreFile.launch(arrayOf("*/*"))
                })
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("清空全部快捷键", style = AmType.body, color = c.accent)
                    Text(status.shortcutCount.toString() + " 条", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("清空", vm::clearShortcuts, accent = true)
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("重新运行引导", style = AmType.body, color = c.ink)
                    Text("重新检查三项能力", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton("重新运行", onClick = {
                    vm.updateSettings { it.copy(onboardingDone = false) }
                })
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("隐私 · PRIVACY · LOCAL ONLY")
            Spacer(Modifier.height(4.dp))
            Text(
                "按键仅在内存中处理，不记录文本输入，不联网上传。应用不申请网络、通讯录、存储、位置权限。",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }
        Spacer(Modifier.height(AmSpace.s4))
    }
}
