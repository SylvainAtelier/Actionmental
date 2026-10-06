package com.actionmental.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.platform.DesktopIcon
import com.actionmental.ui.i18n.localize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionCatalog
import com.actionmental.core.key.KeyCatalog
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.SystemKeyPolicy
import com.actionmental.core.shortcut.AppScope
import com.actionmental.platform.HardeningNotifier
import com.actionmental.platform.PackageBackend
import com.actionmental.platform.TermuxBackend
import com.actionmental.ui.ShortcutEditorViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmComboField
import com.actionmental.ui.components.AmKeyPickerSheet
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmPickerSheet
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.KeyComboRow
import com.actionmental.ui.components.KeyTokensRow
import com.actionmental.ui.components.PickerGroup
import com.actionmental.ui.components.PickerItem
import com.actionmental.ui.components.PickerTone
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 编辑页里同一时刻最多打开一个弹层。 */
private enum class EditorSheet { KEY, ACTION, APP, URL, SHELL, TERMUX }

/**
 * 编辑 + 录制 + 冲突确认（PRD 15）。
 *
 * 组合键有两条等价的输入路径：录制（按下就是它）与目录选择（键盘上没有的键也能绑）。
 * 动作、按键、应用三个选择器共用同一个二级弹层组件，所以「点开一级 → 挑一条」
 * 在整个应用里是同一个动作。冲突时必须弹层确认，不允许静默覆盖。
 */
@Composable
fun ShortcutEditorScreen(
    modifier: Modifier = Modifier,
    shortcutId: String?,
    presetCombo: KeyCombo?,
    onDone: () -> Unit,
) {
    val c = amColors
    val vm: ShortcutEditorViewModel = viewModel()

    // 装载必须发生在首帧之前，不能放进 LaunchedEffect：ViewModel 挂在 Activity 上，
    // 关掉编辑页并不会销毁它，晚一帧装载会先闪一下上一次编辑的草稿和冲突弹层。
    // 返回值只是为了满足 remember 的契约（它不接受 Unit）：真正要记住的是
    // 「这组参数已经装载过」，重组时不该再装一次。
    remember(shortcutId, presetCombo) {
        vm.load(shortcutId, presetCombo)
        shortcutId to presetCombo
    }

    val draft by vm.draft.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val conflict by vm.conflict.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val desktopPinned by vm.desktopPinned.collectAsStateWithLifecycle()
    // 桌面上的移除系统不通知；从桌面的确认框回来时也要重查一次
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDesktop() }

    // 关闭请求是一次性事件，只有本次在场的收集者会收到，因此不会被上一轮的保存结果误触发。
    LaunchedEffect(vm) { vm.closeRequests.collect { onDone() } }
    LaunchedEffect(recording?.combo) { if (recording?.combo != null) vm.acceptRecorded() }

    // 离开编辑页时收回录制态：录制由全局的 KeyPipeline 持有，留着会一直吞掉按键。
    DisposableEffect(vm) { onDispose { vm.cancelRecording() } }

    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }

    val combo = draft.combo
    val hasMainKey = combo != null && combo.keyCode != KeyEvent.KEYCODE_UNKNOWN

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        ScreenTitle(
            if (draft.isNew) "新建快捷键" else "编辑快捷键",
            "SHORTCUT EDITOR",
        ) {
            AmSecondaryButton("关闭", onDone)
        }

        AmComboField(
            label = "快捷键 · KEY COMBINATION",
            combo = combo,
            recording = recording != null,
            onStartRecording = vm::startRecording,
            onCancelRecording = vm::cancelRecording,
            onOpenCatalog = {
                openGroup = combo?.let { KeyCatalog.groupOf(it.keyCode)?.id }
                sheet = EditorSheet.KEY
            },
            onToggleModifier = vm::toggleModifier,
            modifier = Modifier.fillMaxWidth(),
            showRaw = true,
        )

        // 系统抢先执行的组合（目前只有 Meta）：能匹配、能拦，但拦不掉系统自己那份动作
        SystemKeyPolicy.reservation(combo)?.let { note ->
            AmCard(Modifier.fillMaxWidth(), alert = true) {
                AmLabel("系统会抢先执行 · SYSTEM RESERVED", color = c.warn)
                Spacer(Modifier.height(4.dp))
                Text(note, style = AmType.secondary, color = c.inkMid)
            }
        }

        // --- 动作 -----------------------------------------------------------
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("动作 · ACTION")
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surfaceSunken, RoundedCornerShape(AmShape.key + 2.dp))
                    .clickable {
                        openGroup = draft.action?.let { current ->
                            ActionCatalog.groups.firstOrNull { group ->
                                group.actions.any { it::class == current::class }
                            }?.id
                        }
                        sheet = EditorSheet.ACTION
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val action = draft.action
                    Text(
                        action?.label ?: "未选择动作",
                        style = AmType.body,
                        color = if (action == null) c.inkFaint else c.ink,
                        maxLines = 1,
                    )
                    val detail = if (action == null) "点击选择这个快捷键要做什么" else action.detail
                    detail?.let {
                        Text(it, style = AmType.data, color = c.inkMuted, maxLines = 1)
                    }
                }
                if (draft.action?.requiresPrivilege == true) {
                    AmLabel("需 SHIZUKU", color = c.warn)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = c.inkFaint,
                )
            }

            Spacer(Modifier.height(AmSpace.s2))
            AmLabel("名称 · LABEL（留空则用动作名）")
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = draft.label,
                onValueChange = vm::setLabel,
                singleLine = true,
                placeholder = {
                    Text(
                        draft.action?.label ?: "先选一个动作",
                        style = AmType.secondary,
                        color = c.inkFaint,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AmShape.key + 2.dp),
            )
        }

        // --- 桌面按钮 --------------------------------------------------------
        DesktopCard(
            action = draft.action,
            name = draft.action?.let { action ->
                draft.label.trim().ifBlank {
                    DesktopName.of(action).let { if (DesktopName.isBuiltIn(action)) localize(it) else it }
                }
            }.orEmpty(),
            pinned = !draft.isNew && draft.id in desktopPinned,
            supported = vm.desktopSupported,
            isNew = draft.isNew,
            enabled = hasMainKey && draft.action != null,
            onPin = vm::saveAndPin,
        )

        // --- 作用范围 --------------------------------------------------------
        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("作用范围 · SCOPE")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备范围", style = AmType.body, color = c.ink)
                    Text(draft.deviceScope.label, style = AmType.data, color = c.inkFaint)
                }
            }
            Spacer(Modifier.height(AmSpace.s1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("应用范围", style = AmType.body, color = c.ink)
                    Text(draft.appScope.label, style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton(
                    if (draft.appScope.mode == AppScope.Mode.GLOBAL) "全局" else "限定",
                    onClick = { vm.setAppScope(AppScope.GLOBAL) },
                )
            }
            Spacer(Modifier.height(AmSpace.s1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", style = AmType.body, color = c.ink, modifier = Modifier.weight(1f))
                AmSwitch(checked = draft.enabled, onCheckedChange = vm::setEnabled)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            AmPrimaryButton(
                "保存",
                onClick = { vm.save() },
                enabled = hasMainKey && draft.action != null,
                modifier = Modifier.weight(1f),
            )
            if (!draft.isNew) {
                AmSecondaryButton(
                    "删除",
                    onClick = { vm.delete() },
                    accent = true,
                )
            }
        }
        Spacer(Modifier.height(AmSpace.s4))
    }

    when (sheet) {
        EditorSheet.KEY -> AmKeyPickerSheet(
            selectedKeyCode = combo?.keyCode,
            openGroupId = openGroup,
            onOpenGroup = { openGroup = it },
            onPick = { keyCode -> vm.setMainKey(keyCode); sheet = null },
            onDismiss = { sheet = null },
        )

        EditorSheet.ACTION -> ActionPickerSheet(
            current = draft.action,
            openGroupId = openGroup,
            onOpenGroup = { openGroup = it },
            onPickAction = { action -> vm.setAction(action); sheet = null },
            onPickDirect = { groupId ->
                openGroup = null
                sheet = when (groupId) {
                    ActionCatalog.GROUP_APP -> EditorSheet.APP
                    ActionCatalog.GROUP_URL -> EditorSheet.URL
                    ActionCatalog.GROUP_TERMUX -> EditorSheet.TERMUX
                    else -> EditorSheet.SHELL
                }
            },
            onDismiss = { sheet = null },
        )

        EditorSheet.APP -> AppActivityPickerSheet(
            apps = apps,
            loading = appsLoading,
            onShown = vm::ensureApps,
            activities = activities,
            current = draft.action as? Action.LaunchApp,
            openPackage = openGroup,
            onOpenPackage = { pkg ->
                openGroup = pkg
                pkg?.let(vm::loadActivities)
            },
            onPick = { app, entry ->
                if (entry == null) vm.selectApp(app) else vm.selectActivity(app, entry)
                sheet = null
            },
            onRescan = vm::refreshApps,
            onDismiss = { sheet = null },
        )

        EditorSheet.URL -> UrlSheet(
            current = draft.action as? Action.OpenUrl,
            onConfirm = { url, title -> vm.setAction(Action.OpenUrl(url, title)); sheet = null },
            onDismiss = { sheet = null },
        )

        EditorSheet.SHELL -> ShellSheet(
            current = draft.action as? Action.Shell,
            onConfirm = { command, title -> vm.setAction(Action.Shell(command, title)); sheet = null },
            onDismiss = { sheet = null },
        )

        EditorSheet.TERMUX -> TermuxSheet(
            current = draft.action as? Action.Termux,
            vm = vm,
            onConfirm = { action -> vm.setAction(action); sheet = null },
            onDismiss = { sheet = null },
        )

        null -> Unit
    }

    conflict?.let { ConflictSheet(it, vm) }
}

// --- 桌面按钮 ---------------------------------------------------------------

/**
 * 桌面按钮的配置与预览。
 *
 * 预览就是桌面上那一个的样子（同一张位图、同样的裁切），名称字段改一个字这里跟着变 ——
 * 所以「名称」同时就是桌面按钮的配置，不另开一套表单。
 * 已钉住时不再给按钮，只说明修改会自动同步；桌面不支持时如实说，不给一个点了没反应的按钮。
 */
@Composable
private fun DesktopCard(
    action: Action?,
    name: String,
    pinned: Boolean,
    supported: Boolean,
    isNew: Boolean,
    enabled: Boolean,
    onPin: () -> Unit,
) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth()) {
        AmLabel("桌面按钮 · HOME SCREEN")
        Spacer(Modifier.height(AmSpace.s1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesktopIconPreview(action, name)
            Spacer(Modifier.width(AmSpace.s2))
            Column(Modifier.weight(1f)) {
                // 名字已经按桌面的规则处理过语言，不再经 Text 的逐词翻译，免得预览与桌面上的不一样
                androidx.compose.material3.Text(
                    if (action == null) localize("未选择动作") else name,
                    style = AmType.body,
                    color = if (action == null) c.inkFaint else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        action == null -> "选好动作后，可放到桌面一键执行"
                        !supported -> "当前桌面不支持添加快捷方式"
                        pinned -> "改名称或动作，桌面上的会自动同步"
                        else -> "轻点图标即执行 · 图标与文字取自名称"
                    },
                    style = AmType.data,
                    color = c.inkFaint,
                )
            }
            Spacer(Modifier.width(AmSpace.s1))
            when {
                pinned -> AmLabel("已在桌面", color = c.ok)
                supported && action != null -> AmSecondaryButton(
                    if (isNew) "保存并添加" else "添加到桌面",
                    onClick = onPin,
                    enabled = enabled,
                )
            }
        }
    }
}

/** 桌面会把自适应图标裁到中间三分之二，预览照样放大 1.5 倍再裁，看到的就是桌面上的样子。 */
@Composable
private fun DesktopIconPreview(action: Action?, name: String) {
    val c = amColors
    val context = LocalContext.current
    val shape = RoundedCornerShape(30)
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(c.surfaceSunken)
            .border(1.dp, c.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (action != null) {
            val bitmap = remember(action, name) { DesktopIcon.bitmap(context, action, name).asImageBitmap() }
            Image(bitmap, contentDescription = null, modifier = Modifier.requiredSize(72.dp))
        }
    }
}

// --- 动作选择 ---------------------------------------------------------------

@Composable
private fun ActionPickerSheet(
    current: Action?,
    openGroupId: String?,
    onOpenGroup: (String?) -> Unit,
    onPickAction: (Action) -> Unit,
    onPickDirect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = ActionCatalog.groups.map { group ->
        PickerGroup(
            id = group.id,
            title = group.label,
            subtitle = group.hint,
            badge = if (group.direct) null else group.actions.size.toString(),
            direct = group.direct,
            items = if (group.direct) null else group.actions.mapIndexed { index, action ->
                PickerItem(
                    id = index.toString(),
                    title = action.label,
                    subtitle = action.detail,
                    badge = if (action.requiresPrivilege) "SHIZUKU" else null,
                    tone = PickerTone.WARN,
                    selected = action == current,
                )
            },
        )
    }

    AmPickerSheet(
        title = "选择动作",
        subtitle = "一级是能力，二级才是具体动作",
        groups = groups,
        openGroupId = openGroupId,
        onOpenGroup = onOpenGroup,
        searchPlaceholder = "搜索动作…",
        emptyHint = "没有匹配的动作",
        onPick = { groupId, itemId ->
            val group = ActionCatalog.groups.first { it.id == groupId }
            if (group.direct || itemId == null) onPickDirect(groupId)
            else onPickAction(group.actions[itemId.toInt()])
        },
        onDismiss = onDismiss,
    )
}

// --- 应用 / Activity 选择 ----------------------------------------------------

private const val DEFAULT_ENTRY_ID = "__default__"

@Composable
private fun AppActivityPickerSheet(
    apps: List<PackageBackend.InstalledApp>,
    loading: Boolean,
    onShown: () -> Unit,
    activities: Map<String, List<PackageBackend.ActivityEntry>>,
    current: Action.LaunchApp?,
    openPackage: String?,
    onOpenPackage: (String?) -> Unit,
    onPick: (PackageBackend.InstalledApp, PackageBackend.ActivityEntry?) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onShown() }
    val groups = remember(apps, activities, current) {
        apps.map { app ->
            val loaded = activities[app.packageName]
            PickerGroup(
                id = app.packageName,
                title = app.label,
                subtitle = app.packageName,
                badge = when {
                    app.frozen -> "已冻结"
                    loaded != null -> loaded.size.toString()
                    else -> null
                },
                loading = loaded == null,
                items = loaded?.let { entries ->
                    listOf(
                        PickerItem(
                            id = DEFAULT_ENTRY_ID,
                            title = "默认启动入口",
                            subtitle = "由系统决定，应用更新后依然有效",
                            selected = current?.packageName == app.packageName && current.isDefaultEntry,
                        )
                    ) + entries.filterNot { it.isDefaultEntry }.map { entry ->
                        PickerItem(
                            id = entry.className,
                            title = entry.label,
                            subtitle = entry.className,
                            badge = if (entry.requiresPrivilege) "SHIZUKU" else null,
                            tone = PickerTone.WARN,
                            selected = current?.packageName == app.packageName &&
                                current.activity == entry.className,
                        )
                    }
                },
            )
        }
    }

    AmPickerSheet(
        title = "选择要启动的应用",
        subtitle = "点开应用再选入口；已冻结的应用会先通过 Shizuku 解冻",
        groups = groups,
        openGroupId = openPackage,
        onOpenGroup = onOpenPackage,
        searchPlaceholder = "搜索应用名或包名…",
        emptyHint = "这个应用没有可启动的入口",
        loading = loading,
        onPick = { packageName, itemId ->
            val app = apps.firstOrNull { it.packageName == packageName } ?: return@AmPickerSheet
            val entry = activities[packageName]?.firstOrNull { it.className == itemId }
            if (itemId == null || itemId == DEFAULT_ENTRY_ID) onPick(app, null) else onPick(app, entry)
        },
        onDismiss = onDismiss,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmSecondaryButton("重新扫描应用", onRescan)
            }
        },
    )
}

// --- 链接 / Shell 输入 -------------------------------------------------------

@Composable
private fun UrlSheet(
    current: Action.OpenUrl?,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(current?.url.orEmpty()) }
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    val normalized = PackageBackend.normalizeUrl(url)

    InputSheet(
        title = "打开链接",
        subtitle = "交给系统默认浏览器。应用自身不联网。",
        confirmText = "使用这个链接",
        confirmEnabled = normalized != null,
        footnote = normalized ?: "补全后的地址会显示在这里",
        onConfirm = { onConfirm(normalized!!, title.trim()) },
        onDismiss = onDismiss,
    ) {
        LabeledField("网址 · URL", url, "example.com/page") { url = it }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("名称 · 可选", title, "列表里显示的名字") { title = it }
    }
}

@Composable
private fun ShellSheet(
    current: Action.Shell?,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var command by remember { mutableStateOf(current?.command.orEmpty()) }
    var title by remember { mutableStateOf(current?.title.orEmpty()) }

    InputSheet(
        title = "Shell 命令",
        subtitle = "逐条明确配置，不做通用包装。需要 Shizuku。",
        confirmText = "设为 Shell 动作",
        confirmEnabled = command.isNotBlank(),
        footnote = "命令原样执行，输出会记录在旋转诊断页的 SHELL 日志里",
        onConfirm = { onConfirm(command.trim(), title.trim()) },
        onDismiss = onDismiss,
    ) {
        LabeledField("命令 · COMMAND", command, "例如 wm density reset") { command = it }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("名称 · 可选", title, "列表里显示的名字") { title = it }
    }
}

/**
 * Termux 命令。前提写在表单上方，而不是等用户按下快捷键才发现跑不起来：
 * 装没装、给没给执行权限、给没给通知权限，各一行，缺哪一项就在那一行上补。
 * allow-external-apps 从外面查不到，只能把那条命令摆出来让用户复制。
 */
@Composable
private fun TermuxSheet(
    current: Action.Termux?,
    vm: ShortcutEditorViewModel,
    onConfirm: (Action.Termux) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = amColors
    val context = LocalContext.current
    var command by remember { mutableStateOf(current?.command.orEmpty()) }
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    var background by remember { mutableStateOf(current?.background ?: true) }

    var installed by remember { mutableStateOf(vm.termuxInstalled()) }
    var granted by remember { mutableStateOf(vm.termuxPermissionGranted()) }
    var canNotify by remember { mutableStateOf(vm.notificationsAllowed()) }
    var overlay by remember { mutableStateOf(vm.termuxOverlayGranted()) }
    // 系统对拒绝过两次的权限不再弹框，申请过一轮之后再点就直接送去应用详情页
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = vm.termuxInstalled()
        granted = vm.termuxPermissionGranted()
        canNotify = vm.notificationsAllowed()
        overlay = vm.termuxOverlayGranted()
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        asked = true
        granted = vm.termuxPermissionGranted()
        canNotify = vm.notificationsAllowed()
    }
    val requestPermissions: () -> Unit = {
        val wanted = buildList {
            if (!granted) add(TermuxBackend.PERMISSION)
            if (!canNotify && HardeningNotifier.requiresRuntimePermission) add(HardeningNotifier.permission)
        }
        if (asked || wanted.isEmpty()) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            launcher.launch(wanted.toTypedArray())
        }
    }

    InputSheet(
        title = "Termux 命令",
        subtitle = "交给 Termux 执行，pkg 装的工具与 ~/ 下的脚本都能用。不需要 Shizuku。",
        confirmText = "设为 Termux 动作",
        confirmEnabled = command.isNotBlank(),
        footnote = if (background) {
            "先加载 ~/.bashrc 再执行，其中的函数与别名可用；工作目录为 Termux 主目录。跑完发一条通知：成功静默，失败提醒，正文是输出的最后几行"
        } else {
            "先加载 ~/.bashrc 再执行，其中的函数与别名可用；工作目录为 Termux 主目录。在 Termux 里新开一个会话，输出直接显示在终端里"
        },
        onConfirm = { onConfirm(Action.Termux(command.trim(), title.trim(), background)) },
        onDismiss = onDismiss,
    ) {
        TermuxRequirement(
            ok = installed,
            text = if (installed) "Termux 已安装" else "未安装 Termux · 请从 F-Droid 或 GitHub 安装",
        )
        TermuxRequirement(
            ok = granted,
            text = if (granted) "已授予执行权限" else "未授予 Termux 执行权限",
            action = if (installed && !granted) "去授权" else null,
            onAction = requestPermissions,
        )
        if (!background && installed) {
            // 缺的是 Termux 的权限，不是本应用的：按钮直接送到 Termux 那一页
            TermuxRequirement(
                ok = overlay == true,
                warnOnly = overlay == null,
                text = when (overlay) {
                    true -> "Termux 可显示在其他应用上层"
                    false -> "Termux 未获「显示在其他应用上层」权限 · 前台执行打不开终端"
                    null -> "无法确认 Termux 的「显示在其他应用上层」权限 · 前台执行需要它"
                },
                action = if (overlay != true) "去设置" else null,
                onAction = vm::openTermuxOverlaySettings,
            )
        }
        if (background) {
            TermuxRequirement(
                ok = canNotify,
                warnOnly = true,
                text = if (canNotify) "可以发送结果通知" else "通知被关闭 · 结果只记在日志里",
                action = if (!canNotify) "允许通知" else null,
                onAction = requestPermissions,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("还需在 Termux 里执行一次：", style = AmType.secondary, color = c.inkMid)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TermuxBackend.PROPERTIES_HINT,
                style = AmType.data,
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .background(c.surfaceSunken, RoundedCornerShape(AmShape.key))
                    .padding(8.dp),
            )
            Spacer(Modifier.width(6.dp))
            AmSecondaryButton("复制", {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("termux", TermuxBackend.PROPERTIES_HINT))
            })
        }
        Spacer(Modifier.height(AmSpace.s3))
        LabeledField("命令 · COMMAND", command, "例如 ~/bin/sync.sh 或 pkg upgrade -y") { command = it }
        Spacer(Modifier.height(AmSpace.s2))
        LabeledField("名称 · 可选", title, "列表与通知里显示的名字") { title = it }
        Spacer(Modifier.height(AmSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("后台执行", style = AmType.body, color = c.ink)
                Text(
                    if (background) "不打开 Termux，完成后通知结果" else "关闭 · 打开 Termux 新会话前台执行",
                    style = AmType.data,
                    color = c.inkFaint,
                )
            }
            AmSwitch(background, onCheckedChange = { background = it })
        }
    }
}

@Composable
private fun TermuxRequirement(
    ok: Boolean,
    text: String,
    warnOnly: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val c = amColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        StatusDot(
            when {
                ok -> c.ok
                warnOnly -> c.warn
                else -> c.accent
            }
        )
        Text(text, style = AmType.data, color = if (ok) c.inkMid else c.ink, modifier = Modifier.weight(1f))
        if (action != null) AmSecondaryButton(action, onAction)
    }
}

@Composable
private fun InputSheet(
    title: String,
    subtitle: String,
    confirmText: String,
    confirmEnabled: Boolean,
    footnote: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = amColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = AmShape.sheet, topEnd = AmShape.sheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmSpace.screen)) {
            Text(title, style = AmType.cardTitle, color = c.ink)
            Text(subtitle, style = AmType.secondary, color = c.inkMid)
            Spacer(Modifier.height(AmSpace.s3))
            content()
            Spacer(Modifier.height(AmSpace.s2))
            Text(footnote, style = AmType.data, color = c.inkFaint)
            Spacer(Modifier.height(AmSpace.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmPrimaryButton(
                    confirmText,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.weight(1f),
                )
                AmSecondaryButton("取消", onDismiss)
            }
            Spacer(Modifier.height(AmSpace.s4))
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val c = amColors
    Column {
        AmLabel(label)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, style = AmType.secondary, color = c.inkFaint) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AmShape.key + 2.dp),
        )
    }
}

/** 冲突弹层（设计规范 5.5）：当前绑定 → 将替换为，主操作朱红。 */
@Composable
private fun ConflictSheet(
    conflict: com.actionmental.core.shortcut.ShortcutConflict,
    vm: ShortcutEditorViewModel,
) {
    val c = amColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = vm::dismissConflict,
        sheetState = sheetState,
        containerColor = c.accentBg,
        shape = RoundedCornerShape(topStart = AmShape.sheet, topEnd = AmShape.sheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(AmSpace.screen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c.accent)
                Text("快捷键冲突", style = AmType.cardTitle, color = c.accent)
                Spacer(Modifier.width(6.dp))
                AmLabel("CONFLICT", color = c.accent)
            }
            Spacer(Modifier.height(AmSpace.s2))
            KeyComboRow(conflict.existing.combo, large = true)
            Spacer(Modifier.height(AmSpace.s3))

            AmLabel("已绑定 CURRENT")
            Text(conflict.existing.displayLabel, style = AmType.body, color = c.ink)
            Text(conflict.existing.action.technical, style = AmType.data, color = c.inkFaint)

            Spacer(Modifier.height(AmSpace.s2))
            AmLabel("将替换为 NEW")
            Text(conflict.incoming.displayLabel, style = AmType.body, color = c.accent)
            Text(conflict.incoming.action.technical, style = AmType.data, color = c.inkFaint)

            Spacer(Modifier.height(AmSpace.s2))
            Text(
                "覆盖后同一作用域内仅保留一条记录，原绑定的创建时间等元数据会保留。",
                style = AmType.secondary,
                color = c.inkMid,
            )

            Spacer(Modifier.height(AmSpace.s3))
            AmPrimaryButton(
                "覆盖原绑定",
                onClick = { vm.save(override = true) },
                destructive = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AmSpace.s1))
            Row(horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                AmSecondaryButton("编辑原绑定", vm::editConflicting, modifier = Modifier.weight(1f))
                AmSecondaryButton("取消", vm::dismissConflict, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(AmSpace.s4))
        }
    }
}
