package com.actionmental.data

import com.actionmental.core.hardening.HardeningJournal
import com.actionmental.core.hardening.HardeningRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 加固 / 自愈历史。落盘，不是内存缓冲。
 *
 * 理由只有一条：自愈发生在用户没看屏幕的时候，往往还紧跟着一次进程重启 ——
 * 记在内存里等于没记。[ShellLog] 那套环形缓冲解决的是另一个问题（刚才那条命令回了什么），
 * 两者互补而不重复。
 */
class HardeningRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) : HardeningJournal {

    private companion object {
        const val KEY = "hardening_journal"
        const val CAPACITY = 100
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    }

    @Serializable
    private data class Journal(
        val records: List<HardeningRecord> = emptyList(),
        /** 用户看过的最大 id。未读徽标靠它算，不需要给每条记录加字段。 */
        val lastSeenId: Long = 0L,
    )

    /** 写入是读-改-写，必须串行；自愈可能和用户点按钮撞在一起。 */
    private val writeLock = Mutex()

    private val journal: StateFlow<Journal> = appStore.stringFlow(KEY, "{}")
        .map { raw ->
            runCatching { appStore.json.decodeFromString(Journal.serializer(), raw) }
                .getOrDefault(Journal())
        }
        .stateIn(scope, SharingStarted.Eagerly, Journal())

    /** 新的在前，界面直接渲染，不用再排一次。 */
    val records: StateFlow<List<HardeningRecord>> = journal
        .map { it.records.sortedByDescending { record -> record.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = journal
        .map { j -> j.records.count { it.id > j.lastSeenId } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val latest: StateFlow<HardeningRecord?> = journal
        .map { j -> j.records.maxByOrNull { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun append(
        kind: HardeningRecord.Kind,
        step: String,
        ok: Boolean,
        detail: String,
    ) = writeLock.withLock {
        val timestamp = System.currentTimeMillis()
        mutate { current ->
            val record = HardeningRecord(
                id = (current.records.maxOfOrNull { it.id } ?: 0L) + 1L,
                timestampMs = timestamp,
                kind = kind,
                step = step,
                ok = ok,
                detail = detail.trim(),
            )
            current.copy(records = (current.records + record).takeLast(CAPACITY))
        }
    }

    suspend fun markAllSeen() = writeLock.withLock {
        mutate { current ->
            val newest = current.records.maxOfOrNull { it.id } ?: 0L
            if (newest == current.lastSeenId) current else current.copy(lastSeenId = newest)
        }
    }

    suspend fun clear() = writeLock.withLock { mutate { Journal() } }

    /**
     * 原子读改写。
     *
     * 历史尤其不能基于内存里的 [journal] 覆盖：自愈往往就发生在进程刚被拉起的那一刻，
     * 那时它还是空的 —— 用旧写法追加一条，等于把之前所有的自愈记录一笔勾销，
     * 而这些记录正是事后唯一能查的东西。
     */
    private suspend fun mutate(transform: (Journal) -> Journal) {
        appStore.updateJson(KEY, Journal.serializer(), Journal(), transform)
    }

    /** 导出成纯文本，和 shell 日志一样可以直接复制去提 issue。 */
    fun export(): String = buildString {
        val list = records.value
        appendLine("# Actionmental 加固与自愈历史 · " + list.size + " 条")
        list.forEach { r ->
            appendLine()
            appendLine(
                "[" + stamp.format(Date(r.timestampMs)) + "] " + r.kind.label + " · " +
                    r.step + " · " + (if (r.ok) "成功" else "失败")
            )
            if (r.detail.isNotBlank()) appendLine(r.detail)
        }
    }
}
