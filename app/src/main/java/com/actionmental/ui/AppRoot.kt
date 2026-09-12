package com.actionmental.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.actionmental.core.key.KeyCombo
import com.actionmental.ui.screens.DashboardScreen
import com.actionmental.ui.screens.KeyMonitorScreen
import com.actionmental.ui.screens.LogScreen
import com.actionmental.ui.screens.OnboardingScreen
import com.actionmental.ui.screens.PrivilegeScreen
import com.actionmental.ui.screens.RemapScreen
import com.actionmental.ui.screens.RotationScreen
import com.actionmental.ui.screens.SettingsScreen
import com.actionmental.ui.screens.ShortcutsPane
import com.actionmental.ui.i18n.AppTranslations
import com.actionmental.ui.theme.amColors
import kotlinx.coroutines.delay

/**
 * 应用外壳。
 *
 * 自适应只有一条规则：宽度 < 600dp 用底部导航，>= 600dp 用左侧栏；
 * >= 840dp 时快捷键列表与编辑器并排（平板 / 展开态折叠屏）。
 * 因为 Activity 声明了 configChanges 并且布局完全由宽度驱动，
 * 折叠 / 展开 / 旋转都只是一次重组，不会重建状态。
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val localizedToast = toast?.let { AppTranslations.translate(it, settings.language) }

    // 系统状态一律轮询真实值，不靠本地标记（PRD 3.2、引导规则第 2 条）。
    // 只在界面真的在前台时轮询：这一圈里有读 secure 设置和一次 shell 调用，
    // 应用切到后台后没人看，却还在每 1.5 秒占一次主线程和一次特权通道。
    // 无障碍开关、键盘、Shizuku 现在都由观察者与广播驱动，变化当场就到。
    // 这一圈只是兜底：厂商 ROM 改自己的开关时未必发通知。既然是兜底，60 秒就够 ——
    // 而原来的 1.5 秒等于每分钟做七百次跨进程查询，机器一直在发热。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 应用清单在界面离开够久之后才被放掉，回到前台补一次
            // （warmUp 自带「已经有了就不查」，短暂切走再回来不会重查）
            vm.ensureAppCatalog()
            while (true) {
                vm.refreshEverything()
                delay(60_000)
            }
        }
    }

    LaunchedEffect(localizedToast) {
        localizedToast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // 设置还没从盘上读出来时，onboardingDone 拿到的是默认值 false。
    // 直接照它渲染，每次冷启动都会先闪一段引导页 —— 尤其在进程崩溃后重启时，
    // 用户看到的就是「怎么又要我引导一遍」。空一帧比闪一次引导好。
    if (!settingsLoaded) {
        Box(Modifier.fillMaxSize().background(amColors.bgScreen))
        return
    }

    if (!settings.onboardingDone) {
        OnboardingScreen(vm)
        return
    }

    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    var editingShortcutId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNew by rememberSaveable { mutableStateOf(false) }
    var presetCombo by remember { mutableStateOf<KeyCombo?>(null) }

    fun openEditor(id: String?, combo: KeyCombo? = null) {
        editingShortcutId = id
        editingNew = id == null
        presetCombo = combo
        destination = Destination.SHORTCUTS
    }

    fun closeEditor() {
        editingShortcutId = null
        editingNew = false
        presetCombo = null
    }

    // 旋转状态每读一次要 fork 一个 shell 进程，所以只在真的显示它的两页上轮询。
    // 间隔从 9 秒放到 30 秒：自动旋转与锁定角度这两个值已经由 ContentObserver 驱动，
    // 用户在快捷设置里拨完当场就刷新了，轮询只剩下兜底 `ignore-orientation-request`
    // 被别的特权应用改掉这一种情形 —— 那本来就不该按秒去查。
    val showsRotation = destination == Destination.DASHBOARD || destination == Destination.ROTATION
    LaunchedEffect(lifecycleOwner, showsRotation) {
        if (!showsRotation) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshRotationState()
                delay(30_000)
            }
        }
    }

    val editorOpen = editingNew || editingShortcutId != null
    BackHandler(enabled = editorOpen || destination != Destination.DASHBOARD) {
        if (editorOpen) closeEditor() else destination = Destination.DASHBOARD
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(amColors.bgScreen)) {
        val widthDp = maxWidth
        val wide = widthDp >= 600.dp
        val twoPane = widthDp >= 840.dp

        val content: @Composable (Modifier) -> Unit = { modifier ->
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(
                    vm = vm,
                    modifier = modifier,
                    onNavigate = { destination = it },
                )

                Destination.SHORTCUTS -> ShortcutsPane(
                    vm = vm,
                    modifier = modifier,
                    twoPane = twoPane,
                    editingId = editingShortcutId,
                    editingNew = editingNew,
                    presetCombo = presetCombo,
                    onOpenEditor = { id -> openEditor(id) },
                    onCloseEditor = { closeEditor() },
                )

                Destination.MONITOR -> KeyMonitorScreen(
                    vm = vm,
                    modifier = modifier,
                    onBind = { combo -> openEditor(null, combo) },
                )

                Destination.REMAP -> RemapScreen(modifier)
                Destination.ROTATION -> RotationScreen(vm, modifier)
                Destination.PRIVILEGE -> PrivilegeScreen(vm, modifier)
                Destination.LOGS -> LogScreen(vm, modifier)
                Destination.SETTINGS -> SettingsScreen(vm, modifier)
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationSidebar(
                    current = destination,
                    onSelect = { destination = it; closeEditor() },
                    vm = vm,
                    modifier = Modifier.width(if (widthDp >= 1000.dp) 208.dp else 168.dp).fillMaxHeight(),
                )
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = amColors.bgScreen,
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { inner ->
                    content(Modifier.padding(inner))
                }
            }
        } else {
            Scaffold(
                containerColor = amColors.bgScreen,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBottomBar(
                        current = destination,
                        onSelect = { destination = it; closeEditor() },
                    )
                },
            ) { inner ->
                content(Modifier.padding(inner))
            }
        }
    }
}

/** 供其它文件使用的一个薄包装：保证正文区域统一处理安全区。 */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .consumeWindowInsets(WindowInsets.safeDrawing)
    ) { content() }
}
