package io.nekohasekai.sfa.agent

import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DeepSeekModelClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val modelProvider: () -> String = { DEFAULT_MODEL },
) : AgentModelClient {
    private fun currentModel(): String = modelProvider().trim().ifBlank { DEFAULT_MODEL }

    override suspend fun complete(
        request: AgentModelRequest,
        apiKey: String,
    ): AgentModelResponse =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw AgentModelException(AgentTurnErrorCode.NO_API_KEY, "DeepSeek API key is not configured.")
            }
            val payload =
                buildJsonObject {
                    put("model", currentModel())
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
                    put("stream", false)
                }
            val (status, body) = post("/chat/completions", payload.toString(), apiKey, request.timeoutMs)
            if (status !in 200..299) {
                throw httpError(status, body)
            }
            parseResponse(body)
        }

    suspend fun testConnection(apiKey: String): String {
        complete(
            AgentModelRequest(
                messages = listOf(AgentMessage.User("ping")),
                tools = emptyList(),
                maxOutputTokens = 1,
                timeoutMs = 15_000,
            ),
            apiKey,
        )
        return "Connection OK (${currentModel()})"
    }

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

    private fun parseResponse(body: String): AgentModelResponse {
        val root =
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw AgentModelException(
                    AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                    "DeepSeek returned a non-JSON response.",
                    retryable = true,
                )
        val choice =
            (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw AgentModelException(
                    AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                    "DeepSeek returned no choices.",
                    retryable = true,
                )
        val message = choice["message"] as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        val toolCalls =
            (message?.get("tool_calls") as? JsonArray)
                ?.mapNotNull { element -> parseToolCall(element as? JsonObject) }
                .orEmpty()
        val finishReason = finishReason((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull)
        val usage =
            (root["usage"] as? JsonObject)?.let {
                TokenUsage(
                    inputTokens = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
                    outputTokens = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull,
                    cacheHitTokens = (it["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull,
                    cacheMissTokens = (it["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull,
                )
            }
        if (content.isNullOrBlank() && toolCalls.isEmpty()) {
            throw AgentModelException(
                AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                "DeepSeek returned an empty message.",
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
        path: String,
        body: String,
        apiKey: String,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val connection =
            (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
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
                                "DeepSeek response is too large.",
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
            throw AgentModelException(
                AgentTurnErrorCode.MODEL_UNAVAILABLE,
                AgentSanitizer.sanitizeText(e.message ?: "network error"),
                retryable = true,
            )
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
                    ?: root?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        val message = detail?.takeIf { it.isNotBlank() } ?: "DeepSeek HTTP $status"
        return AgentModelException(
            code,
            AgentSanitizer.sanitizeText(message),
            retryable = retryable,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 4 * 1024
    }
}
