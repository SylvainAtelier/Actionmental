package com.actionmental

import com.actionmental.core.translate.TranslationException
import com.actionmental.core.translate.TranslationFailure
import com.actionmental.platform.MiniMaxTranslator
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationModelsTest {
    @Test fun draftUsesReadOnlyModelEndpointWithoutSavingOrLoadingStoredToken() = runBlocking {
        val connection = FakeConnection(page("MiniMax-M3"))
        var stored = false
        var loaded = false
        val translator = MiniMaxTranslator(
            { loaded = true; "saved-token".toCharArray() }, { stored = true }, {}, { true },
            { error("Chat endpoint must not be used") },
            openModelsConnection = { connection },
        )
        assertEquals("https://api.minimaxi.com/anthropic/v1/models", MiniMaxTranslator.MODELS_ENDPOINT)
        assertEquals(listOf("MiniMax-M3"), translator.listModels("  draft-token  "))
        assertFalse(stored)
        assertFalse(loaded)
        assertEquals("GET", connection.requestMethod)
        assertEquals("draft-token", connection.getRequestProperty("x-api-key"))
        assertFalse(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun loadsAllPagesAndDeduplicatesWithoutSelectingOrProbingModels() = runBlocking {
        val cursors = mutableListOf<String?>()
        val tokens = mutableListOf<CharArray>()
        val translator = MiniMaxTranslator(
            { "saved-token".toCharArray().also { tokens.add(it) } }, {}, {}, { true },
            { error("No chat probes") },
            openModelsConnection = { cursor ->
                cursors.add(cursor)
                if (cursor == null) FakeConnection(page("model-a", more = true))
                else FakeConnection("""{"data":[{"id":"model-a","type":"model"},{"id":"model-b","type":"model"}],"has_more":false,"last_id":"model-b"}""")
            },
        )
        assertEquals(listOf("model-a", "model-b"), translator.listModels())
        assertEquals(listOf(null, "model-a"), cursors)
        assertTrue(tokens.all { token -> token.all { it == '\u0000' } })
    }

    @Test fun emptySuccessfulListIsNotReplacedWithStaticReferences() = runBlocking {
        assertEquals(emptyList<String>(), client(FakeConnection("""{"data":[],"has_more":false,"last_id":null}""")).listModels())
    }

    @Test fun failuresAreTypedAndNeverExposeRawProviderBodies() = runBlocking {
        listOf(
            401 to TranslationFailure.AUTH, 403 to TranslationFailure.AUTH,
            404 to TranslationFailure.MODELS_UNSUPPORTED, 405 to TranslationFailure.MODELS_UNSUPPORTED,
            501 to TranslationFailure.MODELS_UNSUPPORTED, 429 to TranslationFailure.RATE_LIMIT,
            302 to TranslationFailure.HTTP, 500 to TranslationFailure.HTTP,
        ).forEach { (status, kind) ->
            try { client(FakeConnection("secret-token provider response", status)).listModels(); fail() }
            catch (error: TranslationException) {
                assertEquals(kind, error.kind)
                assertNull(error.cause)
                assertFalse(error.toString().contains("secret-token"))
            }
        }
    }

    @Test fun missingOrInvalidDraftDoesNotOpenNetwork() = runBlocking {
        var opened = false
        val translator = MiniMaxTranslator(
            { null }, {}, {}, { false }, { error("No chat") },
            openModelsConnection = { opened = true; FakeConnection(page("model-a")) },
        )
        for (draft in listOf(null, "", "bad\nheader")) {
            try { translator.listModels(draft); fail() }
            catch (error: TranslationException) {
                assertEquals(if (draft == null) TranslationFailure.MISSING_TOKEN else TranslationFailure.INVALID_REQUEST, error.kind)
            }
        }
        assertFalse(opened)
    }

    @Test fun malformedResponsesAndCursorLoopsNeverReturnPartialLists() = runBlocking {
        listOf(
            "{broken", """{"error":{"message":"secret-token"}}""",
            """{"data":[{"id":"bad\nheader","type":"model"}],"has_more":false}""",
            """{"data":[{"id":12,"type":"model"}],"has_more":false}""",
            """{"data":[{"id":"model-a","type":"model"}],"has_more":true}""",
            """{"data":[],"has_more":true,"last_id":"model-a"}""",
        ).forEach { body ->
            try { client(FakeConnection(body)).listModels(); fail() }
            catch (error: TranslationException) { assertEquals(TranslationFailure.PROVIDER, error.kind) }
        }
        try { client(FakeConnection(page("model-a", more = true))).listModels(); fail() }
        catch (error: TranslationException) { assertEquals(TranslationFailure.PROVIDER, error.kind) }
    }

    @Test fun cancellationDisconnectsModelRequest() = runBlocking {
        val connection = FakeConnection(page("model-a"), block = true)
        val job = launch(Dispatchers.Default) { client(connection).listModels("draft-token") }
        assertTrue(withContext(Dispatchers.IO) { connection.started.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(connection.disconnected)
    }

    @Test fun timeoutDisconnectsModelRequest() = runBlocking {
        val connection = FakeConnection(page("model-a"), block = true)
        try { client(connection, timeout = 100).listModels(); fail() }
        catch (error: TranslationException) { assertEquals(TranslationFailure.TIMEOUT, error.kind) }
        assertTrue(connection.disconnected)
    }

    private fun page(id: String, more: Boolean = false) =
        """{"data":[{"id":"$id","type":"model"}],"has_more":$more,"last_id":"$id"}"""

    private fun client(connection: FakeConnection, timeout: Long = 45000) = MiniMaxTranslator(
        { "secret-token".toCharArray() }, {}, {}, { true }, { error("No chat probes") }, timeout,
        openModelsConnection = { connection },
    )

    private class FakeConnection(private val body: String, private val status: Int = 200, private val block: Boolean = false) :
        HttpURLConnection(URL(MiniMaxTranslator.MODELS_ENDPOINT)) {
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var disconnected = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode(): Int {
            started.countDown()
            if (block) released.await(3, TimeUnit.SECONDS)
            return status
        }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}
