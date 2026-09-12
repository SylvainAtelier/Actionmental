package com.actionmental.ui

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionmental.AppGraph
import com.actionmental.core.action.Action
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.AppScope
import com.actionmental.core.shortcut.DeviceScope
import com.actionmental.core.shortcut.SaveOutcome
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.core.shortcut.ShortcutConflict
import com.actionmental.platform.PackageBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 快捷键编辑 / 录制 / 冲突确认。
 *
 * 录制期间由 [com.actionmental.core.key.KeyPipeline] 吞掉事件，
 * 因此不存在「录制时误触发原快捷键」的情况（PRD 4.3 / 37）。
 * 手动改键走的是同一个 [Draft.combo]，录制与手选之后的状态完全一致。
 */
class ShortcutEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)
    private val repository = graph.shortcutRepository

    data class Draft(
        val id: String,
        val combo: KeyCombo? = null,
        /** 新建时为 null：动作必须由用户明确选一次，不给默认值（PRD 26 的同一条原则）。 */
        val action: Action? = null,
        val label: String = "",
        val enabled: Boolean = true,
        val deviceScope: DeviceScope = DeviceScope.ALL,
        val appScope: AppScope = AppScope.GLOBAL,
        val isNew: Boolean = true,
        val createdAt: Long = 0L,
    )

    private val _draft = MutableStateFlow(Draft(id = repository.newId()))
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    private val _conflict = MutableStateFlow<ShortcutConflict?>(null)
    val conflict: StateFlow<ShortcutConflict?> = _conflict.asStateFlow()

    val recording = graph.pipeline.recording

    /** 预热过的应用清单：选择器打开时已经在内存里，不必等 PackageManager。 */
    val apps: StateFlow<List<PackageBackend.InstalledApp>> = graph.appCatalog.apps
    val appsLoading: StateFlow<Boolean> = graph.appCatalog.loading

    private val _activities = MutableStateFlow<Map<String, List<PackageBackend.ActivityEntry>>>(emptyMap())

    /** 已加载的 Activity 清单，按包名索引。没有的键表示「还没查」。 */
    val activities: StateFlow<Map<String, List<PackageBackend.ActivityEntry>>> = _activities.asStateFlow()

    /**
     * 「编辑结束，请关闭本页」的一次性事件。
     *
     * 这里必须是 replay = 0 的 SharedFlow 而不是 StateFlow：本 ViewModel 挂在 Activity 上，
     * 关闭编辑页并不会销毁它。若用 StateFlow 保存「已保存的快捷键」，下次打开编辑页时
     * 首帧读到的仍是上一轮的非空值，页面会立刻自我关闭——表现为第一次点击列表项或
     * 「录制」按钮时界面一闪即退，第二次才进得去。
     */
    private val _closeRequests = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    /** 装了新应用之后重新扫描；顺带丢掉 Activity 缓存，避免读到旧入口。 */
    fun refreshApps() {
        _activities.value = emptyMap()
        graph.appCatalog.invalidate()
    }

    /** 展开某个应用时才查它的 Activity；结果进缓存，再次展开是瞬时的。 */
    fun loadActivities(packageName: String) {
        if (_activities.value.containsKey(packageName)) return
        viewModelScope.launch {
            val loaded = graph.appCatalog.activitiesOf(packageName)
            _activities.update { it + (packageName to loaded) }
        }
    }

    fun selectApp(app: PackageBackend.InstalledApp) {
        setAction(
            Action.LaunchApp(
                packageName = app.packageName,
                activity = null,
                appLabel = app.label,
            )
        )
    }

    fun selectActivity(app: PackageBackend.InstalledApp, entry: PackageBackend.ActivityEntry) {
        setAction(
            Action.LaunchApp(
                packageName = app.packageName,
                activity = if (entry.isDefaultEntry) null else entry.className,
                appLabel = app.label,
                activityLabel = if (entry.isDefaultEntry) "" else entry.label,
            )
        )
    }

    /** @param id 传 null 表示新建。 */
    fun load(id: String?, presetCombo: KeyCombo? = null) {
        val existing = id?.let { repository.find(it) }
        _draft.value = if (existing != null) {
            Draft(
                id = existing.id,
                combo = existing.combo,
                action = existing.action,
                label = existing.label,
                enabled = existing.enabled,
                deviceScope = existing.deviceScope,
                appScope = existing.appScope,
                isNew = false,
                createdAt = existing.createdAt,
            )
        } else {
            Draft(id = repository.newId(), combo = presetCombo)
        }
        _conflict.value = null
    }

    fun startRecording() = graph.pipeline.startRecording()

    fun cancelRecording() = graph.pipeline.cancelRecording()

    /** 录制得到的组合键落到草稿上。 */
    fun acceptRecorded() {
        val combo = graph.pipeline.recording.value?.combo ?: return
        _draft.value = _draft.value.copy(combo = combo)
        graph.pipeline.cancelRecording()
    }

    /**
     * 手动指定主键：修饰键沿用当前草稿，没录过就是空。
     * 若主键本身是修饰键，要把它自己那一位摘掉 —— 否则得到一个永远按不出来的组合。
     */
    fun setMainKey(keyCode: Int) {
        val inherited = _draft.value.combo?.modifiers ?: 0
        _draft.value = _draft.value.copy(
            combo = KeyCombo(
                keyCode = keyCode,
                modifiers = inherited and KeyCombo.selfModifierMask(keyCode).inv(),
            )
        )
    }

    /**
     * 勾掉 / 勾上一个修饰键。
     *
     * 还没有主键时允许先勾修饰键：草稿里留一个 keyCode 为 UNKNOWN 的中间态，
     * 保存按钮据此保持禁用，直到主键被录制或从目录里选出来。
     */
    fun toggleModifier(mask: Int) {
        val current = _draft.value.combo ?: KeyCombo(KeyEvent.KEYCODE_UNKNOWN, 0)
        _draft.value = _draft.value.copy(
            combo = current.copy(modifiers = current.modifiers xor mask)
        )
    }

    fun setAction(action: Action) {
        _draft.value = _draft.value.copy(action = action)
    }

    fun setLabel(label: String) {
        _draft.value = _draft.value.copy(label = label)
    }

    fun setEnabled(enabled: Boolean) {
        _draft.value = _draft.value.copy(enabled = enabled)
    }

    fun setAppScope(scope: AppScope) {
        _draft.value = _draft.value.copy(appScope = scope)
    }

    fun setDeviceScope(scope: DeviceScope) {
        _draft.value = _draft.value.copy(deviceScope = scope)
    }

    fun save(override: Boolean = false) = viewModelScope.launch {
        val d = _draft.value
        val combo = d.combo?.takeIf { it.keyCode != KeyEvent.KEYCODE_UNKNOWN } ?: return@launch
        val action = d.action ?: return@launch
        val shortcut = Shortcut(
            id = d.id,
            combo = combo,
            action = action,
            label = d.label,
            enabled = d.enabled,
            deviceScope = d.deviceScope,
            appScope = d.appScope,
            createdAt = d.createdAt,
        )
        when (val outcome = repository.save(shortcut, overrideExisting = override)) {
            is SaveOutcome.Conflict -> _conflict.value = outcome.conflict
            is SaveOutcome.Saved -> {
                _conflict.value = null
                _closeRequests.emit(Unit)
            }
        }
    }

    fun dismissConflict() {
        _conflict.value = null
    }

    /** 冲突弹层里的「编辑原绑定」：把草稿切换到已存在的那条。 */
    fun editConflicting() {
        val existing = _conflict.value?.existing ?: return
        _conflict.value = null
        load(existing.id)
    }

    fun delete() = viewModelScope.launch {
        repository.delete(_draft.value.id)
        _closeRequests.emit(Unit)
    }

    override fun onCleared() {
        graph.pipeline.cancelRecording()
        super.onCleared()
    }
}
