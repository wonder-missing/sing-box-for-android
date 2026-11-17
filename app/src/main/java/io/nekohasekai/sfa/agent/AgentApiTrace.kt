package io.nekohasekai.sfa.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

@Serializable
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
 * Small audit record kept in the persisted session. Full request/response
 * bodies stay in memory only; summaries survive app upgrades and restarts.
 */
@Serializable
data class ApiTraceSummary(
    val timestamp: Long,
    val version: String = "",
    val status: Int? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
)

@Serializable
private data class PersistedApiTrace(
    val version: Int = 1,
    val appVersion: String = "",
    val systemPrompt: String? = null,
    val entries: List<ApiTraceEntry> = emptyList(),
    val summaries: List<ApiTraceSummary> = emptyList(),
)

/**
 * Debug trace for Conductor. Full request/response bodies stay in a bounded
 * private file so an app update does not erase the last requests; credential
 * headers are redacted before they enter the buffer or the file.
 */
object AgentApiTrace {
    private const val MAX_ENTRIES = 20
    private const val MAX_SUMMARIES = 100
    private const val MAX_BODY_CHARS = 200_000
    private const val MAX_TOTAL_BODY_CHARS = 1_000_000
    private const val MAX_OLD_BODY_CHARS = 20_000
    private const val MAX_MARKDOWN_CHARS = 2_000_000
    private const val MAX_PERSIST_CHARS = 2_500_000

    private val lock = Any()
    private val entries = ArrayDeque<ApiTraceEntry>()
    private val summaryEntries = ArrayDeque<ApiTraceSummary>()
    private val prettyJson = Json { prettyPrint = true }
    private val persistJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var persistenceFile: File? = null

    /** True once the file bound by [bindPersistence] has been read. */
    private var persistenceLoaded = true

    /** Bumped by bind/clear so a slow load cannot apply stale entries. */
    private var persistenceGeneration = 0

    @Volatile
    private var appVersion: String = ""

    @Volatile
    private var systemPrompt: String? = null

    fun setAppVersion(version: String) {
        appVersion = version
    }

    /** The main Conductor prompt, not the separate compaction prompt. */
    fun recordSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }

    fun lastSystemPrompt(): String? = systemPrompt

    /**
     * Binds the bounded trace file written by a previous process and loads it
     * synchronously. Callers already on a worker thread can use this; startup
     * code should use [bindPersistence] and [loadPersisted] instead so the
     * read never runs on the main thread.
     */
    fun attachPersistence(file: File) {
        bindPersistence(file)
        loadPersisted()
    }

    /**
     * Binds the trace file without reading it. Records written before
     * [loadPersisted] finishes stay in memory, so the load never observes a
     * partially written file and never drops what was recorded meanwhile.
     */
    fun bindPersistence(file: File) {
        synchronized(lock) {
            persistenceFile = file
            persistenceLoaded = false
            persistenceGeneration += 1
        }
    }

    /**
     * Loads the file bound by [bindPersistence]. Call off the main thread: the
     * file can hold megabytes of request bodies. Records buffered while the
     * read was in flight are merged after the persisted entries and written
     * once, keeping newest-last order.
     */
    fun loadPersisted() {
        val (file, generation) =
            synchronized(lock) {
                val bound = persistenceFile ?: return
                bound to persistenceGeneration
            }
        val persisted =
            runCatching {
                if (!file.isFile) return@runCatching null
                persistJson.decodeFromString<PersistedApiTrace>(file.readText())
            }.getOrNull()
        synchronized(lock) {
            if (generation != persistenceGeneration || persistenceFile !== file) return
            val liveEntries = entries.toList()
            val liveSummaries = summaryEntries.toList()
            if (persisted != null) {
                entries.clear()
                entries.addAll(
                    (persisted.entries.takeLast(MAX_ENTRIES) + liveEntries).takeLast(MAX_ENTRIES),
                )
                summaryEntries.clear()
                summaryEntries.addAll(
                    (persisted.summaries.takeLast(MAX_SUMMARIES) + liveSummaries)
                        .takeLast(MAX_SUMMARIES),
                )
                if (systemPrompt == null) systemPrompt = persisted.systemPrompt
                if (appVersion.isBlank() && persisted.appVersion.isNotBlank()) {
                    appVersion = persisted.appVersion
                }
                enforceBodyBudget()
            }
            persistenceLoaded = true
            if (liveEntries.isNotEmpty() || liveSummaries.isNotEmpty()) persistLocked()
        }
    }

    /** Test helper: drop in-memory state without deleting the persistence file. */
    internal fun resetInMemoryForTest() {
        synchronized(lock) {
            entries.clear()
            summaryEntries.clear()
            systemPrompt = null
        }
    }

    fun record(entry: ApiTraceEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            enforceBodyBudget()
            summaryEntries.addLast(
                ApiTraceSummary(
                    timestamp = entry.startedAt ?: entry.timestamp,
                    version = appVersion,
                    status = entry.status,
                    durationMs = entry.durationMs,
                    error = entry.error,
                    errorCode = entry.errorCode,
                    inputTokens = entry.usage?.inputTokens,
                    outputTokens = entry.usage?.outputTokens,
                    cacheHitTokens = entry.usage?.cacheHitTokens,
                    cacheMissTokens = entry.usage?.cacheMissTokens,
                ),
            )
            while (summaryEntries.size > MAX_SUMMARIES) {
                summaryEntries.removeFirst()
            }
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            summaryEntries.clear()
            systemPrompt = null
            persistenceGeneration += 1
            persistenceLoaded = true
        }
        runCatching { persistenceFile?.delete() }
    }

    private fun persistLocked() {
        // A load in flight must not be overwritten with only the entries that
        // arrived after the bind; loadPersisted flushes them after the merge.
        if (!persistenceLoaded) return
        val file = persistenceFile ?: return
        runCatching {
            var selected = entries.toList()
            fun payload(): String =
                persistJson.encodeToString(
                    PersistedApiTrace(
                        appVersion = appVersion,
                        systemPrompt = systemPrompt,
                        entries = selected,
                        summaries = summaryEntries.toList(),
                    ),
                )
            var text = payload()
            while (text.length > MAX_PERSIST_CHARS && selected.size > 1) {
                selected = selected.drop(1)
                text = payload()
            }
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }

    /**
     * Restores the compact audit trail written by a previous process. Full
     * bodies are intentionally not restored: they are large and debug-only.
     */
    fun restoreSummaries(restored: List<ApiTraceSummary>) {
        synchronized(lock) {
            if (summaryEntries.isNotEmpty() || restored.isEmpty()) return
            summaryEntries.addAll(restored.takeLast(MAX_SUMMARIES))
        }
    }

    fun summaries(): List<ApiTraceSummary> = synchronized(lock) { summaryEntries.toList() }

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

    fun hasEntries(): Boolean =
        synchronized(lock) { entries.isNotEmpty() || summaryEntries.isNotEmpty() }

    fun lastUsage(): TokenUsage? = synchronized(lock) { entries.peekLast()?.usage }

    fun asMarkdown(lastEntries: Int? = null): String = export(lastEntries).text

    fun export(
        lastEntries: Int? = null,
        since: Long? = null,
        until: Long? = null,
    ): TraceExport {
        val allEntries = synchronized(lock) { entries.toList() }
        val allHistory = synchronized(lock) { summaryEntries.toList() }
        val earliest = allEntries.minOfOrNull { it.startedAt ?: it.timestamp }
        val filteredEntries = allEntries.filter { inRange(it.startedAt ?: it.timestamp, since, until) }
        val filteredHistory = allHistory.filter { inRange(it.timestamp, since, until) }
        val limit = lastEntries?.coerceIn(1, MAX_ENTRIES)
        val snapshot = if (limit != null) filteredEntries.takeLast(limit) else filteredEntries
        val history = if (limit != null) filteredHistory.takeLast(limit) else filteredHistory
        val missingBefore =
            since != null &&
                (
                    (earliest != null && earliest > since) ||
                        (earliest == null && allHistory.any { it.timestamp < since })
                )
        val text = render(snapshot, history, limit, since, until, earliest, missingBefore)
        return TraceExport(
            text = text,
            count = snapshot.size,
            firstTimestamp = snapshot.firstOrNull()?.let { it.startedAt ?: it.timestamp },
            lastTimestamp = snapshot.lastOrNull()?.let { it.startedAt ?: it.timestamp },
            availableFrom = earliest,
            missingBefore = missingBefore,
        )
    }

    private fun inRange(
        time: Long,
        since: Long?,
        until: Long?,
    ): Boolean = (since == null || time >= since) && (until == null || time <= until)

    private fun render(
        snapshot: List<ApiTraceEntry>,
        history: List<ApiTraceSummary>,
        limit: Int?,
        since: Long?,
        until: Long?,
        availableFrom: Long?,
        missingBefore: Boolean,
    ): String {
        val text =
            buildString {
                appendLine("# Conductor API trace")
                appendLine()
                if (since != null || until != null) {
                    appendLine(
                        "- requested range: ${since?.let(::formatTime) ?: "start"} – ${until?.let(::formatTime) ?: "now"}",
                    )
                }
                if (missingBefore) {
                    if (availableFrom != null) {
                        appendLine("- full request bodies start at: ${formatTime(availableFrom)}")
                        appendLine("- earlier entries exist only as request-history summaries")
                    } else {
                        appendLine("- full request/response bodies are not retained")
                    }
                }
                if (limit != null) appendLine("- showing last $limit requests")
                appendLine()
                if (snapshot.isEmpty() && history.isEmpty()) {
                    appendLine("No API calls in the requested range.")
                    return@buildString
                }
                appendSystemPromptSection(snapshot, history)
                if (history.isNotEmpty()) {
                    appendLine("## Request history (persisted)")
                    appendLine()
                    history.forEach { summary -> appendSummary(summary) }
                    appendLine()
                }
                if (snapshot.isEmpty()) {
                    appendLine("Full request/response bodies in the requested range are not retained; the history above is the available evidence.")
                    return@buildString
                }
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
                    appendLine(markdownFence(headersText(entry.requestHeaders), "http"))
                    appendLine()
                    appendLine("### Request body")
                    appendLine()
                    appendLine(markdownFence(prettyBody(entry.requestBody), "json"))
                    appendLine()
                    if (entry.responseHeaders.isNotEmpty()) {
                        appendLine("### Response headers")
                        appendLine()
                        appendLine(markdownFence(headersText(entry.responseHeaders), "http"))
                        appendLine()
                    }
                    entry.responseBody?.let { body ->
                        appendLine("### Response body")
                        appendLine()
                        appendLine(markdownFence(prettyBody(body), "json"))
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

    private data class RequestFacts(
        val systemPrompt: String?,
        val model: String?,
    )

    private fun requestFacts(entry: ApiTraceEntry): RequestFacts {
        val root =
            runCatching {
                Json.parseToJsonElement(entry.requestBody) as? JsonObject
            }.getOrNull() ?: return RequestFacts(null, null)
        val system =
            (root["messages"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["role"] as? JsonPrimitive)?.contentOrNull == "system" }
        val prompt = (system?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val model = (root["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return RequestFacts(prompt, model)
    }

    /**
     * Puts the main system prompt at the top of the export. The prompt is read
     * from the first retained request, not from the mutable trace field, so a
     * changed prompt shows up as a change at a concrete request number.
     */
    private fun StringBuilder.appendSystemPromptSection(
        snapshot: List<ApiTraceEntry>,
        history: List<ApiTraceSummary>,
    ) {
        if (snapshot.isEmpty() && history.isEmpty()) return
        appendLine("## System Prompt")
        appendLine()
        val facts = snapshot.map(::requestFacts)
        val firstPrompt =
            facts.firstNotNullOfOrNull { fact ->
                fact.systemPrompt?.takeIf { it != COMPACT_SYSTEM_PROMPT }
            } ?: systemPrompt?.takeIf { it.isNotBlank() }
        val model = facts.firstNotNullOfOrNull { it.model }
        val firstTime =
            snapshot.firstOrNull()?.let { it.startedAt ?: it.timestamp }
                ?: history.first().timestamp
        val lastTime =
            snapshot.lastOrNull()?.let { it.startedAt ?: it.timestamp }
                ?: history.last().timestamp
        appendLine("- app version: ${appVersion.ifBlank { "unknown" }}")
        appendLine("- model: ${model ?: "unknown"}")
        appendLine("- trace time: ${formatTime(firstTime)} – ${formatTime(lastTime)}")
        appendLine()
        if (firstPrompt == null) {
            appendLine("The full system prompt is unavailable because request bodies were cleared when the app restarted.")
            appendLine()
            return
        }
        appendLine(markdownFence(firstPrompt, "text"))
        appendLine()
        facts.forEachIndexed { index, fact ->
            if (
                index > 0 &&
                fact.systemPrompt != null &&
                fact.systemPrompt != COMPACT_SYSTEM_PROMPT &&
                fact.systemPrompt != firstPrompt
            ) {
                appendLine("- system prompt changed at request ${index + 1}")
            }
        }
        appendLine()
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

    private val imageDataUrl = Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=_\-\s]+""")

    /**
     * Image data URLs are large and not useful as raw base64 in the trace. Keep
     * the media type and a size marker so a vision request is still auditable.
     */
    internal fun redactImageDataUrls(body: String?): String? =
        body?.replace(imageDataUrl) { match ->
            val media = match.value.substringBefore(";base64,")
            "$media;base64,[redacted ${match.value.length} chars]"
        }

    fun truncateBody(body: String?): String? {
        val redacted = redactImageDataUrls(body)
        return if (redacted != null && redacted.length > MAX_BODY_CHARS) {
            redacted.take(MAX_BODY_CHARS) + "\n[body truncated]"
        } else {
            redacted
        }
    }

    private fun StringBuilder.appendSummary(summary: ApiTraceSummary) {
        append("- ${formatTime(summary.timestamp)}")
        if (summary.version.isNotBlank()) append(" v${summary.version}")
        summary.status?.let { append(" status=$it") }
        summary.durationMs?.let { append(" ${it}ms") }
        if (summary.inputTokens != null || summary.outputTokens != null) {
            append(" prompt=${summary.inputTokens ?: "?"} completion=${summary.outputTokens ?: "?"}")
            summary.cacheHitTokens?.let { append(" cache_hit=$it") }
            summary.cacheMissTokens?.let { append(" cache_miss=$it") }
        }
        summary.errorCode?.let { append(" error=$it") }
        summary.error?.takeIf { it.isNotBlank() }?.let { append(" ${it.take(120)}") }
        appendLine()
    }

    private fun headersText(headers: Map<String, String>): String =
        buildString { appendHeaders(headers) }

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
