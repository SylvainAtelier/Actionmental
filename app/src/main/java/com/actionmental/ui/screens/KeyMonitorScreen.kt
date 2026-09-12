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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyTrace
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.DataRow
import com.actionmental.ui.components.KeyTokensRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 实时按键检测（PRD 4.2 / 16）。
 * 只显示内存中的即时状态与最多 200 条事件，不落盘、不联网。
 */
@Composable
fun KeyMonitorScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onBind: (KeyCombo) -> Unit,
) {
    val c = amColors
    val snapshot by vm.keySnapshot.collectAsStateWithLifecycle()
    val traces by vm.keyTraces.collectAsStateWithLifecycle()
    val device by vm.lastDevice.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    // 事件流只在这一页开着的时候记：它跑在按键回调的主线程上，常开会拖慢每一次按键
    DisposableEffect(Unit) {
        vm.setKeyTracing(true)
        onDispose { vm.setKeyTracing(false) }
    }

    val last = snapshot.last
    val tokens = snapshot.displayTokens().ifEmpty { listOf("等待按键") }
    val hasMain = snapshot.pressedKeyCodes.any { !KeyCombo.isModifier(it) }
    val activeCombo = snapshot.activeCombo
    val binding = activeCombo?.let { combo -> shortcuts.firstOrNull { it.combo == combo } }

    Column(
        modifier
            .fillMaxSize()
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        ScreenTitle("实时按键检测", "LIVE KEY MONITOR · 本地处理，不记录文本") {
            AmSecondaryButton("清空日志", vm::clearTraces)
        }

        if (!status.accessibilityConnected) {
            AmCard(Modifier.fillMaxWidth(), alert = true) {
                if (status.accessibilityWaitingForBind) {
                    Text("服务已在系统设置里开启，但还没连上，暂时收不到按键。", style = AmType.body, color = c.accent)
                    Text("到「设置」页点「重新连接」，或在系统设置里关掉再打开。", style = AmType.secondary, color = c.inkMid)
                    Text("settings = ON · service_state = OFF", style = AmType.data, color = c.inkFaint)
                } else {
                    Text("无障碍键盘服务未开启，收不到按键。", style = AmType.body, color = c.accent)
                    Text("settings = OFF · service_state = OFF", style = AmType.data, color = c.inkFaint)
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            KeyTokensRow(tokens, large = true, pressed = snapshot.pressedKeyCodes.isNotEmpty(), highlightLast = hasMain)
            Spacer(Modifier.height(AmSpace.s2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (binding != null) {
                    StatusDot(c.accent)
                    AmLabel("已绑定 BOUND", color = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(binding.displayLabel, style = AmType.body, color = c.ink)
                } else {
                    AmLabel("当前未绑定 UNBOUND")
                }
                Spacer(Modifier.weight(1f))
                if (activeCombo != null && binding == null) {
                    AmSecondaryButton("保存为快捷键", { onBind(activeCombo) }, accent = true)
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("按键原始数据 · RAW")
            Spacer(Modifier.height(4.dp))
            DataRow("keyCode", last?.let { it.keyCode.toString() + " · KEYCODE_" + it.rawKeyName } ?: "—")
            DataRow("scanCode", last?.scanCode?.toString() ?: "—")
            DataRow("action", last?.let { if (it.down) "ACTION_DOWN" else "ACTION_UP" } ?: "—")
            DataRow("metaState", last?.let { "0x" + it.metaState.toString(16) } ?: "—")
            DataRow("repeatCount", last?.repeatCount?.toString() ?: "—")
            DataRow("deviceId", device.id.takeIf { it >= 0 }?.toString() ?: "—")
            DataRow("device", device.name)
            DataRow("vendor / product", device.vendorHex + " / " + device.productHex)
            DataRow("descriptor", device.shortDescriptor.ifBlank { "—" })
        }

        AmCard(Modifier.fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmLabel("事件流 · EVENT STREAM")
                Spacer(Modifier.weight(1f))
                StatusDot(c.ok)
                Text("live", style = AmType.data, color = c.inkFaint)
            }
            Spacer(Modifier.height(4.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(traces.size) {
                if (traces.isNotEmpty()) listState.animateScrollToItem(traces.lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(traces, key = { it.id }) { trace -> TraceRow(trace) }
            }
            Text(
                "缓冲 200 条 · 仅内存 · 不记录文本输入",
                style = AmType.data,
                color = c.inkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val traceFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun TraceRow(trace: KeyTrace) {
    val c = amColors
    val tone = when (trace.kind) {
        KeyTrace.Kind.DOWN -> c.ok
        KeyTrace.Kind.UP -> c.warn
        KeyTrace.Kind.MATCH -> c.accent
        KeyTrace.Kind.VERIFIED -> c.ok
        KeyTrace.Kind.UNBOUND -> c.inkFaint
        KeyTrace.Kind.ERROR -> c.accent
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            traceFormatter.format(trace.timestampMs),
            style = AmType.data,
            color = c.inkFaint,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(tone.copy(alpha = 0.14f), RoundedCornerShape(AmShape.key - 2.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(trace.kind.name, style = AmType.label, color = tone)
        }
        Spacer(Modifier.width(8.dp))
        Text(trace.text, style = AmType.data, color = c.ink)
        Spacer(Modifier.weight(1f))
        Text(trace.detail, style = AmType.data, color = c.inkFaint)
    }
}
