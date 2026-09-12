package com.actionmental.data

import com.actionmental.core.key.KeyCombo
import com.actionmental.core.remap.KeyRemap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * 键位映射持久化。
 *
 * 源键在表中唯一：一颗键只能有一个去向，所以这里不需要快捷键那套冲突弹层 ——
 * 再次映射同一颗键就是改它，[upsert] 会替换掉原来那条并保留创建时间。
 */
class KeyRemapRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) {
    private companion object {
        const val KEY = "key_remaps"
    }

    private val serializer = ListSerializer(KeyRemap.serializer())

    val remaps: StateFlow<List<KeyRemap>> = appStore.stringFlow(KEY, "[]")
        .map { raw -> runCatching { appStore.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun find(id: String): KeyRemap? = remaps.value.firstOrNull { it.id == id }

    /** 这颗源键已经映射到别处了吗？UI 用它提示「会替换现有映射」。 */
    fun findBySource(from: KeyCombo, exceptId: String? = null): KeyRemap? =
        remaps.value.firstOrNull { it.from == from && it.id != exceptId }

    suspend fun upsert(remap: KeyRemap): KeyRemap {
        val now = System.currentTimeMillis()
        val existing = findBySource(remap.from, remap.id)
        val target = remap.copy(
            id = existing?.id ?: remap.id.ifBlank { newId() },
            createdAt = existing?.createdAt ?: find(remap.id)?.createdAt ?: now,
            updatedAt = now,
        )
        mutate { list -> list.filterNot { it.id == remap.id || it.id == target.id } + target }
        return target
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it }
    }

    suspend fun replaceAll(remaps: List<KeyRemap>) = mutate { remaps }

    /**
     * 整表替换成导入的内容，返回真正落盘的条数。
     *
     * 唯一性同样要在这里兜住：源键重复的后来者被丢弃，否则一颗键会有两个去向，
     * 匹配器的 associateBy 只会留下其中一个，用户看到的是「有一条映射不生效」。
     */
    suspend fun importAll(parsed: List<KeyRemap>): Int {
        val deduped = parsed.distinctBy { it.from }
        replaceAll(deduped)
        return deduped.size
    }

    /** 原子读改写，理由同 [ShortcutRepository.mutate]。 */
    private suspend fun mutate(transform: (List<KeyRemap>) -> List<KeyRemap>) {
        appStore.updateJson(KEY, serializer, emptyList()) { current ->
            transform(current).sortedBy { it.createdAt }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()
}
