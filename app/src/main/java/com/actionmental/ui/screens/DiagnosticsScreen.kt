package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.diag.ShellEntry
import com.actionmental.core.diag.Verdict
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.DataRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Locale

private val logStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * 「已经强制了，这个应用还是不转」的排查页。
 *
 * 三段递进：先看诊断结论（哪一层拦住了），再用对应手段拆掉它，
 * 最后是每一条 shell 的原始输出 —— 结论可能判错，日志原文不会。
 */
@Composable
fun DiagnosticsScreen(vm: AppViewModel, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val c = amColors
    val clipboard = LocalClipboardManager.current
    val status by vm.status.collectAsStateWithLifecycle()
    val report by vm.diagnosis.collectAsStateWithLifecycle()
    val diagnosing by vm.diagnosing.collectAsStateWithLifecycle()
    val log by vm.shellLog.collectAsStateWithLifecycle()
    val foreground = status.foregroundPackage

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        val startButton: @Composable () -> Unit = {
            AmSecondaryButton(
                if (diagnosing) "诊断中…" else "开始诊断",
                vm::runDiagnosis,
                enabled = !diagnosing && status.shizuku.usable,
                accent = true,
            )
        }
        if (embedded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { startButton() }
        } else {
            ScreenTitle("旋转诊断", "DIAGNOSTICS · 为什么这个应用没转") { startButton() }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("检查对象 · TARGET")
            Spacer(Modifier.height(4.dp))
            DataRow("前台应用", foreground ?: "未知（无障碍服务未连接）")
            DataRow("特权后端", status.shizuku.conclusion)
            Spacer(Modifier.height(6.dp))
            Text(
                "诊断只跑只读命令，不改动任何系统状态。把要排查的应用切到前台再回来点「开始诊断」。",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }

        report?.let { r ->
            AmCard(Modifier.fillMaxWidth(), alert = r.blocked) {
                AmLabel("诊断结论 · FINDINGS")
                Spacer(Modifier.height(AmSpace.s1))
                r.findings.forEach { finding ->
                    Row(verticalAlignment = Alignment.Top) {
                        StatusDot(verdictColor(finding.verdict))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(finding.title, style = AmType.body, color = c.ink)
                                Spacer(Modifier.weight(1f))
                                AmLabel(finding.verdict.label, color = verdictColor(finding.verdict))
                            }
                            Text(finding.detail, style = AmType.data, color = c.inkFaint)
                            if (finding.advice.isNotBlank()) {
                                Text(finding.advice, style = AmType.secondary, color = c.inkMid)
                            }
                        }
                    }
                    Spacer(Modifier.height(AmSpace.s2))
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("第一级 · 把强制推到每一块屏幕")
            Spacer(Modifier.height(4.dp))
            Text(
                "ignore-orientation-request 是按屏幕存的，默认只写 display 0。" +
                    "折叠屏内外屏若是两块独立 display，另一块上的强制并不存在。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s2))
            AmSecondaryButton(
                "对所有屏幕下发",
                vm::spreadToAllDisplays,
                enabled = status.shizuku.usable,
                accent = true,
            )
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("第二级 · 应用级兼容覆盖")
            Spacer(Modifier.height(4.dp))
            Text(
                "全局开关只解决「应用请求了竖屏」。应用若声明不可调整大小，" +
                    "画面会停在尺寸兼容模式里加黑边 —— 这一步逐条施加 compat override 拆掉剩下的几层。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                "覆盖存在 system_server 内存里：需要结束应用后重新打开才生效，重启设备后全部失效。",
                style = AmType.secondary,
                color = c.accent,
            )
            Spacer(Modifier.height(AmSpace.s2))
            if (foreground == null) {
                Text("拿不到前台包名，无法定位目标应用。", style = AmType.data, color = c.inkFaint)
            } else {
                DataRow("目标包名", foreground)
                Spacer(Modifier.height(AmSpace.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                    AmSecondaryButton(
                        "施加覆盖",
                        { vm.applyAppOverrides(foreground) },
                        enabled = status.shizuku.usable,
                        accent = true,
                    )
                    AmSecondaryButton(
                        "结束应用",
                        { vm.forceStopApp(foreground) },
                        enabled = status.shizuku.usable,
                    )
                    AmSecondaryButton(
                        "撤销",
                        { vm.resetAppOverrides(foreground) },
                        enabled = status.shizuku.usable,
                    )
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmLabel("SHELL 日志 · " + log.size + " 条")
                Spacer(Modifier.weight(1f))
                AmSecondaryButton("复制", { clipboard.setText(AnnotatedString(vm.exportShellLog())) })
                AmSecondaryButton("清空", vm::clearShellLog, Modifier.padding(start = AmSpace.s1))
            }
            Spacer(Modifier.height(AmSpace.s2))
            if (log.isEmpty()) {
                Text(
                    "还没有记录。执行一次强制旋转或诊断，这里会出现每一条命令与系统原样返回的输出。",
                    style = AmType.data,
                    color = c.inkFaint,
                )
            } else {
                // 新的在上，排查时不用滚到底
                log.asReversed().forEach { entry -> ShellEntryRow(entry) }
            }
        }
    }
}

@Composable
private fun ShellEntryRow(entry: ShellEntry) {
    val c = amColors
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(AmShape.key)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(c.surfaceSunken, shape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (entry.ok) c.ok else c.warn)
            Text(entry.source, style = AmType.label, color = c.inkMuted)
            Spacer(Modifier.weight(1f))
            Text(
                logStamp.format(entry.timestampMs) + " · " + entry.durationMs + "ms · exit " + entry.exitCode,
                style = AmType.label,
                color = c.inkFaint,
            )
        }
        Text(
            "$ " + entry.command,
            style = AmType.data,
            color = c.ink,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        if (expanded) {
            if (entry.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.output, style = AmType.data, color = if (entry.ok) c.inkMid else c.accent)
            }
        } else {
            Text(entry.summary, style = AmType.data, color = c.inkFaint, maxLines = 1)
        }
    }
}

@Composable
private fun verdictColor(verdict: Verdict): Color {
    val c = amColors
    return when (verdict) {
        Verdict.OK -> c.ok
        Verdict.WARN -> c.warn
        Verdict.BLOCKER -> c.accent
        Verdict.UNKNOWN -> c.inkFaint
    }
}
