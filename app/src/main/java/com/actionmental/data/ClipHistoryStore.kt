package com.actionmental.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.core.clip.SearchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 剪贴板历史的本地库。
 *
 * 用系统自带的 SQLite 而不是 Room：只有一张表、五六条语句，为它引入注解处理器不划算。
 * 库文件在 databases/ 下，而备份规则只收 datastore/ —— 剪贴板内容不会跟着系统备份离开这台设备。
 *
 * 所有方法都切到 IO 线程；[version] 每次写入加一，界面拿它决定要不要重查。
 *
 * 搜索的性能来自两处，都是在设备上量出来的（一万条时最坏的全表扫描从 133ms 降到 17ms）：
 * - 搜索键入库时就算好（见 [SearchKey]），查询只做字节级的 `instr`，不用要逐字折叠大小写的 LIKE；
 * - 列的顺序：短的搜索键排在长正文**前面**。SQLite 读一列要先走过它前面的列，
 *   正文长了会溢出到别的页 —— 键放在后面，每扫一行都得把正文的溢出页走一遍。
 *
 * @param keyOf 原文 → 搜索键。拼音来自平台层，这里只管存。
 */
class ClipHistoryStore(context: Context, private val keyOf: (String) -> String) {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, FILE, null, SCHEMA) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) = createTable(db, "clips")

        /** v1 的正文在搜索键前面、也没有搜索键：按新列序重建，逐行补算键，数据原样保留。 */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion >= 2) return
            db.execSQL("DROP INDEX IF EXISTS clips_order")
            db.execSQL("ALTER TABLE clips RENAME TO clips_v1")
            createTable(db, "clips")
            db.rawQuery(
                "SELECT id, hash, text, source, created_at, last_used_at, use_count, pinned_at FROM clips_v1",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    db.insertOrThrow(
                        "clips",
                        null,
                        ContentValues().apply {
                            put("id", c.getLong(0))
                            put("hash", c.getString(1))
                            put("text", c.getString(2))
                            put("source", if (c.isNull(3)) null else c.getString(3))
                            put("created_at", c.getLong(4))
                            put("last_used_at", c.getLong(5))
                            put("use_count", c.getInt(6))
                            put("pinned_at", c.getLong(7))
                            put("search_key", keyOf(c.getString(2)))
                        },
                    )
                }
            }
            db.execSQL("DROP TABLE clips_v1")
        }
    }

    private fun createTable(db: SQLiteDatabase, name: String) {
        // 列序有讲究：定长的小列和搜索键在前，正文最后（见类注释）
        db.execSQL(
            "CREATE TABLE " + name + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "pinned_at INTEGER NOT NULL DEFAULT 0, " +
                "last_used_at INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "use_count INTEGER NOT NULL DEFAULT 1, " +
                "hash TEXT NOT NULL UNIQUE, " +
                "source TEXT, " +
                "search_key TEXT NOT NULL, " +
                "text TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX clips_order ON " + name + " (pinned_at DESC, last_used_at DESC)")
    }

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun changed() {
        _version.value = _version.value + 1
    }

    /**
     * 记一条。已经有同样内容的，只把它的「最近使用」顶上来、次数加一。
     * 顺手把超出保留数的旧条目删掉，不另起定时任务。
     */
    suspend fun record(text: String, source: String?, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            val hash = ClipPolicy.hash(text)
            // 事务外算：查 ICU 是这一趟里最慢的一步，不该占着写锁
            val key = keyOf(text)
            db.beginTransaction()
            try {
                // 不用 ON CONFLICT DO UPDATE：那要 SQLite 3.24，Android 10 自带的是 3.22
                val bumped = db.compileStatement(
                    "UPDATE clips SET last_used_at = ?, use_count = use_count + 1, " +
                        "source = COALESCE(?, source) WHERE hash = ?",
                ).use { statement ->
                    statement.bindLong(1, now)
                    if (source == null) statement.bindNull(2) else statement.bindString(2, source)
                    statement.bindString(3, hash)
                    statement.executeUpdateDelete()
                }
                if (bumped == 0) {
                    db.insertOrThrow(
                        "clips",
                        null,
                        ContentValues().apply {
                            put("hash", hash)
                            put("text", text)
                            put("source", source)
                            put("created_at", now)
                            put("last_used_at", now)
                            put("search_key", key)
                        },
                    )
                }
                deleteOverCapacity(db)
                deleteExpired(db, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            changed()
        }

    /**
     * 自动清理的天数，0 为不清理。由设置流写进来。
     *
     * 清理不起定时器：每次记录时顺手删，设置一改当场删一次；读的时候再把过期的滤掉 ——
     * 好几天没复制过东西时，库里虽然还躺着过期条目，面板和列表里也看不到它们。
     */
    @Volatile
    var retentionDays: Int = 0

    /** 未置顶条目的保留数，0 为不限。同样由设置流写进来。 */
    @Volatile
    var capacity: Int = ClipPolicy.DEFAULT_CAPACITY

    /** 设置刚改过、或进程刚起来：按当前天数与条数删一次。真删掉了才通知界面重查。 */
    suspend fun prune(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (deleteOverCapacity(db) + deleteExpired(db, now) > 0) changed()
    }

    /**
     * 跳过最近的 [capacity] 条，删掉其余未置顶的。
     *
     * 用 OFFSET 走索引，而不是 `NOT IN (前 N 条)`：后者要把 N 条攒成临时表，
     * 条数一多每次插入都在付这笔账。十万条时这一句实测 17ms。
     */
    private fun deleteOverCapacity(db: SQLiteDatabase): Int {
        val keep = capacity
        if (keep <= 0) return 0
        return db.compileStatement(
            "DELETE FROM clips WHERE id IN (SELECT id FROM clips WHERE pinned_at = 0 " +
                "ORDER BY last_used_at DESC LIMIT -1 OFFSET ?)",
        ).use { statement ->
            statement.bindLong(1, keep.toLong())
            statement.executeUpdateDelete()
        }
    }

    private fun deleteExpired(db: SQLiteDatabase, now: Long): Int {
        val cutoff = ClipPolicy.expiryCutoff(now, retentionDays) ?: return 0
        return db.delete("clips", "pinned_at = 0 AND last_used_at < ?", arrayOf(cutoff.toString()))
    }

    /**
     * 置顶的在前（后置顶的更靠前），其余按最近使用。
     *
     * [query] 按空白拆词，每个词都要出现在搜索键里 —— 原文、全拼、首字母任一段都算。
     * 顺着排序索引走、凑够 [limit] 条就停：常见词一两毫秒，只有冷门词才会扫完全表。
     */
    suspend fun search(query: String, limit: Int, now: Long = System.currentTimeMillis()): List<ClipEntry> =
        withContext(Dispatchers.IO) {
            val terms = SearchKey.terms(query)
            val cutoff = ClipPolicy.expiryCutoff(now, retentionDays)
            val conditions = terms.map { "instr(search_key, ?) > 0" } +
                listOfNotNull(cutoff?.let { "(pinned_at > 0 OR last_used_at >= ?)" })
            val args = terms + listOfNotNull(cutoff?.toString())
            val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
            helper.readableDatabase.rawQuery(
                "SELECT id, substr(text, 1, " + ClipPolicy.PREVIEW_CHARS + "), source, created_at, " +
                    "last_used_at, use_count, pinned_at, length(text) FROM clips" + where +
                    " ORDER BY pinned_at DESC, last_used_at DESC LIMIT " + limit,
                args.toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEntry()) } }
        }

    /** 一条的全文。列表里只有预览（见 [ClipEntry.complete]），上屏、复制、看详情时才取。 */
    suspend fun text(id: Long): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT text FROM clips WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** 全文：预览已经是全文就不查库。条目被删了就退回手里的预览。 */
    suspend fun fullText(entry: ClipEntry): String =
        if (entry.complete) entry.text else text(entry.id) ?: entry.text

    /** 从面板或列表里用了一次：和再复制一次是同一个意思。 */
    suspend fun markUsed(id: Long, now: Long = System.currentTimeMillis()) = update(id) {
        helper.writableDatabase.execSQL(
            "UPDATE clips SET last_used_at = ?, use_count = use_count + 1 WHERE id = ?",
            arrayOf<Any?>(now, id),
        )
    }

    suspend fun setPinned(id: Long, pinned: Boolean, now: Long = System.currentTimeMillis()) = update(id) {
        helper.writableDatabase.update(
            "clips",
            ContentValues().apply { put("pinned_at", if (pinned) now else 0L) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    suspend fun delete(id: Long) = update(id) {
        helper.writableDatabase.delete("clips", "id = ?", arrayOf(id.toString()))
    }

    /** 清空：默认留下置顶的那几条，那是用户明确说过要留的。 */
    suspend fun clear(keepPinned: Boolean = true) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("clips", if (keepPinned) "pinned_at = 0" else null, null)
        changed()
    }

    /** 不再记录某个应用时，把它以前留下的未置顶条目一并删掉。 */
    suspend fun deleteFrom(source: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("clips", "source = ? AND pinned_at = 0", arrayOf(source))
        changed()
    }

    private suspend fun update(id: Long, block: () -> Unit) = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext
        block()
        changed()
    }

    private fun Cursor.toEntry() = ClipEntry(
        id = getLong(0),
        text = getString(1),
        sourcePackage = if (isNull(2)) null else getString(2),
        createdAtMs = getLong(3),
        lastUsedAtMs = getLong(4),
        useCount = getInt(5),
        pinnedAtMs = getLong(6),
        length = getInt(7),
    )

    private companion object {
        const val FILE = "clip_history.db"
        /** 2：加搜索键、正文挪到最后。 */
        const val SCHEMA = 2
    }
}
