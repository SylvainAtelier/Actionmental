package com.actionmental.data

import android.content.Context
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationRequest
import com.actionmental.core.translate.TranslationResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Successful translations only, independent of clipboard collection and ConfigTransfer.
 * Android's noBackupFilesDir excludes this file from both cloud backup and device transfer.
 * Fifty records fit a small snapshot; fsync plus same-directory atomic replacement prevents partial writes.
 */
class TranslationHistoryStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "translation_history.json"))

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _version = MutableStateFlow(0L)
    val version = _version.asStateFlow()

    @Serializable
    private data class Saved(val key: String, val entry: TranslationHistoryEntry)
    @Serializable
    private data class Snapshot(val nextId: Long = 1, val records: List<Saved> = emptyList())

    suspend fun recent(): List<TranslationHistoryEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { load().records.map { it.entry } }
    }

    suspend fun findMatch(request: TranslationRequest): TranslationHistoryEntry? = withContext(Dispatchers.IO) {
        mutex.withLock { load().records.firstOrNull { it.key == request.matchKey }?.entry }
    }

    suspend fun record(request: TranslationRequest, result: TranslationResult, now: Long = System.currentTimeMillis()): Unit =
        withContext(Dispatchers.IO) {
            request.validate()
            require(result.text.isNotBlank()) { "Only completed non-empty translations can be recorded" }
            mutex.withLock {
                val previous = load()
                val key = request.matchKey
                val existing = previous.records.firstOrNull { it.key == key }
                val entry = TranslationHistoryEntry(existing?.entry?.id ?: previous.nextId,
                    request.prompt, request.model, request.source, request.target.id, request.target.name, result.text, now)
                val records = (listOf(Saved(key, entry)) + previous.records.filterNot { it.key == key }).take(50)
                coroutineContext.ensureActive()
                save(Snapshot(if (existing == null) Math.addExact(previous.nextId, 1L) else previous.nextId, records))
            }
        }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = load()
            val records = previous.records.filterNot { it.entry.id == id }
            if (records.size != previous.records.size) save(previous.copy(records = records))
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = load()
            if (previous.records.isNotEmpty()) save(previous.copy(records = emptyList()))
        }
    }

    /** No persistent handles are kept open. */
    fun close() = Unit

    private fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        // Do not silently overwrite malformed history with an empty snapshot.
        return json.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8)).also { snapshot ->
            check(snapshot.records.size <= 50 && snapshot.nextId > 0 &&
                snapshot.records.all { it.entry.id > 0 && it.entry.id < snapshot.nextId && it.entry.result.isNotBlank() } &&
                snapshot.records.map { it.key }.distinct().size == snapshot.records.size &&
                snapshot.records.map { it.entry.id }.distinct().size == snapshot.records.size) { "Invalid translation history" }
        }
    }

    private fun save(snapshot: Snapshot) {
        val directory = file.absoluteFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create translation history directory" }
        val temporary = File.createTempFile("translation-history-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(Snapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            _version.value += 1
        } finally {
            temporary.delete()
        }
    }
}
