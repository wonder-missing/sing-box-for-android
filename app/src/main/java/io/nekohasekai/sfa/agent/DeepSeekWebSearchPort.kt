package io.nekohasekai.sfa.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Executes a server-side `web_search_20250305` call through DeepSeek's
 * Anthropic-compatible Messages API using the same BYOK provider key as the
 * Conductor model.
 *
 * The API key is read from the existing encrypted provider store. It is never
 * put in the tool output, an exception message or a trace entry.
 */
class DeepSeekWebSearchPort(
    private val endpointProvider: () -> ModelEndpoint,
    private val apiKeyProvider: suspend () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val today: () -> String = { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) },
) : WebSearchPort {
    override suspend fun search(
        query: String,
        maxResults: Int,
        cancellation: ToolCancellation?,
    ): WebSearchOutcome =
        withContext(Dispatchers.IO) {
            if (cancellation?.isCancelled() == true) {
                return@withContext WebSearchOutcome(error = "cancelled")
            }
            val endpoint = endpointProvider()
            if (endpoint.providerId != ModelProviders.DEEPSEEK) {
                return@withContext WebSearchOutcome(
                    error = "web_search currently uses the DeepSeek provider; select DeepSeek in Conductor settings",
                )
            }
            val baseUrl = endpoint.baseUrl.trim()
            if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                return@withContext WebSearchOutcome(error = "DeepSeek base URL is not configured")
            }
            val model = endpoint.model.trim()
            if (model.isEmpty()) {
                return@withContext WebSearchOutcome(error = "DeepSeek model is not configured")
            }
            val apiKey = apiKeyProvider()?.trim().orEmpty()
            if (apiKey.isEmpty()) {
                return@withContext WebSearchOutcome(error = "DeepSeek API key is not configured")
            }

            val payload = buildPayload(query, model, today())
            val request =
                Request
                    .Builder()
                    .url(anthropicMessagesUrl(baseUrl))
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            val call = client.newCall(request)
            cancellation?.onCancel { call.cancel() }
            val startedAt = System.nanoTime()
            try {
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val latencyMs = elapsedMs(startedAt)
                    if (!response.isSuccessful) {
                        return@withContext WebSearchOutcome(
                            latencyMs = latencyMs,
                            error = httpError(response.code, body),
                        )
                    }
                    parseWebSearchResponse(body, maxResults)
                        .copy(latencyMs = latencyMs)
                }
            } catch (e: Exception) {
                if (cancellation?.isCancelled() == true) {
                    WebSearchOutcome(error = "cancelled")
                } else {
                    WebSearchOutcome(error = e.message ?: e.toString())
                }
            }
        }

    private fun buildPayload(
        query: String,
        model: String,
        today: String,
    ): String =
        buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                "Search the web for the following query and answer concisely with " +
                                    "source URLs. Current date: $today\n\n$query",
                            )
                        },
                    )
                },
            )
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", MAX_USES)
                        },
                    )
                },
            )
        }.toString()

    private fun httpError(
        code: Int,
        body: String,
    ): String {
        val detail =
            body
                .replace(Regex("\\s+"), " ")
                .replace(API_KEY_TOKEN, "[redacted-key]")
                .trim()
                .take(MAX_ERROR_CHARS)
        return "DeepSeek web search HTTP $code" + if (detail.isEmpty()) "" else ": $detail"
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    companion object {
        private const val MAX_USES = 3
        private const val MAX_ERROR_CHARS = 500
        private val API_KEY_TOKEN = Regex("""(?i)\b(?:sk|xai)-[A-Za-z0-9_-]{8,}\b""")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .callTimeout(70, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }
}

/**
 * DeepSeek's Anthropic-compatible endpoint lives next to the OpenAI-compatible
 * base. Accept the common base spellings so a custom DeepSeek proxy works too.
 */
internal fun anthropicMessagesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/anthropic/v1/messages") -> trimmed
        trimmed.endsWith("/anthropic") -> "$trimmed/v1/messages"
        trimmed.endsWith("/v1") -> trimmed.removeSuffix("/v1") + "/anthropic/v1/messages"
        else -> "$trimmed/anthropic/v1/messages"
    }
}

/**
 * Parsed without keeping `encrypted_content`: this is a one-shot tool call, not
 * a multi-turn Messages API conversation.
 */
internal fun parseWebSearchResponse(
    body: String,
    maxResults: Int,
): WebSearchOutcome {
    val root =
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return WebSearchOutcome(error = "DeepSeek returned a non-JSON web-search response")
    if (root["type"] == JsonPrimitive("error")) {
        val error = root["error"] as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
        return WebSearchOutcome(error = message ?: "DeepSeek returned a web-search error")
    }
    val blocks = root["content"] as? JsonArray
        ?: return WebSearchOutcome(error = "DeepSeek returned no web-search content")

    val answer = StringBuilder()
    val queries = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val sources = linkedMapOf<String, SourceBuilder>()

    blocks.forEach { element ->
        val block = element as? JsonObject ?: return@forEach
        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
            "server_tool_use" -> {
                val input = block["input"] as? JsonObject
                val query = (input?.get("query") as? JsonPrimitive)?.contentOrNull?.trim()
                if (!query.isNullOrEmpty()) queries += query
            }

            "web_search_tool_result" -> {
                when (val content = block["content"]) {
                    is JsonArray -> {
                        content.forEach { item ->
                            val error = webSearchResultError(item)
                            if (error != null) errors += error else addSource(item, sources)
                        }
                    }
                    is JsonObject -> {
                        val error = webSearchResultError(content)
                        if (error != null) errors += error else addSource(content, sources)
                    }
                    is JsonPrimitive -> {
                        if (content.isString && content.content.isNotBlank()) errors += content.content
                    }
                    else -> Unit
                }
            }

            "text" -> {
                val text = (block["text"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (!text.isNullOrEmpty()) {
                    if (answer.isNotEmpty()) answer.append("\n\n")
                    answer.append(text)
                }
                (block["citations"] as? JsonArray)?.forEach { citation ->
                    addSource(citation, sources)
                }
            }

            else -> Unit
        }
    }

    val limitedSources = sources.values.take(maxResults.coerceAtLeast(1)).map { it.build() }
    val answerText = answer.toString().trim().takeIf { it.isNotEmpty() }
    return WebSearchOutcome(
        answer = answerText,
        sources = limitedSources,
        searchQueries = queries.distinct(),
        searchErrors = errors,
    )
}

private fun webSearchResultError(element: Any?): String? {
    val obj = element as? JsonObject ?: return null
    if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "web_search_tool_result_error") return null
    val code = (obj["error_code"] as? JsonPrimitive)?.contentOrNull
    val message = (obj["error_message"] as? JsonPrimitive)?.contentOrNull
    return listOfNotNull(code, message)
        .joinToString(": ")
        .ifBlank { "web search failed" }
}

private fun addSource(
    element: Any?,
    sources: MutableMap<String, SourceBuilder>,
) {
    val obj = element as? JsonObject ?: return
    val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim()
    if (url.isNullOrEmpty() || !isAllowedHttpUrl(url)) return
    val source = sources.getOrPut(url) { SourceBuilder(url) }
    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
    if (!title.isNullOrEmpty()) source.title = title
    val pageAge = (obj["page_age"] as? JsonPrimitive)?.contentOrNull?.trim()
    if (!pageAge.isNullOrEmpty()) source.pageAge = pageAge
    val citedText = (obj["cited_text"] as? JsonPrimitive)?.contentOrNull?.trim()
    if (!citedText.isNullOrEmpty()) source.citedText = citedText
}

private class SourceBuilder(
    private val url: String,
) {
    var title: String? = null
    var pageAge: String? = null
    var citedText: String? = null

    fun build(): WebSearchSource =
        WebSearchSource(
            url = url,
            title = title,
            pageAge = pageAge,
            citedText = citedText,
        )
}
