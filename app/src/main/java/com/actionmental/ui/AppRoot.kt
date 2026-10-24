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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.actionmental.core.key.KeyCombo
import com.actionmental.ui.screens.ClipHistoryScreen
import com.actionmental.ui.screens.DashboardScreen
import com.actionmental.ui.screens.KeyMonitorScreen
import com.actionmental.ui.screens.LogScreen
import com.actionmental.ui.screens.OnboardingScreen
import com.actionmental.ui.screens.PrivilegeScreen
import com.actionmental.ui.screens.RemapScreen
import com.actionmental.ui.screens.RotationScreen
import com.actionmental.ui.screens.SettingsScreen
import com.actionmental.ui.screens.TranslationSettingsActions
import com.actionmental.ui.screens.ShortcutsPane
import com.actionmental.ui.i18n.AppTranslations
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.amColors
import kotlinx.coroutines.delay

/**
 * 应用外壳。
 *
 * 自适应按 Material 的窗口宽度分档：
 * - < 600dp：底部导航；
 * - 600～840dp：80dp 导航栏（rail），正文不被一整条侧栏挤窄；
 * - >= 840dp: full sidebar; split panes only when the remaining content fits two readable panes.
 * 单栏页面的正文宽度封顶 [AmSpace.contentMax] 并居中，宽屏上卡片不会拉成一条横线。
 * 因为 Activity 声明了 configChanges 并且布局完全由宽度驱动，
 * 折叠 / 展开 / 旋转都只是一次重组，不会重建状态。
 */
@Composable
fun AppRoot(vm: AppViewModel, settingsRequest: Int = 0) {
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
            // 应用清单不在这里补：只有三个应用选择器用得上它，由它们打开时各自要
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
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) destination = Destination.SETTINGS
    }
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
        val navigationWidth = AdaptiveLayout.navigationWidth(widthDp.value)
        val wide = navigationWidth > 0f
        val expandedNavigation = navigationWidth == AdaptiveLayout.SIDEBAR_WIDTH
        val twoPane = AdaptiveLayout.useTwoPanes(widthDp.value, LocalDensity.current.fontScale)

        val content: @Composable (Modifier) -> Unit = { modifier ->
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(
                    vm = vm,
                    modifier = modifier,
                    // 宽屏侧栏里已经列着全部入口，首页再列一遍就是重复；手机只补底栏放不下的那几个
                    moreEntries = if (wide) emptyList() else Destination.entries.filter { !it.inBottomBar },
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
                Destination.CLIPBOARD -> ClipHistoryScreen(vm, modifier)
                Destination.PRIVILEGE -> PrivilegeScreen(vm, modifier)
                Destination.LOGS -> LogScreen(vm, modifier)
                Destination.SETTINGS -> {
                    val translationState by vm.translationSettings.collectAsStateWithLifecycle()
                    SettingsScreen(vm, modifier, translationState, TranslationSettingsActions(
                        onSaveToken = vm::saveTranslationToken,
                        onClearToken = vm::clearTranslationToken,
                        onModelChange = vm::updateTranslationModel,
                        onLanguagesChange = vm::updateTranslationLanguages,
                        onTestConnection = vm::testTranslationConnection,
                        onLoadModels = vm::loadTranslationModels,
                    ))
                }
            }
        }

        // 快捷键双栏与键位映射的键盘筛选栏自己会用满宽度，其余页面一律收窄居中
        val fullBleed = (destination == Destination.SHORTCUTS && twoPane) || destination == Destination.REMAP
        val body: @Composable (Modifier) -> Unit = { modifier ->
            if (fullBleed) {
                content(modifier)
            } else {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    content(Modifier.widthIn(max = AmSpace.contentMax).fillMaxSize())
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationSidebar(
                    current = destination,
                    onSelect = { destination = it; closeEditor() },
                    vm = vm,
                    rail = !expandedNavigation,
                    modifier = Modifier.width(navigationWidth.dp).fillMaxHeight(),
                )
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = amColors.bgScreen,
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { inner ->
                    body(Modifier.padding(inner).consumeWindowInsets(inner))
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
                body(Modifier.padding(inner).consumeWindowInsets(inner))
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
