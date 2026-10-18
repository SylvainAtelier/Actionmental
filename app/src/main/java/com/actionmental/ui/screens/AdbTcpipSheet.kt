package com.actionmental.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.actionmental.core.action.Action
import com.actionmental.ui.ShortcutEditorViewModel
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSegmented
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.i18n.localize
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import kotlinx.coroutines.launch

/** 端口的快捷选项：5555 是 adb 自己的默认，6677 是避开它、不和别的设备撞的那个。 */
private val COMMON_PORTS = listOf(5555, Action.AdbTcpip.DEFAULT_PORT)

/**
 * ADB 网络调试的配置。
 *
 * 顺序按做决定的顺序排：先选开还是关，再定端口 —— 端口一改，下面那条电脑上要敲的命令跟着变，
 * 输入的就是最终要用的东西，不用在脑子里拼。最后才是前提：
 * 缺哪一项就在那一行上补，首次配对也可以在这里先做掉，免得第一次按快捷键时多等几秒。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdbTcpipSheet(
    current: Action.AdbTcpip?,
    vm: ShortcutEditorViewModel,
    onConfirm: (Action.AdbTcpip) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = amColors
    val context = LocalContext.current
    var enable by remember { mutableStateOf(current?.enable ?: true) }
    var portText by remember { mutableStateOf((current?.port ?: Action.AdbTcpip.DEFAULT_PORT).toString()) }
    var copyAddress by remember { mutableStateOf(current?.copyAddress ?: true) }
    val port = Action.AdbTcpip.parsePort(portText)

    var root by remember { mutableStateOf(vm.adbRootAvailable()) }
    var shizuku by remember { mutableStateOf(vm.adbShizukuUsable()) }
    var wireless by remember { mutableStateOf(vm.adbWirelessEnabled()) }
    var paired by remember { mutableStateOf(vm.adbPaired()) }
    var ip by remember { mutableStateOf(vm.wifiAddress()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        root = vm.adbRootAvailable()
        shizuku = vm.adbShizukuUsable()
        wireless = vm.adbWirelessEnabled()
        paired = vm.adbPaired()
        ip = vm.wifiAddress()
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<String>?>(null) }
    val scope = rememberCoroutineScope()

    InputSheet(
        title = "ADB 网络调试",
        subtitle = "让 adbd 在固定端口上监听，电脑上 adb connect 一次就连上。等同 adb tcpip。",
        confirmText = "设为 ADB 动作",
        confirmEnabled = !enable || port != null,
        footnote = "执行时 adbd 会重启，此刻的 adb 连接（包括无线调试）会断开一次，重连即可。端口在重启手机后失效。",
        onConfirm = {
            onConfirm(Action.AdbTcpip(port ?: current?.port ?: Action.AdbTcpip.DEFAULT_PORT, enable, copyAddress))
        },
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            AmSegmented(
                options = listOf("开启（tcpip）", "关闭（usb）"),
                selectedIndex = if (enable) 0 else 1,
                onSelect = { enable = it == 0 },
            )
            Text(
                if (enable) "adbd 额外在这个端口上监听 TCP，USB 与无线调试照常可用"
                else "收回 TCP 端口，只留 USB 与无线调试 · 等同 adb usb",
                style = AmType.data,
                color = c.inkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(AmSpace.s3))

            if (enable) {
                // --- 端口 ---------------------------------------------------------
                AmLabel("端口 · PORT")
                Spacer(Modifier.height(4.dp))
                val portDescription = localize("端口")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { raw -> portText = raw.filter(Char::isDigit).take(5) },
                        singleLine = true,
                        isError = port == null,
                        textStyle = AmType.keycapLarge.copy(color = c.ink),
                        placeholder = { Text(Action.AdbTcpip.DEFAULT_PORT.toString(), style = AmType.keycapLarge, color = c.inkFaint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(AmShape.key + 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = portDescription },
                    )
                    Spacer(Modifier.width(AmSpace.s1))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        COMMON_PORTS.forEach { option ->
                            AmChip(option.toString(), port == option) { portText = option.toString() }
                        }
                    }
                }
                // 错误紧贴在输入框下面，说清范围；没错时同一行放提示，高度不跳
                Text(
                    if (port == null) "端口要在 1024–65535 之间" else "1024 以下是特权端口，adbd 绑不上",
                    style = AmType.data,
                    color = if (port == null) c.accent else c.inkFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(AmSpace.s3))

                // --- 电脑上要敲的那一行 ---------------------------------------------
                AmLabel("电脑上执行 · ON YOUR COMPUTER")
                Spacer(Modifier.height(4.dp))
                val command = "adb connect " + (ip ?: "<IP>") + ":" + (port?.toString() ?: "—")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text(
                        command,
                        style = AmType.data,
                        color = if (port == null) c.inkFaint else c.ink,
                        modifier = Modifier
                            .weight(1f)
                            .background(c.surfaceSunken, RoundedCornerShape(AmShape.key))
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    AmSecondaryButton(
                        "复制",
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("adb", command))
                        },
                        enabled = port != null && ip != null,
                    )
                }
                if (ip == null) {
                    Text("没连 Wi-Fi · IP 以执行时为准", style = AmType.data, color = c.warn, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(AmSpace.s2))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("完成后复制地址", style = AmType.body, color = c.ink)
                        Text(
                            if (copyAddress) "开启 · IP:端口 写进剪贴板，直接贴到 adb connect 后面" else "关闭 · 地址只显示在顶部提示里",
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                    }
                    AmSwitch(copyAddress, onCheckedChange = { copyAddress = it })
                }
                Spacer(Modifier.height(AmSpace.s3))
            }

            // --- 前提 -----------------------------------------------------------
            AmLabel("前提 · REQUIREMENTS")
            Spacer(Modifier.height(4.dp))
            if (root) {
                TermuxRequirement(ok = true, text = "Shizuku 以 root 运行 · 直接改 adbd 的端口，不需要无线调试与配对")
            } else {
                TermuxRequirement(
                    ok = wireless != false,
                    warnOnly = wireless == null,
                    text = when (wireless) {
                        true -> "无线调试已开启"
                        false -> "无线调试未开启 · 本应用经它向 adbd 发请求"
                        null -> "无法确认无线调试是否开启"
                    },
                    action = if (wireless != true) "去开启" else null,
                    onAction = vm::openDeveloperOptions,
                )
                TermuxRequirement(
                    ok = paired,
                    warnOnly = !paired && shizuku,
                    text = when {
                        paired -> "已与本机 adbd 配对 · 之后不再需要 Shizuku"
                        shizuku -> "尚未配对 · 首次执行时经 Shizuku 自动完成"
                        else -> "尚未配对 · 首次配对需要 Shizuku"
                    },
                )
                Spacer(Modifier.height(AmSpace.s1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmSecondaryButton(
                        when {
                            testing -> "连接中…"
                            paired -> "测试连接"
                            else -> "立即配对"
                        },
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = vm.checkAdb()
                                paired = vm.adbPaired()
                                testing = false
                            }
                        },
                        enabled = !testing && wireless != false && (paired || shizuku),
                    )
                    Spacer(Modifier.width(8.dp))
                    testResult?.let { result ->
                        // 结论既用颜色也用符号，原因原样给 —— 要照着它去改
                        Text(
                            result.fold({ "✓ $it" }, { "✗ " + (it.message ?: "") }),
                            style = AmType.data,
                            color = if (result.isSuccess) c.ok else c.accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    "只连接，不切端口，不会断开任何连接。配对后在开发者选项「已配对的设备」里显示为 Actionmental",
                    style = AmType.data,
                    color = c.inkFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(AmSpace.s1))
        }
    }
}
