package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.foundation.layout.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.actionmental.ui.i18n.localize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.action.ActionCategory
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.KeyComboRow
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 快捷键区域。
 * 窄屏：列表与编辑器互相顶替；宽屏（>=840dp）：左列表右编辑器并排。
 */
@Composable
fun ShortcutsPane(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    twoPane: Boolean,
    editingId: String?,
    editingNew: Boolean,
    presetCombo: KeyCombo?,
    onOpenEditor: (String?) -> Unit,
    onCloseEditor: () -> Unit,
) {
    val editorOpen = editingNew || editingId != null

    if (twoPane) {
        Row(modifier.fillMaxSize()) {
            ShortcutListScreen(vm, Modifier.weight(1f), editingId, onOpenEditor)
            Box(Modifier.width(1.dp).fillMaxSize().background(amColors.line))
            Box(Modifier.weight(1f)) {
                if (editorOpen) {
                    ShortcutEditorScreen(
                        shortcutId = editingId,
                        presetCombo = presetCombo,
                        onDone = onCloseEditor,
                    )
                } else {
                    EmptyEditorHint()
                }
            }
        }
    } else {
        if (editorOpen) {
            ShortcutEditorScreen(
                modifier = modifier,
                shortcutId = editingId,
                presetCombo = presetCombo,
                onDone = onCloseEditor,
            )
        } else {
            ShortcutListScreen(vm, modifier, editingId, onOpenEditor)
        }
    }
}

@Composable
private fun EmptyEditorHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("选择一条快捷键，或录制新的", style = AmType.secondary, color = amColors.inkFaint)
    }
}

@Composable
fun ShortcutListScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    selectedId: String?,
    onOpenEditor: (String?) -> Unit,
) {
    val c = amColors
    val all by vm.shortcuts.collectAsStateWithLifecycle()
    val pinned by vm.desktopPinned.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDesktop() }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<ActionCategory?>(null) }

    val visible = all.filter { shortcut ->
        val matchesFilter = filter == null || shortcut.action.category == filter
        val q = query.trim()
        val matchesQuery = q.isEmpty() ||
            shortcut.combo.toString().contains(q, ignoreCase = true) ||
            shortcut.displayLabel.contains(q, ignoreCase = true) ||
            shortcut.action.technical.contains(q, ignoreCase = true)
        matchesFilter && matchesQuery
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s1),
    ) {
        item(key = "controls") {
            Column {
                ScreenTitle("快捷键", "SHORTCUTS · " + all.size) {
                    AmPrimaryButton("录制", { onOpenEditor(null) }, icon = Icons.Filled.Add)
                }
                Spacer(Modifier.height(AmSpace.s2))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索组合键或动作…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.inkFaint) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AmShape.key + 2.dp),
                )
                Spacer(Modifier.height(AmSpace.s2))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AmChip("全部 " + all.size, filter == null) { filter = null }
                    ActionCategory.entries.forEach { category ->
                        val count = all.count { it.action.category == category }
                        if (count > 0) {
                            AmChip(category.label + " " + count, filter == category) { filter = category }
                        }
                    }
                }
            }
        }
        items(visible, key = { it.id }) { shortcut ->
            ShortcutRow(
                shortcut = shortcut,
                selected = shortcut.id == selectedId,
                onDesktop = shortcut.id in pinned,
                onClick = { onOpenEditor(shortcut.id) },
                onToggle = { vm.setShortcutEnabled(shortcut.id, it) },
                onDuplicate = { vm.duplicateShortcut(shortcut.id) },
                onPinDesktop = if (vm.desktopSupported) ({ vm.pinToDesktop(shortcut.id) }) else null,
                onDelete = { vm.deleteShortcut(shortcut.id) },
            )
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (all.isEmpty()) "还没有快捷键。点右上角开始录制。" else "没有匹配的快捷键。",
                    style = AmType.secondary,
                    color = c.inkFaint,
                    modifier = Modifier.padding(vertical = AmSpace.s4),
                )
            }
        }
    }
}

/** 单条快捷键。长按给出启用 / 禁用 / 复制 / 添加到桌面 / 删除（PRD 14）。 */
@Composable
private fun ShortcutRow(
    shortcut: Shortcut,
    selected: Boolean,
    onDesktop: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    /** 桌面不支持钉快捷方式时为 null，菜单里就不出现这一项。 */
    onPinDesktop: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val c = amColors
    var menuOpen by remember { mutableStateOf(false) }

    AmCard(
        modifier = Modifier.fillMaxWidth(),
        selected = selected,
        onClick = onClick,
        onLongClick = { menuOpen = true },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyComboRow(shortcut.combo, modifier = Modifier.weight(1f))
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = localize("更多"), tint = c.inkMuted)
            }
        }
        Spacer(Modifier.height(AmSpace.s1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    shortcut.displayLabel,
                    style = AmType.body,
                    color = if (shortcut.enabled) c.ink else c.inkFaint,
                )
                shortcut.action.detail?.let { detail ->
                    Text(
                        detail,
                        style = AmType.secondary,
                        color = if (shortcut.enabled) c.inkMid else c.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    shortcut.action.technical + " · " + shortcut.scopeLabel,
                    style = AmType.data,
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标记放在文字下面而不是挤在开关左边：手机上这一行本来就只够放组合键、标题和开关
                val tagged = onDesktop || shortcut.combo.isModifierKey || !shortcut.enabled || shortcut.action.requiresPrivilege
                if (tagged) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onDesktop) {
                            Icon(
                                Icons.AutoMirrored.Outlined.AddToHomeScreen,
                                contentDescription = localize("已在桌面"),
                                tint = c.inkMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (shortcut.combo.isModifierKey) AmLabel("轻点", color = c.warn)
                        if (!shortcut.enabled) AmLabel("已禁用", color = c.warn)
                        if (shortcut.action.requiresPrivilege) AmLabel("SHIZUKU", color = c.warn)
                    }
                }
            }
            Spacer(Modifier.width(AmSpace.s1))
            AmSwitch(checked = shortcut.enabled, onCheckedChange = onToggle, label = shortcut.displayLabel)
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (shortcut.enabled) "禁用" else "启用") },
                onClick = { onToggle(!shortcut.enabled); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = { onDuplicate(); menuOpen = false },
            )
            if (onPinDesktop != null) {
                DropdownMenuItem(
                    text = { Text(if (onDesktop) "再添加到桌面" else "添加到桌面") },
                    onClick = { onPinDesktop(); menuOpen = false },
                )
            }
            DropdownMenuItem(
                text = { Text("删除", color = c.accent) },
                onClick = { onDelete(); menuOpen = false },
            )
        }
    }
}
