package com.actionmental.data

import com.actionmental.core.key.KeyCombo
import com.actionmental.core.shortcut.SaveOutcome
import com.actionmental.core.shortcut.Shortcut
import com.actionmental.core.shortcut.ShortcutConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * 快捷键持久化 + 冲突检测（PRD 21 / 35.11 / 35.12）。
 *
 * 冲突判定完全在这一层：任何写入路径（编辑页、引导预设、导入）都必须经过 [save]，
 * 因此不存在「某个入口绕过了冲突检查」的可能。
 */
class ShortcutRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) {
    private companion object {
        const val KEY = "shortcuts"
    }

    private val serializer = ListSerializer(Shortcut.serializer())

    val shortcuts: StateFlow<List<Shortcut>> = appStore.stringFlow(KEY, "[]")
        .map { raw -> runCatching { appStore.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun all(): List<Shortcut> = shortcuts.value

    fun find(id: String): Shortcut? = shortcuts.value.firstOrNull { it.id == id }

    /** 返回该组合键在同一作用域下已有的绑定。 */
    fun conflictOf(candidate: Shortcut): Shortcut? =
        shortcuts.value.firstOrNull { it.id != candidate.id && it.scopeKey == candidate.scopeKey }

    /** 查询某组合键当前绑定了什么，用于按键检测页显示。 */
    fun bindingFor(combo: KeyCombo): Shortcut? =
        shortcuts.value.firstOrNull { it.combo == combo }

    /**
     * @param overrideExisting true 表示用户已在冲突弹层里确认覆盖。
     *        覆盖会复用原记录的 id 与 createdAt（保留元数据），不产生重复记录（PRD 4.6）。
     */
    suspend fun save(shortcut: Shortcut, overrideExisting: Boolean = false): SaveOutcome {
        val now = System.currentTimeMillis()
        val existing = conflictOf(shortcut)

        if (existing != null && !overrideExisting) {
            return SaveOutcome.Conflict(ShortcutConflict(existing, shortcut))
        }

        val target = if (existing != null) {
            shortcut.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = now)
        } else {
            val previous = find(shortcut.id)
            shortcut.copy(
                createdAt = previous?.createdAt
                    ?: if (shortcut.createdAt == 0L) now else shortcut.createdAt,
                updatedAt = now,
            )
        }

        // 三个 id 都要清掉：草稿原本的记录、被覆盖的记录、以及目标本身，
        // 否则「编辑已有快捷键 → 改成与另一条相同的组合 → 覆盖」会留下两条记录。
        mutate { list ->
            list.filterNot { it.id == shortcut.id || it.id == target.id || it.id == existing?.id } + target
        }
        return SaveOutcome.Saved(target)
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it }
    }

    suspend fun duplicate(id: String) {
        val source = find(id) ?: return
        mutate { list ->
            list + source.copy(
                id = newId(),
                enabled = false,                     // 复制品默认禁用，避免立刻与原绑定冲突
                label = source.displayLabel + " 副本",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun replaceAll(shortcuts: List<Shortcut>) = mutate { shortcuts }

    /**
     * 整表替换成导入的内容，返回真正落盘的条数。
     *
     * 导入同样要过唯一性：同作用域重复的后来者被丢弃。
     */
    suspend fun importAll(parsed: List<Shortcut>): Int {
        val deduped = parsed.distinctBy { it.scopeKey }
        replaceAll(deduped)
        return deduped.size
    }

    /**
     * 原子读改写。基准是盘上的当前值，不是内存里的 [shortcuts] ——
     * 后者在进程刚起来的那一小段时间里是空列表，那时保存一条就等于清空整张表。
     */
    private suspend fun mutate(transform: (List<Shortcut>) -> List<Shortcut>) {
        appStore.updateJson(KEY, serializer, emptyList()) { current ->
            transform(current).sortedBy { it.createdAt }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()
}
