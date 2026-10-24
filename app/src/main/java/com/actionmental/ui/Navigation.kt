package com.actionmental.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.R
import com.actionmental.core.status.SystemStatus
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

private fun SystemStatus.badgeFor(destination: Destination): String? = when (destination) {
    Destination.SHORTCUTS -> shortcutCount.toString()
    Destination.REMAP -> remapCount.takeIf { it > 0 }?.toString()
    Destination.ROTATION -> ruleCount.takeIf { it > 0 }?.toString()
    else -> null
}

/**
 * 宽屏的左侧导航，对应设计稿 2c / 1a-03。
 *
 * - [rail] = true（600～840dp，竖放的小平板、横放的手机）：80dp 窄栏，图标在上、名字在下；
 * - [rail] = false（>= 840dp）：带名字、计数与底部状态的完整侧栏。
 *
 * 九个入口在横屏手机上竖着放不下，所以入口列表自己可以滚动，品牌与底部状态不动。
 */
@Composable
fun NavigationSidebar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    vm: AppViewModel,
    rail: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = amColors
    val status by vm.status.collectAsStateWithLifecycle()

    Row(modifier.background(c.bg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .padding(horizontal = if (rail) 8.dp else 12.dp, vertical = AmSpace.s3),
            horizontalAlignment = if (rail) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Brand(compact = rail)
            Spacer(Modifier.height(AmSpace.s3))

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(if (rail) 4.dp else 2.dp),
            ) {
                Destination.entries.forEach { destination ->
                    if (rail) {
                        RailItem(destination, destination == current) { onSelect(destination) }
                    } else {
                        SidebarItem(destination, destination == current, status.badgeFor(destination)) { onSelect(destination) }
                    }
                }
            }

            if (!rail) {
                Spacer(Modifier.height(AmSpace.s2))
                SidebarFooterStatus("KEYBOARD", status.primaryKeyboard?.name ?: "未检测到", status.keyboardConnected)
                Spacer(Modifier.height(AmSpace.s1))
                SidebarFooterStatus("SHIZUKU", status.shizuku.conclusion, status.shizuku.usable)
            }
        }
        // 1dp 分隔，比整块换底色更克制，两层背景规则也不破
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
    }
}

@Composable
private fun Brand(compact: Boolean) {
    val c = amColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (compact) 0.dp else 8.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = "Actionmental",
            tint = c.ink,
            modifier = Modifier.size(if (compact) 28.dp else 24.dp),
        )
        if (!compact) {
            Spacer(Modifier.width(10.dp))
            Row {
                Text("Action", style = AmType.cardTitle, color = c.ink)
                Text("mental", style = AmType.cardTitle, color = c.accent)
            }
        }
    }
}

@Composable
private fun SidebarItem(destination: Destination, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    val face by animateColorAsState(if (selected) c.surface else c.bg, label = "nav-face")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .clip(shape)
            .background(face)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            tint = if (selected) c.accent else c.inkMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            destination.title,
            style = AmType.body,
            color = if (selected) c.ink else c.inkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Text(badge, style = AmType.data, color = c.inkMuted)
        }
    }
}

@Composable
private fun RailItem(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    val c = amColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AmShape.card))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavIndicator(destination, selected)
        Spacer(Modifier.height(4.dp))
        Text(
            destination.title,
            style = AmType.label,
            color = if (selected) c.ink else c.inkMuted,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** M3 式的选中胶囊：图标外一圈 56×32 的底，比只换图标颜色好认得多，也不只靠颜色传达状态。 */
@Composable
private fun NavIndicator(destination: Destination, selected: Boolean) {
    val c = amColors
    val face by animateColorAsState(if (selected) c.surface else c.surface.copy(alpha = 0f), label = "nav-indicator")
    Box(
        Modifier
            .size(width = 56.dp, height = 32.dp)
            .background(face, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            tint = if (selected) c.accent else c.inkMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SidebarFooterStatus(label: String, value: String, ok: Boolean) {
    val c = amColors
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(label, style = AmType.label, color = c.inkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (ok) c.ok else c.warn)
            Spacer(Modifier.width(4.dp))
            Text(
                value,
                style = AmType.data,
                color = c.inkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 手机底部导航，只放五个高频入口，其余从状态中心「更多」进入。 */
@Composable
fun NavigationBottomBar(current: Destination, onSelect: (Destination) -> Unit) {
    val c = amColors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Destination.entries.filter { it.inBottomBar }.forEach { destination ->
                val selected = destination == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 56.dp)
                        .clip(RoundedCornerShape(AmShape.card))
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    NavIndicator(destination, selected)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        destination.title,
                        style = AmType.label,
                        color = if (selected) c.ink else c.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
