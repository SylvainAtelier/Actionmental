package com.actionmental.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.rotation.RotationMode
import com.actionmental.platform.PackageBackend
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmPickerSheet
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.PickerGroup
import com.actionmental.ui.components.PickerItem
import com.actionmental.ui.components.PickerTone
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 应用旋转规则 + 应用选择器（PRD 9 / 36）。 */
@Composable
fun AppRulesScreen(vm: AppViewModel, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val c = amColors
    val rules by vm.rules.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(AmSpace.screen)) {
        if (embedded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AmSecondaryButton("添加应用规则", { pickerOpen = true }, accent = true)
            }
        } else {
            ScreenTitle("应用旋转规则", "APP RULES · " + rules.size) {
                AmSecondaryButton("添加", { pickerOpen = true }, accent = true)
            }
        }
        Spacer(Modifier.height(AmSpace.s2))

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用应用级规则", style = AmType.body, color = c.ink)
                    Text(
                        "离开受控应用后自动回到全局模式，不保持最后一次设置。",
                        style = AmType.secondary,
                        color = c.inkMid,
                    )
                }
                AmSwitch(
                    checked = settings.appRulesEnabled,
                    onCheckedChange = { enabled -> vm.updateSettings { it.copy(appRulesEnabled = enabled) } },
                )
            }
        }

        Spacer(Modifier.height(AmSpace.s2))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            items(rules, key = { it.id }) { rule ->
                AmCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.appLabel, style = AmType.body, color = c.ink, maxLines = 1)
                            Text(rule.packageName, style = AmType.data, color = c.inkFaint, maxLines = 1)
                        }
                        Text(
                            rule.rotationMode.label,
                            style = AmType.data,
                            color = if (rule.rotationMode.isForced) c.accent else c.inkMid,
                        )
                        Spacer(Modifier.height(0.dp))
                        AmSwitch(rule.enabled, { vm.setRuleEnabled(rule.id, it) })
                    }
                    Spacer(Modifier.height(6.dp))
                    // 五个方向 chip 在窄屏上放不下，横向滚动而不是挤成两行
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RotationMode.selectable.forEach { mode ->
                            AmChip(mode.label, mode == rule.rotationMode) {
                                vm.upsertRule(rule.packageName, rule.appLabel, mode)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AmSecondaryButton("删除", { vm.deleteRule(rule.id) }, accent = true)
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    Text(
                        "还没有规则。添加一个应用，进入它时自动切换方向。",
                        style = AmType.secondary,
                        color = c.inkFaint,
                        modifier = Modifier.padding(vertical = AmSpace.s4),
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        AppPickerSheet(vm, onDismiss = { pickerOpen = false }) { app, mode ->
            vm.upsertRule(app.packageName, app.label, mode)
            pickerOpen = false
        }
    }
}

/**
 * 应用选择器：一级是应用，二级是方向 —— 与快捷键编辑页的应用选择器是同一个组件，
 * 应用清单来自预热好的缓存，所以装了几百个应用也是瞬开。
 */
@Composable
private fun AppPickerSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onPick: (PackageBackend.InstalledApp, RotationMode) -> Unit,
) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    var openPackage by remember { mutableStateOf<String?>(null) }

    val groups = remember(apps) {
        apps.map { app ->
            PickerGroup(
                id = app.packageName,
                title = app.label,
                subtitle = app.packageName,
                badge = if (app.frozen) "已冻结" else null,
                items = RotationMode.selectable.map { mode ->
                    PickerItem(
                        id = mode.name,
                        title = mode.label,
                        subtitle = mode.technical,
                        tone = if (mode.isForced) PickerTone.ACCENT else PickerTone.NEUTRAL,
                    )
                },
            )
        }
    }

    AmPickerSheet(
        title = "添加应用规则",
        subtitle = "进入这个应用时自动切换到选定方向，离开后回到全局模式",
        groups = groups,
        openGroupId = openPackage,
        onOpenGroup = { openPackage = it },
        searchPlaceholder = "搜索应用名或包名…",
        emptyHint = "没有匹配的应用",
        onPick = { packageName, modeName ->
            val app = apps.firstOrNull { it.packageName == packageName }
            val mode = modeName?.let { name -> RotationMode.selectable.firstOrNull { it.name == name } }
            if (app != null && mode != null) onPick(app, mode)
        },
        onDismiss = onDismiss,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmSecondaryButton("重新扫描应用", vm::refreshApps)
            }
        },
    )
}
