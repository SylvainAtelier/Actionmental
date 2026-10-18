package com.actionmental.core.shortcut

import com.actionmental.core.action.Action
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyboardDevice
import kotlinx.serialization.Serializable

/**
 * 设备作用域。空列表 = 所有实体键盘，否则只对列出的键盘生效。
 * 保存的是 descriptor（稳定），不是 deviceId（每次连接都会变，PRD 17）。
 *
 * [names] 是保存时的名字快照：蓝牙键盘断开之后系统里查不到它，
 * 规则自己带着名字，列表里才认得出这是哪一把（导出到别的机器上也一样）。
 * 它只用于显示；比较两个范围用 [sameDevices]，不要用 ==。
 */
@Serializable
data class DeviceScope(
    val descriptors: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
) {
    val isAll: Boolean get() = descriptors.isEmpty()
    fun matches(descriptor: String): Boolean = isAll || descriptor in descriptors
    val label: String get() = if (isAll) "所有实体键盘" else descriptors.size.toString() + " 个指定键盘"

    fun sameDevices(other: DeviceScope): Boolean = descriptors.toSet() == other.descriptors.toSet()

    /** 两个范围能不能同时命中同一把键盘。 */
    fun overlaps(other: DeviceScope): Boolean =
        isAll || other.isAll || descriptors.any { it in other.descriptors }

    /** 快照里有名字用名字，没有就退回描述符的短码。 */
    fun nameOf(descriptor: String): String = names[descriptor]
        ?: if (descriptor.length > 8) descriptor.take(4) + "…" + descriptor.takeLast(4) else descriptor

    companion object {
        val ALL = DeviceScope()

        fun only(device: KeyboardDevice) =
            DeviceScope(listOf(device.descriptor), mapOf(device.descriptor to device.name))
    }
}

/** 应用作用域。INCLUDE = 仅这些应用生效；EXCLUDE = 这些应用之外生效。 */
@Serializable
data class AppScope(
    val mode: Mode = Mode.GLOBAL,
    val packages: List<String> = emptyList(),
) {
    enum class Mode { GLOBAL, INCLUDE, EXCLUDE }

    fun matches(foreground: String?): Boolean = when (mode) {
        Mode.GLOBAL -> true
        Mode.INCLUDE -> foreground != null && foreground in packages
        Mode.EXCLUDE -> foreground == null || foreground !in packages
    }

    val label: String
        get() = when (mode) {
            Mode.GLOBAL -> "全局"
            Mode.INCLUDE -> "仅 " + packages.size + " 个应用"
            Mode.EXCLUDE -> "排除 " + packages.size + " 个应用"
        }

    companion object {
        val GLOBAL = AppScope()
    }
}

/**
 * 一条快捷键绑定。
 *
 * 唯一性由 (combo + deviceScope + appScope) 决定 —— 也就是 [scopeKey]，
 * 仓库层用它做冲突检测与覆盖（PRD 35.11 / 35.12）。
 */
@Serializable
data class Shortcut(
    val id: String,
    val combo: KeyCombo,
    val action: Action,
    val label: String = "",
    val enabled: Boolean = true,
    val deviceScope: DeviceScope = DeviceScope.ALL,
    val appScope: AppScope = AppScope.GLOBAL,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val displayLabel: String get() = label.ifBlank { action.label }

    /** 同一作用域中不允许出现两条相同的组合键。 */
    val scopeKey: ScopeKey get() = ScopeKey(combo, deviceScope, appScope)

    val scopeLabel: String get() = appScope.label + " · " + deviceScope.label

    @Serializable
    data class ScopeKey(val combo: KeyCombo, val device: DeviceScope, val app: AppScope)
}
