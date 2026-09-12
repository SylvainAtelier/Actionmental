package com.actionmental.ui

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionmental.AppGraph
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.remap.KeyRemap
import com.actionmental.core.remap.RemapPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    data class Draft(
        val id: String,
        val from: KeyCombo? = null,
        val to: KeyCombo? = null,
        val enabled: Boolean = true,
        val isNew: Boolean = true,
    ) {
        private fun KeyCombo?.usable() = this != null && keyCode != KeyEvent.KEYCODE_UNKNOWN
        val complete: Boolean get() = from.usable() && to.usable() && from != to
    }

    val remaps: StateFlow<List<KeyRemap>> = repository.remaps
    val shortcuts = graph.shortcutRepository.shortcuts
    val status = graph.status
    val recording = graph.pipeline.recording

    /** 注入链路最近一次的失败原因。为 null 表示上一次注入是成功的。 */
    val injectError: StateFlow<String?> = graph.keyInjector.lastError

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    private val _recordingField = MutableStateFlow<Field?>(null)
    val recordingField: StateFlow<Field?> = _recordingField.asStateFlow()

    private val _selfTest = MutableStateFlow<String?>(null)

    /** 自检结论，点一次出一条，不常驻。 */
    val selfTest: StateFlow<String?> = _selfTest.asStateFlow()

    val presets = RemapPresets.common

    // --- 编辑器 ---------------------------------------------------------------

    fun create() {
        _draft.value = Draft(id = repository.newId())
    }

    fun edit(id: String) {
        val existing = repository.find(id) ?: return
        _draft.value = Draft(existing.id, existing.from, existing.to, existing.enabled, isNew = false)
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
        return repository.findBySource(from, d.id)
    }

    fun save() = viewModelScope.launch {
        val d = _draft.value ?: return@launch
        if (!d.complete) return@launch
        repository.upsert(
            KeyRemap(id = d.id, from = d.from!!, to = d.to!!, enabled = d.enabled)
        )
        close()
    }

    // --- 列表 -----------------------------------------------------------------

    fun setEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repository.setEnabled(id, enabled) }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }

    fun addPreset(preset: RemapPresets.Preset) = viewModelScope.launch {
        repository.upsert(KeyRemap(id = repository.newId(), from = preset.from, to = preset.to))
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
