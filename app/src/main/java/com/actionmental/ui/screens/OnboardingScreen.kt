package com.actionmental.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.R
import com.actionmental.core.action.Action
import com.actionmental.core.key.KeyCombo
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.DataRow
import com.actionmental.ui.components.KeyComboRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 首次启动引导，8 步（设计稿 3a）。
 *
 * 规则：每一步显示的都是当前真实检测结果（外层 1.5s 轮询），
 * 不用本地标记记录「已完成」；三项能力互相独立，Shizuku 可跳过。
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val c = amColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status by vm.status.collectAsStateWithLifecycle()
    val snapshot by vm.keySnapshot.collectAsStateWithLifecycle()
    val device by vm.lastDevice.collectAsStateWithLifecycle()

    var step by rememberSaveable { mutableStateOf(0) }
    val presetSelection = remember { mutableStateListOf(0, 1, 2, 3) }

    fun finish() = vm.updateSettings { it.copy(onboardingDone = true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgScreen)
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
    ) {
        Spacer(Modifier.height(AmSpace.s4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = c.ink,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Action", style = AmType.cardTitle, color = c.ink)
            Text("mental", style = AmType.cardTitle, color = c.accent)
            Spacer(Modifier.weight(1f))
            AmLabel("STEP " + "%02d".format(step + 1) + " / 08")
        }
        Spacer(Modifier.height(AmSpace.s2))
        StepBar(step)
        Spacer(Modifier.height(AmSpace.s4))

        when (step) {
            0 -> StepWelcome()
            1 -> StepPermissionList(status.accessibilityEnabledInSettings, status.shizuku.usable, status.keyboardConnected)
            2 -> StepAccessibility(status.accessibilityEnabledInSettings, status.accessibilityWaitingForBind) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            3 -> StepShizuku(vm, clipboard::setText)
            4 -> StepKeyboard(snapshot.displayTokens(), device)
            5 -> StepPresets(presetSelection)
            6 -> StepTiles()
            else -> StepDone(status.shortcutCount)
        }

        Spacer(Modifier.height(AmSpace.s4))
        Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            if (step > 0) {
                AmSecondaryButton("上一步", { step-- })
            }
            AmPrimaryButton(
                if (step >= 7) "进入状态中心" else "下一步",
                onClick = {
                    if (step >= 7) {
                        finish()
                    } else {
                        if (step == 5) applyPresets(vm, presetSelection)
                        step++
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (step in 1..6) {
            Spacer(Modifier.height(AmSpace.s1))
            AmSecondaryButton("跳过引导", { finish() }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(AmSpace.s4))
    }
}

@Composable
private fun StepBar(step: Int) {
    val c = amColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(8) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        if (index <= step) c.ink else c.keycapLine,
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

/** 06 步的推荐预设。与系统占用冲突的项默认不勾选。 */
private data class Preset(
    val combo: KeyCombo,
    val action: Action,
    val conflictsWithSystem: Boolean = false,
)

private val PRESETS = listOf(
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_L, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT),
        Action.Rotation(Action.Rotation.Op.TOGGLE_LANDSCAPE),
    ),
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_P, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT),
        Action.Rotation(Action.Rotation.Op.RESTORE),
    ),
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_DPAD_LEFT, KeyCombo.MOD_ALT),
        Action.Navigation(Action.Navigation.Target.BACK),
    ),
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_H, KeyCombo.MOD_META),
        Action.Navigation(Action.Navigation.Target.HOME),
    ),
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_SPACE, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT),
        Action.Media(Action.Media.Target.PLAY_PAUSE),
    ),
    Preset(
        KeyCombo(android.view.KeyEvent.KEYCODE_R, KeyCombo.MOD_CTRL or KeyCombo.MOD_ALT),
        Action.Navigation(Action.Navigation.Target.RECENTS),
        conflictsWithSystem = true,
    ),
)

private fun applyPresets(vm: AppViewModel, selection: List<Int>) {
    val chosen = selection.mapNotNull { PRESETS.getOrNull(it) }
    vm.addPresets(chosen.map { it.combo to it.action })
}

@Composable
private fun StepWelcome() {
    val c = amColors
    Column {
        Text("把实体键盘", style = AmType.pageTitle, color = c.ink)
        Text("变成系统遥控器", style = AmType.pageTitle, color = c.accent)
        Spacer(Modifier.height(6.dp))
        AmLabel("ACTIONMENTAL · 无需 ROOT")
        Spacer(Modifier.height(AmSpace.s3))
        listOf(
            Triple("全局快捷键", "任意应用中触发系统动作", "SHORTCUTS"),
            Triple("强制横屏 / 竖屏", "忽略应用自身方向声明", "ROTATION"),
            Triple("真实状态磁贴", "状态取自系统，不是本地开关", "QUICK SETTINGS"),
        ).forEach { (title, subtitle, label) ->
            AmCard(Modifier.fillMaxWidth().padding(bottom = AmSpace.s1)) {
                Text(title, style = AmType.body, color = c.ink)
                Text(subtitle, style = AmType.secondary, color = c.inkMid)
                AmLabel(label)
            }
        }
        Text("本地运行 · 不联网 · 不记录输入内容", style = AmType.data, color = c.inkFaint)
    }
}

@Composable
private fun StepPermissionList(accessibility: Boolean, shizuku: Boolean, keyboard: Boolean) {
    val c = amColors
    Column {
        Text("需要的权限", style = AmType.pageTitle, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "三项能力互相独立，缺一项只会关闭对应功能，不影响其他部分。",
            style = AmType.secondary,
            color = c.inkMid,
        )
        Spacer(Modifier.height(AmSpace.s2))
        PermissionRow("无障碍键盘服务", "必需", "用于接收实体键盘按键。没有它无法监听快捷键。",
            "AccessibilityService · 仅读取按键事件", accessibility)
        PermissionRow("Shizuku", "旋转控制需要", "用于强制屏幕方向与启动部分系统动作。可以稍后再配。",
            "adb shell 权限 · 不需要 Root", shizuku)
        PermissionRow("实体键盘", "检测", "USB 或蓝牙键盘。稍后会做一次按键确认。",
            "InputDevice · KEYBOARD_TYPE_ALPHABETIC", keyboard)
        Spacer(Modifier.height(AmSpace.s2))
        Text("不申请：网络、通讯录、存储、位置。", style = AmType.data, color = c.inkFaint)
    }
}

@Composable
private fun PermissionRow(title: String, badge: String, body: String, technical: String, ok: Boolean) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth().padding(bottom = AmSpace.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (ok) c.ok else c.warn)
            Text(title, style = AmType.body, color = c.ink, modifier = Modifier.weight(1f))
            AmLabel(if (ok) "已就绪" else badge, color = if (ok) c.ok else c.warn)
        }
        Text(body, style = AmType.secondary, color = c.inkMid)
        Text(technical, style = AmType.data, color = c.inkFaint)
    }
}

@Composable
private fun StepAccessibility(enabled: Boolean, waitingForBind: Boolean, onOpenSettings: () -> Unit) {
    val c = amColors
    Column {
        Text("开启键盘服务", style = AmType.pageTitle, color = c.ink)
        AmLabel("STEP 03 · ACCESSIBILITY")
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth(), alert = !enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (enabled) c.ok else c.accent)
                Text(
                    if (enabled) "已开启" else "等待开启",
                    style = AmType.cardTitle,
                    color = if (enabled) c.ink else c.accent,
                )
            }
            Text(
                "settings = " + (if (enabled) "ON" else "OFF") + " · 每 1.5 秒轮询真实状态",
                style = AmType.data,
                color = c.inkFaint,
            )
            if (waitingForBind) {
                Text(
                    "系统里已经打开，但服务还没连上：关掉再打开一次即可。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
            }
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("操作路径 · PATH")
            Spacer(Modifier.height(4.dp))
            listOf(
                "1 · 点击下方按钮跳转系统设置",
                "2 · 已下载的应用 › Actionmental",
                "3 · 打开「使用此服务」",
                "4 · 返回本页，状态会自动刷新",
            ).forEach { Text(it, style = AmType.secondary, color = c.inkMid) }
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("这个服务能看到什么 · SCOPE")
            Spacer(Modifier.height(4.dp))
            DataRow("读取", "实体键盘的按键码与修饰键状态")
            DataRow("读取", "当前前台应用包名（用于应用规则）")
            DataRow("不会", "保存任何文本输入")
            DataRow("不会", "读取屏幕内容或联网上传")
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmSecondaryButton("前往系统设置", onOpenSettings, accent = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepShizuku(vm: AppViewModel, copy: (AnnotatedString) -> Unit) {
    val c = amColors
    val status by vm.status.collectAsStateWithLifecycle()
    val shizuku = status.shizuku
    val command = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

    Column {
        Text("配置 Shizuku", style = AmType.pageTitle, color = c.ink)
        AmLabel("STEP 04 · " + shizuku.conclusion)
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            DataRow("已安装 Shizuku", if (shizuku.installed) "是" else "否")
            DataRow("服务正在运行", if (shizuku.running) "是" else "未运行")
            DataRow("已授权本应用", if (shizuku.granted) "是" else "等待")
            DataRow("运行身份", shizuku.identity)
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("方式 A · 无线调试（推荐，重启后需重连）")
            Spacer(Modifier.height(4.dp))
            listOf(
                "1 · 开发者选项 › 打开「无线调试」",
                "2 · Shizuku › 通过无线调试启动",
                "3 · 配对码配对后自动运行",
            ).forEach { Text(it, style = AmType.secondary, color = c.inkMid) }
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("方式 B · 电脑 ADB")
            Spacer(Modifier.height(4.dp))
            Text(command, style = AmType.data, color = c.ink)
            Spacer(Modifier.height(6.dp))
            AmSecondaryButton("复制命令", { copy(AnnotatedString(command)) })
        }
        Spacer(Modifier.height(AmSpace.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            if (shizuku.running && !shizuku.granted) {
                AmSecondaryButton("请求授权", vm::requestShizukuPermission, accent = true)
            }
            AmSecondaryButton("重新检测", vm::refreshAll)
        }
        Spacer(Modifier.height(AmSpace.s1))
        Text(
            "跳过后：快捷键与按键检测正常工作，屏幕方向相关动作与磁贴显示为「不可用」。",
            style = AmType.secondary,
            color = c.inkMid,
        )
    }
}

@Composable
private fun StepKeyboard(tokens: List<String>, device: com.actionmental.core.key.KeyboardDevice) {
    val c = amColors
    val received = tokens.isNotEmpty()
    Column {
        Text("键盘确认", style = AmType.pageTitle, color = c.ink)
        AmLabel(if (received) "已收到按键 · RECEIVED" else "STEP 05 · 按任意键")
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            com.actionmental.ui.components.KeyTokensRow(
                tokens.ifEmpty { listOf("等待按键") },
                large = true,
                highlightLast = received,
            )
        }
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("输入设备 · DEVICE")
            Spacer(Modifier.height(4.dp))
            DataRow("name", device.name)
            DataRow("id / source", device.id.toString() + " · " + device.transport)
            DataRow("vendor / product", device.vendorHex + " / " + device.productHex)
            DataRow("descriptor", device.shortDescriptor.ifBlank { "—" })
        }
        Spacer(Modifier.height(AmSpace.s2))
        Text(
            "descriptor 会作为稳定设备标识保存，用于将来的「仅此键盘」绑定；deviceId 每次连接都会变，不作为标识。",
            style = AmType.secondary,
            color = c.inkMid,
        )
    }
}

@Composable
private fun StepPresets(selection: androidx.compose.runtime.snapshots.SnapshotStateList<Int>) {
    val c = amColors
    Column {
        Text("推荐快捷键", style = AmType.pageTitle, color = c.ink)
        AmLabel("STEP 06 · 已选 " + selection.size + " / " + PRESETS.size)
        Spacer(Modifier.height(AmSpace.s2))
        PRESETS.forEachIndexed { index, preset ->
            val checked = index in selection
            AmCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AmSpace.s1)
                    .androidxClickable { if (checked) selection.remove(index) else selection.add(index) },
                alert = preset.conflictsWithSystem,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (checked) c.ok else c.inkFaint)
                    KeyComboRow(preset.combo)
                    Spacer(Modifier.width(AmSpace.s2))
                    Column(Modifier.weight(1f)) {
                        Text(preset.action.label, style = AmType.body, color = c.ink)
                        if (preset.conflictsWithSystem) {
                            Text("与系统占用冲突", style = AmType.data, color = c.accent)
                        }
                    }
                }
            }
        }
        Text(
            "冲突项默认不勾选；勾选后保存时若真的撞车，会进入覆盖确认。",
            style = AmType.secondary,
            color = c.inkMid,
        )
    }
}

@Composable
private fun StepTiles() {
    val c = amColors
    Column {
        Text("加入快捷设置", style = AmType.pageTitle, color = c.ink)
        AmLabel("STEP 07 · QUICK SETTINGS")
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            Text(
                "长按快捷设置面板 › 编辑 › 把 Actionmental 磁贴拖到前排。Android 13+ 可在「Shizuku 与磁贴」页直接请求添加。",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }
        Spacer(Modifier.height(AmSpace.s2))
        Text(
            "磁贴打开时读取真实状态 → 执行 → 再读一次 → 更新显示。Shizuku 不可用时显示「不可用」，不会显示为已关闭。",
            style = AmType.secondary,
            color = c.inkMid,
        )
    }
}

@Composable
private fun StepDone(shortcutCount: Int) {
    val c = amColors
    Column {
        Text("配置完成", style = AmType.pageTitle, color = c.ink)
        AmLabel("STEP 08 · DONE")
        Spacer(Modifier.height(AmSpace.s2))
        AmCard(Modifier.fillMaxWidth()) {
            DataRow("快捷键", shortcutCount.toString() + " 已添加")
            DataRow("引导", "可在 设置 › 重新运行引导 再次打开")
        }
        Spacer(Modifier.height(AmSpace.s2))
        Text("现在按下你刚才添加的组合键试一下。", style = AmType.secondary, color = c.inkMid)
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
