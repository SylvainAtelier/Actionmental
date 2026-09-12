package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.diag.LogEntry
import com.actionmental.core.diag.LogLevel
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * 运行日志。
 *
 * 存在的理由只有一个：服务掉线、进程崩溃这类问题发生时，用户手上没有 adb，
 * 而现场恰恰在上一次进程里。所以这一页读的是**盘上**的日志，
 * 不是内存里那一段 —— 内存里的那一段在崩溃时已经跟着进程没了。
 *
 * 不含任何按键内容：记录的是 keyCode、组件名、退出码这类技术标识（PRD 26）。
 */
@Composable
fun LogScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = amColors
    val clipboard = LocalClipboardManager.current
    val entries by vm.logEntries.collectAsStateWithLifecycle()

    val writeError by vm.logWriteError.collectAsStateWithLifecycle()

    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    var persisted by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    // 进程崩溃后重启，内存里是空的，上一次的现场全在盘上
    LaunchedEffect(Unit) { persisted = vm.readPersistedLog() }

    val visible = entries.filter { it.level.ordinal >= minLevel.ordinal }.asReversed()
    val errorCount = entries.count { it.level == LogLevel.ERROR }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        item {
            ScreenTitle("运行日志", "EVENT LOG") {
                AmSecondaryButton("清空", onClick = {
                    vm.clearLog()
                    persisted = ""
                })
            }
        }

        item {
            AmCard(Modifier.fillMaxWidth(), alert = errorCount > 0) {
                Text(
                    if (errorCount > 0) {
                        "本次运行有 " + errorCount + " 条错误"
                    } else {
                        "本次运行没有错误记录"
                    },
                    style = AmType.body,
                    color = if (errorCount > 0) c.accent else c.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "日志落在应用私有目录，进程崩溃后仍然保留。复制出来即可直接用于反馈问题。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
                Spacer(Modifier.height(AmSpace.s2))
                Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s2)) {
                    AmSecondaryButton("复制全部日志", onClick = {
                        clipboard.setText(AnnotatedString(vm.exportFullLog()))
                    })
                }
            }
        }

        item {
            AmCard(Modifier.fillMaxWidth()) {
                AmLabel("级别过滤 · MIN LEVEL")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogLevel.entries.forEach { level ->
                        LevelChip(level, selected = level == minLevel) { minLevel = level }
                    }
                }
            }
        }

        // 写盘失败过去是静默的，于是「日志是空的」分不清是没事发生还是没记下来
        writeError?.let { error ->
            item {
                AmCard(Modifier.fillMaxWidth(), alert = true) {
                    Text("日志写盘失败，记录可能不完整。", style = AmType.body, color = c.accent)
                    Text(error, style = AmType.data, color = c.inkFaint)
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                AmCard(Modifier.fillMaxWidth()) {
                    Text("本次运行还没有这个级别的日志。", style = AmType.body, color = c.inkMid)
                }
            }
        }

        items(visible, key = { it.id }) { entry ->
            LogRow(entry, expanded == entry.id) {
                expanded = if (expanded == entry.id) null else entry.id
            }
        }

        // 崩溃后重启，本次运行的日志当然是空的 —— 要找的东西全在上一次进程里。
        // 这一段就是那份记录：不展示它，用户看到的就是「日志被清空了」。
        if (persisted.isNotBlank()) {
            item {
                AmCard(Modifier.fillMaxWidth().clickable { showHistory = !showHistory }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("更早的记录（含上一次进程）", style = AmType.body, color = c.ink)
                            Text(
                                persisted.lineSequence().count().toString() + " 行 · 点按" +
                                    if (showHistory) "折起" else "展开",
                                style = AmType.data,
                                color = c.inkFaint,
                            )
                        }
                    }
                    if (showHistory) {
                        Spacer(Modifier.height(AmSpace.s2))
                        // 原样呈现：崩溃堆栈重排过就不好读了
                        Text(persisted, style = AmType.data, color = c.inkMid)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(level: LogLevel, selected: Boolean, onClick: () -> Unit) {
    val c = amColors
    Row(
        Modifier
            .background(if (selected) c.ink else c.surface, RoundedCornerShape(AmShape.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            level.name,
            style = AmType.data,
            color = if (selected) c.surface else c.inkMid,
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit) {
    val c = amColors
    val tone = when (entry.level) {
        LogLevel.ERROR -> c.accent
        LogLevel.WARN -> c.warn
        LogLevel.INFO -> c.ok
        LogLevel.DEBUG -> c.inkFaint
    }
    AmCard(Modifier.fillMaxWidth().clickable(onClick = onToggle), alert = entry.level == LogLevel.ERROR) {
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(tone)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.message, style = AmType.body, color = c.ink)
                Text(
                    TIME.format(Date(entry.timestampMs)) + " · " + entry.tag,
                    style = AmType.data,
                    color = c.inkFaint,
                )
                if (entry.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 堆栈很长，默认只给一行；点开才铺全，否则一条崩溃就吃掉整屏
                        if (expanded) entry.detail else entry.detail.lineSequence().first().take(120),
                        style = AmType.data,
                        color = c.inkMid,
                    )
                }
            }
        }
    }
}
