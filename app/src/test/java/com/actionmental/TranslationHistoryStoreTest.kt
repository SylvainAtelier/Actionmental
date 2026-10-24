package com.actionmental

import com.actionmental.core.translate.*
import com.actionmental.data.TranslationHistoryStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val language = TranslationLanguage("en", "English", "Translate into English.")
    private fun request(source: String = "你好") = TranslationRequest(source, language, "MiniMax-M2.7")
    private fun historyFile() = File(temporary.root, "no-backup/history.json")

    @Test fun survivesRestartAndStoresExactSnapshotWithoutReasoningOrCredentials() = runBlocking {
        val file = historyFile()
        val store = TranslationHistoryStore(file)
        store.record(request(), TranslationResult("Hello", "private reasoning"), 1234)
        val restarted = TranslationHistoryStore(file)
        val entry = restarted.recent().single()
        assertEquals("Hello", entry.result)
        assertEquals(request().prompt, entry.prompt)
        assertEquals("MiniMax-M2.7", entry.model)
        assertEquals("你好", entry.source)
        assertEquals("en", entry.targetId)
        assertEquals("English", entry.targetName)
        assertEquals(1234L, entry.createdAtMs)
        assertFalse(file.readText().contains("private reasoning"))
        assertEquals(entry, restarted.findMatch(request()))
    }

    @Test fun keepsOnlyFiftyMostRecentSuccessfulRequestsAndBumpsDuplicate() = runBlocking {
        val store = TranslationHistoryStore(historyFile())
        repeat(55) { store.record(request("source $it"), TranslationResult("result $it"), it.toLong()) }
        assertEquals(50, store.recent().size)
        assertNull(store.findMatch(request("source 0")))
        val old = store.findMatch(request("source 5"))!!
        store.record(request("source 5"), TranslationResult("updated"), 100)
        assertEquals(50, store.recent().size)
        val first = store.recent().first()
        assertEquals(old.id, first.id)
        assertEquals("updated", first.result)
        assertEquals(100L, first.createdAtMs)
    }

    @Test fun matchingRequiresSamePromptModelSourceAndTargetId() = runBlocking {
        val store = TranslationHistoryStore(historyFile())
        store.record(request(), TranslationResult("Hello"))
        assertNull(store.findMatch(request().copy(model = "other")))
        assertNull(store.findMatch(request("other source")))
        assertNull(store.findMatch(request().copy(target = language.copy(prompt = "new prompt"))))
        assertNull(store.findMatch(request().copy(target = language.copy(id = "new id"))))
        assertNotNull(store.findMatch(request().copy(target = language.copy(name = "Renamed"))))
    }

    @Test fun deleteAndClearAreDurableAndIdsNeverGetReused() = runBlocking {
        val file = historyFile()
        val store = TranslationHistoryStore(file)
        store.record(request(), TranslationResult("Hello"))
        val id = store.recent().single().id
        store.delete(id)
        assertTrue(TranslationHistoryStore(file).recent().isEmpty())
        store.record(request("second"), TranslationResult("Second"))
        assertTrue(store.recent().single().id > id)
        store.clear()
        assertTrue(TranslationHistoryStore(file).recent().isEmpty())
        store.record(request("third"), TranslationResult("Third"))
        assertTrue(store.recent().single().id > id + 1)
    }

    @Test fun concurrentWritesDoNotLoseUpdates() = runBlocking {
        val store = TranslationHistoryStore(historyFile())
        coroutineScope {
            repeat(40) { launch(Dispatchers.Default) { store.record(request("source $it"), TranslationResult("result $it")) } }
        }
        assertEquals(40, store.recent().size)
        assertEquals(40, store.recent().map { it.id }.distinct().size)
        assertEquals(40L, store.version.value)
    }

    @Test fun rejectsEmptyResultsWithoutWritingAndDoesNotOverwriteCorruptHistory() = runBlocking {
        val file = historyFile()
        val store = TranslationHistoryStore(file)
        try { store.record(request(), TranslationResult(" ")); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(file.exists())
        file.parentFile!!.mkdirs()
        file.writeText("corrupt")
        try { store.record(request(), TranslationResult("Hello")); fail() } catch (_: Exception) { }
        assertEquals("corrupt", file.readText())
    }
}
