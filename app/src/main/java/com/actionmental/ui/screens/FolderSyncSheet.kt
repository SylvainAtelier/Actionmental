package com.actionmental.ui.screens

import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.actionmental.core.action.Action
import com.actionmental.core.sync.StoragePaths
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncConflict
import com.actionmental.core.sync.SyncDirection
import com.actionmental.ui.ShortcutEditorViewModel
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 文件夹同步的配置表单。
 *
 * 信息按「一件事一屏」的顺序排：先说清楚两头是哪（手机的哪个文件夹、NAS 的哪个共享），
 * 再说怎么同步（方向、同名怎么办、删不删），最后才是前提与测试。
 * 前提（所有文件访问）写在表单里而不是等按下快捷键才报错，缺哪项就在那一行上补。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderSyncSheet(
    current: Action.FolderSync?,
    vm: ShortcutEditorViewModel,
    onConfirm: (Action.FolderSync) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = amColors
    val initial = current?.config
    val key = remember { initial?.key ?: UUID.randomUUID().toString() }

    var local by remember { mutableStateOf(initial?.localPath.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var share by remember { mutableStateOf(initial?.share.orEmpty()) }
    var remote by remember { mutableStateOf(initial?.remotePath.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(initial?.direction ?: SyncDirection.UPLOAD) }
    var conflict by remember { mutableStateOf(initial?.conflict ?: SyncConflict.NEWER) }
    var deletes by remember { mutableStateOf(initial?.deletes ?: false) }
    var wifiOnly by remember { mutableStateOf(initial?.wifiOnly ?: true) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }

    var storageOk by remember { mutableStateOf(vm.syncStorageGranted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { storageOk = vm.syncStorageGranted() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = StoragePaths.fromDocumentId(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory().path,
            )
            if (path != null) local = path
        }
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<String>?>(null) }
    val scope = rememberCoroutineScope()

    fun build() = SyncConfig(
        key = key,
        localPath = local.trim().trimEnd('/'),
        host = host.trim(),
        share = share.trim().trim('/', '\\'),
        remotePath = remote.trim(),
        username = user.trim(),
        port = initial?.port ?: 445,
        direction = direction,
        deletes = deletes,
        conflict = conflict,
        wifiOnly = wifiOnly,
        title = title.trim(),
    )

    val complete = build().complete
    val hasSavedPassword = remember(key) { vm.syncHasPassword(key) }
    val needsPassword = user.isNotBlank() && password.isEmpty() && !hasSavedPassword

    InputSheet(
        title = "文件夹同步",
        subtitle = "手机共享存储里的文件夹，与 NAS 上的共享目录（SMB）互相同步。",
        confirmText = "设为同步动作",
        confirmEnabled = complete && !needsPassword,
        footnote = "只比对大小与修改时间，不读文件内容；传输先写临时文件再换名，中断不会留下残缺文件。" +
            "同一时刻只跑一个同步。",
        onConfirm = {
            if (password.isNotEmpty()) vm.saveSyncPassword(key, password)
            onConfirm(Action.FolderSync(build()))
        },
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- 两头 ---------------------------------------------------------
            AmLabel("手机 · LOCAL")
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    LabeledField("本地文件夹", local, "/storage/emulated/0/Documents/notes") { local = it }
                }
                Spacer(Modifier.width(6.dp))
                AmSecondaryButton("选择", { picker.launch(null) })
            }
            Spacer(Modifier.height(AmSpace.s3))

            AmLabel("NAS · SMB")
            Spacer(Modifier.height(4.dp))
            LabeledField("地址", host, "192.168.1.10 或 nas.local") { host = it; testResult = null }
            Spacer(Modifier.height(AmSpace.s1))
            LabeledField("共享名", share, "QNAP 上的共享文件夹名，例如 Public") { share = it; testResult = null }
            Spacer(Modifier.height(AmSpace.s1))
            LabeledField("共享内的子目录 · 可选", remote, "例如 Backup/notes，留空为共享根目录") { remote = it }
            Spacer(Modifier.height(AmSpace.s1))
            LabeledField("用户名 · 留空为访客", user, "NAS 上的账户") { user = it; testResult = null }
            Spacer(Modifier.height(AmSpace.s1))
            LabeledField(
                "密码",
                password,
                if (hasSavedPassword) "已保存 · 留空则不修改" else "仅存放在本机的加密存储里",
                secret = true,
            ) { password = it; testResult = null }
            if (needsPassword) {
                Text("填了用户名就需要密码", style = AmType.data, color = c.warn)
            }
            Spacer(Modifier.height(AmSpace.s3))

            // --- 怎么同步 ------------------------------------------------------
            AmLabel("方向 · DIRECTION")
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SyncDirection.entries.forEach { option ->
                    AmChip(option.label, direction == option) {
                        direction = option
                        if (option == SyncDirection.BOTH && conflict == SyncConflict.OVERWRITE) conflict = SyncConflict.NEWER
                    }
                }
            }
            Text(
                when (direction) {
                    SyncDirection.UPLOAD -> "手机 → NAS · 以手机为准，NAS 跟上来"
                    SyncDirection.DOWNLOAD -> "NAS → 手机 · 以 NAS 为准，手机跟上来"
                    SyncDirection.BOTH -> "两边的改动互相合并 · 靠上次同步的记录判断谁改了、谁删了"
                },
                style = AmType.data,
                color = c.inkFaint,
            )
            Spacer(Modifier.height(AmSpace.s2))

            AmLabel("两边同名但内容不同 · ON CONFLICT")
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SyncConflict.entries
                    .filter { it != SyncConflict.OVERWRITE || direction != SyncDirection.BOTH }
                    .forEach { option -> AmChip(option.label, conflict == option) { conflict = option } }
            }
            Text(
                when (conflict) {
                    SyncConflict.NEWER -> "修改时间较新的那份留下，覆盖较旧的"
                    SyncConflict.OVERWRITE -> "无条件用源端覆盖目的端"
                    SyncConflict.KEEP_BOTH -> "覆盖前先把旧的改名为「名称 (冲突 时间)」留着，谁的都不丢"
                    SyncConflict.SKIP -> "这个文件不动，只在结果里计一次冲突"
                },
                style = AmType.data,
                color = c.inkFaint,
            )
            Spacer(Modifier.height(AmSpace.s2))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同步删除", style = AmType.body, color = c.ink)
                    Text(
                        when {
                            !deletes -> "关闭 · 只新增与更新，从不删除任何文件"
                            direction == SyncDirection.UPLOAD -> "开启 · 手机上没有的文件，会从 NAS 上删除"
                            direction == SyncDirection.DOWNLOAD -> "开启 · NAS 上没有的文件，会从手机上删除"
                            else -> "开启 · 一边删除的文件，下次同步会在另一边一并删除"
                        },
                        style = AmType.data,
                        color = if (deletes) c.warn else c.inkFaint,
                    )
                }
                AmSwitch(deletes, onCheckedChange = { deletes = it }, label = "同步删除")
            }
            Spacer(Modifier.height(AmSpace.s1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("仅 Wi-Fi", style = AmType.body, color = c.ink)
                    Text(
                        if (wifiOnly) "移动数据下不同步，省流量" else "任何网络下都会同步",
                        style = AmType.data,
                        color = c.inkFaint,
                    )
                }
                AmSwitch(wifiOnly, onCheckedChange = { wifiOnly = it }, label = "仅 Wi-Fi")
            }
            Spacer(Modifier.height(AmSpace.s2))
            LabeledField("名称 · 可选", title, "列表与通知里显示的名字") { title = it }
            Spacer(Modifier.height(AmSpace.s3))

            // --- 前提与测试 ------------------------------------------------------
            TermuxRequirement(
                ok = storageOk,
                text = if (storageOk) "已允许访问所有文件" else "未允许「所有文件访问」· 读不了上面的本地文件夹",
                action = if (!storageOk) "去授权" else null,
                onAction = vm::openSyncStorageSettings,
            )
            Spacer(Modifier.height(AmSpace.s1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmSecondaryButton(
                    if (testing) "测试中…" else "测试连接",
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = vm.testSync(build(), password)
                            testing = false
                        }
                    },
                    enabled = !testing && host.isNotBlank() && share.isNotBlank() && !needsPassword,
                )
                Spacer(Modifier.width(8.dp))
                testResult?.let { result ->
                    // 结论既用颜色也用文字，原因原样给 —— 要照着它去改
                    Text(
                        result.fold({ "✓ $it" }, { "✗ " + (it.message ?: "") }),
                        style = AmType.data,
                        color = if (result.isSuccess) c.ok else c.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(AmSpace.s1))
        }
    }
}
