package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class ApiTraceEntry(
    val timestamp: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val error: String?,
)

/**
 * In-memory debug trace for the current Conductor session. Request/response
 * bodies are kept for analysis, but credential headers are redacted before
 * they enter this buffer and before anything is copied to the clipboard.
 */
object AgentApiTrace {
    private const val MAX_ENTRIES = 20
    private const val MAX_BODY_CHARS = 200_000
    private const val MAX_MARKDOWN_CHARS = 2_000_000

    private val lock = Any()
    private val entries = ArrayDeque<ApiTraceEntry>()
    private val prettyJson = Json { prettyPrint = true }

    fun record(entry: ApiTraceEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun asMarkdown(): String {
        val snapshot = synchronized(lock) { entries.toList() }
        if (snapshot.isEmpty()) return "No API calls recorded in this session."
        val text =
            buildString {
                appendLine("# Conductor API trace")
                appendLine()
                snapshot.forEachIndexed { index, entry ->
                    appendLine("## ${index + 1}. ${entry.method} ${entry.url}")
                    appendLine()
                    appendLine("- time: ${formatTime(entry.timestamp)}")
                    entry.status?.let { appendLine("- status: $it") }
                    entry.error?.let { appendLine("- error: $it") }
                    entry.responseBody?.let { body -> usageLine(body)?.let { appendLine("- usage: $it") } }
                    appendLine()
                    appendLine("### Request headers")
                    appendLine()
                    appendLine("~~~http")
                    appendHeaders(entry.requestHeaders)
                    appendLine("~~~")
                    appendLine()
                    appendLine("### Request body")
                    appendLine()
                    appendLine("~~~json")
                    appendLine(prettyBody(entry.requestBody))
                    appendLine("~~~")
                    appendLine()
                    if (entry.responseHeaders.isNotEmpty()) {
                        appendLine("### Response headers")
                        appendLine()
                        appendLine("~~~http")
                        appendHeaders(entry.responseHeaders)
                        appendLine("~~~")
                        appendLine()
                    }
                    entry.responseBody?.let { body ->
                        appendLine("### Response body")
                        appendLine()
                        appendLine("~~~json")
                        appendLine(prettyBody(body))
                        appendLine("~~~")
                        appendLine()
                    }
                }
            }.trim()
        return if (text.length > MAX_MARKDOWN_CHARS) {
            text.take(MAX_MARKDOWN_CHARS) + "\n\n[trace truncated]"
        } else {
            text
        }
    }

    fun sanitizeHeaders(headers: Map<String, List<String>>?): Map<String, String> =
        headers
            .orEmpty()
            .mapNotNull { (name, values) ->
                val headerName = name ?: return@mapNotNull null
                val value =
                    if (isSensitiveHeader(headerName)) {
                        "***"
                    } else {
                        values.joinToString(", ")
                    }
                headerName to value
            }.toMap()

    fun truncateBody(body: String?): String? =
        if (body != null && body.length > MAX_BODY_CHARS) {
            body.take(MAX_BODY_CHARS) + "\n[body truncated]"
        } else {
            body
        }

    private fun StringBuilder.appendHeaders(headers: Map<String, String>) {
        headers
            .toSortedMap { left, right -> left.compareTo(right, ignoreCase = true) }
            .forEach { (name, value) -> appendLine("$name: $value") }
    }

    private fun prettyBody(body: String): String =
        runCatching {
            val element = Json.parseToJsonElement(body) as JsonElement
            prettyJson.encodeToString(JsonElement.serializer(), element)
        }.getOrElse { body }

    private fun usageLine(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val usage = root["usage"] as? JsonObject ?: return null
        val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: return null
        return buildString {
            append("prompt=$prompt")
            (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull?.let { append(" completion=$it") }
            (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull?.let { append(" cache_hit=$it") }
            (usage["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull?.let { append(" cache_miss=$it") }
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    private fun isSensitiveHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower == "authorization" ||
            lower == "proxy-authorization" ||
            lower == "cookie" ||
            lower == "set-cookie" ||
            lower.contains("token") ||
            lower.contains("secret") ||
            lower.contains("api-key") ||
            lower.contains("apikey")
    }
}
