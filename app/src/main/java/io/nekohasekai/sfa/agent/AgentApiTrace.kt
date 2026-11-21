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
    /** Original request size before the bounded trace copied it. */
    val requestChars: Int = 0,
    /** True when [requestBody] is not the full request. */
    val requestTruncated: Boolean = false,
    /** Last part of a long request; keeps the tail recitation auditable. */
    val requestTail: String? = null,
    /** Original assembled response size before the bounded trace copied it. */
    val responseChars: Int = 0,
    /** True when [responseBody] is not the full assembled response. */
    val responseTruncated: Boolean = false,
    /** True when [responseBody] was assembled from streaming chunks. */
    val responseNormalized: Boolean = false,
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
    val version: Int = 2,
    val appVersion: String = "",
    val systemPrompt: String? = null,
    val entries: List<ApiTraceEntry> = emptyList(),
    val summaries: List<ApiTraceSummary> = emptyList(),
)

/**
 * A complete snapshot for one Conductor turn. The live trace keeps only the
 * last [MAX_ENTRIES] calls; the snapshot is the authoritative record for a
 * real-device experiment, including calls that were evicted from the live
 * trace later in the same turn.
 */
@Serializable
private data class PersistedTurnTrace(
    val version: Int = 2,
    val appVersion: String = "",
    val systemPrompt: String? = null,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val entries: List<ApiTraceEntry> = emptyList(),
    val summaries: List<ApiTraceSummary> = emptyList(),
    val droppedEntries: Int = 0,
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
    private const val REQUEST_TAIL_CHARS = 8_000
    private const val MAX_MARKDOWN_CHARS = 2_000_000
    private const val MAX_PERSIST_CHARS = 2_500_000
    private const val MAX_TURN_ENTRIES = 120
    private const val MAX_TURN_BODY_CHARS = 4_000_000
    private const val MAX_TURN_SNAPSHOTS = 3
    private const val MAX_TURN_SNAPSHOT_BYTES = 12_000_000L
    private const val SNAPSHOT_DIR = "trace-turns"

    private val lock = Any()
    private val entries = ArrayDeque<ApiTraceEntry>()
    private val summaryEntries = ArrayDeque<ApiTraceSummary>()
    private val turnEntries = ArrayDeque<ApiTraceEntry>()
    private var turnActive = false
    private var turnStartedAt = 0L
    private var turnDroppedEntries = 0
    private var lastTurnSnapshot: File? = null
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
            turnEntries.clear()
            turnActive = false
            turnStartedAt = 0L
            turnDroppedEntries = 0
            lastTurnSnapshot = null
            systemPrompt = null
        }
    }

    fun generation(): Int = synchronized(lock) { persistenceGeneration }

    fun record(
        entry: ApiTraceEntry,
        generation: Int? = null,
    ) {
        val prepared = prepareEntry(entry)
        synchronized(lock) {
            if (generation != null && generation != persistenceGeneration) return
            entries.addLast(prepared)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            enforceBodyBudget()
            if (turnActive) {
                turnEntries.addLast(prepared)
                while (turnEntries.size > MAX_TURN_ENTRIES) {
                    turnEntries.removeFirst()
                    turnDroppedEntries += 1
                }
                enforceTurnBudgetLocked()
            }
            summaryEntries.addLast(
                ApiTraceSummary(
                    timestamp = prepared.startedAt ?: prepared.timestamp,
                    version = appVersion,
                    status = prepared.status,
                    durationMs = prepared.durationMs,
                    error = prepared.error,
                    errorCode = prepared.errorCode,
                    inputTokens = prepared.usage?.inputTokens,
                    outputTokens = prepared.usage?.outputTokens,
                    cacheHitTokens = prepared.usage?.cacheHitTokens,
                    cacheMissTokens = prepared.usage?.cacheMissTokens,
                ),
            )
            while (summaryEntries.size > MAX_SUMMARIES) {
                summaryEntries.removeFirst()
            }
            persistLocked()
        }
    }

    private fun prepareEntry(entry: ApiTraceEntry): ApiTraceEntry {
        val request = prepareRequest(entry.requestBody)
        val response = AgentTraceResponse.prepareForTrace(entry.responseBody, MAX_BODY_CHARS)
        return entry.copy(
            requestBody = request.text,
            requestChars = request.originalChars,
            requestTruncated = request.truncated,
            requestTail = request.tail,
            responseBody = response.text.ifEmpty { null },
            responseChars = response.originalChars,
            responseTruncated = response.truncated,
            responseNormalized = response.normalized,
        )
    }

    private data class PreparedRequest(
        val text: String,
        val tail: String?,
        val truncated: Boolean,
        val originalChars: Int,
    )

    private fun prepareRequest(body: String): PreparedRequest {
        val redacted = redactImageDataUrls(body) ?: body
        val truncated = redacted.length > MAX_BODY_CHARS
        val text = if (truncated) redacted.take(MAX_BODY_CHARS) + "\n[body truncated]" else redacted
        val tail =
            if (redacted.length > MAX_OLD_BODY_CHARS) {
                redacted.takeLast(REQUEST_TAIL_CHARS)
            } else {
                null
            }
        return PreparedRequest(text, tail, truncated, redacted.length)
    }

    /**
     * Marks the start of a Conductor turn. Entries recorded until [endTurn]
     * are kept separately from the live 20-entry window and written to a full
     * turn snapshot at the end. Calling this outside a turn is harmless.
     */
    fun beginTurn() {
        synchronized(lock) {
            turnEntries.clear()
            turnDroppedEntries = 0
            turnStartedAt = System.currentTimeMillis()
            turnActive = true
        }
    }

    /**
     * Writes the complete turn trace and prunes old snapshots. Returns the
     * snapshot file, or null when no turn is active or no call was recorded.
     */
    fun endTurn(): File? =
        synchronized(lock) {
            if (!turnActive) return null
            turnActive = false
            val entries = turnEntries.toList()
            val dropped = turnDroppedEntries
            turnEntries.clear()
            turnDroppedEntries = 0
            val started = turnStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            turnStartedAt = 0L
            if (entries.isEmpty()) return null
            val traceFile = persistenceFile ?: return null
            val dir = File(traceFile.parentFile ?: File("."), SNAPSHOT_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val ended = System.currentTimeMillis()
            val target = File(dir, "trace-turn-${fileTime(started)}-${started % 1000}.json")
            val payload =
                PersistedTurnTrace(
                    appVersion = appVersion,
                    systemPrompt = systemPrompt,
                    startedAt = started,
                    endedAt = ended,
                    entries = entries,
                    summaries = summaryEntries.filter { it.timestamp >= started },
                    droppedEntries = dropped,
                )
            runCatching {
                target.writeText(persistJson.encodeToString(payload))
                lastTurnSnapshot = target
                pruneSnapshotsLocked(dir)
                target
            }.getOrNull()
        }

    fun latestTurnSnapshot(): File? =
        synchronized(lock) {
            lastTurnSnapshot?.takeIf { it.isFile }
                ?: snapshotDir()
                    ?.listFiles { file -> file.isFile && file.name.startsWith("trace-turn-") && file.name.endsWith(".json") }
                    ?.maxByOrNull { it.lastModified() }
        }

    internal fun latestTurnSnapshotName(): String? = latestTurnSnapshot()?.name

    fun clear() {
        synchronized(lock) {
            entries.clear()
            summaryEntries.clear()
            turnEntries.clear()
            turnActive = false
            turnStartedAt = 0L
            turnDroppedEntries = 0
            lastTurnSnapshot = null
            systemPrompt = null
            persistenceGeneration += 1
            persistenceLoaded = true
        }
        runCatching { persistenceFile?.delete() }
        runCatching { snapshotDir()?.deleteRecursively() }
    }

    private fun snapshotDir(): File? =
        persistenceFile?.parentFile?.let { File(it, SNAPSHOT_DIR) }

    private fun pruneSnapshotsLocked(dir: File) {
        val files =
            dir
                .listFiles { file -> file.isFile && file.name.startsWith("trace-turn-") && file.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() }
                ?: return
        var count = files.size
        var total = files.sumOf { it.length() }
        files.forEach { file ->
            if (count > MAX_TURN_SNAPSHOTS || total > MAX_TURN_SNAPSHOT_BYTES) {
                if (file.delete()) {
                    count -= 1
                    total -= file.length()
                }
            }
        }
        if (lastTurnSnapshot?.exists() != true) lastTurnSnapshot = null
    }

    private fun fileTime(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))

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
            val shortened = shortenEntryForMemory(entry)
            total -= entry.bodyChars() - shortened.bodyChars()
            kept[index] = shortened
        }
        while (total > MAX_TOTAL_BODY_CHARS && kept.size > 1) {
            total -= kept.removeAt(0).bodyChars()
        }
        entries.clear()
        entries.addAll(kept)
    }

    private fun enforceTurnBudgetLocked() {
        var total = turnEntries.sumOf { it.bodyChars() }
        if (total <= MAX_TURN_BODY_CHARS) return
        val kept = turnEntries.toMutableList()
        for (index in 0 until kept.lastIndex) {
            if (total <= MAX_TURN_BODY_CHARS) break
            val entry = kept[index]
            val shortened = shortenEntryForMemory(entry)
            total -= entry.bodyChars() - shortened.bodyChars()
            kept[index] = shortened
        }
        while (total > MAX_TURN_BODY_CHARS && kept.size > 1) {
            total -= kept.removeAt(0).bodyChars()
            turnDroppedEntries += 1
        }
        turnEntries.clear()
        turnEntries.addAll(kept)
    }

    private fun shortenEntryForMemory(entry: ApiTraceEntry): ApiTraceEntry {
        val request = shortenText(entry.requestBody)
        val response =
            entry.responseBody?.let { body ->
                val prepared = AgentTraceResponse.prepareForTrace(body, MAX_OLD_BODY_CHARS)
                prepared.text to (entry.responseTruncated || prepared.truncated)
            }
        return entry.copy(
            requestBody = request.first,
            requestTruncated = entry.requestTruncated || request.second,
            responseBody = response?.first,
            responseTruncated = response?.second ?: entry.responseTruncated,
        )
    }

    /** Head-truncates non-structured text; structured responses use field caps. */
    private fun shortenText(body: String): Pair<String, Boolean> =
        if (body.length <= MAX_OLD_BODY_CHARS) {
            body to false
        } else {
            (body.take(MAX_OLD_BODY_CHARS) + "\n[body truncated for memory]") to true
        }

    internal fun retainedBodyChars(): Int = synchronized(lock) { entries.sumOf { it.bodyChars() } }

    internal fun retainedEntryCount(): Int = synchronized(lock) { entries.size }

    internal fun retainedTurnEntryCount(): Int = synchronized(lock) { turnEntries.size }

    private fun ApiTraceEntry.bodyChars(): Int =
        requestBody.length + (requestTail?.length ?: 0) + (responseBody?.length ?: 0)

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
        val totalCount = maxOf(filteredHistory.size, filteredEntries.size)
        val droppedCount = (totalCount - snapshot.size).coerceAtLeast(0)
        val missingBefore =
            droppedCount > 0 ||
                (
                    since != null &&
                        (
                            (earliest != null && earliest > since) ||
                                (earliest == null && allHistory.any { it.timestamp < since })
                        )
                )
        val text =
            render(
                snapshot = snapshot,
                history = history,
                limit = limit,
                since = since,
                until = until,
                availableFrom = earliest,
                missingBefore = missingBefore,
                totalCount = totalCount,
                droppedCount = droppedCount,
                turnSnapshot = latestTurnSnapshotName(),
            )
        return TraceExport(
            text = text,
            count = snapshot.size,
            firstTimestamp = snapshot.firstOrNull()?.let { it.startedAt ?: it.timestamp },
            lastTimestamp = snapshot.lastOrNull()?.let { it.startedAt ?: it.timestamp },
            availableFrom = earliest,
            missingBefore = missingBefore,
            totalCount = totalCount,
            droppedCount = droppedCount,
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
        totalCount: Int,
        droppedCount: Int,
        turnSnapshot: String?,
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
                appendLine("- retained request bodies: ${snapshot.size} of $totalCount")
                if (missingBefore) {
                    if (availableFrom != null) {
                        appendLine("- full request bodies start at: ${formatTime(availableFrom)}")
                        if (droppedCount > 0) {
                            appendLine("- $droppedCount earlier request(s) exist only as request-history summaries")
                        } else {
                            appendLine("- earlier requests are outside the requested range")
                        }
                    } else {
                        appendLine("- full request/response bodies are not retained")
                    }
                }
                turnSnapshot?.let { appendLine("- latest full-turn snapshot: $it (app-private)") }
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
                    if (entry.requestChars > entry.requestBody.length) {
                        appendLine("- request body: kept ${entry.requestBody.length} of ${entry.requestChars} chars")
                    }
                    if (entry.responseChars > (entry.responseBody?.length ?: 0)) {
                        appendLine(
                            "- response body: kept ${entry.responseBody?.length ?: 0} of ${entry.responseChars} chars",
                        )
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
                    if (entry.requestTail != null && entry.requestChars > entry.requestBody.length) {
                        appendLine("### Request tail")
                        appendLine()
                        appendLine("The stored request body was shortened; this is its original tail.")
                        appendLine()
                        appendLine(markdownFence(entry.requestTail, "text"))
                        appendLine()
                    }
                    if (entry.responseHeaders.isNotEmpty()) {
                        appendLine("### Response headers")
                        appendLine()
                        appendLine(markdownFence(headersText(entry.responseHeaders), "http"))
                        appendLine()
                    }
                    entry.responseBody?.let { body ->
                        appendLine("### Response body")
                        appendLine()
                        appendLine(markdownFence(prettyResponseBody(body), "json"))
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

    private fun prettyResponseBody(body: String): String =
        AgentTraceResponse.normalizeStreaming(body)?.let { normalized ->
            prettyJson.encodeToString(JsonObject.serializer(), normalized)
        } ?: prettyBody(body)

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
