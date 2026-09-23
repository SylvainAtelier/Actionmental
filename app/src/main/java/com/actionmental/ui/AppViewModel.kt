package com.actionmental.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionmental.AppGraph
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionResult
import com.actionmental.core.diag.DiagnosisReport
import com.actionmental.core.hardening.HardeningRecord
import com.actionmental.core.hardening.HardeningState
import com.actionmental.core.hardening.HealOutcome
import com.actionmental.core.hardening.HealTrigger
import com.actionmental.core.rotation.AppRotationRule
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.data.UserSettings
import com.actionmental.platform.PackageBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI 与领域层之间的薄适配层。
 *
 * 这里刻意不放任何业务规则：冲突判定在仓库、旋转在控制器、动作在执行器。
 * ViewModel 只做「转发 + 暴露流 + 一次性提示」。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)

    val status = graph.status
    val settings = graph.settingsRepository.settings

    /**
     * 真的停下来了没有，以及是谁让它停的。
     *
     * 不能只看 settings.paused：自动暂停（没有键盘）不写设置，而且要过一段宽限期
     * 才落地 —— 界面上说「已暂停」的那一刻，必须是它真的已经停了。
     */
    val paused: StateFlow<Boolean> = graph.paused
    val pauseReason: StateFlow<AppGraph.PauseReason> = graph.pauseReason

    /** 盘上的设置读出来了没有。界面据此决定「现在能不能判断该不该显示引导」。 */
    val settingsLoaded = graph.settingsRepository.loaded
    val shortcuts: StateFlow<List<Shortcut>> = graph.shortcutRepository.shortcuts
    val rules: StateFlow<List<AppRotationRule>> = graph.rotationRuleRepository.rules
    val rotationState = graph.rotation.state
    val screenAwake = graph.screenAwake.state

    /** 预热过的应用清单，选择器直接读它，不再现查 PackageManager。 */
    val apps: StateFlow<List<PackageBackend.InstalledApp>> = graph.appCatalog.apps
    /**
     * 逐键事件流的开关。
     *
     * 默认关着：每一颗键都会经过管线，常开会让 onKeyEvent 变慢，
     * 慢到系统判定服务无响应就会把它解绑。只有真在看监视页时才值得记。
     */
    fun setKeyTracing(on: Boolean) = graph.pipeline.setTracing(on)

    val keyTracing = graph.pipeline.tracing

    val keySnapshot = graph.pipeline.snapshot
    val keyTraces = graph.pipeline.traces
    val lastDevice = graph.pipeline.lastDevice

    /**
     * 应用自己写系统设置的两项授权。
     *
     * 授予都发生在别处（系统设置页、电脑上的 adb），没有回调可听，
     * 所以跟着 [refreshEverything] 在界面回到前台时重读一次。
     */
    data class SettingsAccessState(val system: Boolean = false, val secure: Boolean = false)

    private val _settingsAccess = MutableStateFlow(SettingsAccessState())
    val settingsAccess: StateFlow<SettingsAccessState> = _settingsAccess.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        // 自愈发生在图里、可能在用户没看屏幕时；界面一回来就把它接过来当作提示
        viewModelScope.launch {
            graph.hardeningNotice.collect { notice ->
                if (notice != null) {
                    _toast.value = notice
                    graph.hardeningNotice.value = null
                }
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    /**
     * 暂停期间挡住那些会改动系统状态的入口。
     *
     * 不是悄悄不做 —— 写下去也没用（旋转控制器此刻一律按「系统默认」算），
     * 而界面上会显示成功。宁可当场说清楚为什么不动（PRD 25：不静默失败）。
     */
    private fun blockedWhilePaused(): Boolean {
        if (!paused.value) return false
        _toast.value = "已暂停 · 先在设置里恢复运行"
        return true
    }

    // --- 状态刷新 -------------------------------------------------------------

    /**
     * 轻量状态刷新：读设置、查设备、看本地标志，都不出进程边界之外太远。
     *
     * 刻意不含旋转 —— 那一项要跑两条 shell，见 [refreshRotationState]。
     */
    /**
     * 界面要用应用清单了，确保它在。
     *
     * 清单会在界面退到后台时被放掉（见 AppGraph.onTrimMemory），所以每次回到前台
     * 都要问一次。[com.actionmental.platform.AppCatalog.warmUp] 自带「已经有了就不查」。
     */
    fun ensureAppCatalog() = graph.appCatalog.warmUp()

    fun refreshEverything() {
        refreshSettingsAccess()
        graph.shizuku.refresh()
        graph.refreshAccessibility()
        graph.refreshKeyboards()
        graph.screenAwake.refresh()
    }

    /**
     * 旋转状态。单独拿出来是因为它贵：读一次要 fork 两个 shell 进程。
     *
     * 原来它跟着 1.5 秒的轮询一起跑，等于每分钟凭空 fork 八十个进程 ——
     * 持续的 CPU 占用和发热，而 ROM 的热控策略正是据此杀掉后台进程的。
     */
    fun refreshRotationState() = graph.rotation.refreshAsync()

    /** 用户按下「重新检测」时的全量刷新：这一下是他要的，贵一点无妨。 */
    fun refreshAll() {
        refreshEverything()
        refreshRotationState()
    }

    fun accessibilityEnabled(): Boolean = graph.accessibilityEnabledInSettings()

    // --- 权限 ----------------------------------------------------------------

    fun requestShizukuPermission() = graph.shizuku.requestPermission()

    fun reconnectShizuku() = graph.shizuku.reconnect()

    fun refreshSettingsAccess() {
        val access = graph.settingsAccess
        val next = SettingsAccessState(system = access.systemWritable(), secure = access.secureWritable())
        if (next != _settingsAccess.value) {
            _settingsAccess.value = next
            // 写入级别可能因此变了（多了系统设置这一级），旋转按当前意图重新落一次
            graph.rotation.reapplyAsync()
        }
    }

    /** 打开系统的「修改系统设置」授权页。回来时 [refreshEverything] 会重读结果。 */
    fun openWriteSettings() {
        runCatching { getApplication<android.app.Application>().startActivity(graph.settingsAccess.manageWriteSettingsIntent()) }
            .onFailure { _toast.value = "打不开系统的「修改系统设置」页 · " + it.message.orEmpty() }
    }

    /** 授予 WRITE_SECURE_SETTINGS 的那一行 adb 命令。 */
    fun secureSettingsCommand(): String = graph.settingsAccess.grantSecureCommand()

    // --- 快捷键 ---------------------------------------------------------------

    fun setShortcutEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { graph.shortcutRepository.setEnabled(id, enabled) }

    fun deleteShortcut(id: String) =
        viewModelScope.launch { graph.shortcutRepository.delete(id) }

    fun duplicateShortcut(id: String) =
        viewModelScope.launch { graph.shortcutRepository.duplicate(id) }

    /** 导出的是整份配置：快捷键 + 键位映射，一次剪贴板搞定换机。 */
    fun exportConfig(): String = graph.configTransfer.export()

    fun importConfig(raw: String) = viewModelScope.launch { applyImport(raw, "剪贴板") }

    /** 文件选择器里预填的文件名。 */
    fun suggestedBackupName(): String = graph.configBackupFile.suggestedName()

    /**
     * 备份到用户选定的文件。
     *
     * 和剪贴板导出是同一份 JSON，区别只在落到哪儿 —— 而那个区别恰恰是备份的全部意义：
     * 剪贴板会被下一次复制冲掉，也过不了重启，唯一会丢配置的时刻（卸载重装）
     * 它一次都帮不上。
     */
    fun backupConfigToFile(uri: android.net.Uri) = viewModelScope.launch {
        val payload = graph.configTransfer.export()
        val shortcutCount = graph.shortcutRepository.shortcuts.value.size
        val remapCount = graph.keyRemapRepository.remaps.value.size
        graph.configBackupFile.write(uri, payload).fold(
            onSuccess = { bytes ->
                val summary = shortcutCount.toString() + " 条快捷键 · " + remapCount + " 条键位映射"
                _toast.value = "已备份到文件 · " + summary
                // 备份留一条日志。事后「我到底备过没有、备的是哪一版」只有这里答得出来
                graph.eventLog.info("config", "已备份配置到文件", summary + " · " + bytes + " 字节")
            },
            onFailure = {
                _toast.value = "备份失败 · " + it.message.orEmpty()
                graph.eventLog.error("config", "备份配置到文件失败", it)
            },
        )
    }

    /** 从备份文件恢复。走的是和粘贴导入完全同一条写入路径，唯一性约束照旧生效。 */
    fun restoreConfigFromFile(uri: android.net.Uri) = viewModelScope.launch {
        graph.configBackupFile.read(uri).fold(
            onSuccess = { applyImport(it, "文件") },
            onFailure = {
                _toast.value = "读取备份失败 · " + it.message.orEmpty()
                graph.eventLog.error("config", "读取备份文件失败", it)
            },
        )
    }

    /**
     * 导入的唯一落点。
     *
     * 剪贴板和文件两条入口共用它：两边的合并规则、唯一性检查、提示语必须一模一样，
     * 否则「从文件恢复」会悄悄变成一个行为略有不同的第二套导入。
     */
    private suspend fun applyImport(raw: String, source: String) {
        graph.configTransfer.import(raw).fold(
            onSuccess = {
                val summary = "已导入 " + it.shortcuts + " 条快捷键 · " + it.remaps + " 条键位映射"
                _toast.value = summary
                graph.eventLog.info("config", summary, "来源 · " + source)
            },
            onFailure = {
                _toast.value = "导入失败 · " + it.message.orEmpty()
                graph.eventLog.error("config", "导入失败 · 来源 " + source, it)
            },
        )
    }

    fun clearShortcuts() = viewModelScope.launch { graph.shortcutRepository.replaceAll(emptyList()) }

    /** 引导里的推荐预设批量写入，同样走仓库的唯一性检查。 */
    fun addPresets(presets: List<Pair<com.actionmental.core.key.KeyCombo, Action>>) = viewModelScope.launch {
        presets.forEach { (combo, action) ->
            graph.shortcutRepository.save(
                Shortcut(
                    id = graph.shortcutRepository.newId(),
                    combo = combo,
                    action = action,
                ),
                overrideExisting = true,
            )
        }
    }

    // --- 旋转 -----------------------------------------------------------------

    fun setRotation(mode: RotationMode) = viewModelScope.launch {
        if (blockedWhilePaused()) return@launch
        report(graph.rotation.setGlobal(mode))
    }

    fun toggleLandscape() = viewModelScope.launch {
        if (blockedWhilePaused()) return@launch
        report(graph.rotation.toggleLandscape())
    }

    fun refreshRotation() = viewModelScope.launch { graph.rotation.refresh() }

    // --- 屏幕常亮 -------------------------------------------------------------

    // 常亮不受全局暂停影响，所以这两个入口不走 blockedWhilePaused
    fun setScreenAwake(on: Boolean) = viewModelScope.launch {
        report(graph.screenAwake.set(on))
    }

    fun toggleScreenAwake() = viewModelScope.launch {
        report(graph.screenAwake.toggle())
    }

    // --- 诊断 -----------------------------------------------------------------

    /** 每一条特权 shell 的原始记录，诊断页直接展示。 */
    val shellLog = graph.shellLog.entries

    // --- 运行日志 -------------------------------------------------------------

    val logEntries = graph.eventLog.entries

    /** 写盘失败的原因。日志是空的到底是没事，还是没记下来，得分得清。 */
    val logWriteError = graph.eventLog.writeError

    /**
     * 导出给开发者的那一份：盘上的全部，加上本次进程内存里的。
     *
     * 只给内存里那一段没有意义 —— 服务掉线、进程崩溃的现场都在上一次进程里。
     */
    fun exportFullLog(): String = buildString {
        val onDisk = graph.eventLog.readPersisted()
        if (onDisk.isNotBlank()) {
            appendLine("# 盘上记录（含更早的进程）")
            append(onDisk)
            appendLine()
        }
        append(graph.eventLog.export())
    }

    fun readPersistedLog(): String = graph.eventLog.readPersisted()

    fun clearLog() = graph.eventLog.clear()

    private val _diagnosis = MutableStateFlow<DiagnosisReport?>(null)
    val diagnosis: StateFlow<DiagnosisReport?> = _diagnosis.asStateFlow()

    private val _diagnosing = MutableStateFlow(false)
    val diagnosing: StateFlow<Boolean> = _diagnosing.asStateFlow()

    /** 只读检查：全局开关、每块屏幕、前台应用的方向与尺寸声明。 */
    fun runDiagnosis() = viewModelScope.launch {
        _diagnosing.value = true
        // 直接问源头，不经过 status —— 后者只在有人订阅时才组装，值可能是旧的
        _diagnosis.value = graph.diagnostics.run(graph.accessibility.foregroundPackage.value)
        _diagnosing.value = false
    }

    /** 折叠屏 / 外接屏：把当前生效模式推到每一块 display 上。 */
    fun spreadToAllDisplays() = viewModelScope.launch {
        if (blockedWhilePaused()) return@launch
        report(graph.rotation.spreadToAllDisplays())
    }

    /** 第二级手段：对某个包施加应用级兼容覆盖。 */
    fun applyAppOverrides(packageName: String) = viewModelScope.launch {
        graph.appOverrides.apply(packageName).fold(
            onSuccess = { _toast.value = it.summary + "，结束该应用后重新打开生效" },
            onFailure = { _toast.value = "覆盖失败 · " + it.message.orEmpty() },
        )
    }

    fun resetAppOverrides(packageName: String) =
        viewModelScope.launch { report(graph.appOverrides.reset(packageName)) }

    fun forceStopApp(packageName: String) =
        viewModelScope.launch { report(graph.appOverrides.forceStop(packageName)) }

    fun clearShellLog() = graph.shellLog.clear()

    // --- 后台加固与自愈 -------------------------------------------------------

    val hardeningState: StateFlow<HardeningState> = graph.hardening.state
    val hardeningBusy: StateFlow<Boolean> = graph.hardening.busy
    val hardeningRecords: StateFlow<List<HardeningRecord>> = graph.hardeningRepository.records
    val hardeningUnread: StateFlow<Int> = graph.hardeningRepository.unreadCount
    val hardeningLatest: StateFlow<HardeningRecord?> = graph.hardeningRepository.latest

    /** 只读复查，不改动任何系统状态。进入权限页时调一次。 */
    fun verifyHardening() = viewModelScope.launch { graph.hardening.verify() }

    fun runHardening() = viewModelScope.launch { report(graph.hardening.harden()) }

    /** 手动自愈：不受冷却与退避限制，并清掉之前的失败计数。 */
    fun healAccessibilityNow() = viewModelScope.launch {
        graph.hardening.resetAutoHealBackoff()
        val outcome = graph.hardening.healAccessibility(HealTrigger.MANUAL)
        _toast.value = outcome.message
        if (outcome is HealOutcome.Attempted && outcome.result.succeeded) graph.refreshAccessibility()
    }

    /**
     * 强制重新绑定监听服务。
     *
     * 「已授权但未连接」时才有意义：设置里一切正常、服务却没起来，
     * 只能靠摘掉再写回逼系统重绑。需要 Shizuku，不可用时会如实报出原因。
     */
    fun rebindAccessibility() = viewModelScope.launch { report(graph.rebindAccessibility()) }

    /** 通知栏是否真的发得出去：权限、渠道开关缺一不可。 */
    fun canNotify(): Boolean = graph.notifier.canNotify()

    fun notificationPermissionGranted(): Boolean = graph.notifier.granted()

    fun markHardeningSeen() = viewModelScope.launch { graph.hardeningRepository.markAllSeen() }

    fun clearHardeningHistory() = viewModelScope.launch { graph.hardeningRepository.clear() }

    fun exportHardeningHistory(): String = graph.hardeningRepository.export()

    fun exportShellLog(): String = graph.shellLog.export()


    // --- 应用规则 -------------------------------------------------------------

    fun upsertRule(packageName: String, label: String, mode: RotationMode) = viewModelScope.launch {
        graph.rotationRuleRepository.upsert(
            AppRotationRule(
                id = UUID.randomUUID().toString(),
                packageName = packageName,
                appLabel = label,
                rotationMode = mode,
            )
        )
    }

    fun setRuleEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { graph.rotationRuleRepository.setEnabled(id, enabled) }

    fun deleteRule(id: String) = viewModelScope.launch { graph.rotationRuleRepository.delete(id) }

    // --- 设置 -----------------------------------------------------------------

    fun updateSettings(transform: (UserSettings) -> UserSettings) =
        viewModelScope.launch { graph.settingsRepository.update(transform) }

    // --- 其他 -----------------------------------------------------------------

    fun runAction(action: Action) = viewModelScope.launch {
        if (blockedWhilePaused()) return@launch
        report(graph.executor.execute(action))
    }

    /** 装了新应用之后重新扫描。 */
    fun refreshApps() = graph.appCatalog.invalidate()

    fun appLabel(packageName: String): String = graph.packages.labelOf(packageName)

    fun clearTraces() = graph.pipeline.clearTraces()

    private fun report(result: ActionResult) {
        _toast.value = result.message
    }
}
