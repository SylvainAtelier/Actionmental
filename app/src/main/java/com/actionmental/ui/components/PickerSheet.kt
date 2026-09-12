package com.actionmental.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

enum class PickerTone { NEUTRAL, ACCENT, WARN }

/** 二级菜单里的一条具体选项。 */
data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val tone: PickerTone = PickerTone.NEUTRAL,
    val selected: Boolean = false,
    /** 有值时用键帽渲染，按键选择器用。 */
    val keyTokens: List<String>? = null,
)

/**
 * 一级项。
 *
 * [items] 为 null 表示「点开才知道有什么」（例如某个应用的 Activity 清单），
 * 展开时通过 onOpenGroup 通知调用方去加载；[direct] 表示这一级本身就是终点
 * （启动应用 / 打开链接 / Shell 命令这类要另开编辑流程的动作）。
 */
data class PickerGroup(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val items: List<PickerItem>? = null,
    val loading: Boolean = false,
    val direct: Boolean = false,
)

/**
 * 全应用统一的二级选择弹层。
 *
 * 窄屏逐级推进（一级 → 二级 → 返回），宽屏左右并排 —— 同一份数据，两种排布，
 * 平板上不会为了看二级而丢掉一级的上下文。
 * 搜索一律跨级：输入后直接列出所有已加载的具体项，不必先想清楚它属于哪一类。
 */
@Composable
fun AmPickerSheet(
    title: String,
    groups: List<PickerGroup>,
    openGroupId: String?,
    onOpenGroup: (String?) -> Unit,
    onPick: (groupId: String, itemId: String?) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    searchPlaceholder: String = "搜索…",
    emptyHint: String = "没有匹配项",
    header: (@Composable () -> Unit)? = null,
) {
    val c = amColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val term = query.trim()
    val open = groups.firstOrNull { it.id == openGroupId }

    val matches = remember(groups, term) {
        if (term.isEmpty()) emptyList() else groups.flatMap { group ->
            group.items.orEmpty().filter { it.matches(term) }.map { group to it }
        }
    }
    val matchedGroups = remember(groups, term) {
        if (term.isEmpty()) groups else groups.filter { it.matches(term) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = AmShape.sheet, topEnd = AmShape.sheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmSpace.screen)) {
            Column {
                Text(title, style = AmType.cardTitle, color = c.ink)
                if (subtitle != null) {
                    Text(subtitle, style = AmType.secondary, color = c.inkMid)
                }
            }
            Spacer(Modifier.height(AmSpace.s2))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(searchPlaceholder, style = AmType.secondary, color = c.inkFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.inkFaint) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AmShape.key + 2.dp),
            )
            header?.let {
                Spacer(Modifier.height(AmSpace.s2))
                it()
            }
            Spacer(Modifier.height(AmSpace.s2))

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoPane = maxWidth >= 620.dp
                val listHeight = 420.dp

                when {
                    term.isNotEmpty() -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = listHeight),
                        contentPadding = PaddingValues(bottom = AmSpace.s2),
                    ) {
                        if (matches.isEmpty() && matchedGroups.isEmpty()) {
                            item { EmptyLine(emptyHint) }
                        }
                        items(matchedGroups, key = { "g-" + it.id }) { group ->
                            GroupRow(group, selected = false) {
                                if (group.direct) {
                                    onPick(group.id, null)
                                } else {
                                    query = ""
                                    onOpenGroup(group.id)
                                }
                            }
                        }
                        items(matches, key = { it.first.id + "/" + it.second.id }) { pair ->
                            ItemRow(pair.second, pair.first.title) { onPick(pair.first.id, pair.second.id) }
                        }
                    }

                    twoPane -> Row(Modifier.fillMaxWidth().height(listHeight)) {
                        LazyColumn(Modifier.width(232.dp).fillMaxHeight()) {
                            items(groups, key = { it.id }) { group ->
                                GroupRow(group, selected = group.id == openGroupId) {
                                    if (group.direct) onPick(group.id, null) else onOpenGroup(group.id)
                                }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            DetailList(open, emptyHint) { item -> onPick(open!!.id, item.id) }
                        }
                    }

                    open != null -> Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenGroup(null) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                                tint = c.inkMuted,
                            )
                            Text(open.title, style = AmType.body, color = c.ink)
                            Spacer(Modifier.weight(1f))
                            open.subtitle?.let { AmLabel(it, color = c.inkFaint) }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
                        Box(Modifier.fillMaxWidth().height(listHeight - 42.dp)) {
                            DetailList(open, emptyHint) { item -> onPick(open.id, item.id) }
                        }
                    }

                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                        items(groups, key = { it.id }) { group ->
                            GroupRow(group, selected = false) {
                                if (group.direct) onPick(group.id, null) else onOpenGroup(group.id)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(AmSpace.s4))
        }
    }
}

@Composable
private fun DetailList(group: PickerGroup?, emptyHint: String, onPick: (PickerItem) -> Unit) {
    val c = amColors
    val items = group?.items
    when {
        group == null -> CenteredHint("选择左侧的一类")
        group.loading || items == null -> CenteredHint("正在读取…")
        items.isEmpty() -> CenteredHint(emptyHint)
        else -> LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(start = AmSpace.s1, bottom = AmSpace.s2),
        ) {
            items(items, key = { it.id }) { item -> ItemRow(item, null) { onPick(item) } }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(text, style = AmType.secondary, color = amColors.inkFaint)
    }
}

@Composable
private fun GroupRow(group: PickerGroup, selected: Boolean, onClick: () -> Unit) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (selected) c.accentBg else Color.Transparent, shape)
            .border(1.dp, if (selected) c.accentLine else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.title,
                style = AmType.body,
                color = if (selected) c.accent else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            group.subtitle?.let {
                Text(it, style = AmType.secondary, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        group.badge?.let {
            Text(it, style = AmType.data, color = c.inkFaint)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (selected) c.accent else c.inkFaint,
        )
    }
}

@Composable
private fun ItemRow(item: PickerItem, groupTitle: String?, onClick: () -> Unit) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(if (item.selected) c.accentBg else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmSpace.s1),
    ) {
        if (item.keyTokens != null) {
            KeyTokensRow(item.keyTokens, highlightLast = item.selected)
        }
        Column(Modifier.weight(1f)) {
            if (item.keyTokens == null) {
                Text(
                    item.title,
                    style = AmType.body,
                    color = if (item.selected) c.accent else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val sub = item.subtitle ?: groupTitle
            if (sub != null) {
                Text(sub, style = AmType.data, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.badge?.let {
            AmLabel(
                it,
                color = when (item.tone) {
                    PickerTone.ACCENT -> c.accent
                    PickerTone.WARN -> c.warn
                    PickerTone.NEUTRAL -> c.inkFaint
                },
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = AmType.secondary,
        color = amColors.inkFaint,
        modifier = Modifier.padding(vertical = AmSpace.s3),
    )
}

private fun PickerItem.matches(term: String): Boolean =
    title.contains(term, true) ||
        subtitle?.contains(term, true) == true ||
        keyTokens?.any { it.contains(term, true) } == true

private fun PickerGroup.matches(term: String): Boolean =
    title.contains(term, true) || subtitle?.contains(term, true) == true
