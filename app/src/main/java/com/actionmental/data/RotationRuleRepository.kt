package com.actionmental.data

import com.actionmental.core.rotation.AppRotationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** 应用级旋转规则持久化。包名在规则集中唯一。 */
class RotationRuleRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) {
    private companion object {
        const val KEY = "rotation_rules"
    }

    private val serializer = ListSerializer(AppRotationRule.serializer())

    val rules: StateFlow<List<AppRotationRule>> = appStore.stringFlow(KEY, "[]")
        .map { raw -> runCatching { appStore.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun ruleFor(packageName: String?): AppRotationRule? =
        packageName?.let { pkg -> rules.value.firstOrNull { it.packageName == pkg && it.enabled } }

    suspend fun upsert(rule: AppRotationRule) {
        val now = System.currentTimeMillis()
        val existing = rules.value.firstOrNull { it.packageName == rule.packageName }
        val target = rule.copy(
            id = existing?.id ?: rule.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        mutate { list -> list.filterNot { it.packageName == target.packageName } + target }
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it }
    }

    private suspend fun mutate(transform: (List<AppRotationRule>) -> List<AppRotationRule>) {
        appStore.updateJson(KEY, serializer, emptyList()) { current ->
            transform(current).sortedBy { it.appLabel }
        }
    }
}
