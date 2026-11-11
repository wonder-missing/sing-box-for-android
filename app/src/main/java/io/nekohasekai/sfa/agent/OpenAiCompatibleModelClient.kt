package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenAiCompatibleModelClient(
    private val endpointProvider: () -> ModelEndpoint,
) : AgentModelClient,
    AgentStreamingModelClient {
    private val okHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var cancelled = false

    private fun currentEndpoint(): ModelEndpoint {
        val endpoint = endpointProvider()
        val baseUrl = endpoint.baseUrl.trim()
        if (baseUrl.isBlank()) {
            throw AgentModelException(
                AgentTurnErrorCode.INVALID_REQUEST,
                "${endpoint.providerLabel} base URL is not configured.",
            )
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            throw AgentModelException(
                AgentTurnErrorCode.INVALID_REQUEST,
                "${endpoint.providerLabel} base URL must start with http:// or https://.",
            )
        }
        val model = endpoint.model.trim()
        if (model.isBlank()) {
            throw AgentModelException(
                AgentTurnErrorCode.INVALID_REQUEST,
                "${endpoint.providerLabel} model is not configured.",
            )
        }
        runCatching { URL(chatCompletionsUrl(baseUrl)) }
            .onFailure {
                throw AgentModelException(
                    AgentTurnErrorCode.INVALID_REQUEST,
                    "${endpoint.providerLabel} base URL is invalid.",
                )
            }
        return endpoint.copy(baseUrl = baseUrl, model = model)
    }

    override fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    override suspend fun complete(
        request: AgentModelRequest,
        apiKey: String,
    ): AgentModelResponse =
        withContext(Dispatchers.IO) {
            val endpoint = currentEndpoint()
            if (apiKey.isBlank()) {
                throw AgentModelException(
                    AgentTurnErrorCode.NO_API_KEY,
                    "${endpoint.providerLabel} API key is not configured.",
                )
            }
            val payload = buildPayload(request, endpoint, stream = false)
            val (status, body) =
                post(
                    url = chatCompletionsUrl(endpoint.baseUrl),
                    body = payload,
                    apiKey = apiKey,
                    timeoutMs = request.timeoutMs,
                    providerLabel = endpoint.providerLabel,
                )
            if (status !in 200..299) {
                throw httpError(status, body, endpoint.providerLabel)
            }
            parseResponse(body, endpoint.providerLabel)
        }

    override fun stream(
        request: AgentModelRequest,
        apiKey: String,
    ): Flow<AgentModelChunk> =
        flow {
            val endpoint = currentEndpoint()
            if (apiKey.isBlank()) {
                throw AgentModelException(
                    AgentTurnErrorCode.NO_API_KEY,
                    "${endpoint.providerLabel} API key is not configured.",
                )
            }
            cancelled = false
            val payload = buildPayload(request, endpoint, stream = true)
            val httpRequest =
                Request
                    .Builder()
                    .url(chatCompletionsUrl(endpoint.baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
            val client =
                okHttp
                    .newBuilder()
                    .readTimeout(request.timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
                    .build()
            val call = client.newCall(httpRequest)
            activeCall = call
            if (cancelled) call.cancel()
            val requestHeaders = AgentApiTrace.sanitizeHeaders(httpRequest.headers.toMultimap())
            var status: Int? = null
            var responseHeaders: Map<String, String> = emptyMap()
            val responseBody = StringBuilder()
            var traceError: String? = null
            try {
                call.execute().use { response ->
                    status = response.code
                    responseHeaders = AgentApiTrace.sanitizeHeaders(response.headers.toMultimap())
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty().take(MAX_ERROR_BYTES)
                        traceError = "HTTP ${response.code}"
                        throw httpError(response.code, body, endpoint.providerLabel)
                    }
                    val source =
                        response.body?.source()
                            ?: throw AgentModelException(
                                AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                                "${endpoint.providerLabel} returned an empty stream.",
                                retryable = true,
                            )
                    var finish: FinishReason? = null
                    var usage: TokenUsage? = null
                    while (true) {
                        if (cancelled) throw CancellationException("cancelled")
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        responseBody.append(data).append('\n')
                        val chunk = parseStreamChunk(data) ?: continue
                        finish = chunk.finishReason ?: finish
                        usage = chunk.usage ?: usage
                        chunk.reasoning?.let { emit(AgentModelChunk.ReasoningDelta(it)) }
                        chunk.content?.let { emit(AgentModelChunk.ContentDelta(it)) }
                        chunk.toolCalls.forEach { emit(it) }
                    }
                    emit(AgentModelChunk.Completed(finish ?: FinishReason.UNKNOWN, usage))
                }
            } catch (e: CancellationException) {
                traceError = "cancelled"
                throw e
            } catch (e: AgentModelException) {
                traceError = e.message
                throw e
            } catch (e: IOException) {
                if (cancelled) {
                    traceError = "cancelled"
                    throw CancellationException("cancelled")
                }
                traceError = e.message ?: "network error"
                throw modelTransportException(e, endpoint.providerLabel, endpoint.baseUrl)
            } finally {
                if (activeCall === call) activeCall = null
                cancelled = false
                AgentApiTrace.record(
                    ApiTraceEntry(
                        timestamp = System.currentTimeMillis(),
                        method = "POST",
                        url = httpRequest.url.toString(),
                        requestHeaders = requestHeaders,
                        requestBody = AgentApiTrace.truncateBody(payload) ?: payload,
                        status = status,
                        responseHeaders = responseHeaders,
                        responseBody = AgentApiTrace.truncateBody(responseBody.toString()),
                        error = traceError,
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    suspend fun testConnection(apiKey: String): String {
        val endpoint = currentEndpoint()
        complete(
            AgentModelRequest(
                messages = listOf(AgentMessage.User("ping")),
                tools = emptyList(),
                maxOutputTokens = 1,
                timeoutMs = 15_000,
            ),
            apiKey,
        )
        return "Connection OK (${endpoint.providerLabel} · ${endpoint.model})"
    }

    private fun buildPayload(
        request: AgentModelRequest,
        endpoint: ModelEndpoint,
        stream: Boolean,
    ): String =
        buildJsonObject {
            put("model", endpoint.model)
            put("messages", buildMessages(request.messages))
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { add(it.toOpenAiJson()) }
                    },
                )
                put(
                    "tool_choice",
                    when (request.toolChoice) {
                        AgentToolChoice.AUTO -> "auto"
                        AgentToolChoice.NONE -> "none"
                    },
                )
            }
            put("temperature", request.temperature)
            put("max_tokens", request.maxOutputTokens)
            put("stream", stream)
        }.toString()

    private fun buildMessages(messages: List<AgentMessage>): JsonArray =
        buildJsonArray {
            messages.forEach { message ->
                when (message) {
                    is AgentMessage.System ->
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", message.content)
                            },
                        )

                    is AgentMessage.User ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", message.content)
                            },
                        )

                    is AgentMessage.Assistant ->
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                if (message.content == null) {
                                    put("content", JsonNull)
                                } else {
                                    put("content", message.content)
                                }
                                if (message.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            message.toolCalls.forEach { call ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", call.id)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", call.name)
                                                                put("arguments", call.argumentsJson)
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )

                    is AgentMessage.Tool ->
                        add(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", message.toolCallId)
                                put("content", message.content)
                            },
                        )
                }
            }
        }

    private data class StreamChunk(
        val content: String?,
        val reasoning: String?,
        val toolCalls: List<AgentModelChunk.ToolCallDelta>,
        val finishReason: FinishReason?,
        val usage: TokenUsage?,
    )

    private fun parseStreamChunk(data: String): StreamChunk? {
        val root = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val reasoning =
            (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: (delta?.get("reasoning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val toolCalls =
            (delta?.get("tool_calls") as? JsonArray)
                ?.mapNotNull { parseToolCallDelta(it as? JsonObject) }
                .orEmpty()
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        return StreamChunk(
            content = content,
            reasoning = reasoning,
            toolCalls = toolCalls,
            finishReason = finish?.let { finishReason(it) },
            usage = parseUsage(root["usage"] as? JsonObject),
        )
    }

    private fun parseToolCallDelta(element: JsonObject?): AgentModelChunk.ToolCallDelta? {
        if (element == null) return null
        val function = element["function"] as? JsonObject
        val index = (element["index"] as? JsonPrimitive)?.intOrNull ?: 0
        val id = (element["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val arguments =
            when (val raw = function?.get("arguments")) {
                is JsonPrimitive -> raw.contentOrNull
                is JsonObject -> raw.toString()
                else -> null
            }
        if (id == null && name == null && arguments == null) return null
        return AgentModelChunk.ToolCallDelta(index, id, name, arguments)
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? =
        usage?.let {
            val input = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull
            val details = it["prompt_tokens_details"] as? JsonObject
            val cacheHit =
                (it["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
                    ?: (details?.get("cached_tokens") as? JsonPrimitive)?.intOrNull
            val cacheMiss =
                (it["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull
                    ?: if (cacheHit != null && input != null) (input - cacheHit).coerceAtLeast(0) else null
            TokenUsage(
                inputTokens = input,
                outputTokens = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull,
                cacheHitTokens = cacheHit,
                cacheMissTokens = cacheMiss,
            )
        }

    private fun parseResponse(
        body: String,
        providerLabel: String,
    ): AgentModelResponse {
        val root =
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw AgentModelException(
                    AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                    "$providerLabel returned a non-JSON response.",
                    retryable = true,
                )
        val choice =
            (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw AgentModelException(
                    AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                    "$providerLabel returned no choices.",
                    retryable = true,
                )
        val message = choice["message"] as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        val toolCalls =
            (message?.get("tool_calls") as? JsonArray)
                ?.mapNotNull { element -> parseToolCall(element as? JsonObject) }
                .orEmpty()
        val finishReason = finishReason((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull)
        val usage = parseUsage(root["usage"] as? JsonObject)
        if (content.isNullOrBlank() && toolCalls.isEmpty()) {
            throw AgentModelException(
                AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                "$providerLabel returned an empty message.",
                retryable = true,
            )
        }
        return AgentModelResponse(
            content = content?.takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private fun parseToolCall(element: JsonObject?): ToolCall? {
        if (element == null) return null
        val function = element["function"] as? JsonObject ?: return null
        val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val arguments =
            when (val raw = function["arguments"]) {
                is JsonPrimitive -> raw.contentOrNull ?: "{}"
                is JsonObject -> raw.toString()
                else -> "{}"
            }
        val id =
            (element["id"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: "call_${UUID.randomUUID()}"
        return ToolCall(id = id, name = name, argumentsJson = arguments)
    }

    private fun finishReason(raw: String?): FinishReason =
        when (raw) {
            "stop" -> FinishReason.STOP
            "tool_calls" -> FinishReason.TOOL_CALLS
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.UNKNOWN
        }

    private fun post(
        url: String,
        body: String,
        apiKey: String,
        timeoutMs: Long,
        providerLabel: String,
    ): Pair<Int, String> {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt()
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        val requestHeaders = AgentApiTrace.sanitizeHeaders(connection.requestProperties)
        var status: Int? = null
        var responseHeaders: Map<String, String> = emptyMap()
        var responseBody: String? = null
        var error: String? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            status = connection.responseCode
            responseHeaders = AgentApiTrace.sanitizeHeaders(connection.headerFields)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response =
                stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RESPONSE_BYTES) {
                            throw AgentModelException(
                                AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                                "The model response is too large.",
                                retryable = false,
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
            responseBody = AgentApiTrace.truncateBody(response)
            status to response.take(if (status in 200..299) MAX_RESPONSE_BYTES else MAX_ERROR_BYTES)
        } catch (e: AgentModelException) {
            error = e.message
            throw e
        } catch (e: IOException) {
            error = e.message ?: "network error"
            throw modelTransportException(e, providerLabel, url)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            throw e
        } finally {
            AgentApiTrace.record(
                ApiTraceEntry(
                    timestamp = System.currentTimeMillis(),
                    method = "POST",
                    url = connection.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = AgentApiTrace.truncateBody(body) ?: body,
                    status = status,
                    responseHeaders = responseHeaders,
                    responseBody = responseBody,
                    error = error,
                ),
            )
            connection.disconnect()
        }
    }

    private fun httpError(
        status: Int,
        body: String,
        providerLabel: String,
    ): AgentModelException {
        val code =
            when (status) {
                400 -> AgentTurnErrorCode.INVALID_REQUEST
                401, 403 -> AgentTurnErrorCode.AUTH_FAILED
                402 -> AgentTurnErrorCode.INSUFFICIENT_BALANCE
                404 -> AgentTurnErrorCode.MODEL_NOT_FOUND
                408 -> AgentTurnErrorCode.MODEL_TIMEOUT
                409 -> AgentTurnErrorCode.CONFLICT
                429 -> AgentTurnErrorCode.RATE_LIMITED
                in 500..599 -> AgentTurnErrorCode.MODEL_UNAVAILABLE
                else -> AgentTurnErrorCode.UNKNOWN
            }
        val retryable = status == 408 || status == 429 || status in 500..599
        val detail =
            runCatching {
                val root = Json.parseToJsonElement(body) as? JsonObject
                val error = root?.get("error") as? JsonObject
                (error?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (root?.get("error") as? JsonPrimitive)?.contentOrNull
                    ?: root?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        val message = detail?.takeIf { it.isNotBlank() } ?: "$providerLabel HTTP $status"
        return AgentModelException(
            code,
            AgentSanitizer.sanitizeText(message),
            retryable = retryable,
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 4 * 1024
    }
}
