package com.actionmental.ui

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionmental.AppGraph
import com.actionmental.core.key.KeyCatalog
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.remap.KeyRemap
import com.actionmental.core.remap.RemapPresets
import com.actionmental.core.shortcut.DeviceScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 键位映射模块的状态。
 *
 * 与快捷键编辑器共用同一套录制机制（[com.actionmental.core.key.KeyPipeline] 的录制模式），
 * 区别只在于这里有两个待填的槽，所以要额外记住「现在在录哪一个」。
 */
class RemapViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)
    private val repository = graph.keyRemapRepository

    enum class Field { FROM, TO }

    /** 一把键盘：连着的，或者某条规则提到过但此刻没连着的（蓝牙键盘熄屏断开是常态）。 */
    data class Keyboard(val descriptor: String, val name: String, val connected: Boolean)

    data class Draft(
        val id: String,
        val from: KeyCombo? = null,
        val to: KeyCombo? = null,
        val enabled: Boolean = true,
        val isNew: Boolean = true,
        val deviceScope: DeviceScope = DeviceScope.ALL,
    ) {
        private fun KeyCombo?.usable() = this != null && keyCode != KeyEvent.KEYCODE_UNKNOWN

        /** 两边相同只在指定键盘上有意义：那是「保持原键」，用来挡住全局规则。 */
        val complete: Boolean
            get() = from.usable() && to.usable() && (from != to || !deviceScope.isAll)
    }

    val remaps: StateFlow<List<KeyRemap>> = repository.remaps
    val shortcuts = graph.shortcutRepository.shortcuts
    val status = graph.status
    val recording = graph.pipeline.recording

    /** 最近一次按键来自哪把键盘：录制源键时，编辑器据此提示「这把刚按过」。 */
    val lastDevice = graph.pipeline.lastDevice

    /** 注入链路最近一次的失败原因。为 null 表示上一次注入是成功的。 */
    val injectError: StateFlow<String?> = graph.keyInjector.lastError

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    /** 列表按哪把键盘看；null 是全部。 */
    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()

    fun setFilter(descriptor: String?) {
        _filter.value = descriptor
    }

    val keyboards: StateFlow<List<Keyboard>> = combine(status, remaps, _draft) { s, rules, d ->
        val connected = s.keyboards
            .filter { it.descriptor.isNotBlank() }
            .distinctBy { it.descriptor }
            .map { Keyboard(it.descriptor, it.name, connected = true) }
        val known = connected.map { it.descriptor }.toSet()
        val mentioned = (rules.map { it.deviceScope } + listOfNotNull(d?.deviceScope))
            .flatMap { scope -> scope.descriptors.map { it to scope.nameOf(it) } }
            .filter { (descriptor, _) -> descriptor !in known }
            .distinctBy { it.first }
            .map { (descriptor, name) -> Keyboard(descriptor, name, connected = false) }
        connected + mentioned
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recordingField = MutableStateFlow<Field?>(null)
    val recordingField: StateFlow<Field?> = _recordingField.asStateFlow()

    private val _selfTest = MutableStateFlow<String?>(null)

    /** 自检结论，点一次出一条，不常驻。 */
    val selfTest: StateFlow<String?> = _selfTest.asStateFlow()

    val presets = RemapPresets.common

    // --- 编辑器 ---------------------------------------------------------------

    fun create() {
        _draft.value = Draft(id = repository.newId(), deviceScope = scopeOf(_filter.value))
    }

    fun edit(id: String) {
        val existing = repository.find(id) ?: return
        _draft.value = Draft(
            existing.id, existing.from, existing.to, existing.enabled,
            isNew = false, deviceScope = existing.deviceScope,
        )
    }

    /** 编辑器里选「生效键盘」。null = 所有键盘。 */
    fun setDevice(descriptor: String?) {
        val current = _draft.value ?: return
        _draft.value = current.copy(deviceScope = scopeOf(descriptor))
    }

    private fun scopeOf(descriptor: String?): DeviceScope {
        val keyboard = descriptor?.let { d -> keyboards.value.firstOrNull { it.descriptor == d } }
            ?: return DeviceScope.ALL
        return DeviceScope(listOf(keyboard.descriptor), mapOf(keyboard.descriptor to keyboard.name))
    }

    fun close() {
        cancelRecording()
        _draft.value = null
    }

    fun startRecording(field: Field) {
        _recordingField.value = field
        graph.pipeline.startRecording()
    }

    fun cancelRecording() {
        _recordingField.value = null
        graph.pipeline.cancelRecording()
    }

    fun acceptRecorded() {
        val combo = graph.pipeline.recording.value?.combo ?: return
        val field = _recordingField.value ?: return
        setCombo(field, combo)
        cancelRecording()
    }

    /** 从目录里选主键：修饰键沿用当前草稿，并把主键自己那一位摘掉。 */
    fun setMainKey(field: Field, keyCode: Int) {
        val inherited = comboOf(field)?.modifiers ?: 0
        setCombo(
            field,
            KeyCombo(keyCode, inherited and KeyCombo.selfModifierMask(keyCode).inv()),
        )
    }

    /** 从符号目录里选：「?」直接就是 Shift + /，别的修饰键沿用草稿。 */
    fun setSymbol(field: Field, symbol: KeyCatalog.SymbolKey) {
        setCombo(field, KeyCatalog.applySymbol(comboOf(field), symbol))
    }

    fun toggleModifier(field: Field, mask: Int) {
        val current = comboOf(field) ?: KeyCombo(KeyEvent.KEYCODE_UNKNOWN, 0)
        setCombo(field, current.copy(modifiers = current.modifiers xor mask))
    }

    fun comboOf(field: Field): KeyCombo? =
        _draft.value?.let { if (field == Field.FROM) it.from else it.to }

    /** 这颗源键是不是已经映射到别处了？编辑器据此提示「保存会替换它」。 */
    fun replacing(): KeyRemap? {
        val d = _draft.value ?: return null
        val from = d.from ?: return null
        return repository.findBySource(from, d.deviceScope, d.id)
    }

    fun save() = viewModelScope.launch {
        val d = _draft.value ?: return@launch
        if (!d.complete) return@launch
        repository.upsert(
            KeyRemap(id = d.id, from = d.from!!, to = d.to!!, enabled = d.enabled, deviceScope = d.deviceScope)
        )
        close()
    }

    // --- 列表 -----------------------------------------------------------------

    fun setEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repository.setEnabled(id, enabled) }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }

    fun addPreset(preset: RemapPresets.Preset) = viewModelScope.launch {
        repository.upsert(
            KeyRemap(id = repository.newId(), from = preset.from, to = preset.to, deviceScope = scopeOf(_filter.value))
        )
    }

    /**
     * 注入自检。
     *
     * 发一颗 KEYCODE_UNKNOWN —— 它会走完整条注入链路，但没有任何应用会对它作出反应，
     * 所以能在不产生副作用的前提下回答「Shizuku 到系统这一段通不通」。
     */
    fun runSelfTest() = viewModelScope.launch {
        _selfTest.value = "正在自检…"
        _selfTest.value = graph.keyInjector.inject(KeyCombo(KeyEvent.KEYCODE_UNKNOWN)).fold(
            onSuccess = { "注入链路正常" },
            onFailure = { "注入失败 · " + (it.message ?: "未知原因") },
        )
    }

    fun clearSelfTest() {
        _selfTest.value = null
    }

    private fun setCombo(field: Field, combo: KeyCombo) {
        val current = _draft.value ?: return
        _draft.value = if (field == Field.FROM) current.copy(from = combo) else current.copy(to = combo)
    }

    override fun onCleared() {
        graph.pipeline.cancelRecording()
        super.onCleared()
    }
}
