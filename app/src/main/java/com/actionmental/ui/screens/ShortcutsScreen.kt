package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.combinedClickableCompat
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
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<ActionCategory?>(null) }

    val visible = all.filter { shortcut ->
        val matchesFilter = filter == null || shortcut.action.category == filter
        val q = query.trim()
        val matchesQuery = q.isEmpty() ||
            shortcut.combo.toString().contains(q, ignoreCase = true) ||
            shortcut.displayLabel.contains(q, ignoreCase = true) ||
            shortcut.action.technical.contains(q, ignoreCase = true)
        matchesFilter && matchesQuery
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AmSpace.screen, vertical = AmSpace.s3)) {
            ScreenTitle("快捷键", "SHORTCUTS · " + all.size) {
                Box(
                    Modifier
                        .background(c.ink, RoundedCornerShape(AmShape.key + 2.dp))
                        .clickable { onOpenEditor(null) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = c.bgScreen, modifier = Modifier.height(15.dp))
                        Text(" 录制", style = AmType.body, color = c.bgScreen)
                    }
                }
            }
            Spacer(Modifier.height(AmSpace.s2))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索组合键或动作…", style = AmType.secondary, color = c.inkFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.inkFaint) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AmShape.key + 2.dp),
            )
            Spacer(Modifier.height(AmSpace.s2))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

        LazyColumn(
            contentPadding = PaddingValues(
                start = AmSpace.screen, end = AmSpace.screen, bottom = AmSpace.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(AmSpace.s1),
        ) {
            items(visible, key = { it.id }) { shortcut ->
                ShortcutRow(
                    shortcut = shortcut,
                    selected = shortcut.id == selectedId,
                    onClick = { onOpenEditor(shortcut.id) },
                    onToggle = { vm.setShortcutEnabled(shortcut.id, it) },
                    onDuplicate = { vm.duplicateShortcut(shortcut.id) },
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
}

/** 单条快捷键。长按给出启用 / 禁用 / 复制 / 删除（PRD 14）。 */
@Composable
private fun ShortcutRow(
    shortcut: Shortcut,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = amColors
    var menuOpen by remember { mutableStateOf(false) }

    AmCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick, onLongClick = { menuOpen = true }),
        alert = selected,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyComboRow(shortcut.combo)
            Spacer(Modifier.width(AmSpace.s2))
            Column(Modifier.weight(1f)) {
                Text(
                    shortcut.displayLabel,
                    style = AmType.body,
                    color = if (shortcut.enabled) c.ink else c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            }
            if (shortcut.combo.isModifierKey) {
                AmLabel("轻点", color = c.warn)
                Spacer(Modifier.width(6.dp))
            }
            if (!shortcut.enabled) {
                AmLabel("已禁用", color = c.warn)
                Spacer(Modifier.width(6.dp))
            }
            if (shortcut.action.requiresPrivilege) {
                AmLabel("SHIZUKU", color = c.warn)
                Spacer(Modifier.width(6.dp))
            }
            AmSwitch(checked = shortcut.enabled, onCheckedChange = onToggle)
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
            DropdownMenuItem(
                text = { Text("删除", color = c.accent) },
                onClick = { onDelete(); menuOpen = false },
            )
        }
    }
}
