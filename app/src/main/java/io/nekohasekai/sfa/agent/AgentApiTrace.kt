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
    val errorCode: String? = null,
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val usage: TokenUsage? = null,
    val finishReason: FinishReason? = null,
)

/**
 * In-memory debug trace for the current Conductor session. Request/response
 * bodies are kept for analysis, but credential headers are redacted before
 * they enter this buffer and before anything is copied to the clipboard.
 */
object AgentApiTrace {
    private const val MAX_ENTRIES = 20
    private const val MAX_BODY_CHARS = 200_000
    private const val MAX_TOTAL_BODY_CHARS = 1_000_000
    private const val MAX_OLD_BODY_CHARS = 20_000
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
            enforceBodyBudget()
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * Caps the total request/response text retained for debugging. The newest
     * entry keeps its full bodies; older entries are shortened first, then
     * dropped if the trace is still over budget. Structured fields (status,
     * usage, duration) survive shortening.
     */
    private fun enforceBodyBudget() {
        var total = entries.sumOf { it.bodyChars() }
        if (total <= MAX_TOTAL_BODY_CHARS) return
        val kept = entries.toMutableList()
        for (index in 0 until kept.lastIndex) {
            if (total <= MAX_TOTAL_BODY_CHARS) break
            val entry = kept[index]
            val shortened =
                entry.copy(
                    requestBody = shortenForMemory(entry.requestBody),
                    responseBody = entry.responseBody?.let { shortenForMemory(it) },
                )
            total -= entry.bodyChars() - shortened.bodyChars()
            kept[index] = shortened
        }
        while (total > MAX_TOTAL_BODY_CHARS && kept.size > 1) {
            total -= kept.removeAt(0).bodyChars()
        }
        entries.clear()
        entries.addAll(kept)
    }

    private fun shortenForMemory(body: String): String =
        if (body.length <= MAX_OLD_BODY_CHARS) {
            body
        } else {
            body.take(MAX_OLD_BODY_CHARS) + "\n[body truncated for memory]"
        }

    internal fun retainedBodyChars(): Int = synchronized(lock) { entries.sumOf { it.bodyChars() } }

    internal fun retainedEntryCount(): Int = synchronized(lock) { entries.size }

    private fun ApiTraceEntry.bodyChars(): Int = requestBody.length + (responseBody?.length ?: 0)

    fun hasEntries(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    fun lastUsage(): TokenUsage? = synchronized(lock) { entries.peekLast()?.usage }

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
                    entry.startedAt?.let { appendLine("- started: ${formatTime(it)}") }
                    entry.durationMs?.let { appendLine("- duration: ${it}ms") }
                    entry.status?.let { appendLine("- status: $it") }
                    entry.finishReason?.let { appendLine("- finish: ${it.name.lowercase(Locale.US)}") }
                    entry.error?.let { appendLine("- error: $it") }
                    entry.errorCode?.let { appendLine("- error code: $it") }
                    requestId(entry.responseHeaders)?.let { appendLine("- request id: $it") }
                    entry.usage?.let { appendLine("- usage: ${formatUsage(it)}") }
                    if (entry.usage == null) {
                        entry.responseBody?.let { body -> usageLine(body)?.let { appendLine("- usage: $it") } }
                    }
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
        return formatUsage(
            TokenUsage(
                inputTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
                outputTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull,
                cacheHitTokens = (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull,
                cacheMissTokens = (usage["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull,
            ),
        )
    }

    private fun formatUsage(usage: TokenUsage): String =
        buildString {
            append("prompt=${usage.inputTokens ?: "?"}")
            append(" completion=${usage.outputTokens ?: "?"}")
            usage.cacheHitTokens?.let { append(" cache_hit=$it") }
            usage.cacheMissTokens?.let { append(" cache_miss=$it") }
        }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    private fun requestId(headers: Map<String, String>): String? =
        headers.entries
            .firstOrNull { (name, _) ->
                name.equals("x-request-id", ignoreCase = true) ||
                    name.equals("request-id", ignoreCase = true)
            }?.value
            ?.takeIf { it.isNotBlank() }
            ?.take(160)

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
