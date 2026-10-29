package com.actionmental.platform

import android.content.Context
import com.actionmental.platform.sync.SyncCredentials
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SpeechRecognitionFailure {
    MISSING_TOKEN, INVALID_TOKEN, INVALID_FILE, FILE_TOO_LARGE, AUTH, INSUFFICIENT_BALANCE,
    RATE_LIMIT, EMPTY_RESPONSE, RESPONSE_TOO_LARGE, PROVIDER, HTTP, NETWORK, TIMEOUT,
}

/** Contains only a stable UI reason, never remote text, credentials, paths, or a raw cause. */
class SpeechRecognitionException(val reason: SpeechRecognitionFailure) : Exception(reason.name)

class MiniMaxSpeechRecognizer internal constructor(
    private val loadToken: () -> CharArray?,
    private val storeToken: (String) -> Unit,
    private val removeToken: () -> Unit,
    private val tokenPresent: () -> Boolean,
    private val openConnection: () -> HttpURLConnection,
    private val timeoutMs: Long = 120_000,
    private val dedicatedPresent: () -> Boolean = tokenPresent,
) {
    constructor(context: Context) : this(SyncCredentials(context))
    private constructor(credentials: SyncCredentials) : this(
        { loadStoredToken(credentials::has, credentials::load) },
        { credentials.save(TOKEN_KEY, it) },
        { credentials.remove(TOKEN_KEY) },
        { credentials.has(TOKEN_KEY) || credentials.has(FALLBACK_TOKEN_KEY) },
        { URL(ENDPOINT).openConnection() as HttpURLConnection },
        dedicatedPresent = { credentials.has(TOKEN_KEY) },
    )

    init { require(timeoutMs in 1..MAX_TIMEOUT_MS) }

    fun hasToken(): Boolean = tokenPresent()

    /** Only the speech-specific key, not the translation fallback; settings show which one is in use. */
    fun hasDedicatedToken(): Boolean = dedicatedPresent()

    fun saveToken(token: String) {
        val value = token.trim()
        validateToken(value)
        storeToken(value)
    }

    /** Removes only the speech credential; translation fallback remains available. */
    fun clearToken() = removeToken()

    suspend fun transcribe(file: File): String {
        coroutineContext.ensureActive()
        validateFile(file)
        val answer = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { continuation ->
                val activeConnection = AtomicReference<HttpURLConnection?>()
                continuation.invokeOnCancellation {
                    runCatching { activeConnection.getAndSet(null)?.disconnect() }
                }
                try {
                    executor.execute {
                        var token: CharArray? = null
                        var connection: HttpURLConnection? = null
                        try {
                            if (!continuation.isActive) return@execute
                            // Presence must not decrypt credentials on a UI thread.
                            if (!tokenPresent()) fail(SpeechRecognitionFailure.MISSING_TOKEN)
                            token = loadToken() ?: fail(SpeechRecognitionFailure.MISSING_TOKEN)
                            validateToken(String(token))
                            if (!continuation.isActive) return@execute
                            connection = openConnection()
                            activeConnection.set(connection)
                            if (!continuation.isActive) return@execute
                            val boundary = "minimax-" + UUID.randomUUID()
                            connection.requestMethod = "POST"
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = timeoutMs.toInt()
                            connection.readTimeout = timeoutMs.toInt()
                            connection.doOutput = true
                            connection.setChunkedStreamingMode(8192)
                            connection.setRequestProperty("Authorization", "Bearer " + String(token))
                            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                            connection.setRequestProperty("Accept", "application/json")
                            connection.outputStream.use { output ->
                                writeMultipart(output, boundary, file) {
                                    if (!continuation.isActive) throw java.io.InterruptedIOException()
                                }
                            }
                            val status = connection.responseCode
                            if (status !in 200..299) fail(httpFailure(status))
                            val bytes = connection.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return@execute
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_RESPONSE_BYTES) fail(SpeechRecognitionFailure.RESPONSE_TOO_LARGE)
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            val result = parseResponse(String(bytes, Charsets.UTF_8))
                            token.fill('\u0000')
                            token = null
                            activeConnection.set(null)
                            runCatching { connection?.disconnect() }
                            connection = null
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            val safe = when (error) {
                                is SpeechRecognitionException -> error
                                is SocketTimeoutException -> SpeechRecognitionException(SpeechRecognitionFailure.TIMEOUT)
                                else -> SpeechRecognitionException(SpeechRecognitionFailure.NETWORK)
                            }
                            token?.fill('\u0000')
                            token = null
                            activeConnection.set(null)
                            runCatching { connection?.disconnect() }
                            connection = null
                            if (continuation.isActive) continuation.resumeWithException(safe)
                        } finally {
                            token?.fill('\u0000')
                            activeConnection.set(null)
                            runCatching { connection?.disconnect() }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    if (continuation.isActive) continuation.resumeWithException(SpeechRecognitionException(SpeechRecognitionFailure.NETWORK))
                }
            }
        }
        coroutineContext.ensureActive()
        return answer ?: fail(SpeechRecognitionFailure.TIMEOUT)
    }

    companion object {
        const val ENDPOINT = "https://api.minimax.cn/v1/speech_to_text"
        internal const val TOKEN_KEY = "speech.minimax-cn"
        internal const val FALLBACK_TOKEN_KEY = "translation.minimax-cn"
        internal const val MAX_FILE_BYTES = 50L * 1024 * 1024
        internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_TIMEOUT_MS = 180_000L
        private val json = Json { ignoreUnknownKeys = true }
        // Bounded workers contain sockets on devices that do not promptly honor disconnect().
        private val executor = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { runnable ->
            Thread(runnable, "minimax-speech").apply { isDaemon = true }
        })

        internal fun loadStoredToken(has: (String) -> Boolean, load: (String) -> CharArray?): CharArray? =
            when {
                has(TOKEN_KEY) -> load(TOKEN_KEY)
                has(FALLBACK_TOKEN_KEY) -> load(FALLBACK_TOKEN_KEY)
                else -> null
            }

        internal fun validateFile(file: File) {
            if (!file.isFile || !file.canRead() || file.length() <= 0) fail(SpeechRecognitionFailure.INVALID_FILE)
            if (file.length() > MAX_FILE_BYTES) fail(SpeechRecognitionFailure.FILE_TOO_LARGE)
        }

        private fun validateToken(value: String) {
            if (value.isEmpty() || value.length > 8192 || value.any { it.code !in 33..126 }) {
                fail(SpeechRecognitionFailure.INVALID_TOKEN)
            }
        }

        internal fun httpFailure(status: Int): SpeechRecognitionFailure = when (status) {
            401, 403 -> SpeechRecognitionFailure.AUTH
            402 -> SpeechRecognitionFailure.INSUFFICIENT_BALANCE
            429 -> SpeechRecognitionFailure.RATE_LIMIT
            413 -> SpeechRecognitionFailure.FILE_TOO_LARGE
            else -> SpeechRecognitionFailure.HTTP
        }

        /** WAV is preferred; the filename is generated locally, never taken from an untrusted path. */
        internal fun writeMultipart(output: OutputStream, boundary: String, file: File, checkActive: () -> Unit = {}) {
            require(boundary.matches(Regex("[A-Za-z0-9-]{1,80}")))
            validateFile(file)
            val extension = file.extension.lowercase().takeIf { it in setOf("wav", "mp3", "m4a", "mp4", "flac", "ogg", "aac", "webm") } ?: "bin"
            val contentType = when (extension) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "m4a", "mp4" -> "audio/mp4"
                "flac" -> "audio/flac"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "webm" -> "audio/webm"
                else -> "application/octet-stream"
            }
            fun text(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
            text("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nasr-1.0\r\n")
            text("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
            text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.$extension\"\r\nContent-Type: $contentType\r\n\r\n")
            var total = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FILE_BYTES) fail(SpeechRecognitionFailure.FILE_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            if (total == 0L) fail(SpeechRecognitionFailure.INVALID_FILE)
            text("\r\n--$boundary--\r\n")
        }

        internal fun parseResponse(raw: String): String {
            try {
                val response = json.parseToJsonElement(raw).jsonObject
                if (response["error"] != null && response["error"] != JsonNull) fail(SpeechRecognitionFailure.PROVIDER)
                response["base_resp"]?.let { base ->
                    val status = base.jsonObject["status_code"]?.jsonPrimitive?.intOrNull
                        ?: fail(SpeechRecognitionFailure.PROVIDER)
                    if (status != 0) fail(SpeechRecognitionFailure.PROVIDER)
                }
                val text = response["text"]?.jsonPrimitive ?: fail(SpeechRecognitionFailure.PROVIDER)
                if (!text.isString) fail(SpeechRecognitionFailure.PROVIDER)
                return text.content.trim().ifEmpty { fail(SpeechRecognitionFailure.EMPTY_RESPONSE) }
            } catch (error: SpeechRecognitionException) {
                throw error
            } catch (_: Exception) {
                fail(SpeechRecognitionFailure.PROVIDER)
            }
        }

        private fun fail(reason: SpeechRecognitionFailure): Nothing = throw SpeechRecognitionException(reason)
    }
}
