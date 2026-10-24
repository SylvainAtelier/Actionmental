package com.actionmental.platform

import android.content.Context
import com.actionmental.core.translate.*
import com.actionmental.platform.sync.SyncCredentials
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
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
    private val openModelsConnection: (String?) -> HttpURLConnection = { afterId ->
        val query = "?limit=100" + (afterId?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        URL(MODELS_ENDPOINT + query).openConnection() as HttpURLConnection
    },
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
        validateToken(value)
        storeToken(value)
    }
    fun clearToken() = removeToken()

    suspend fun testConnection(model: String): TranslationResult = translate(
        TranslationRequest("Hello", TranslationSettings.DEFAULT_LANGUAGES[0], model),
    )

    suspend fun translate(request: TranslationRequest): TranslationResult {
        request.validate()
        return executeRequest(null, openConnection, requestBody(request), ::parseResponse)
    }

    /** Draft credentials are used only for this request and are never stored. */
    suspend fun listModels(tokenDraft: String? = null): List<String> {
        if (tokenDraft != null) validateToken(tokenDraft.trim())
        val answer = withTimeoutOrNull(timeoutMs) {
            val models = linkedSetOf<String>()
            val cursors = mutableSetOf<String>()
            var afterId: String? = null
            repeat(10) {
                val cursor = afterId
                val page = executeRequest(tokenDraft, { openModelsConnection(cursor) }, null, ::parseModelsPage)
                models.addAll(page.models)
                if (models.size > 1000) throw TranslationException(TranslationFailure.INCOMPLETE)
                if (!page.hasMore) return@withTimeoutOrNull models.toList()
                val next = page.lastId ?: throw TranslationException(TranslationFailure.PROVIDER)
                if (!cursors.add(next)) throw TranslationException(TranslationFailure.PROVIDER)
                afterId = next
            }
            throw TranslationException(TranslationFailure.INCOMPLETE)
        }
        coroutineContext.ensureActive()
        return answer ?: throw TranslationException(TranslationFailure.TIMEOUT)
    }

    private suspend fun <T> executeRequest(
        tokenDraft: String?,
        open: () -> HttpURLConnection,
        body: String?,
        parse: (String) -> T,
    ): T {
        val answer = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T> { continuation ->
                val activeConnection = AtomicReference<HttpURLConnection?>()
                continuation.invokeOnCancellation { runCatching { activeConnection.getAndSet(null)?.disconnect() } }
                try {
                    executor.execute {
                        var token: CharArray? = null
                        var connection: HttpURLConnection? = null
                        try {
                            if (!continuation.isActive) return@execute
                            token = tokenDraft?.trim()?.toCharArray() ?: loadToken()
                                ?: throw TranslationException(TranslationFailure.MISSING_TOKEN)
                            connection = open()
                            activeConnection.set(connection)
                            if (!continuation.isActive) return@execute
                            connection.requestMethod = if (body == null) "GET" else "POST"
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            connection.readTimeout = connection.connectTimeout
                            connection.doOutput = body != null
                            if (body != null) connection.setRequestProperty("Content-Type", "application/json")
                            connection.setRequestProperty("x-api-key", String(token))
                            connection.setRequestProperty("anthropic-version", "2023-06-01")
                            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                            val status = connection.responseCode
                            if (status !in 200..299) throw TranslationException(when (status) {
                                401, 403 -> TranslationFailure.AUTH
                                429 -> TranslationFailure.RATE_LIMIT
                                404, 405, 501 -> if (body == null) TranslationFailure.MODELS_UNSUPPORTED else TranslationFailure.HTTP
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
                            val result = parse(String(bytes, Charsets.UTF_8))
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
        const val MODELS_ENDPOINT = "https://api.minimaxi.com/anthropic/v1/models"
        // Official reference IDs, not an account-specific or live availability assertion.
        val REFERENCE_MODELS = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5")

        private fun validateToken(value: String) {
            if (value.isEmpty() || value.length > 8192 || value.any { it.isISOControl() }) {
                throw TranslationException(TranslationFailure.INVALID_REQUEST)
            }
        }

        internal data class ModelsPage(val models: List<String>, val hasMore: Boolean, val lastId: String?)

        internal fun parseModelsPage(raw: String): ModelsPage {
            try {
                val response = json.parseToJsonElement(raw).jsonObject
                if (response["error"] != null) throw TranslationException(TranslationFailure.PROVIDER)
                val data = response["data"]!!.jsonArray
                val models = data.map { element ->
                    val entry = element.jsonObject
                    val id = entry["id"]!!.jsonPrimitive
                    if (!id.isString || id.content.isBlank() || id.content.length > 128 ||
                        id.content.any { it.isWhitespace() || it.isISOControl() } ||
                        entry["type"]?.jsonPrimitive?.contentOrNull != "model"
                    ) throw TranslationException(TranslationFailure.PROVIDER)
                    id.content
                }.distinct()
                val hasMore = response["has_more"]?.jsonPrimitive?.booleanOrNull
                    ?: throw TranslationException(TranslationFailure.PROVIDER)
                val lastId = response["last_id"]?.jsonPrimitive?.contentOrNull
                if (hasMore && (models.isEmpty() || lastId != models.last())) {
                    throw TranslationException(TranslationFailure.PROVIDER)
                }
                return ModelsPage(models, hasMore, lastId)
            } catch (error: TranslationException) {
                throw error
            } catch (_: Exception) {
                throw TranslationException(TranslationFailure.PROVIDER)
            }
        }
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
