package com.actionmental

import com.actionmental.core.translate.*
import com.actionmental.platform.MiniMaxTranslator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranslationTest {
    private val target = TranslationLanguage("en", "English", "Translate into English.")
    private val request = TranslationRequest("你好", target, "MiniMax-M2.7")

    @Test fun stableLanguageIdsSurviveRenameAndSettingsSerialization() {
        val changed = target.copy(name = "Renamed", prompt = "Custom prompt")
        assertEquals(target.id, changed.id)
        val settings = TranslationSettings(languages = listOf(changed), lastTargetId = changed.id)
        val json = Json.encodeToString(TranslationSettings.serializer(), settings)
        assertEquals(settings, Json.decodeFromString(TranslationSettings.serializer(), json))
        assertFalse(json.contains("token", ignoreCase = true))
    }

    @Test fun cacheKeyIncludesSourcePromptModelAndStableTargetButNotDisplayName() {
        assertEquals(request.matchKey, request.copy(target = target.copy(name = "Renamed")).matchKey)
        assertNotEquals(request.matchKey, request.copy(source = "不同").matchKey)
        assertNotEquals(request.matchKey, request.copy(model = "MiniMax-M2.5").matchKey)
        assertNotEquals(request.matchKey, request.copy(target = target.copy(prompt = "Other instruction")).matchKey)
        assertNotEquals(request.matchKey, request.copy(target = target.copy(id = "other")).matchKey)
    }

    @Test fun noSilentSourceTruncation() {
        expectFailure(TranslationFailure.INVALID_REQUEST) { request.copy(source = "x".repeat(8001)).validate() }
        request.copy(source = "x".repeat(8000)).validate()
    }

    @Test fun anthropicRequestSeparatesInstructionFromUntrustedText() {
        val body = Json.parseToJsonElement(MiniMaxTranslator.requestBody(request)).jsonObject
        assertEquals("MiniMax-M2.7", body["model"]!!.jsonPrimitive.content)
        assertEquals(request.prompt, body["system"]!!.jsonPrimitive.content)
        assertEquals(request.source, body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun separatesReasoningAndText() {
        val result = MiniMaxTranslator.parseResponse(response("end_turn", """{"type":"thinking","thinking":"private thought"},{"type":"text","text":"Hello"}"""))
        assertEquals("Hello", result.text)
        assertEquals("private thought", result.reasoning)
    }

    @Test fun rejectsTruncatedRefusedToolAndMissingFinish() {
        listOf("max_tokens", "tool_use", "stop_sequence", "refusal", "pause_turn", "").forEach { stop ->
            expectFailure(TranslationFailure.INCOMPLETE) { MiniMaxTranslator.parseResponse(response(stop)) }
        }
        expectFailure(TranslationFailure.PROVIDER) { MiniMaxTranslator.parseResponse("{broken") }
        expectFailure(TranslationFailure.PROVIDER) { MiniMaxTranslator.parseResponse(response("end_turn", """{"type":"text","text":null}""")) }
        expectFailure(TranslationFailure.PROVIDER) { MiniMaxTranslator.parseResponse("""{"type":"error","error":{"message":"secret"}}""") }
        expectFailure(TranslationFailure.EMPTY_RESPONSE) { MiniMaxTranslator.parseResponse(response("end_turn", """{"type":"thinking","thinking":"only thought"}""")) }
        expectFailure(TranslationFailure.INCOMPLETE) { MiniMaxTranslator.parseResponse(response("end_turn", """{"type":"text","text":"<think>unfinished"}""")) }
    }

    @Test fun credentialsAreNotSentInBodyAndRedirectsDisabled() = runBlocking {
        val connection = FakeConnection(response("end_turn"))
        val token = "secret-token".toCharArray()
        val result = client(connection, token).translate(request)
        assertEquals("Hello", result.text)
        assertEquals("secret-token", connection.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", connection.getRequestProperty("anthropic-version"))
        assertFalse(connection.output.toString("UTF-8").contains("secret-token"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(token.all { it == '\u0000' })
        assertTrue(connection.disconnected)
    }

    @Test fun transportErrorDoesNotExposeTokenOrRawCause() = runBlocking {
        val connection = FakeConnection(response("end_turn"), status = 401)
        try { client(connection).translate(request); fail() } catch (error: TranslationException) {
            assertEquals(TranslationFailure.AUTH, error.kind)
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret-token"))
        }
    }

    @Test fun missingTokenDoesNotOpenNetworkAndInvalidTokensAreNeverStored() = runBlocking {
        var opened = false
        var stored = ""
        val translator = MiniMaxTranslator({ null }, { stored = it }, {}, { false }, {
            opened = true
            FakeConnection(response("end_turn"))
        })
        assertFalse(translator.hasToken())
        try { translator.translate(request); fail() } catch (error: TranslationException) {
            assertEquals(TranslationFailure.MISSING_TOKEN, error.kind)
        }
        assertFalse(opened)
        expectFailure(TranslationFailure.INVALID_REQUEST) { translator.saveToken("secret\nheader") }
        assertEquals("", stored)
        translator.saveToken("  valid-token  ")
        assertEquals("valid-token", stored)
    }

    @Test fun cancellationDisconnectsAnInFlightRequest() = runBlocking {
        val connection = FakeConnection(response("end_turn"), block = true)
        val job = launch(Dispatchers.Default) { client(connection).translate(request) }
        assertTrue(withContext(Dispatchers.IO) { connection.started.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(connection.disconnected)
        assertTrue(job.isCancelled)
    }

    @Test fun totalTimeoutDisconnectsAndNeverReturnsPartialSuccess() = runBlocking {
        val connection = FakeConnection(response("end_turn"), block = true)
        try { client(connection, timeout = 100).translate(request); fail() } catch (error: TranslationException) {
            assertEquals(TranslationFailure.TIMEOUT, error.kind)
            assertTrue(connection.disconnected)
        }
    }

    private fun response(stop: String, blocks: String = """{"type":"text","text":"Hello"}""") =
        """{"type":"message","role":"assistant","stop_reason":"$stop","content":[$blocks]}"""

    private fun expectFailure(kind: TranslationFailure, block: () -> Unit) {
        try { block(); fail("Expected $kind") } catch (error: TranslationException) { assertEquals(kind, error.kind) }
    }

    private fun client(connection: FakeConnection, token: CharArray = "secret-token".toCharArray(), timeout: Long = 45000) =
        MiniMaxTranslator({ token }, {}, {}, { true }, { connection }, timeout)

    private class FakeConnection(private val response: String, private val status: Int = 200, private val block: Boolean = false) :
        HttpURLConnection(URL("https://api.minimaxi.com/anthropic/v1/messages")) {
        val output = ByteArrayOutputStream()
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var disconnected = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            started.countDown()
            if (block) released.await(3, TimeUnit.SECONDS)
            return status
        }
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    }
}
