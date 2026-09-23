package com.actionmental.ui.screens

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.R
import com.actionmental.core.hardening.HardeningRecord
import com.actionmental.core.hardening.HardeningStep
import com.actionmental.core.rotation.RotationMode
import com.actionmental.platform.HardeningNotifier
import com.actionmental.service.ForceLandscapeTileService
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.DataRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hardeningStamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

/** Shizuku 状态 + 后台加固 / 自愈 + 磁贴管理（PRD 6 / 10 / 11 / 24）。 */
@Composable
fun PrivilegeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = amColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rotation by vm.rotationState.collectAsStateWithLifecycle()
    val hardening by vm.hardeningState.collectAsStateWithLifecycle()
    val hardeningBusy by vm.hardeningBusy.collectAsStateWithLifecycle()
    val records by vm.hardeningRecords.collectAsStateWithLifecycle()
    val shizuku = status.shizuku
    val access by vm.settingsAccess.collectAsStateWithLifecycle()
    // 自愈只读写 secure 表：Shizuku 与 adb 授予的 WRITE_SECURE_SETTINGS 任一即可
    val healWritable = shizuku.usable || access.secure

    // 进页面就复查一次真实状态：加固是「系统里现在是什么样」，不是「我点过没点过」。
    // Shizuku 从不可用变可用时也要重查，否则会一直停在「未知」。
    LaunchedEffect(shizuku.usable) {
        if (shizuku.usable) vm.verifyHardening()
    }
    LaunchedEffect(Unit) { vm.markHardeningSeen() }

    // 通知权限不是流，只能在回到前台时重新问一次系统 —— 用户可能刚从设置页回来
    var canNotify by remember { mutableStateOf(vm.canNotify()) }
    var permissionAsked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = vm.canNotify() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canNotify = vm.canNotify() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        ScreenTitle("Shizuku 与快捷设置磁贴", "PRIVILEGE & TILES · 状态一律以真实系统值为准")

        AmCard(Modifier.fillMaxWidth(), alert = !shizuku.usable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (shizuku.usable) c.ok else c.warn)
                Text(
                    if (shizuku.usable) "已连接并授权" else shizuku.conclusion,
                    style = AmType.cardTitle,
                    color = if (shizuku.usable) c.ink else c.accent,
                )
                Spacer(Modifier.weight(1f))
                AmLabel(if (shizuku.usable) "GRANTED" else "BLOCKED")
            }
            Spacer(Modifier.height(AmSpace.s2))
            DataRow("版本 version", shizuku.versionCode.takeIf { it >= 0 }?.toString() ?: "—")
            DataRow("运行身份 uid", shizuku.identity)
            DataRow("特权服务 binder", if (shizuku.serviceBound) "alive" else "not bound")
            DataRow("旋转能力", status.rotationControlHealth.label)
            Spacer(Modifier.height(AmSpace.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                if (!shizuku.granted && shizuku.running) {
                    AmSecondaryButton("请求授权", vm::requestShizukuPermission, accent = true)
                }
                AmSecondaryButton("重新连接", vm::reconnectShizuku)
                AmSecondaryButton("重新检测", vm::refreshAll)
            }
            if (!shizuku.installed) {
                Spacer(Modifier.height(AmSpace.s1))
                Text(
                    "未检测到 Shizuku。快捷键与按键检测仍然可用；屏幕方向与键位映射降级运行，能力见下方「免 Shizuku 授权」。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("能力矩阵 · CAPABILITY MATRIX")
            Spacer(Modifier.height(4.dp))
            val ok = shizuku.usable
            DataRow(
                "settings put user_rotation",
                when {
                    ok && rotation.userRotation != null -> "OK"
                    ok -> "PARTIAL"
                    access.system -> "OK · 应用直写"
                    else -> "不可用"
                },
            )
            DataRow("悬浮层强制方向", if (status.accessibilityConnected) "可用 · 无需 Shizuku" else "不可用 · 无障碍未连接")
            DataRow("wm ignore-orientation-request", if (rotation.ignoreAppRequest != null) "OK" else if (ok) "PARTIAL · OEM 限制" else "不可用")
            DataRow("fixed_to_user_rotation", if (rotation.fixedToUserRotation != null) "OK" else if (ok) "PARTIAL · OEM 限制" else "不可用")
            DataRow("am start / launch app", "OK · 普通 API")
            DataRow("自定义 Shell 动作", if (ok) "需逐条确认" else "不可用")
        }

        WithoutShizukuCard(
            systemWritable = access.system,
            secureWritable = access.secure,
            command = vm.secureSettingsCommand(),
            onGrantSystem = vm::openWriteSettings,
            onCopy = { clipboard.setText(AnnotatedString(vm.secureSettingsCommand())) },
            onRecheck = vm::refreshSettingsAccess,
        )

        AmCard(Modifier.fillMaxWidth(), alert = shizuku.usable && hardening.checked && !hardening.hardened) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmLabel("后台加固 · BACKGROUND HARDENING")
                Spacer(Modifier.weight(1f))
                AmLabel(
                    when {
                        !shizuku.usable -> "UNAVAILABLE"
                        !hardening.checked -> "UNKNOWN"
                        hardening.hardened -> "HARDENED"
                        else -> "PARTIAL"
                    },
                    color = if (hardening.hardened) c.ok else c.inkMuted,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "监听服务由系统绑定，进程本身不需要保活。真正会断的是省电策略 —— " +
                    "这三条用 Shizuku 一次性写进系统，之后长期生效，不常驻任何东西。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s2))
            HardeningStep.entries.forEach { step ->
                HardeningStepRow(step, hardening.valueOf(step), shizuku.usable)
            }
            Spacer(Modifier.height(AmSpace.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmSecondaryButton(
                    if (hardeningBusy) "执行中…" else "一键加固",
                    vm::runHardening,
                    enabled = shizuku.usable && !hardeningBusy,
                    accent = true,
                )
                AmSecondaryButton(
                    "重新检测",
                    vm::verifyHardening,
                    enabled = shizuku.usable && !hardeningBusy,
                )
            }
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                if (hardening.checked) {
                    "上次检测 " + hardeningStamp.format(Date(hardening.checkedAtMs)) + " · 每条命令都记在下面的历史里"
                } else {
                    "还没检测过。三项状态一律来自系统真实值，不是「我点过按钮」。"
                },
                style = AmType.data,
                color = c.inkFaint,
            )
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                "厂商自启动白名单只能在系统设置里手动加，这里做不到 —— 小米 / OPPO / vivo / 华为请另外去开。",
                style = AmType.secondary,
                color = c.accent,
            )
        }

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (settings.keepAlive) c.ok else c.inkFaint)
                Text("常驻运行", style = AmType.cardTitle, color = c.ink)
                Spacer(Modifier.weight(1f))
                AmSwitch(
                    checked = settings.keepAlive,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(keepAlive = on) } },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "设备上记录到的每一次被杀都是同一条 bgLimit_level_thermal —— 温控升到高位后，" +
                    "系统清理的是「后台」进程，而不是「占得多」的进程：内存从 977MB 压到 104MB 之后照样被杀。" +
                    "开启后本应用算作前台，那条清理挑不到它。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "代价是通知栏里一条撤不掉的通知。它没有信息量，只是前台服务的门票，可以在系统里把它折叠起来。",
                style = AmType.secondary,
                color = c.inkFaint,
            )
            Spacer(Modifier.height(AmSpace.s2))
            DataRow("当前状态", if (settings.keepAlive) "常驻中" else "未常驻")
            DataRow("通知权限", if (canNotify) "已开启" else "未开启 · 常驻通知发不出来", if (canNotify) null else c.accent)
        }

        AmCard(Modifier.fillMaxWidth(), alert = !status.accessibilityEnabledInSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (status.accessibilityEnabledInSettings) c.ok else c.warn)
                Text("监听服务自愈", style = AmType.cardTitle, color = c.ink)
                Spacer(Modifier.weight(1f))
                AmSwitch(
                    checked = settings.autoHealAccessibility,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(autoHealAccessibility = on) } },
                    enabled = healWritable,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "部分 ROM 会在重启或省电时把第三方无障碍开关踢掉。开启后会用 Shizuku（或 adb 授予的 WRITE_SECURE_SETTINGS）把监听服务" +
                    "重新写回 enabled_accessibility_services —— 只追加，不覆盖别人的服务。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s2))
            DataRow(
                "系统开关",
                if (status.accessibilityEnabledInSettings) "已开启" else "已关闭",
                if (status.accessibilityEnabledInSettings) null else c.accent,
            )
            DataRow("服务实例", if (status.accessibilityConnected) "已连接" else "未连接")
            DataRow("曾经授权过", if (settings.accessibilityEverEnabled) "是" else "否 · 自愈不会生效")
            DataRow(
                "开机后恢复",
                if (settings.autoHealAccessibility) "已启用" else "未启用",
            )
            DataRow("通知栏提醒", if (canNotify) "已开启" else "未开启", if (canNotify) null else c.accent)
            Spacer(Modifier.height(AmSpace.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmSecondaryButton(
                    "立即恢复",
                    vm::healAccessibilityNow,
                    enabled = healWritable && !hardeningBusy,
                    accent = true,
                )
                if (!canNotify) {
                    AmSecondaryButton("开启通知", onClick = {
                        // 系统只给一次弹窗机会，被拒之后再点就只能送去设置页
                        val canAsk = HardeningNotifier.requiresRuntimePermission &&
                            !vm.notificationPermissionGranted() && !permissionAsked
                        if (canAsk) {
                            permissionAsked = true
                            notificationLauncher.launch(HardeningNotifier.permission)
                        } else {
                            openNotificationSettings(context)
                        }
                    })
                }
            }
            if (!canNotify) {
                Spacer(Modifier.height(AmSpace.s1))
                Text(
                    "不开通知也不影响自愈，只是自动恢复发生在后台时，你要打开应用才看得到。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                "开机时同样会跑一次 —— 那正是 ROM 最常动手的时刻，而那一刻应用还没启动，" +
                    "所以要靠开机广播把进程拉起来补救。非 root 的 Shizuku 重启后需要你先重新拉起，" +
                    "它没起来时这次恢复会失败，并在历史里如实记下原因。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                "只在「你自己开启过」且 Shizuku 可用（或授过 WRITE_SECURE_SETTINGS）时才会自动写回，自动重试有冷却与失败上限，" +
                    "不会和系统反复拉锯。注意：你主动在系统设置里关掉它，这里同样会写回去 —— 不想要就关掉这个开关。",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmLabel("加固与自愈历史 · " + records.size + " 条")
                Spacer(Modifier.weight(1f))
                AmSecondaryButton(
                    "复制",
                    { clipboard.setText(AnnotatedString(vm.exportHardeningHistory())) },
                    enabled = records.isNotEmpty(),
                )
                AmSecondaryButton(
                    "清空",
                    vm::clearHardeningHistory,
                    Modifier.padding(start = AmSpace.s1),
                    enabled = records.isNotEmpty(),
                )
            }
            Spacer(Modifier.height(AmSpace.s2))
            if (records.isEmpty()) {
                Text(
                    "还没有记录。这里只记两件事：加固动了哪一条系统开关，自愈在什么时候把监听服务写了回去。" +
                        "它落盘保存，进程重启也不会丢 —— 自愈恰好发生在你没看屏幕的时候。",
                    style = AmType.data,
                    color = c.inkFaint,
                )
            } else {
                records.forEach { record -> HardeningRecordRow(record) }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("磁贴预览 · TILE STATES")
            Spacer(Modifier.height(AmSpace.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                TilePreview("强制横屏", "已开启 · ACTIVE", TileVisual.ACTIVE, Modifier.weight(1f))
                TilePreview("强制横屏", "已关闭 · INACTIVE", TileVisual.INACTIVE, Modifier.weight(1f))
                TilePreview("强制横屏", "不可用 · UNAVAILABLE", TileVisual.UNAVAILABLE, Modifier.weight(1f))
            }
            Spacer(Modifier.height(AmSpace.s2))
            Text(
                "当前真实状态：" + (if (!rotation.available) "不可用" else if (rotation.mode == RotationMode.FORCE_LANDSCAPE) "已开启" else "已关闭") +
                    "。不可用态不会显示为「已关闭」——避免误导。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Spacer(Modifier.height(AmSpace.s2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AmSecondaryButton("请求添加磁贴", { requestAddTile(context) }, accent = true)
            } else {
                Text(
                    "长按快捷设置面板 › 编辑 › 把 Actionmental 磁贴拖到前排。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
        }
    }
}

@Composable
private fun HardeningStepRow(step: HardeningStep, value: Boolean?, available: Boolean) {
    val c = amColors
    val tone: Color = when {
        !available -> c.inkFaint
        value == true -> c.ok
        value == false -> c.warn
        else -> c.inkFaint
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone)
            Text(step.title, style = AmType.body, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                when {
                    !available -> "不可用"
                    value == true -> "已生效"
                    value == false -> "未生效"
                    else -> "未知"
                },
                style = AmType.data,
                color = tone,
            )
        }
        Text(step.technical, style = AmType.data, color = c.inkFaint)
        Text(step.why, style = AmType.secondary, color = c.inkMid)
    }
}

/** 历史里的一条。折起来只给结论，展开才给系统原样返回的那一段。 */
@Composable
private fun HardeningRecordRow(record: HardeningRecord) {
    val c = amColors
    var expanded by remember(record.id) { mutableStateOf(false) }
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
            StatusDot(if (record.ok) c.ok else c.warn)
            Text(record.kind.label, style = AmType.label, color = c.inkMuted)
            Spacer(Modifier.weight(1f))
            Text(
                hardeningStamp.format(Date(record.timestampMs)),
                style = AmType.label,
                color = c.inkFaint,
            )
        }
        Text(record.step, style = AmType.body, color = c.ink)
        if (record.detail.isNotBlank()) {
            Text(
                record.detail,
                style = AmType.data,
                color = if (record.ok) c.inkMid else c.accent,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
            )
        }
    }
}

private enum class TileVisual { ACTIVE, INACTIVE, UNAVAILABLE }

@Composable
private fun TilePreview(title: String, subtitle: String, visual: TileVisual, modifier: Modifier = Modifier) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.card)
    val face = when (visual) {
        TileVisual.ACTIVE -> c.ink
        TileVisual.INACTIVE -> c.surfaceSunken
        TileVisual.UNAVAILABLE -> c.surface
    }
    val ink = when (visual) {
        TileVisual.ACTIVE -> c.bgScreen
        TileVisual.INACTIVE -> c.inkMid
        TileVisual.UNAVAILABLE -> c.warn
    }
    Column(
        modifier
            .background(face, shape)
            .border(1.dp, if (visual == TileVisual.UNAVAILABLE) c.warn else c.line, shape)
            .padding(AmSpace.card),
    ) {
        Text(title, style = AmType.body, color = ink, maxLines = 1)
        Text(subtitle, style = AmType.label, color = ink)
    }
}

/**
 * 不装 Shizuku 也能拿到的两项授权。
 *
 * 它们各自替代 Shizuku 的一小块：「修改系统设置」让旋转在降级时也写得了自动旋转与锁定角度；
 * WRITE_SECURE_SETTINGS 让无障碍自愈不再依赖 Shizuku —— 开机那一刻 Shizuku 往往还没起来，
 * 而那恰恰是 ROM 最常关掉无障碍开关的时候。
 */
@Composable
private fun WithoutShizukuCard(
    systemWritable: Boolean,
    secureWritable: Boolean,
    command: String,
    onGrantSystem: () -> Unit,
    onCopy: () -> Unit,
    onRecheck: () -> Unit,
) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmLabel("免 Shizuku 授权 · WITHOUT SHIZUKU")
            Spacer(Modifier.weight(1f))
            AmSecondaryButton("重新检测", onRecheck)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Shizuku 不在时，这两项让一部分能力照常工作。都可以不给，不给时对应项如实显示不可用。",
            style = AmType.secondary,
            color = c.inkMid,
        )
        Spacer(Modifier.height(AmSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (systemWritable) c.ok else c.inkFaint)
            Column(Modifier.weight(1f)) {
                Text("修改系统设置", style = AmType.body, color = c.ink)
                Text(
                    "旋转降级时写自动旋转开关与锁定角度",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
            if (systemWritable) AmLabel("GRANTED", color = c.ok)
            else AmSecondaryButton("去授权", onGrantSystem, accent = true)
        }
        Spacer(Modifier.height(AmSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (secureWritable) c.ok else c.inkFaint)
            Column(Modifier.weight(1f)) {
                Text("WRITE_SECURE_SETTINGS", style = AmType.body, color = c.ink)
                Text(
                    "监听服务自愈不再需要 Shizuku，开机即可用；也覆盖上一项",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
            if (secureWritable) AmLabel("GRANTED", color = c.ok)
            else AmSecondaryButton("复制命令", onCopy)
        }
        if (!secureWritable) {
            Spacer(Modifier.height(AmSpace.s1))
            Text(
                "在电脑上执行一次（手机开 USB 调试），重启不丢：",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Text(command, style = AmType.data, color = c.ink)
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun requestAddTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return
    runCatching {
        manager.requestAddTileService(
            ComponentName(context, ForceLandscapeTileService::class.java),
            context.getString(R.string.tile_force_landscape),
            Icon.createWithResource(context, R.drawable.ic_tile_force_landscape),
            { runnable -> runnable.run() },
            { },
        )
    }
}
