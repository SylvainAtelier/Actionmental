package com.actionmental

import com.actionmental.platform.MiniMaxSpeechRecognizer
import com.actionmental.platform.SpeechRecognitionException
import com.actionmental.platform.SpeechRecognitionFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechRecognitionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun audio(): File = temporary.newFile("clip-${System.nanoTime()}.wav").apply { writeBytes("RIFF-test-audio".toByteArray()) }

    @Test fun multipartUsesChinaAsrAndDoesNotExposeFilenameOrTokenInBody() = runBlocking {
        val connection = FakeConnection()
        val token = "secret-token".toCharArray()
        assertEquals("你好", client(connection, token).transcribe(audio()))
        assertEquals("https://api.minimax.cn/v1/speech_to_text", MiniMaxSpeechRecognizer.ENDPOINT)
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer secret-token", connection.getRequestProperty("Authorization"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        val type = connection.getRequestProperty("Content-Type")
        assertTrue(type.startsWith("multipart/form-data; boundary="))
        val boundary = type.substringAfter("boundary=")
        val body = connection.output.toString("UTF-8")
        assertTrue(body.contains("name=\"model\"\r\n\r\nasr-1.0\r\n"))
        assertTrue(body.contains("name=\"response_format\"\r\n\r\njson\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav"))
        assertTrue(body.contains("RIFF-test-audio"))
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
        assertFalse(body.contains("secret-token"))
        assertFalse(body.contains("clip-"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.connectTimeout in 1..180000)
        assertTrue(connection.readTimeout in 1..180000)
        assertTrue(token.all { it == '\u0000' })
        assertTrue(connection.disconnected)
    }

    @Test fun dedicatedTokenWinsAndUnreadableDedicatedTokenNeverFallsBack() {
        val requested = mutableListOf<String>()
        fun load(key: String): CharArray? { requested += key; return if (key == MiniMaxSpeechRecognizer.TOKEN_KEY) null else "translation".toCharArray() }
        assertNull(MiniMaxSpeechRecognizer.loadStoredToken({ true }, ::load))
        assertEquals(listOf("speech.minimax-cn"), requested)
        requested.clear()
        assertArrayEquals("translation".toCharArray(), MiniMaxSpeechRecognizer.loadStoredToken({ it == MiniMaxSpeechRecognizer.FALLBACK_TOKEN_KEY }, ::load))
        assertEquals(listOf("translation.minimax-cn"), requested)
        requested.clear()
        assertNull(MiniMaxSpeechRecognizer.loadStoredToken({ false }, ::load))
        assertTrue(requested.isEmpty())
        assertArrayEquals("speech".toCharArray(), MiniMaxSpeechRecognizer.loadStoredToken({ true }, { "speech".toCharArray() }))
    }

    @Test fun presenceDoesNotDecryptAndAbsentPresenceNeverLoadsOrOpensNetwork() = runBlocking {
        var loaded = false
        var opened = false
        var present = false
        var stored = ""
        var removed = false
        val recognizer = MiniMaxSpeechRecognizer({ loaded = true; "secret".toCharArray() }, { stored = it }, { removed = true }, { present }, {
            opened = true; FakeConnection()
        })
        assertFalse(recognizer.hasToken())
        assertFalse(loaded)
        try { recognizer.transcribe(audio()); fail() } catch (error: SpeechRecognitionException) {
            assertEquals(SpeechRecognitionFailure.MISSING_TOKEN, error.reason)
        }
        assertFalse(loaded)
        assertFalse(opened)
        present = true
        assertTrue(recognizer.hasToken())
        assertFalse(loaded)
        listOf("", "secret\nheader", "bad token", "x".repeat(8193)).forEach { token ->
            expectFailure(SpeechRecognitionFailure.INVALID_TOKEN) { recognizer.saveToken(token) }
        }
        assertEquals("", stored)
        recognizer.saveToken("  valid-token  ")
        assertEquals("valid-token", stored)
        recognizer.clearToken()
        assertTrue(removed)
    }

    @Test fun missingAndEmptyAndOversizeFilesAreRejected() {
        expectFailure(SpeechRecognitionFailure.INVALID_FILE) { MiniMaxSpeechRecognizer.validateFile(File(temporary.root, "missing.wav")) }
        expectFailure(SpeechRecognitionFailure.INVALID_FILE) { MiniMaxSpeechRecognizer.validateFile(temporary.root) }
        expectFailure(SpeechRecognitionFailure.INVALID_FILE) { MiniMaxSpeechRecognizer.validateFile(temporary.newFile()) }
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(MiniMaxSpeechRecognizer.MAX_FILE_BYTES) }
        MiniMaxSpeechRecognizer.validateFile(file)
        RandomAccessFile(file, "rw").use { it.setLength(MiniMaxSpeechRecognizer.MAX_FILE_BYTES + 1) }
        expectFailure(SpeechRecognitionFailure.FILE_TOO_LARGE) { MiniMaxSpeechRecognizer.validateFile(file) }
    }

    @Test fun parsesOnlyValidNonemptyTextWithoutLeakingProviderErrors() {
        assertEquals("Hello", MiniMaxSpeechRecognizer.parseResponse("""{"text":" Hello ","base_resp":{"status_code":0},"extra":true}"""))
        expectFailure(SpeechRecognitionFailure.EMPTY_RESPONSE) { MiniMaxSpeechRecognizer.parseResponse("""{"text":"  "}""") }
        listOf("{broken", "{}", """{"text":42}""", """{"text":null}""", """{"text":"success","error":{"message":"secret-token"}}""", """{"text":"partial","base_resp":{"status_code":1004,"status_msg":"secret-token"}}""").forEach { raw ->
            expectFailure(SpeechRecognitionFailure.PROVIDER) { MiniMaxSpeechRecognizer.parseResponse(raw) }
        }
    }

    @Test fun httpErrorsAndRedirectsNeverReadOrExposeResponse() = runBlocking {
        val statuses = mapOf(401 to SpeechRecognitionFailure.AUTH, 402 to SpeechRecognitionFailure.INSUFFICIENT_BALANCE,
            403 to SpeechRecognitionFailure.AUTH, 429 to SpeechRecognitionFailure.RATE_LIMIT, 413 to SpeechRecognitionFailure.FILE_TOO_LARGE,
            302 to SpeechRecognitionFailure.HTTP, 500 to SpeechRecognitionFailure.HTTP)
        for ((status, reason) in statuses) {
            val connection = FakeConnection(response = "secret-token raw response", status = status)
            try { client(connection).transcribe(audio()); fail() } catch (error: SpeechRecognitionException) {
                assertEquals(reason, error.reason)
                assertNull(error.cause)
                assertEquals(reason.name, error.message)
                assertFalse(error.toString().contains("secret-token"))
            }
            assertFalse(connection.inputRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun networkAndSocketTimeoutAreSafe() = runBlocking {
        for ((cause, reason) in listOf(IOException("secret-token") to SpeechRecognitionFailure.NETWORK,
            SocketTimeoutException("secret-token") to SpeechRecognitionFailure.TIMEOUT)) {
            val connection = FakeConnection(failure = cause)
            try { client(connection).transcribe(audio()); fail() } catch (error: SpeechRecognitionException) {
                assertEquals(reason, error.reason)
                assertNull(error.cause)
                assertEquals(reason.name, error.message)
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun responseSizeIsBounded() = runBlocking {
        val connection = FakeConnection(response = "x".repeat(MiniMaxSpeechRecognizer.MAX_RESPONSE_BYTES + 1))
        try { client(connection).transcribe(audio()); fail() } catch (error: SpeechRecognitionException) {
            assertEquals(SpeechRecognitionFailure.RESPONSE_TOO_LARGE, error.reason)
        }
        assertTrue(connection.disconnected)
    }

    @Test fun cancellationDisconnectsAndDoesNotReturnText() = runBlocking {
        val connection = FakeConnection(block = true)
        val file = audio()
        val job = launch(Dispatchers.Default) { client(connection).transcribe(file) }
        assertTrue(withContext(Dispatchers.IO) { connection.started.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(connection.disconnected)
    }

    @Test fun totalTimeoutDisconnectsAndDoesNotReturnPartialText() = runBlocking {
        val connection = FakeConnection(block = true)
        try { client(connection, timeout = 100).transcribe(audio()); fail() } catch (error: SpeechRecognitionException) {
            assertEquals(SpeechRecognitionFailure.TIMEOUT, error.reason)
        }
        assertTrue(connection.disconnected)
    }

    private fun expectFailure(reason: SpeechRecognitionFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: SpeechRecognitionException) {
            assertEquals(reason, error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret-token"))
        }
    }

    private fun client(connection: FakeConnection, token: CharArray = "secret-token".toCharArray(), timeout: Long = 120000) =
        MiniMaxSpeechRecognizer({ token }, {}, {}, { true }, { connection }, timeout)

    private class FakeConnection(
        private val response: String = """{"text":"你好","base_resp":{"status_code":0}}""",
        private val status: Int = 200,
        private val block: Boolean = false,
        private val failure: IOException? = null,
    ) : HttpURLConnection(URL(MiniMaxSpeechRecognizer.ENDPOINT)) {
        val output = ByteArrayOutputStream()
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var disconnected = false
        @Volatile var inputRead = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            started.countDown()
            if (block) released.await(3, TimeUnit.SECONDS)
            failure?.let { throw it }
            return status
        }
        override fun getInputStream(): ByteArrayInputStream {
            inputRead = true
            return ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        }
    }
}
