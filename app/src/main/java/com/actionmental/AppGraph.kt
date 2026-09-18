package com.actionmental

import android.content.BroadcastReceiver
import android.hardware.input.InputManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.actionmental.core.action.ActionExecutor
import com.actionmental.core.action.ActionResult
import com.actionmental.core.awake.ScreenAwakeController
import com.actionmental.core.diag.CrashSink
import com.actionmental.core.diag.EventLog
import com.actionmental.core.diag.LogLevel
import com.actionmental.core.diag.DiagCounters
import com.actionmental.core.diag.ProcessExitReporter
import com.actionmental.core.diag.RotationDiagnostics
import com.actionmental.core.diag.ShellLog
import com.actionmental.core.diag.ThermalWatch
import com.actionmental.core.diag.VitalsWatch
import com.actionmental.core.hardening.HardeningController
import com.actionmental.core.hardening.HealOutcome
import com.actionmental.core.hardening.HealTrigger
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyInjector
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.KeyTrace
import com.actionmental.core.remap.KeyRemapMatcher
import com.actionmental.core.rotation.AppOverrideController
import com.actionmental.core.rotation.AppRotationRuleManager
import com.actionmental.core.rotation.RotationController
import com.actionmental.core.shortcut.ShortcutMatcher
import com.actionmental.core.status.SystemStatus
import com.actionmental.data.AppStore
import com.actionmental.data.ConfigTransfer
import com.actionmental.data.HardeningRepository
import com.actionmental.data.KeyRemapRepository
import com.actionmental.data.RotationRuleRepository
import com.actionmental.data.SettingsRepository
import com.actionmental.data.ShortcutRepository
import com.actionmental.platform.AccessibilityBridge
import com.actionmental.platform.AppCatalog
import com.actionmental.platform.ConfigBackupFile
import com.actionmental.platform.AudioBackend
import com.actionmental.platform.HardeningNotifier
import com.actionmental.platform.InputDeviceBackend
import com.actionmental.platform.LoggingBackend
import com.actionmental.platform.PackageBackend
import com.actionmental.platform.ScreenAwakeBackend
import com.actionmental.platform.ScreenAwakeNotifier
import com.actionmental.platform.SettingsReader
import com.actionmental.platform.shizuku.ShizukuManager
import com.actionmental.service.KeepAliveService
import com.actionmental.service.KeyboardAccessibilityService
import com.actionmental.service.ScreenAwakeReceiver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlinx.coroutines.delay

/**
 * 组合根。
 *
 * 没有用 Hilt：这个应用的对象图是一棵固定的树，而真正需要拿依赖的地方
 * （AccessibilityService、TileService）都由系统实例化，注解注入反而绕远路。
 * 一个在 Application 里建好的图，加上明确的构造参数，已经足够测试与替换。
 */
class AppGraph private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val packageName = context.packageName

    /**
     * 后台协程里漏出来的异常，一律记下来，不让它杀掉进程。
     *
     * 没有它时，这个 scope 上任何一个 launch 抛出异常都会交给线程的默认处理器，进程当场结束。
     * 冷启动最容易撞上：启动那一串收集器、界面拉起的应用清单查询、上次死因的上报
     * 全挤在同一秒里。表现就是「打开即闪退」，下次启动再提示「上次进程被系统结束 · Java 异常崩溃」；
     * 先点一下磁贴让进程在没有界面时起来，这一秒就错开了。
     *
     * 这个进程还要以无障碍服务的身份收按键：一条诊断、一次清单查询失败，
     * 代价最多只应该是那一件事没做成。
     */
    val coroutineFailures = CoroutineExceptionHandler { _, error -> onCoroutineFailure(error) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineFailures)

    private val appStore = AppStore(context)

    val settingsRepository = SettingsRepository(appStore, scope)
    val shortcutRepository = ShortcutRepository(appStore, scope)
    val rotationRuleRepository = RotationRuleRepository(appStore, scope)
    val keyRemapRepository = KeyRemapRepository(appStore, scope)
    val hardeningRepository = HardeningRepository(appStore, scope)

    /** 配置进出口跨快捷键与键位映射两张表，所以放在两个仓库之外。 */
    val configTransfer = ConfigTransfer(appStore, shortcutRepository, keyRemapRepository)

    /** 同一份配置落到用户自己选的文件上。剪贴板过不了重启，备份只能靠文件。 */
    val configBackupFile = ConfigBackupFile(context)

    val accessibility = AccessibilityBridge(context)
    val shizuku = ShizukuManager(context, scope)
    val audio = AudioBackend(context)
    val packages = PackageBackend(context)
    val appCatalog = AppCatalog(
        packages = packages,
        scope = scope,
        onLoaded = { count, elapsedMs ->
            eventLog.debug("catalog", "应用清单已加载 · " + count + " 个", "耗时 " + elapsedMs + "ms")
        },
        onFailed = { error -> eventLog.error("catalog", "应用清单加载失败", error) },
    )
    val inputDevices = InputDeviceBackend(context)
    private val settingsReader = SettingsReader(context)

    /** 所有特权调用的公共日志，诊断页直接订阅。 */
    val shellLog = ShellLog()

    /**
     * 事件日志。落盘，因此进程死了也还在 ——
     * 「服务掉线」的现场就在上一次进程里，只存内存等于没记。
     */
    val eventLog = EventLog(scope, File(appContext.filesDir, "logs"))

    /** 上一次进程的死因。应用自己记不下来的那几种死法，只有系统知道。 */
    val exitReporter = ProcessExitReporter(appContext)

    /** 「这一段时间里进程忙了些什么」的计数器。现场只做原子自增，采样时一并写出去。 */
    val counters = DiagCounters()

    /**
     * 机身温度。
     *
     * 死因里那句 `bgLimit_level_thermal_10` 说明「什么时候会死」的自变量是温度，
     * 而在此之前日志里从来没有过这个量 —— 只看得到死亡，看不到升温。
     */
    val thermal = ThermalWatch(appContext, eventLog, onChanged = { level -> onThermalLevel(level) })

    /**
     * 进程体征。rss + CPU + 活动计数，30 秒起采。
     *
     * 上一次被杀前的三分钟里一条采样都没有（那时最密是五分钟一次、跨 50MB 才写），
     * 于是「11MB 怎么变成 165MB」只能靠猜。这条线就是为了不再靠猜。
     */
    val vitals = VitalsWatch(
        log = eventLog,
        counters = counters,
        profile = { runtimeProfile() },
    )

    // 上层只拿得到包装过的后端，因此没有任何一条 shell 能绕过日志
    private val logged = LoggingBackend(shizuku, shellLog, onCall = { counters.shell.incrementAndGet() })
    private val rotationBackend = logged.tagged("rotation")
    private val actionBackend = logged.tagged("action")
    private val hardeningBackend = logged.tagged("harden")

    val diagnostics = RotationDiagnostics { logged.tagged("diagnose") }
    val appOverrides = AppOverrideController { logged.tagged("compat") }

    /** 无障碍服务的组件名。自愈要把这一串原样写回系统设置。 */
    private val accessibilityComponent =
        packageName + "/" + KeyboardAccessibilityService::class.java.name

    /**
     * 后台加固与无障碍自愈（PRD 附录 · 常驻可靠性）。
     *
     * 它是唯一会主动改动系统开关的模块，因此每一步都写进 [hardeningRepository]，
     * 而不是只落在内存的 shell 日志里 —— 自愈恰好发生在用户看不见的时候。
     */
    val hardening = HardeningController(
        packageName = packageName,
        accessibilityComponent = accessibilityComponent,
        backend = { hardeningBackend },
        journal = hardeningRepository,
    )

    /** 加固 / 自愈的一次性提示，界面用 snackbar 呈现（与 [lastActionResult] 同一套路）。 */
    val hardeningNotice = MutableStateFlow<String?>(null)

    /** 同一件事的第二条出口：应用不在前台时，只有通知栏能当场说清楚。 */
    val notifier = HardeningNotifier(context)

    val rotation = RotationController(
        scope = scope,
        backend = { rotationBackend },
        settings = settingsReader,
        persistGlobalMode = { mode -> settingsRepository.update { it.copy(globalRotationMode = mode) } },
        // 「被要求设置旋转」与「真的写下去了」是两个数。去重到底省掉了多少，只看得见这一对
        onWrite = { counters.rotationWrite.incrementAndGet() },
        onSkipped = { counters.rotationSkipped.incrementAndGet() },
    )

    /**
     * 屏幕常亮。
     *
     * 靠的是无障碍服务已经让进程常驻这一事实 —— 唤醒锁跟着进程走，
     * 不需要前台服务，也不需要 Shizuku（PRD 3.4）。
     */
    val screenAwake = ScreenAwakeController(
        switch = ScreenAwakeBackend(context, accessibility::boundService),
        persistIntent = { on -> settingsRepository.update { it.copy(screenAwake = on) } },
    )

    /**
     * 常亮期间的常驻通知。
     *
     * 磁贴要下拉才看得见，而常亮可能是被一个快捷键按开的；
     * 会一直耗电的状态必须在系统层面一直看得见，且在看见的地方就关得掉。
     */
    val awakeNotifier = ScreenAwakeNotifier(context, ScreenAwakeReceiver::class.java)

    val pipeline = KeyPipeline()
    private val matcher = ShortcutMatcher()
    private val remapMatcher = KeyRemapMatcher()

    /**
     * 按键注入直接挂在 shizuku 上而不是包装过的 [logged]。
     *
     * 注入的内容就是用户的击键，写进 shell 日志等于记录输入 ——
     * 「不记录输入内容」的承诺优先于「每一条特权调用都可查」。
     */
    val keyInjector = KeyInjector(scope, backend = { shizuku })

    val executor = ActionExecutor(
        accessibility = accessibility,
        audio = audio,
        packages = packages,
        rotation = rotation,
        awake = screenAwake,
        privileged = { actionBackend },
    )

    val appRotationRules = AppRotationRuleManager(
        scope = scope,
        rules = rotationRuleRepository,
        settings = settingsRepository,
        controller = rotation,
        foregroundPackage = accessibility.foregroundPackage,
    )

    /** 界面在不在前台。纯诊断用，写进体征日志的运行画像里。 */
    @Volatile
    private var uiForeground = false

    /**
     * 全局暂停。
     *
     * 唯一的真相在盘上（[com.actionmental.data.UserSettings.paused]），这里是它的镜像，
     * 给按键回调、广播接收器这些「拿不到协程」的地方同步读。
     */
    @Volatile
    private var pausedNow = false

    /** 这次暂停是什么时候开始的。只为了让恢复那一条日志自带时长。 */
    @Volatile
    private var pausedSinceMs = 0L

    private val _paused = MutableStateFlow(false)

    /**
     * 界面与服务用它判断「现在是不是什么都不该做」。
     *
     * 刻意不是从设置直接 map 出来的：手动暂停之外还有一条自动来源（没有键盘），
     * 而且自动那条要等一段宽限期才真的落地。这里发的是**已经落地**的事实，
     * 于是磁贴、服务、界面看到的和实际停没停永远是同一件事。
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** 暂停是谁触发的。界面据此说明「为什么停着」，也决定要不要等宽限期。 */
    enum class PauseReason { NONE, MANUAL, NO_KEYBOARD }

    private val _pauseReason = MutableStateFlow(PauseReason.NONE)
    val pauseReason: StateFlow<PauseReason> = _pauseReason.asStateFlow()

    /** 常驻前台服务此刻开没开。同上，只为了让死亡记录能和配置对上号。 */
    @Volatile
    private var keepAliveOn = false

    /** 界面走后延迟释放应用清单的那个任务。界面回来就取消。 */
    private var catalogRelease: kotlinx.coroutines.Job? = null

    private val keyboards = MutableStateFlow<List<com.actionmental.core.key.KeyboardDevice>>(emptyList())

    /**
     * 该不该暂停，以及为什么。
     *
     * 手动优先于自动：两者同时成立时说「手动」，否则用户关掉自动暂停后会以为
     * 应用该跑起来了，而实际上他自己按下的那个暂停还在。
     */
    private val pauseIntent = combine(settingsRepository.settings, keyboards) { s, kbs ->
        when {
            s.paused -> PauseReason.MANUAL
            s.autoPauseWithoutKeyboard && kbs.isEmpty() -> PauseReason.NO_KEYBOARD
            else -> PauseReason.NONE
        }
    }

    /** 最近一次动作执行结果，供界面提示（PRD 25：不静默失败）。 */
    val lastActionResult = MutableStateFlow<Pair<String, ActionResult>?>(null)

    /** 无障碍的四个信号；[status] 的 combine 只能收五路，先在这里并成一路。 */
    private data class AccessibilitySignals(
        val connected: Boolean,
        val enabled: Boolean,
        val listed: Boolean,
        val master: Boolean,
    )

    private data class Bindings(
        val shortcuts: List<com.actionmental.core.shortcut.Shortcut>,
        val rules: List<com.actionmental.core.rotation.AppRotationRule>,
        val remaps: List<com.actionmental.core.remap.KeyRemap>,
        val foreground: String?,
        val activeRule: com.actionmental.core.rotation.AppRotationRule?,
    )

    private val bindings = combine(
        shortcutRepository.shortcuts,
        rotationRuleRepository.rules,
        keyRemapRepository.remaps,
        accessibility.foregroundPackage,
        appRotationRules.activeRule,
        ::Bindings,
    )

    val status: StateFlow<SystemStatus> = combine(
        combine(
            accessibility.connected,
            accessibility.enabledInSettings,
            accessibility.listedInSettings,
            accessibility.masterSwitchOn,
            ::AccessibilitySignals,
        ),
        shizuku.status,
        keyboards,
        combine(rotation.state, screenAwake.state, ::Pair),
        bindings,
    ) { access, shizukuStatus, devices, screen, b ->
        SystemStatus(
            accessibilityConnected = access.connected,
            accessibilityEnabledInSettings = access.enabled,
            accessibilityListedInSettings = access.listed,
            accessibilityMasterSwitchOn = access.master,
            shizuku = shizukuStatus,
            keyboards = devices,
            rotation = screen.first,
            screenAwake = screen.second,
            shortcutCount = b.shortcuts.size,
            disabledCount = b.shortcuts.count { !it.enabled },
            ruleCount = b.rules.size,
            remapCount = b.remaps.size,
            foregroundPackage = b.foreground,
            activeRule = b.activeRule,
        )
    }
        // 只有界面在看的时候才组装。它的上游有前台包，用户每切一次应用就要重跑
        // 一遍两层 combine —— 而频繁切应用恰恰是这个工具最典型的用法。
        // 5 秒的停留期让翻页、转屏这类短暂离开不至于反复重建。
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SystemStatus())

    /**
     * 亮屏就复查一次监听服务。
     *
     * 熄屏期间省电策略会把进程连同无障碍绑定一起收走，而系统并不总会把服务绑回来 ——
     * 用户的症状正是「熄屏唤醒之后就不灵了」。亮屏这一刻是进程还活着时最早的补救时机。
     * ACTION_SCREEN_ON 不能静态注册，所以只能在这里动态挂着。
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 先重新数一遍键盘，且不受暂停影响。
            //
            // 蓝牙键盘熄屏时断连是常态，「没有键盘就自动暂停」会因此落地；
            // 而键盘回来时的那次 InputManager 回调并不保证送得到这个进程 ——
            // 熄屏期间进程可能整段被冻结，回调就丢在那里了。少了这一下，
            // 键盘早就插着，应用却一直停着，而暂停期间连下面那趟绑定复查
            // 都被跳过：外面看到的就是「熄屏之后再也不灵了」。
            refreshKeyboards()

            // 暂停期间余下的都省掉：服务绑没绑上此刻并不重要，
            // 解除暂停时会补查一次
            if (pausedNow) return
            refreshAccessibility()
            scope.launch { ensureAccessibilityBound(trigger = "亮屏 / 解锁") }
        }
    }

    /**
     * 键盘插拔。
     *
     * 挂在图上而不是服务上：服务没连接的时候键盘照样会插拔，而那正是用户
     * 最想知道「到底认没认到键盘」的时刻。长期挂着也让 1.5 秒轮询失去了存在的理由 ——
     * 那一轮里最贵的就是它，要把系统里每一个输入设备都跨进程取一遍。
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = onDevicesChanged()
        override fun onInputDeviceChanged(deviceId: Int) = onDevicesChanged()
        override fun onInputDeviceRemoved(deviceId: Int) {
            pipeline.reset()        // 拔出键盘时清掉残留的按下状态
            onDevicesChanged()
        }
    }

    private fun onDevicesChanged() {
        pipeline.invalidateDevices()
        refreshKeyboards()
    }

    /**
     * Shizuku 装没装。
     *
     * 查一次要问 PackageManager，而它只在装 / 卸 / 更新的那一刻变 ——
     * 原来的轮询每 1.5 秒问一遍，等于一分钟四十次白问。
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.schemeSpecificPart == SHIZUKU_PACKAGE) shizuku.refresh()
        }
    }

    /**
     * 把未捕获异常也送进事件日志。
     *
     * 落盘那一步已经由 [CrashSink] 在 Application 里做掉了 —— 它装得比这张图早，
     * 所以图自己构造时崩掉也记得下来。这里只是把同一条异常补进内存日志，
     * 让日志页当场看得到，不必等下次启动去读盘。
     */
    private fun installCrashHandler() {
        CrashSink.attach { thread, error ->
            eventLog.logBlocking(
                LogLevel.ERROR,
                "crash",
                "进程因未捕获异常终止 · 线程 " + thread.name,
                EventLog.stackTraceOf(error),
            )
        }
    }

    /**
     * 把上一次进程的死因写进日志。
     *
     * native 崩溃、ANR 被杀、内存不足被回收都不会经过未捕获异常处理器 ——
     * 那几种情况下应用自己的日志里只会有一段无声的中断。系统这份记录补上了那一段。
     *
     * 只报告比上次记录过的更新的那些，否则每次启动都会重复刷同样的历史。
     */
    private fun reportLastExit() {
        if (!exitReporter.available()) {
            eventLog.debug("exit", "系统不提供进程退出记录 · 需要 Android 11 以上")
            return
        }
        scope.launch {
            val seen = settingsRepository.load().lastReportedExitMs
            val exits = exitReporter.recentExits().filter { it.timestampMs > seen }
            if (exits.isEmpty()) return@launch

            exits.sortedBy { it.timestampMs }.forEach { exit ->
                val message = "上次进程退出 · " + exit.summary()
                if (exit.abnormal) eventLog.error("exit", message, exit.detail())
                else eventLog.info("exit", message, exit.detail())
            }
            val newest = exits.maxOf { it.timestampMs }
            settingsRepository.update { it.copy(lastReportedExitMs = newest) }

            // 被系统策略杀掉不是崩溃，用户不会收到任何系统提示 —— 他只会发现
            // 快捷键忽然不灵了。这一条是他唯一的线索，也是去做后台加固的理由。
            exits.filter { it.abnormal }.maxByOrNull { it.timestampMs }?.let { worst ->
                hardeningNotice.value = "上次进程被系统结束 · " + worst.summary()
            }
        }
    }

    /**
     * [coroutineFailures] 的落点。
     *
     * 异常可能早到 [eventLog] 还没构造完（仓库的 stateIn 在构造函数里就开始读盘），
     * 那时字段还是 null —— 退回 [CrashSink] 直接写盘，保证这一条无论如何都留得下来。
     */
    private fun onCoroutineFailure(error: Throwable) {
        runCatching {
            eventLog.error("coroutine", "后台任务异常，已拦下 · " + error.javaClass.simpleName, error)
        }.onFailure {
            CrashSink.recordNonFatal("coroutine", error)
        }
    }

    fun start() {
        installCrashHandler()
        eventLog.info("app", "进程启动")
        reportLastExit()
        shizuku.start()
        ContextCompat.registerReceiver(
            appContext,
            screenOnReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // 以下三处把原来靠轮询发现的变化改成由事件通知，
        // 前台那一圈每分钟七百次的跨进程查询就是这么来的。
        inputDevices.registerListener(deviceListener)
        // 系统把服务从运行列表里摘掉的那一刻就复查一次。这是唯一能在「用户还没
        // 熄屏、也还没重启进程」时发现绑定失效的时机 —— 少了它，失灵要等到
        // 下一次亮屏才可能被发现，而如果失灵正好发生在熄屏期间，就得等下下次。
        // 暂停期间不去重绑（那要改系统设置），解除暂停时会补查一次。
        accessibility.onServicesStateChanged = {
            if (!pausedNow) scope.launch { ensureAccessibilityBound(trigger = "系统服务列表变化") }
        }
        accessibility.startObserving(KeyboardAccessibilityService::class.java)

        // 旋转控制器靠「系统现在是什么样」决定还要不要写。用户在快捷设置里拨一下
        // 自动旋转，那个结论就不再成立 —— 这两个键一变就让它作废，
        // 而不是靠定期重读去发现（那正是原来烧掉 CPU 的做法）。
        settingsReader.observeSystem(listOf("accelerometer_rotation", "user_rotation")) {
            rotation.invalidateObservedState()
            // 界面正开着就当场读一次真值。这比轮询快（用户拨完开关立刻就对上了），
            // 也比轮询省（不变就一次都不读）—— 界面的定时轮询因此可以放到很疏。
            if (uiForeground) rotation.refreshAsync()
        }
        ContextCompat.registerReceiver(
            appContext,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        notifier.ensureChannel()
        // 应用清单刻意不在这里预热。这个进程多数时候是被开机广播、无障碍绑定
        // 或者上一次被杀之后的重启拉起来的 —— 那些时刻根本没有界面，
        // 而 queryIntentActivities 要把几百个包连同 disabled 的一起取回来，
        // 是启动路径上最贵的一步。真正要用它的地方（AppViewModel.ensureAppCatalog）
        // 每次回到前台都会问一次，晚这一下没人看得出来。
        refreshKeyboards()
        refreshAccessibility()

        // 快捷键表 / 映射表变化 → 换掉匹配器的内存索引（按键线程永不碰 IO）
        scope.launch {
            shortcutRepository.shortcuts.collect { matcher.update(it) }
        }
        scope.launch {
            keyRemapRepository.remaps.collect { remapMatcher.update(it) }
        }

        // 进程重启后按用户意图重建旋转状态，而不是沿用旧缓存
        scope.launch {
            settingsRepository.settings.map { it.globalRotationMode }.collect { mode ->
                rotation.restoreGlobalMode(mode)
            }
        }
        scope.launch { rotation.refresh() }

        // 唤醒锁随进程消失，所以「上次开着常亮」只能靠盘上的意图重建。
        // 用 load() 而不是订阅 settings：这里只要开机那一次，
        // 订阅会把用户随后每一次关闭又当成一次「重建为关闭」，白跑一趟。
        scope.launch { screenAwake.restore(settingsRepository.load().screenAwake) }

        // 通知只跟真实状态走：不是「谁点了开关」，而是锁到底握没握住。
        // 于是通知栏里有这一条 = 屏幕此刻真的锁着不熄。
        scope.launch { screenAwake.state.collect { awakeNotifier.render(it.on) } }

        // 服务的每一次连上 / 掉线都记一条：排查「什么时候没的」只能靠这条时间线
        scope.launch {
            accessibility.connected.collect { on ->
                if (on) eventLog.info("a11y", "监听服务已连接")
                else eventLog.warn("a11y", "监听服务未连接")
                // 常亮的悬浮层挂在服务实例上：服务一换（连上 / 掉线），旧窗口就作废了。
                // 连上时按意图重挂（从唤醒锁兜底升级回悬浮层），掉线时按意图退回唤醒锁。
                // 常亮不受暂停管，所以这里不看 pausedNow。
                screenAwake.restore(settingsRepository.load().screenAwake)
            }
        }

        // 温控。系统回调驱动，档位不变时一次都不会醒 —— 而它正是死因里的那个自变量。
        thermal.start { command -> scope.launch { command.run() } }

        // 体征。采样便宜（两次 /proc 小读），所以 30 秒起采；写日志才贵，所以只在
        // 跨档、CPU 明显、或者太久没写过时才落一条。上一次被杀前的三分钟一条都没有。
        scope.launch {
            vitals.sample("启动", force = true)
            while (true) {
                // 暂停期间一条都不采。采样本身便宜，但它每隔几十秒就要把这个进程
                // 唤醒一次去读 /proc —— 而「暂停」承诺的正是连这一下都不做。
                // 挂在流上而不是空转轮询：不解除暂停就一次都不会醒。
                paused.first { !it }
                delay(vitals.nextDelayMs)
                if (!pausedNow) vitals.sample("常规采样")
            }
        }

        // 常驻前台服务只跟着开关走，不跟暂停走。
        //
        // 暂停期间进程也得活着：自动暂停要靠它看见键盘回来才解除得了。
        // 原来暂停时把它停掉，还要为「进程刚起来时 keepAlive 与暂停状态前后脚到达」
        // 压一道抖动 —— 脱开之后这条流只剩用户拨开关那一种变化。
        scope.launch {
            settingsRepository.settings.map { it.keepAlive }
                .distinctUntilChanged()
                .collect { applyKeepAlive(it) }
        }

        // 全局暂停。这条流是所有闸门的上游，必须在别的收集器之后挂上 ——
        // 进程重启时它会立刻发一次盘上的值，把暂停状态重建出来。
        scope.launch {
            var pending: kotlinx.coroutines.Job? = null
            pauseIntent.distinctUntilChanged().collect { reason ->
                // 上一次还没落地的自动暂停作废：键盘在宽限期里回来了，就当无事发生。
                // 这一步也是「键盘抖一下不该有任何副作用」的全部实现 ——
                // 没落地的暂停不需要被撤销，因为它从来没发生过。
                pending?.cancel()
                pending = null

                if (reason == PauseReason.NO_KEYBOARD) {
                    // 蓝牙键盘掉一下再回来是常事。进暂停要把强制旋转写回系统，
                    // 那都是 shell 写入 —— 为了几秒钟的抖动拆了又装回去，恰恰是这个应用
                    // 最该避免的那种发热。恢复不等：键盘回来了就该立刻能用。
                    pending = scope.launch {
                        delay(KEYBOARD_GRACE_MS)
                        applyPaused(true, reason)
                    }
                } else {
                    applyPaused(reason != PauseReason.NONE, reason)
                }
            }
        }

        // 无障碍自愈：开关被 ROM 抹掉时写回去（P2）
        scope.launch { watchAccessibilitySwitch() }

        // 进程刚被拉起来时也查一次：这一次拉起，往往就是上一次被杀之后
        scope.launch { ensureAccessibilityBound(trigger = "进程启动") }

        // 唯一的快捷键触发点
        pipeline.onTrigger = { combo, device ->
            val shortcut = matcher.match(combo, device, accessibility.foregroundPackage.value)
            if (shortcut == null) {
                false
            } else {
                pipeline.trace(KeyTrace.Kind.MATCH, combo.toString(), shortcut.action.technical)
                scope.launch {
                    counters.action.incrementAndGet()
                    val result = executor.execute(shortcut.action)
                    lastActionResult.value = shortcut.displayLabel to result
                    // 触发与结果都留一条：排查「连按几下就失灵」时，这条时间线是唯一的线索
                    if (result.succeeded) {
                        eventLog.debug("action", shortcut.displayLabel, shortcut.action.technical)
                    } else {
                        eventLog.error("action", shortcut.displayLabel + " 执行失败", result.message)
                    }
                    pipeline.trace(
                        if (result.succeeded) KeyTrace.Kind.VERIFIED else KeyTrace.Kind.ERROR,
                        shortcut.displayLabel,
                        result.message,
                    )
                }
                true
            }
        }

        // 修饰键映射：按住的修饰键被映射时，先把组合改写成映射之后的样子。
        // 快捷键匹配用的就是改写后的组合 —— 于是「左 Shift 映射成 Alt」以后，
        // 绑在 Alt + X 上的快捷键真的能被 左 Shift + X 按出来。
        pipeline.rewriteCombo = { combo, pressed -> remapMatcher.rewrite(combo, pressed) }

        // 整颗替换：源键的按下 / 连发 / 抬起被拦下，换成目标键的同一半事件。
        // 输入法看不到原键，所以「短按左 Shift 切中英文」这类由原键触发的行为也一并消失。
        pipeline.onReplacedKey = { keyCode, modifiers, down, device ->
            val source = KeyCombo(keyCode, modifiers)
            val remap = remapMatcher.match(source)
            val availability = if (remap == null) ActionResult.OK else shizuku.availability()
            when {
                remap == null -> false

                // 快捷键优先：同一个组合已经绑了动作，就别把这颗键换走
                matcher.match(source, device, accessibility.foregroundPackage.value) != null -> false

                // 注入断了还拦下源键，这颗键就彻底哑掉了。宁可映射不生效，也不能吞键（PRD 25）
                !availability.succeeded -> {
                    if (down) {
                        pipeline.trace(
                            KeyTrace.Kind.ERROR,
                            source.toString(),
                            "映射未生效 · " + availability.message,
                        )
                    }
                    false
                }

                else -> {
                    if (down) {
                        pipeline.trace(KeyTrace.Kind.MATCH, source.toString(), "→ " + remap.to.toString())
                    }
                    keyInjector.enqueueState(remap.to, down)
                    true
                }
            }
        }

        // 没有快捷键命中时才轮到映射：把该发出去的键交给系统重新分发
        pipeline.onRemap = { original, effective, _ ->
            val remap = remapMatcher.match(effective)

            // 映射成修饰键：源键被拦下，但什么都不发 ——
            // 它现在只是一颗修饰键，作用体现在按住期间的**别的**键上
            val heldModifier = remap != null && remap.isModifierRemap
            val target = when {
                heldModifier -> null
                remap != null -> remap.to
                // 修饰位被改写过：即使这个组合没有任何绑定，也要按改写后的样子发出去
                effective != original -> effective
                else -> null
            }

            val engaged = heldModifier || target != null
            val availability = if (engaged) shizuku.availability() else ActionResult.OK

            when {
                !engaged -> false

                // 注入链路断了还拦下源键，等于这颗键彻底哑掉 —— 用户既没有原键也没有目标键，
                // 而且完全看不出原因。宁可映射不生效，也不能吞键（PRD 25）。
                !availability.succeeded -> {
                    pipeline.trace(
                        KeyTrace.Kind.ERROR,
                        original.toString(),
                        "映射未生效 · " + availability.message,
                    )
                    false
                }

                heldModifier -> {
                    pipeline.trace(KeyTrace.Kind.MATCH, original.toString(), "→ 修饰键")
                    true
                }

                else -> {
                    pipeline.trace(KeyTrace.Kind.MATCH, original.toString(), "→ " + target.toString())
                    keyInjector.enqueue(target!!)
                    true
                }
            }
        }
    }

    /**
     * 盯住系统里的无障碍开关，掉了就尝试写回去。
     *
     * 三道闸门缺一不可，且顺序是刻意的：
     *  1. 用户在设置里开了自愈；
     *  2. 这个开关**曾经**是开的 —— 从没授权过的服务，绝不由应用替用户打开；
     *  3. Shizuku 可用 —— 没有 shell 身份本来也写不进 secure 设置。
     * 冷却与失败退避在 [HardeningController] 内部，这里不重复实现。
     */
    private suspend fun watchAccessibilitySwitch() {
        combine(
            accessibility.enabledInSettings,
            shizuku.status,
            settingsRepository.settings,
        ) { enabled, shizukuStatus, userSettings ->
            Triple(enabled, shizukuStatus.usable, userSettings)
        }.collect { (enabled, shizukuUsable, userSettings) ->
            if (enabled) {
                // 记下「用户确实授权过」，这是将来允许自动写回的唯一依据
                if (!userSettings.accessibilityEverEnabled) {
                    settingsRepository.update { it.copy(accessibilityEverEnabled = true) }
                }
                hardening.resetAutoHealBackoff()
                return@collect
            }
            // 暂停期间不替用户去改系统开关：这个应用此刻本来就不该起作用，
            // 把无障碍写回去只会让「已暂停」和一个正在被自动恢复的服务同时成立
            if (userSettings.paused) return@collect
            if (!userSettings.autoHealAccessibility) return@collect
            if (!userSettings.accessibilityEverEnabled || !shizukuUsable) return@collect

            // Skipped（冷却中、开关本来就在）不打扰用户；只有真的动了手才提示
            when (val outcome = hardening.healAccessibility(HealTrigger.AUTO)) {
                is HealOutcome.Skipped -> Unit
                is HealOutcome.Attempted -> {
                    val succeeded = outcome.result.succeeded
                    hardeningNotice.value = if (succeeded) {
                        "监听服务被系统关闭，已自动恢复"
                    } else {
                        "监听服务被关闭，自动恢复失败 · " + outcome.result.message
                    }
                    // 自动触发才推通知：手动点按钮时用户正看着屏幕，再推一条只是噪音
                    notifier.notifyHeal(succeeded, outcome.result.message)
                    refreshAccessibility()
                }
            }
        }
    }

    /**
     * 开机后的一次性自愈，由 [com.actionmental.service.BootReceiver] 调用。
     *
     * 不复用 [watchAccessibilitySwitch] 的被动监视，是因为开机这一刻的时序刚好相反：
     * 进程是被广播拉起来的，Shizuku 往往还要几秒才连得上，被动等状态变化可能等不到
     * 广播窗口结束。这里主动等一轮，然后不管结果如何都试一次 ——
     * Shizuku 始终没起来本身就是用户必须知道的结论，而不是「什么都没发生」。
     *
     * @return 是否真的动了手（写回了开关，或留下了一条失败记录）。
     */
    suspend fun healAfterBoot(timeoutMs: Long = 15_000L): Boolean {
        // 盘上的真值，不是 StateFlow 里那个还没填充的默认值
        val userSettings = settingsRepository.load()
        if (userSettings.paused) return false
        if (!userSettings.autoHealAccessibility || !userSettings.accessibilityEverEnabled) return false

        refreshAccessibility()
        if (accessibility.enabledInSettings.value) return false

        // Shizuku 开机后要几秒才起得来；等不到就带着真实原因去尝试，让它留一条记录
        withTimeoutOrNull(timeoutMs) {
            while (!shizuku.status.value.usable) {
                shizuku.refresh()
                delay(500)
            }
        }

        return when (val outcome = hardening.healAccessibility(HealTrigger.BOOT)) {
            is HealOutcome.Skipped -> false
            is HealOutcome.Attempted -> {
                val succeeded = outcome.result.succeeded
                hardeningNotice.value = if (succeeded) {
                    "开机后监听服务被关闭，已自动恢复"
                } else {
                    "开机后监听服务被关闭，自动恢复失败 · " + outcome.result.message
                }
                notifier.notifyHeal(succeeded, outcome.result.message)
                refreshAccessibility()
                true
            }
        }
    }

    /**
     * 开关都对、服务却没连上时，逼系统重新绑定一次。
     *
     * 先等一段宽限期：系统自己重绑通常只要几秒，抢在它前面摘掉服务反而制造一次掉线。
     * 只有等过之后仍然没连上，才动手。
     *
     * @return 是否真的动了手。
     */
    suspend fun ensureAccessibilityBound(
        graceMs: Long = 15_000L,
        trigger: String = "常规复查",
        /**
         * 没抢到档期时愿意等多久。
         *
         * 默认不等：亮屏、解锁这类触发点隔一会儿还会再来，撞上就让它过去。
         * 但「解除暂停」这一趟不能让：暂停前发起的那趟复查最长要挂三十几秒，
         * 而它一开头就因为 `pausedNow` 返回了 false —— 静默让路的结果是恢复那一刻
         * 一次都没查过，用户拿到的就是一个没人管的失效绑定。
         */
        waitForSlotMs: Long = 0L,
    ): Boolean {
        // 一次只查一趟。触发点有五个（亮屏、解锁、进程启动、解除暂停、系统服务列表变化），
        // 而这里最长要挂住二十几秒 —— 几个触发点凑在一起时，重叠的那几趟不只是白跑：
        // 它们会各自去摘一次服务。
        if (!boundCheckRunning.compareAndSet(false, true)) {
            if (waitForSlotMs <= 0L) {
                eventLog.debug("a11y", "已有复查在跑，这次让路", "触发=" + trigger)
                return false
            }
            // 刻意不用 withTimeoutOrNull：超时是靠取消实现的，而取消点恰好可能落在
            // 「抢到了标志」和「把它用起来」之间 —— 那一下会让标志永远留在 true，
            // 从此再也没有任何一次绑定复查跑得起来。自己数时间就没有这个窗口。
            val deadline = System.currentTimeMillis() + waitForSlotMs
            var acquired = false
            while (System.currentTimeMillis() < deadline) {
                delay(HEALTH_POLL_MS)
                if (boundCheckRunning.compareAndSet(false, true)) {
                    acquired = true
                    break
                }
            }
            if (!acquired) {
                eventLog.warn(
                    "a11y",
                    "等不到复查档期，这次跳过",
                    "触发=" + trigger + " · 等了 " + (waitForSlotMs / 1000L) + "s",
                )
                return false
            }
        }
        return try {
            checkAccessibilityBound(graceMs, trigger)
        } finally {
            boundCheckRunning.set(false)
        }
    }

    private val boundCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun checkAccessibilityBound(graceMs: Long, trigger: String): Boolean {
        // 暂停时服务连不连得上没有意义，而这段等待要挂住一个协程十几秒
        if (pausedNow) return false
        refreshAccessibility()
        if (accessibilityHealthy()) return false
        // 开关本身掉了是另一回事，交给 watchAccessibilitySwitch 写回去
        if (!accessibility.enabledInSettings.value) {
            eventLog.debug(
                "a11y",
                "服务没连上，但系统开关也不在，这一趟不管",
                "触发=" + trigger + " · " + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java),
            )
            return false
        }

        // 设置先读掉。
        //
        // 原来是等完宽限期再去读盘，而那一次读（几十到上百毫秒）恰好是系统把服务
        // 绑上来的窗口：日志里留下过 `46.596 已连接` 紧跟着 `46.631 已请求重新绑定`——
        // 摘掉开关再写回去，等于把一个刚站起来的服务当场又推倒一次。
        val autoHeal = settingsRepository.load().autoHealAccessibility

        // 手里还攥着服务实例，说明这不是「还没绑上」，而是绑定已经作废 ——
        // 熄屏被冻结、连接被服务端丢掉，系统都不会回调 onUnbind / onDestroy。
        // 这种情况等下去没有意义：不会有任何一方再发起绑定。
        val stale = accessibility.connected.value
        val startedAt = System.currentTimeMillis()
        if (stale) {
            eventLog.warn(
                "a11y",
                "服务实例还在，系统却不认这个绑定",
                "触发=" + trigger + " · 按键收不到了 · 自动恢复=" + autoHeal +
                    "\n" + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java),
            )
            // attach 和系统把服务登记进「正在运行」之间有一点时间差，
            // 给一个确认期，免得刚绑上就被当成失效连接拆一次
            delay(CONFIRM_MS)
            refreshAccessibility()
        } else {
            eventLog.debug(
                "a11y",
                "等待系统绑定监听服务",
                "触发=" + trigger + " · 宽限 " + (graceMs / 1000L) + "s · 自动恢复=" + autoHeal +
                    "\n" + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java),
            )
            withTimeoutOrNull(graceMs) { accessibility.connected.first { it } }

            // 宽限期到点与真正绑上之间常常只差一瞬（实测有过差 140ms 的），
            // 所以到点之后再确认一小段，确认期内连上就当作正常绑定，不动手。
            if (!accessibility.connected.value) {
                withTimeoutOrNull(CONFIRM_MS) { accessibility.connected.first { it } }
            }
        }
        val waitedMs = System.currentTimeMillis() - startedAt

        // 等到了就把时长记下来。实测有过等 44 秒的情况，而那段时间界面上
        // 只有一句「已授权但未连接」，看不出到底是在等还是已经没救了。
        if (accessibilityHealthy()) {
            if (!stale) eventLog.info("a11y", "系统在 " + waitedMs + "ms 后完成绑定")
            return false
        }

        if (!autoHeal) {
            eventLog.warn(
                "a11y",
                if (stale) "绑定已失效" else "等待 " + waitedMs + "ms 仍未绑定",
                "触发=" + trigger + " · 自动恢复未开启，只能手动重连或到系统设置里关掉再打开" +
                    "\n" + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java),
            )
            // 自动恢复关着时，这条路到此为止 —— 用户唯一的线索只有界面上那句提示，
            // 所以必须把它推到界面去，而不是只写进日志里等人翻
            hardeningNotice.value = "监听服务掉线，自动恢复未开启 · 请手动重连"
            return false
        }

        eventLog.warn(
            "a11y",
            if (stale) "绑定已失效，开始自动重连" else "等待 " + waitedMs + "ms 仍未绑定，开始自动重连",
            "触发=" + trigger,
        )
        return when (val outcome = hardening.rebindAccessibilityAuto()) {
            is HealOutcome.Skipped -> {
                // 静默跳过是原来最难查的一种：日志里什么都没有，看上去像是压根没检查过
                eventLog.warn(
                    "a11y",
                    "这次不重连 · " + outcome.why,
                    "触发=" + trigger + " · 按键此刻仍然收不到" +
                        "\n" + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java),
                )
                false
            }
            is HealOutcome.Attempted -> {
                // 写进系统设置只是「请求」，服务有没有真的连回来是另一回事：
                // 日志里出现过写入成功、十几秒后才由系统自己绑上的情况。
                // 报成功却没连上，等于让用户以为修好了，然后继续对着失灵的键盘。
                val requested = outcome.result.succeeded
                val recovered = requested && awaitAccessibilityHealthy(REBIND_VERIFY_MS)
                val message = when {
                    recovered -> outcome.result.message
                    requested -> "已写回系统设置，但服务仍未连上，请到系统设置里手动关掉再打开"
                    else -> outcome.result.message
                }
                val tail = "触发=" + trigger + " · " + message +
                    "\n" + accessibility.diagnosticSummary(KeyboardAccessibilityService::class.java)
                if (recovered) eventLog.info("a11y", "自动重连完成", tail)
                else eventLog.error("a11y", "自动重连失败", tail)
                hardeningNotice.value = if (recovered) {
                    "监听服务掉线，已自动重新连上"
                } else {
                    "监听服务掉线，自动重连失败 · " + message
                }
                notifier.notifyHeal(recovered, message)
                refreshAccessibility()
                true
            }
        }
    }

    /**
     * 现在真的收得到按键吗。
     *
     * 两个条件缺一不可，因为「失灵」有两种长得一模一样的成因：
     *  1. 进程里没有服务实例 —— 被杀之后系统还没绑回来；
     *  2. 实例在、系统那边不认 —— 熄屏冻结留下的失效连接，不会有任何回调。
     * 只看第 1 条时，第 2 种情况下自动恢复根本不会启动：应用一路显示「已连接」，
     * 用户却只能去系统设置里关掉再打开。
     */
    private fun accessibilityHealthy(): Boolean =
        accessibility.connected.value &&
            accessibility.isRunningPerSystem(KeyboardAccessibilityService::class.java)

    /**
     * 等服务真的重新站起来。
     *
     * 轮询而不是挂在 [AccessibilityBridge.connected] 上：两条判据里只有「有没有实例」
     * 是流，「系统认不认这个绑定」只能当场去问 —— 挂在流上时，实例一直在、
     * 流一次都不会再发，于是这里只会干等到超时，然后报一个假的「没救了」。
     */
    private suspend fun awaitAccessibilityHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (accessibilityHealthy()) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(HEALTH_POLL_MS)
        }
    }

    /**
     * 强制系统重新绑定监听服务，然后刷新状态。
     *
     * 「已授权但未连接」有两种成因：总开关被关掉（[hardening] 的自愈能修），
     * 以及系统单纯没有重绑（设置里读什么都正常，自愈认为无事可做）。
     * 后者只有摘掉再写回才解得开，这是用户手动「关掉再打开」的等价动作。
     */
    suspend fun rebindAccessibility(): ActionResult {
        val result = hardening.rebindAccessibility()
        refreshAccessibility()
        return result
    }

    /**
     * 系统提示内存吃紧，或者界面已经看不见了。
     *
     * 这个进程的本职是收按键，界面只是偶尔露面 —— 界面一走，它带来的那些缓存
     * 就该跟着走。留着它们不但没用，还会让进程在后台限制策略的名单上排得更靠前：
     * 那类策略基本按占用挑目标，而实测被杀那次进程占了将近 1GB。
     */
    fun onTrimMemory(level: Int) {
        if (level < TRIM_MEMORY_UI_HIDDEN) return

        releaseUiCaches()
        val freed = vitals.sample("界面隐藏 / 内存吃紧 level=" + level, force = true)

        // 内存真的告急时，连诊断数据也让位 —— 事件日志已经落盘，丢的只是内存里那一份
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            shellLog.clear()
            eventLog.trimMemory()
            eventLog.warn("mem", "内存告急，已释放缓存与诊断数据", "level=" + level + " rss=" + freed + "MB")
        }
    }

    /**
     * 界面停下来了。由 [com.actionmental.ui.MainActivity] 的 onStop 直接调用。
     *
     * 不等 [onTrimMemory]：那是系统在**它**觉得紧张的时候才发的信号，什么时候发、
     * 发不发都不由我们决定。而「界面看不见了」这件事本身是确定的，也正是这些缓存
     * 变成纯粹负担的那一刻 —— 进程还要以无障碍服务的身份继续活着。
     */
    fun onUiStopped() {
        uiForeground = false
        pipeline.setTracing(false)   // 按键轨迹是按键监视页专用的，界面一走就没人看
        shellLog.trim()
        eventLog.trimMemory(keep = 200)
        // 界面进出是回看日志时最有用的锚点之一：前后两条体征之间的差值就是界面的代价
        vitals.sample("界面退到后台", force = true)

        // 应用清单**不**当场放掉。
        //
        // 它重建一次要把几百个包连同 disabled 的一起查回来，还要逐条 loadLabel ——
        // 几百毫秒的 CPU。用户来回开关界面时，立刻释放等于把内存换成了发热，
        // 而杀掉这个进程的策略看的正是发热。等一会儿再放：真的走了才收，
        // 顺手回来一趟不收。
        catalogRelease?.cancel()
        catalogRelease = scope.launch {
            delay(CATALOG_GRACE_MS)
            appCatalog.release()
            eventLog.debug("mem", "界面离开超过 " + (CATALOG_GRACE_MS / 1000L) + " 秒，已放掉应用清单缓存")
        }
    }

    /** 界面回来了。由 [com.actionmental.ui.MainActivity] 的 onStart 调用。 */
    fun onUiStarted() {
        uiForeground = true
        catalogRelease?.cancel()
        catalogRelease = null
        // 与「界面退到后台」那条成对。两条之间的 rss 差值就是界面本身的代价 ——
        // 上一次被杀时 rss 是 165MB，而启动那一刻只有 11MB，中间的账一直没人算过。
        vitals.sample("界面进入前台", force = true)
    }

    /**
     * 机器热到了会开始收拾进程的区间。
     *
     * 这条策略挑目标看的是当下这一刻的样子，所以「热起来了」正是该瘦下来的时刻 ——
     * 界面不在前台时那些缓存本来就没人用，留到被杀那一刻才释放毫无意义。
     * 界面还开着就不动它：把用户正在看的列表清空，换不来任何东西。
     */
    private fun onThermalLevel(level: Int) {
        if (level < THERMAL_SEVERE) return
        if (uiForeground) {
            eventLog.warn("thermal", "温控进入清理区间，但界面在前台，保留缓存", "level=" + level)
            return
        }
        releaseUiCaches()
        eventLog.trimMemory()
        vitals.sample("温控升到 " + thermal.statusLabel + "，已提前释放缓存", force = true)
    }

    /** 只有界面用得上的那几份东西。系统真的喊内存吃紧时才当场全放。 */
    private fun releaseUiCaches() {
        appCatalog.release()
        pipeline.setTracing(false)   // 顺带清空按键轨迹：那是按键监视页专用的
        shellLog.trim()
    }

    /**
     * 一行运行画像，跟着每一条体征日志走。
     *
     * 「这次被杀的时候，常驻前台服务到底是开着的吗、屏幕是不是正被我们锁着不熄、
     * 界面在不在前台」—— 事后没有任何别的地方能回答这些，而它们恰恰是
     * 判断厂商策略按什么挑目标的全部依据。
     */
    private fun runtimeProfile(): String = buildString {
        if (pausedNow) append("暂停 ")
        append("界面=").append(if (uiForeground) "前台" else "后台")
        append(" 温控=").append(thermal.statusLabel)
        append(" 常驻=").append(if (keepAliveOn) "开" else "关")
        append(" 常亮=").append(if (screenAwake.state.value.on) "开" else "关")
        append(" 监听=").append(if (accessibility.connected.value) "连接" else "断开")
        append(" 清单=").append(appCatalog.apps.value.size)
    }

    /**
     * 进 / 出全局暂停。
     *
     * 一处集中处理，而不是让每个模块自己去订阅设置：暂停要求的是一个**顺序** ——
     * 先掐掉入口（按键直通、窗口事件丢弃），再处理屏幕（写一次 0° 竖屏后关闸），
     * 最后才丢缓存。反过来做会在中间那一瞬留下「事件还在进、状态已经拆了」的窗口。
     *
     * 暂停不碰无障碍服务的配置：按键照常送到 [pipeline]，由它原样放行。
     * 原来靠改写 serviceInfo 让系统别再分发，恢复时再写回去 —— 有些 ROM 写回去之后
     * 不重算按键过滤链，于是有了「解除暂停后按键不灵、只能去系统设置里关掉再打开」，
     * 以及为它准备的三段式恢复。配置从不改，这一整类故障就不存在了。
     *
     * 恢复时不去记「暂停前各项是什么样」：意图本来就在设置里存着，
     * 照着 [com.actionmental.data.UserSettings] 重建一遍就是了。
     */
    private suspend fun applyPaused(on: Boolean, reason: PauseReason) {
        // 结论没变就一步都不做。这一句挡掉两种最容易出问题的情形：冷启动时设置流
        // 必然先发一次默认值（照做会在每次启动都跑一遍恢复流程，还往日志里写一条
        // 从没发生过的「已恢复」），以及键盘在宽限期里回来 —— 那次暂停根本没落地，
        // 不该有一次「恢复」跟在后面。
        if (_paused.value == on && _pauseReason.value == reason) {
            pausedNow = on
            // 冷启动且没有暂停：旋转控制器默认关着闸（见 RotationController.paused），
            // 只有在这里确认了才放开。已经放开时这是一次空操作。
            if (!on) resumeRotation()
            return
        }
        pausedNow = on
        _paused.value = on
        _pauseReason.value = reason
        pipeline.setPaused(on)

        if (on) {
            pausedSinceMs = System.currentTimeMillis()
            val portrait = enterPauseRotation()
            // 前台包停在暂停那一刻会让恢复后的第一条规则算不出来，索性清掉
            accessibility.clearForeground()
            releaseUiCaches()
            eventLog.trimMemory(keep = 200)
            eventLog.info(
                "pause",
                if (reason == PauseReason.NO_KEYBOARD) "未检测到键盘，已自动暂停" else "已暂停 · 全部功能停止",
                "按键直通 · 旋转" + portrait + " · 常亮保持" +
                    (if (screenAwake.state.value.on) "开启" else "关闭"),
            )
            vitals.sample("进入暂停", force = true)
        } else {
            val pausedForMs = if (pausedSinceMs == 0L) 0L else System.currentTimeMillis() - pausedSinceMs
            pausedSinceMs = 0L
            settingsRepository.update { it.copy(pauseRotationApplied = false) }
            resumeRotation()
            screenAwake.restore(settingsRepository.load().screenAwake)
            refreshAccessibility()
            eventLog.info(
                "pause",
                "已恢复 · 全部功能重新生效",
                "暂停 " + formatDuration(pausedForMs) + " · 键盘=" + keyboards.value.size + " 个",
            )
            vitals.sample("退出暂停", force = true)
            // 暂停期间跳过了所有绑定复查，这里补一次。单独起一条：它最长要等十几秒，
            // 留在收集器里会挡住下一次暂停切换。要等档期：暂停前发起的那趟可能还挂着
            scope.launch {
                ensureAccessibilityBound(trigger = "解除暂停", waitForSlotMs = RESUME_SLOT_WAIT_MS)
            }
        }
    }

    /**
     * 暂停落地时关旋转闸门，并在这一段暂停里第一次落地时把屏幕放回 0° 竖屏。
     *
     * 「第一次」以盘上的 [com.actionmental.data.UserSettings.pauseRotationApplied] 为准，
     * 所以暂停期间进程被杀、重启后暂停重新落地，不会再把用户自己转过的屏幕扳回来。
     * 写失败（多半是 Shizuku 不在）不置位，下一次落地再试。
     *
     * @return 给日志用的一句话。
     */
    private suspend fun enterPauseRotation(): String {
        val pending = !settingsRepository.load().pauseRotationApplied
        val result = rotation.setPaused(true, lockPortrait = pending)
        if (!pending) return "不再修改（本段暂停已放回过 0°）"
        if (!result.succeeded) return "放回 0° 竖屏失败 · " + result.message
        settingsRepository.update { it.copy(pauseRotationApplied = true) }
        return "已放回 0° 竖屏，之后不再修改"
    }

    /**
     * 打开旋转写入闸门。
     *
     * 先从盘上取回全局意图再放开：冷启动时 [RotationController.globalMode] 还是默认的 NORMAL，
     * 恢复它的那条订阅未必已经跑到 —— 抢先放开会把系统自动旋转写回去。
     */
    private suspend fun resumeRotation() {
        rotation.restoreGlobalMode(settingsRepository.load().globalRotationMode)
        rotation.setPaused(false)
    }

    /** 把毫秒说成人话。日志里「暂停 4200000ms」没人算得动，而这个时长正是关键自变量。 */
    private fun formatDuration(ms: Long): String = when {
        ms <= 0L -> "未知时长"
        ms < 60_000L -> (ms / 1000L).toString() + "秒"
        ms < 3_600_000L -> (ms / 60_000L).toString() + "分" + (ms % 60_000L / 1000L) + "秒"
        else -> (ms / 3_600_000L).toString() + "小时" + (ms % 3_600_000L / 60_000L) + "分"
    }

    /**
     * 开 / 关常驻前台服务。
     *
     * 失败必须留痕：Android 12 起从后台启动前台服务是受限的，而进程被杀之后的
     * 那一次重启恰好就在后台 —— 静默失败的表现是「开关是开的、通知栏什么都没有」，
     * 用户会以为已经保住了。
     */
    private fun applyKeepAlive(on: Boolean) {
        val wasOn = keepAliveOn
        keepAliveOn = on
        val intent = Intent(appContext, KeepAliveService::class.java)
        runCatching {
            when {
                on -> ContextCompat.startForegroundService(appContext, intent)
                // 停也得走 startForegroundService：stopService 不解除系统那个
                // 「startForegroundService 之后必须进前台」的超时，服务被停在进前台
                // 之前就是一次 ForegroundServiceDidNotStartInTimeException。
                // 让服务自己先 startForeground 再 stopSelf，超时才算兑现。
                wasOn -> ContextCompat.startForegroundService(
                    appContext,
                    Intent(intent).setAction(KeepAliveService.ACTION_STOP),
                )
                // 从没启动过就没有待兑现的超时，stopService 收掉可能残留的实例即可
                // （进程崩溃重启后系统会按 START_STICKY 把它拉起来）。
                else -> appContext.stopService(intent)
            }
        }.onFailure {
            eventLog.error("keepalive", if (on) "常驻前台服务启动失败" else "常驻前台服务停止失败", it)
        }
    }

    fun refreshKeyboards() {
        keyboards.value = inputDevices.physicalKeyboards()
    }

    /** 重新读系统设置里的无障碍开关：进程重启后服务还没回连时，这是唯一可信的来源。 */
    fun refreshAccessibility() {
        accessibility.refresh(KeyboardAccessibilityService::class.java)
    }

    fun accessibilityEnabledInSettings(): Boolean =
        accessibility.isEnabledInSettings(KeyboardAccessibilityService::class.java)

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        // 直接用字面值，免得为了两个常量把 ComponentCallbacks2 拖进领域层
        /** PowerManager.THERMAL_STATUS_SEVERE。到这一档系统已经在限制后台了。 */
        private const val THERMAL_SEVERE = 3

        /** 宽限期到点之后再确认这么久。绑定与超时之间常常只差一瞬。 */
        private const val CONFIRM_MS = 3_000L

        /**
         * 重绑写进设置之后，等服务真的连回来多久。
         *
         * 实测系统这一步慢的时候要十几秒（日志里有过 19s），所以宁可等长一点：
         * 早早报「失败」并推一条通知，比等着更打扰人。
         */
        private const val REBIND_VERIFY_MS = 20_000L

        /** 解除暂停那一趟复查愿意等多久的档期。够上一趟（最长 43s）跑完。 */
        private const val RESUME_SLOT_WAIT_MS = 45_000L

        /** 健康判据里有两条只能当场去问，只好轮询。这是轮询间隔。 */
        private const val HEALTH_POLL_MS = 250L

        /** 界面离开多久才真的放掉应用清单。短暂切走再回来不该付重建的代价。 */
        private const val CATALOG_GRACE_MS = 90_000L

        /**
         * 键盘不见了之后等多久才真的自动暂停。
         *
         * 蓝牙键盘休眠、重连，或者换一个 USB 口，中间都会有几秒钟查不到设备。
         * 立刻暂停意味着立刻拆掉唤醒锁与强制旋转，几秒后又原样装回去 ——
         * 用户看到的是屏幕转了两次，机器白热一趟。
         */
        private const val KEYBOARD_GRACE_MS = 8_000L

        private const val TRIM_MEMORY_UI_HIDDEN = 20
        private const val TRIM_MEMORY_RUNNING_LOW = 10

        @Volatile
        private var instance: AppGraph? = null

        fun init(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also {
                instance = it
                it.start()
            }
        }

        /** 服务与磁贴由系统创建，用这个拿图；必要时按需初始化。 */
        fun get(context: Context): AppGraph = instance ?: init(context)
    }
}
