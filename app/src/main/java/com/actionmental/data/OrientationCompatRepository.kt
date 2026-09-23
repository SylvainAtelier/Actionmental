package com.actionmental.data

import com.actionmental.core.rotation.OrientationCompatTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer

/** 「压住自带方向」名单的持久化。包名唯一。 */
class OrientationCompatRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) {
    private companion object {
        const val KEY = "orientation_compat"
    }

    private val serializer = ListSerializer(OrientationCompatTarget.serializer())

    val targets: StateFlow<List<OrientationCompatTarget>> = appStore.stringFlow(KEY, "[]")
        .map { raw -> runCatching { appStore.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun add(packageName: String, appLabel: String) = mutate { list ->
        val existing = list.firstOrNull { it.packageName == packageName }
        list.filterNot { it.packageName == packageName } +
            (existing?.copy(enabled = true) ?: OrientationCompatTarget(packageName, appLabel, true, System.currentTimeMillis()))
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
    }

    suspend fun delete(packageName: String) = mutate { list -> list.filterNot { it.packageName == packageName } }

    private suspend fun mutate(transform: (List<OrientationCompatTarget>) -> List<OrientationCompatTarget>) {
        appStore.updateJson(KEY, serializer, emptyList()) { current -> transform(current).sortedBy { it.appLabel } }
    }
}
