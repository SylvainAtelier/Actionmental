package com.actionmental.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.R
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.i18n.localize
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 平板 / 折叠屏展开态的左侧栏，对应设计稿 2c / 1a-03。 */
@Composable
fun NavigationSidebar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val c = amColors
    val status by vm.status.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = AmSpace.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = c.ink,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Action", style = AmType.cardTitle, color = c.ink)
                Text("mental", style = AmType.cardTitle, color = c.accent)
            }
        }
        Spacer(Modifier.height(AmSpace.s4))

        Destination.entries.forEach { destination ->
            val selected = destination == current
            val badge = when (destination) {
                Destination.SHORTCUTS -> status.shortcutCount.toString()
                Destination.REMAP -> status.remapCount.takeIf { it > 0 }?.toString()
                Destination.ROTATION -> status.ruleCount.takeIf { it > 0 }?.toString()
                else -> null
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(if (selected) c.surface else c.bg, RoundedCornerShape(AmShape.key + 2.dp))
                    .clickable { onSelect(destination) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = if (selected) c.accent else c.inkMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        destination.title,
                        style = AmType.body,
                        color = if (selected) c.ink else c.inkMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badge != null) {
                    Text(badge, style = AmType.data, color = c.inkFaint)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        SidebarFooterStatus("KEYBOARD", status.primaryKeyboard?.name ?: "未检测到", status.keyboardConnected)
        Spacer(Modifier.height(6.dp))
        SidebarFooterStatus("SHIZUKU", status.shizuku.conclusion, status.shizuku.usable)
    }
}

@Composable
private fun SidebarFooterStatus(label: String, value: String, ok: Boolean) {
    val c = amColors
    Column {
        Text(label, style = AmType.label, color = c.inkFaint)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (ok) c.ok else c.warn)
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

/** 手机底部导航，只放四个高频入口，其余从状态中心进入。 */
@Composable
fun NavigationBottomBar(current: Destination, onSelect: (Destination) -> Unit) {
    val c = amColors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Destination.entries.filter { it.inBottomBar }.forEach { destination ->
                val selected = destination == current
                Column(
                    modifier = Modifier
                        .clickable { onSelect(destination) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = localize(destination.title),
                        tint = if (selected) c.accent else c.inkMuted,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        destination.title,
                        style = AmType.label,
                        color = if (selected) c.ink else c.inkMuted,
                    )
                }
            }
        }
    }
}
