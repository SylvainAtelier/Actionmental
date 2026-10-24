package com.actionmental.platform

import android.content.Context
import com.actionmental.core.translate.*
import com.actionmental.platform.sync.SyncCredentials
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
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

/** MiniMax CN uses the Anthropic messages protocol, not OpenAI chat/completions. */
class MiniMaxTranslator internal constructor(
    private val loadToken: () -> CharArray?,
    private val storeToken: (String) -> Unit,
    private val removeToken: () -> Unit,
    private val tokenPresent: () -> Boolean,
    private val openConnection: () -> HttpURLConnection,
    private val timeoutMs: Long = 45_000,
) {
    constructor(context: Context) : this(SyncCredentials(context))
    private constructor(credentials: SyncCredentials) : this(
        { credentials.load(TOKEN_KEY) }, { credentials.save(TOKEN_KEY, it) },
        { credentials.remove(TOKEN_KEY) }, { credentials.has(TOKEN_KEY) },
        { URL(ENDPOINT).openConnection() as HttpURLConnection },
    )

    fun hasToken(): Boolean = tokenPresent()
    fun saveToken(token: String) {
        val value = token.trim()
        if (value.isEmpty() || value.length > 8192 || value.any { it.isISOControl() }) {
            throw TranslationException(TranslationFailure.INVALID_REQUEST)
        }
        storeToken(value)
    }
    fun clearToken() = removeToken()

    suspend fun testConnection(model: String): TranslationResult = translate(
        TranslationRequest("Hello", TranslationSettings.DEFAULT_LANGUAGES[0], model),
    )

    suspend fun translate(request: TranslationRequest): TranslationResult {
        request.validate()
        val answer = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val activeConnection = AtomicReference<HttpURLConnection?>()
                continuation.invokeOnCancellation { runCatching { activeConnection.getAndSet(null)?.disconnect() } }
                try {
                    executor.execute {
                        var token: CharArray? = null
                        var connection: HttpURLConnection? = null
                        try {
                            if (!continuation.isActive) return@execute
                            token = loadToken() ?: throw TranslationException(TranslationFailure.MISSING_TOKEN)
                            connection = openConnection()
                            activeConnection.set(connection)
                            if (!continuation.isActive) return@execute
                            connection.requestMethod = "POST"
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            connection.readTimeout = connection.connectTimeout
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.setRequestProperty("x-api-key", String(token))
                            connection.setRequestProperty("anthropic-version", "2023-06-01")
                            connection.outputStream.use { it.write(requestBody(request).toByteArray(Charsets.UTF_8)) }
                            val status = connection.responseCode
                            if (status !in 200..299) throw TranslationException(when (status) {
                                401, 403 -> TranslationFailure.AUTH
                                429 -> TranslationFailure.RATE_LIMIT
                                else -> TranslationFailure.HTTP
                            })
                            val bytes = connection.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return@execute
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_RESPONSE) throw TranslationException(TranslationFailure.INCOMPLETE)
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            val result = parseResponse(String(bytes, Charsets.UTF_8))
                            token.fill('\u0000')
                            token = null
                            activeConnection.set(null)
                            connection.disconnect()
                            connection = null
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            val safe = when (error) {
                                is TranslationException -> error
                                is SocketTimeoutException -> TranslationException(TranslationFailure.TIMEOUT)
                                else -> TranslationException(TranslationFailure.NETWORK)
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
                    if (continuation.isActive) continuation.resumeWithException(TranslationException(TranslationFailure.NETWORK))
                }
            }
        }
        coroutineContext.ensureActive()
        return answer ?: throw TranslationException(TranslationFailure.TIMEOUT)
    }

    companion object {
        const val ENDPOINT = "https://api.minimaxi.com/anthropic/v1/messages"
        private const val TOKEN_KEY = "translation.minimax-cn"
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        // Bound abandoned platform sockets even if a ROM does not promptly honor disconnect().
        private val executor = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { runnable ->
            Thread(runnable, "minimax-translation").apply { isDaemon = true }
        })
        private val json = Json { ignoreUnknownKeys = true }

        internal fun requestBody(request: TranslationRequest): String = buildJsonObject {
            put("model", request.model)
            put("max_tokens", 8192)
            put("stream", false)
            putJsonObject("thinking") { put("type", "disabled") }
            put("system", request.prompt)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", request.source) }
            }
        }.toString()

        internal fun parseResponse(raw: String): TranslationResult {
            try {
                val response = json.parseToJsonElement(raw).jsonObject
                if (response["error"] != null || response["type"]?.jsonPrimitive?.contentOrNull == "error") {
                    throw TranslationException(TranslationFailure.PROVIDER)
                }
                if (response["type"]?.jsonPrimitive?.contentOrNull != "message" ||
                    response["role"]?.jsonPrimitive?.contentOrNull != "assistant" ||
                    response["stop_reason"]?.jsonPrimitive?.contentOrNull != "end_turn") {
                    throw TranslationException(TranslationFailure.INCOMPLETE)
                }
                val text = StringBuilder()
                val reasoning = StringBuilder()
                response["content"]!!.jsonArray.forEach { element ->
                    val block = element.jsonObject
                    when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> text.append(block["text"]!!.jsonPrimitive.let {
                            if (!it.isString) throw TranslationException(TranslationFailure.PROVIDER)
                            it.content
                        })
                        "thinking" -> reasoning.append(block["thinking"]!!.jsonPrimitive.let {
                            if (!it.isString) throw TranslationException(TranslationFailure.PROVIDER)
                            it.content
                        })
                        "redacted_thinking" -> Unit
                        else -> throw TranslationException(TranslationFailure.INCOMPLETE)
                    }
                }
                if (text.isBlank()) throw TranslationException(TranslationFailure.EMPTY_RESPONSE)
                // Never silently salvage a partial or unclosed reasoning envelope as translated text.
                if (text.contains("<think>", ignoreCase = true) || text.contains("</think>", ignoreCase = true)) {
                    throw TranslationException(TranslationFailure.INCOMPLETE)
                }
                return TranslationResult(text.toString().trim(), reasoning.toString())
            } catch (error: TranslationException) {
                throw error
            } catch (_: Exception) {
                throw TranslationException(TranslationFailure.PROVIDER)
            }
        }
    }
}
