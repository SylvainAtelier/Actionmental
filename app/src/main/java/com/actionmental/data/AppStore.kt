package com.actionmental.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "actionmental")

/**
 * 持久化底座。
 *
 * 这里刻意没有用 Room：全部数据是几十条记录、必须常驻内存供按键线程零延迟匹配，
 * 引入 SQLite + KSP 只会带来编译期开销与一次异步查询，换不来任何东西。
 * 唯一性约束由 [ShortcutRepository] 在内存索引上保证，且接口是抽象的——
 * 将来真的需要关系查询时，换一个实现即可，键盘链路不受影响。
 */
class AppStore(context: Context) {

    val store: DataStore<Preferences> = context.dataStore

    /**
     * 不缩进。
     *
     * 缩进只对着人看的文件有意义，而这几份 JSON 从来没有人打开过 ——
     * 换来的是每次保存都要多编码、多写、多 fsync 一批空格，
     * 每次读取都要多解析一遍。导出给用户看的那几份走的是各自的文本格式，不受影响。
     */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 某个键的值流。
     *
     * [DataStore.data] 是整份 Preferences 的快照流：任何一个键被写，五个仓库
     * 全都会收到一次。少了这里的去重，一次转屏（它要把全局意图存回盘上）就会
     * 连带触发快捷键表、映射表、规则表、自愈历史的整份 JSON 重新解码，
     * 以及按键匹配器的索引重建 —— 而其中没有一个字节真的变过。
     * 连按转屏时这一串是每秒一遍的空转，最后都算在温控账上。
     */
    fun stringFlow(key: String, default: String): Flow<String> {
        val prefKey = stringPreferencesKey(key)
        return store.data.map { it[prefKey] ?: default }.distinctUntilChanged()
    }

    suspend fun putString(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    /**
     * 原子读—改—写。所有持久化的修改都必须走这里。
     *
     * 曾经的写法是「读内存里的 StateFlow → 改 → 整份覆盖」，而那个 StateFlow 在进程刚起来的
     * 几十毫秒里还是默认值：这个窗口里发生的任何一次保存，都会把盘上的真实数据整份换成默认值 ——
     * 用户看到的是引导页重新出现、快捷键全部消失。DataStore 的 edit 本身是串行的，
     * 从它手里拿到的一定是盘上的当前值，这个窗口才彻底不存在。
     */
    suspend fun <T> updateJson(
        key: String,
        serializer: KSerializer<T>,
        default: T,
        transform: (T) -> T,
    ) {
        val prefKey = stringPreferencesKey(key)
        store.edit { prefs ->
            val current = prefs[prefKey]
                ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
                ?: default
            prefs[prefKey] = json.encodeToString(serializer, transform(current))
        }
    }
}
