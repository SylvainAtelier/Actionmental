package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actionmental.core.action.Action
import com.actionmental.core.action.SymbolCatalog
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 「输入符号」：按下快捷键，往当前输入框里打一个符号。
 *
 * 上面一排分组、下面一格一个符号，点一下就选中；表里没有的可以在「直接输入」里粘贴，任意文字都行。
 * 符号本身不过翻译：界面的英文替换会把全角标点换成半角，符号就变了。
 */
@Composable
internal fun SymbolSheet(
    current: Action.TypeSymbol?,
    onConfirm: (Action.TypeSymbol) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = amColors
    val groups = SymbolCatalog.groups
    var selected by remember { mutableStateOf(current?.text.orEmpty()) }
    // 当前符号不在表里（之前是自己输入的），那它就在「直接输入」框里
    var custom by remember {
        mutableStateOf(current?.text?.takeIf { SymbolCatalog.groupOf(it) == null }.orEmpty())
    }
    var groupIndex by remember {
        mutableIntStateOf(current?.text?.let { SymbolCatalog.groupOf(it) }?.let(groups::indexOf)?.coerceAtLeast(0) ?: 0)
    }
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    val group = groups[groupIndex]
    val gridState = rememberLazyGridState()
    LaunchedEffect(groupIndex) { gridState.scrollToItem(0) }

    InputSheet(
        title = "输入符号",
        subtitle = "按下快捷键时，把选中的符号直接写进当前输入框，和输入法上屏一样。不需要 Shizuku。",
        confirmText = "输入这个符号",
        confirmEnabled = selected.isNotEmpty(),
        footnote = "走无障碍的输入通道（Android 13+）· 没有输入框时放进剪贴板",
        onConfirm = { onConfirm(Action.TypeSymbol(selected, title.trim())) },
        onDismiss = onDismiss,
    ) {
        SymbolPreview(selected)
        Spacer(Modifier.height(AmSpace.s2))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            groups.forEachIndexed { index, g ->
                AmChip(text = g.label, selected = index == groupIndex) { groupIndex = index }
            }
        }
        Spacer(Modifier.height(AmSpace.s1))
        AmLabel(group.label + " · " + group.technical + " · " + group.symbols.size)
        Spacer(Modifier.height(4.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp)
                .background(c.surfaceSunken, RoundedCornerShape(AmShape.key + 2.dp))
                .border(1.dp, c.line, RoundedCornerShape(AmShape.key + 2.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(group.symbols, key = { it }) { symbol ->
                SymbolCell(symbol, selected = symbol == selected) {
                    selected = symbol
                    custom = ""
                }
            }
        }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("或直接输入 · CUSTOM", custom, "粘贴任意符号或文字") {
            custom = it
            if (it.isNotEmpty()) selected = it
        }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("名称 · 可选", title, "列表里显示的名字") { title = it }
    }
}

/** 选中的符号放大显示，旁边是码位：长得像的符号（− 和 -、· 和 •）靠它分清。 */
@Composable
private fun SymbolPreview(symbol: String) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .background(c.surfaceSunken, shape)
                .border(1.dp, c.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            val name = SymbolCatalog.nameOf(symbol)
            if (symbol.isEmpty() || name != null) {
                Text(if (symbol.isEmpty()) "—" else "␣", style = AmType.keycapLarge, color = c.inkFaint)
            } else {
                androidx.compose.material3.Text(
                    symbol,
                    fontSize = 26.sp,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(AmSpace.s2))
        Column(Modifier.weight(1f)) {
            Text(
                if (symbol.isEmpty()) "还没有选符号" else SymbolCatalog.nameOf(symbol) ?: "已选中",
                style = AmType.body,
                color = c.ink,
            )
            if (symbol.isNotEmpty()) {
                androidx.compose.material3.Text(
                    SymbolCatalog.codePoints(symbol),
                    style = AmType.data,
                    color = c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SymbolCell(symbol: String, selected: Boolean, onClick: () -> Unit) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key)
    val name = SymbolCatalog.nameOf(symbol)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) c.ink else c.surface, shape)
            .border(1.dp, if (selected) c.ink else c.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (name != null) {
            // 看不见的字符写名字，字小一点、可以折行
            Text(
                name,
                style = AmType.label.copy(fontSize = 8.sp, lineHeight = 10.sp, textAlign = TextAlign.Center),
                color = if (selected) c.bgScreen else c.inkMid,
                maxLines = 3,
                modifier = Modifier.padding(2.dp),
            )
        } else {
            androidx.compose.material3.Text(
                symbol,
                fontSize = 19.sp,
                color = if (selected) c.bgScreen else c.ink,
                maxLines = 1,
            )
        }
    }
}
