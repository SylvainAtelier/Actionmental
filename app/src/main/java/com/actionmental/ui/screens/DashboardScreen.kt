package com.actionmental.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.AppGraph
import com.actionmental.core.hardening.HardeningRecord
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.Destination
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.KeyComboRow
import com.actionmental.ui.components.StatusCard
import com.actionmental.ui.components.StatusTone
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val noticeStamp = SimpleDateFormat("MM-dd HH:mm", Locale.US)

/**
 * 状态中心（PRD 13 / 24）。
 * 四张状态卡的内容全部来自同一个 SystemStatus 流，页面本身不做任何查询。
 */
@Composable
fun DashboardScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit,
) {
    val c = amColors
    val status by vm.status.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val pauseReason by vm.pauseReason.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val unread by vm.hardeningUnread.collectAsStateWithLifecycle()
    val latestNotice by vm.hardeningLatest.collectAsStateWithLifecycle()

    val entries = listOf(
        Destination.SHORTCUTS to status.shortcutCount.toString(),
        Destination.REMAP to status.remapCount.takeIf { it > 0 }?.toString().orEmpty(),
        Destination.MONITOR to "",
        Destination.ROTATION to status.ruleCount.takeIf { it > 0 }?.let { it.toString() + " 条规则" }.orEmpty(),
        Destination.PRIVILEGE to "",
        Destination.LOGS to "",
        Destination.SETTINGS to "",
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        item { ScreenTitle("状态中心", "DASHBOARD") }

        // 暂停时下面那几张卡照样会写「监听服务 运行中」—— 那是系统事实，没错，
        // 但它此刻什么都不做。这条横幅必须排在它们前面，否则整页都在误导。
        if (paused) {
            item { PausedBanner(pauseReason) { onNavigate(Destination.SETTINGS) } }
        }

        // 加固 / 自愈是应用擅自改了系统状态，用户不点进权限页也必须看得见一次
        latestNotice?.takeIf { unread > 0 }?.let { notice ->
            item { HardeningNoticeCard(notice, unread) { onNavigate(Destination.PRIVILEGE) } }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s2)) {
                StatusCard(
                    name = "监听服务",
                    conclusion = when {
                        status.accessibilityConnected -> "运行中"
                        status.accessibilityWaitingForBind -> "已开启 · 等待连接"
                        else -> "未开启"
                    },
                    technical = "AccessibilityService",
                    tone = when {
                        status.accessibilityConnected -> StatusTone.OK
                        status.accessibilityWaitingForBind -> StatusTone.WARN
                        else -> StatusTone.ALERT
                    },
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Destination.SETTINGS) },
                )
                StatusCard(
                    name = "Shizuku",
                    conclusion = status.shizuku.conclusion,
                    technical = status.shizuku.identity,
                    tone = if (status.shizuku.usable) StatusTone.OK else StatusTone.WARN,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Destination.PRIVILEGE) },
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s2)) {
                StatusCard(
                    name = "键盘",
                    conclusion = status.primaryKeyboard?.name ?: "未检测到",
                    technical = status.primaryKeyboard
                        ?.let { "id " + it.id + " · " + it.transport }
                        ?: "no physical keyboard",
                    tone = if (status.keyboardConnected) StatusTone.OK else StatusTone.WARN,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Destination.MONITOR) },
                )
                StatusCard(
                    name = "屏幕方向",
                    conclusion = status.rotation.mode.label,
                    technical = status.rotation.mode.technical,
                    tone = when {
                        !status.rotation.available -> StatusTone.WARN
                        status.rotation.mode.isForced -> StatusTone.ALERT
                        else -> StatusTone.IDLE
                    },
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(Destination.ROTATION) },
                )
            }
        }

        // 常亮是会耗电的系统状态，而它可能是被一个快捷键悄悄打开的 ——
        // 所以它必须在状态中心一直看得见，且当场关得掉。
        item {
            AmCard(Modifier.fillMaxWidth(), alert = status.screenAwake.on) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("屏幕常亮", style = AmType.body, color = c.ink)
                        Text(
                            if (status.screenAwake.available) {
                                status.screenAwake.label + " · " + status.screenAwake.technical
                            } else {
                                status.screenAwake.failure ?: "不可用"
                            },
                            style = AmType.data,
                            color = if (status.screenAwake.on) c.accent else c.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AmSwitch(
                        checked = status.screenAwake.on,
                        onCheckedChange = { vm.setScreenAwake(it) },
                        // 常亮不受全局暂停影响，暂停期间照样能开关
                        enabled = status.screenAwake.available,
                    )
                }
            }
        }

        item {
            AmCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("已绑定 ", style = AmType.body, color = c.inkMid)
                            Text(status.shortcutCount.toString(), style = AmType.pageTitle, color = c.ink)
                            Text(" 个快捷键", style = AmType.body, color = c.inkMid)
                        }
                        Text(
                            "0 conflicts · " + status.disabledCount + " disabled",
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                    }
                    shortcuts.firstOrNull { it.enabled }?.let { KeyComboRow(it.combo) }
                }
            }
        }

        item {
            AmCard(Modifier.fillMaxWidth()) {
                AmLabel("当前前台 · FOREGROUND RULE")
                Spacer(Modifier.height(6.dp))
                val rule = status.activeRule
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule?.appLabel ?: (status.foregroundPackage ?: "未知应用"),
                        style = AmType.body,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (rule != null) "→ " + rule.rotationMode.label else "→ 无规则",
                        style = AmType.data,
                        color = if (rule != null) c.accent else c.inkFaint,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(AmSpace.s1)) }

        items(entries.size) { index ->
            val (destination, badge) = entries[index]
            AmCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(destination) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(destination.title, style = AmType.body, color = c.ink)
                    }
                    if (badge.isNotEmpty()) Text(badge, style = AmType.data, color = c.inkMuted)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = c.inkFaint,
                    )
                }
            }
        }
    }
}

/**
 * 未读的加固 / 自愈通知。
 *
 * 自愈往往发生在应用不在前台的时候，snackbar 那一瞬间没人看见 ——
 * 所以它在这里留到用户进过一次权限页为止，而不是弹一下就没。
 */
@Composable
private fun HardeningNoticeCard(record: HardeningRecord, unread: Int, onOpen: () -> Unit) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth(), alert = !record.ok) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.kind.label + " · " + record.step,
                    style = AmType.body,
                    color = if (record.ok) c.ink else c.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    noticeStamp.format(Date(record.timestampMs)) + " · " +
                        (if (record.ok) "成功" else "失败") +
                        (if (unread > 1) " · 另有 " + (unread - 1) + " 条未读" else ""),
                    style = AmType.data,
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.inkFaint,
            )
        }
    }
}

@Composable
private fun PausedBanner(reason: AppGraph.PauseReason, onOpenSettings: () -> Unit) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth().clickable(onClick = onOpenSettings), alert = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (reason == AppGraph.PauseReason.NO_KEYBOARD) "已暂停 · 未检测到键盘" else "已暂停",
                    style = AmType.body,
                    color = c.warn,
                )
                Text(
                    if (reason == AppGraph.PauseReason.NO_KEYBOARD) {
                        "键盘接回来就自动恢复 · 期间快捷键、映射、旋转规则都不生效，常亮照常"
                    } else {
                        "快捷键、映射、旋转规则都已停止 · 屏幕常亮照常"
                    },
                    style = AmType.data,
                    color = c.inkFaint,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.inkFaint,
            )
        }
    }
}

@Composable
fun ScreenTitle(title: String, technical: String, trailing: (@Composable () -> Unit)? = null) {
    val c = amColors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AmType.pageTitle, color = c.ink)
        }
        trailing?.invoke()
    }
}
