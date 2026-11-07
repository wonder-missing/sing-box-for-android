package io.nekohasekai.sfa.telemetry

import android.content.Context
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.concurrent.thread

/** Reads the libbox group exactly once: its items iterator is single-use. */
private fun OutboundGroup.toSelection(): OutboundGroupSelection {
    val itemTags = ArrayList<String>()
    var selectedDelay: Int? = null
    val iterator = items
    while (iterator.hasNext()) {
        val item = iterator.next()
        itemTags.add(item.tag)
        if (item.tag == selected) {
            selectedDelay = item.urlTestDelay.takeIf { it > 0 }
        }
    }
    return OutboundGroupSelection(
        tag = tag,
        type = type,
        selectable = selectable,
        selected = selected,
        selectedDelayMs = selectedDelay,
        itemTags = itemTags,
    )
}

/**
 * Collects the same sing-box log lines as the Log screen and uploads them
 * while the VPN service is running. Uploads run off the VPN thread.
 */
internal object TelemetryRuntime {
    private const val TAG = "Telemetry"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val FINAL_CONNECT_TIMEOUT_MS = 8_000
    private const val FINAL_READ_TIMEOUT_MS = 8_000
    private const val RETRY_ATTEMPTS = 2
    private const val FINAL_ATTEMPTS = 1

    /** Background stop flush budget; keeps the upload mutex from starving a restart. */
    private const val FINAL_FLUSH_BUDGET_MS = 45_000L

    private val stateLock = Any()
    private val uploadMutex = Mutex()

    @Volatile
    private var attached = false

    @Volatile
    private var collectorConnected = false

    @Volatile
    private var connectInFlight = false

    private var suppressDisconnect = false
    private var scope: CoroutineScope? = null
    private var commandClient: CommandClient? = null
    private var buffer: TelemetryLogBuffer? = null
    private var uploader: TelemetryUploader? = null
    private var coreVersion: String = ""
    private var appContext: Context? = null
    private var persistQueued = false
    private var ackFile: File? = null
    private var lastAckedId: String? = null
    private var firstFlushScheduled = false

    /** One id per successful VPN service session; null while detached. */
    private val session = VpnSessionState()

    /** Replaced on every attach so the first Groups snapshot is reported as `start`. */
    @Volatile
    private var outboundTracker = OutboundSelectionTracker()

    internal fun currentSessionId(): String? = session.current

    @Volatile
    private var flushRequested = false

    /** Bumped on every attach so a detached flush cannot overwrite a newer queue file. */
    @Volatile
    private var sessionEpoch = 0L

    private class FlushContext(
        val context: Context,
        val queue: TelemetryLogBuffer,
        val uploader: TelemetryUploader,
        val coreVersion: String,
        val epoch: Long,
        val sessionId: String?,
    )

    private data class UploadOutcome(
        val batches: Int,
        val lines: Int,
        val crashReports: Int,
        val hadPending: Boolean,
        val failure: String?,
    )

    private data class SendOutcome(
        val sent: Boolean,
        val lines: Int = 0,
        val crashAcked: Boolean = false,
        val detail: String? = null,
    )

    private val handler = object : CommandClient.Handler {
        override fun onConnected() {
            connectInFlight = false
            collectorConnected = true
        }

        override fun onDisconnected() {
            if (suppressDisconnect) return
            collectorConnected = false
            connectInFlight = false
        }

        override fun onConnectionError(message: String) {
            collectorConnected = false
            connectInFlight = false
            Log.w(TAG, "log collector error")
        }

        override fun appendLogs(message: List<LogEntry>) {
            if (!attached) return
            val context = appContext ?: return
            if (!TelemetryPreferences.canUpload(context)) return
            val lines = message.mapNotNull { entry ->
                TelemetryPayload.normalizeLine(entry.message ?: return@mapNotNull null)
            }
            if (lines.isEmpty()) return
            // Serialize with detachAndFlushAsync: once it clears the buffer and
            // persists, no late collector callback may add lines to a dead queue.
            val crossed = synchronized(stateLock) {
                if (!attached) return
                val queue = buffer ?: return
                queue.append(lines)
            }
            schedulePersist()
            if (!firstFlushScheduled) {
                firstFlushScheduled = true
                scope?.launch {
                    delay(15_000)
                    uploadAvailable("interval", finalFlush = false)
                }
            }
            if (crossed) {
                scope?.launch { uploadAvailable("threshold", finalFlush = false) }
            }
        }

        override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
            // Same Groups command path the Agent/dashboard use; never a second
            // query channel. Any conversion error is telemetry's problem only.
            if (!attached) return
            val context = appContext ?: return
            val groups = runCatching { newGroups.map { it.toSelection() } }.getOrDefault(emptyList())
            if (groups.isEmpty()) return
            val changes = runCatching { outboundTracker.update(groups) }.getOrDefault(emptyList())
            for (change in changes) {
                runCatching {
                    TelemetryEventLog.record(
                        context,
                        "outbound_selected",
                        buildMap {
                            put("group", change.group)
                            change.from?.let { put("from", it) }
                            put("to", change.to)
                            put("reason", change.reason.wireValue)
                            put("root", change.root)
                            change.ms?.let { put("ms", it) }
                        },
                    )
                }.onFailure {
                    Log.w(TAG, "outbound selection event failed", it)
                }
            }
        }
    }

    fun attach(vpn: VpnService) {
        synchronized(stateLock) {
            if (attached) return
            attached = true
            val context = vpn.applicationContext
            appContext = context
            // New VPN session: new id, and the first Groups snapshot speaks again
            // even if the previous session had the same selection.
            session.start()
            outboundTracker = OutboundSelectionTracker()
            coreVersion = runCatching { Libbox.version() }.getOrDefault("")
            buffer = newBuffer(context).also {
                if (TelemetryPreferences.canUpload(context)) {
                    it.append(listOf("INFO[0000] WONDER service start app=${BuildConfig.VERSION_NAME}"))
                }
            }
            TelemetrySignals.startNetworkWatch(context)
            runCatching {
                TelemetryEventLog.record(
                    context,
                    "service_start",
                    TelemetrySignals.powerState(context) +
                        TelemetrySignals.currentDefaultNetwork(context) +
                        mapOf("app" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT),
                )
            }
            ackFile = File(context.filesDir, "telemetry/last_ack")
            lastAckedId = runCatching { ackFile?.readText()?.trim() }.getOrNull()
            uploader = TelemetryUploader(context) { socket -> vpn.protect(socket) }
            collectorConnected = false
            connectInFlight = false
            firstFlushScheduled = false
            sessionEpoch++
            val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = created
            commandClient = CommandClient(
                scope = created,
                connectionTypes =
                    listOf(
                        CommandClient.ConnectionType.Log,
                        CommandClient.ConnectionType.Groups,
                    ),
                handler = handler,
                localOnly = true,
            )
            created.launch { runLoop() }
        }
        Log.i(TAG, "attached")
    }

    fun onSettingsChanged(context: Context) {
        if (!attached) {
            val vpn = io.nekohasekai.sfa.bg.BoxService.runningVpn
            if (vpn != null) {
                // Called from the UI thread; a telemetry failure must not crash it.
                runCatching { attach(vpn) }.onFailure {
                    Log.w(TAG, "telemetry attach failed", it)
                }
            }
        }
        flushRequested = true
        scope?.launch {
            syncCollector()
            delay(15_000)
            if (attached) {
                uploadAvailable("interval", finalFlush = false)
            }
        }
        Log.i(TAG, "settings changed; upload armed=${TelemetryPreferences.canUpload(context)} attached=$attached")
    }

    fun probe(context: Context): String {
        if (!TelemetryPreferences.isEnabled(context)) {
            return "Upload is off. Turn it on and save."
        }
        val endpoint = TelemetryPreferences.normalizeEndpoint(TelemetryPreferences.endpointUrl(context))
        val token = TelemetryPreferences.ingestToken(context)
        if (!TelemetryPreferences.isHttpsEndpoint(endpoint) || token.isBlank()) {
            return "Save an https endpoint and token first."
        }
        val body = TelemetryPayload.build(
            device = TelemetryPreferences.deviceId(context),
            appVersion = BuildConfig.VERSION_NAME,
            coreVersion = runCatching { Libbox.version() }.getOrDefault(""),
            tsMillis = System.currentTimeMillis(),
            lines = listOf("telemetry probe"),
        )
        val result = TelemetryUploader(context.applicationContext, null).post(
            endpoint = endpoint,
            token = token,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            tags = mapOf("reason" to "probe"),
        )
        if (result == UploadResult.Success) {
            TelemetryPreferences.noteProbeOk(context)
        }
        return describe(result)
    }

    fun describe(result: UploadResult): String = when (result) {
        UploadResult.Success -> "Connected. The server accepted the probe."
        UploadResult.Unauthorized -> "HTTP 401. The ingest token was rejected."
        UploadResult.TooLarge -> "HTTP 413. The server rejected the payload size."
        is UploadResult.Failed -> result.detail
    }

    /**
     * Detaches the collector and persists pending data synchronously, then runs
     * the final upload on a daemon thread. The Stopping UI never waits for HTTP.
     */
    fun detachAndFlushAsync(reason: String = "stop") {
        val detached = synchronized(stateLock) {
            if (!attached) return
            attached = false
            val client = commandClient
            commandClient = null
            collectorConnected = false
            connectInFlight = false
            val oldScope = scope
            scope = null
            persistQueued = false
            val context = appContext
            val queue = buffer
            val currentUploader = uploader
            val sessionId = session.current
            if (context != null) {
                runCatching { TelemetrySignals.stopNetworkWatch(context) }
                runCatching { TelemetryEventLog.record(context, "service_stop", mapOf("reason" to reason)) }
                if (TelemetryPreferences.canUpload(context)) {
                    runCatching { queue?.append(listOf("INFO[0000] WONDER service stop")) }
                }
                // Persist first: the file survives even if the upload never completes.
                runCatching { queue?.persist() }
            }
            // The stop event above carries this id; later flushed events carry it
            // through the upload tags added in sendOne().
            session.stop()
            val snapshot = if (context != null && queue != null && currentUploader != null) {
                FlushContext(context, queue, currentUploader, coreVersion, sessionEpoch, sessionId)
            } else {
                null
            }
            buffer = null
            uploader = null
            coreVersion = ""
            appContext = null
            // Local command-socket disconnect, not network. Do it under the lock
            // so a quickly restarted service cannot receive this session's callbacks.
            runCatching { client?.disconnect() }
            oldScope to snapshot
        }
        detached.first?.cancel()
        thread(name = "telemetry-stop-flush", isDaemon = true) {
            val flush = detached.second ?: return@thread
            runCatching {
                runBlocking(Dispatchers.IO) {
                    val outcome = uploadContext(
                        flush,
                        reason,
                        finalFlush = true,
                        maxBatches = TelemetryPayload.MAX_STOP_BATCHES,
                        deadline = System.currentTimeMillis() + FINAL_FLUSH_BUDGET_MS,
                    )
                    persistIfCurrent(flush)
                    Log.i(
                        TAG,
                        "final flush batches=${outcome.batches} lines=${outcome.lines} " +
                            "crash=${outcome.crashReports} failure=${outcome.failure}",
                    )
                }
            }.onFailure {
                Log.w(TAG, "final flush failed", it)
            }
        }
    }

    fun detachFromDestroy() {
        // onDestroy() runs on the main thread; keep the disk write off it too.
        thread(name = "telemetry-detach", isDaemon = true) {
            runCatching { detachAndFlushAsync("destroy") }
        }
    }

    private suspend fun runLoop() {
        try {
            runLoopInner()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last line of defence: an uncaught coroutine exception reaches the
            // Android default handler and kills the process (VPN included).
            Log.w(TAG, "telemetry loop stopped", e)
        }
    }

    private suspend fun runLoopInner() {
        syncCollector()
        if (buffer?.isEmpty() == false) {
            uploadAvailable("interval", finalFlush = false)
        }
        var nextFlushAt = System.currentTimeMillis() + TelemetryPayload.FLUSH_INTERVAL_MS
        while (attached && scope?.isActive == true) {
            delay(TelemetryPayload.COLLECTOR_POLL_MS)
            if (!attached) break
            syncCollector()
            val now = System.currentTimeMillis()
            if (flushRequested || now >= nextFlushAt) {
                flushRequested = false
                nextFlushAt = now + TelemetryPayload.FLUSH_INTERVAL_MS
                uploadAvailable("interval", finalFlush = false)
            }
        }
    }

    private fun syncCollector() {
        // Collector control failures are telemetry's problem, never the VPN's:
        // swallow them here so the service loop cannot die on a socket error.
        try {
            val context = appContext ?: return
            val client = commandClient ?: return
            val want = TelemetryPreferences.canUpload(context)
            if (want && !collectorConnected && !connectInFlight) {
                connectInFlight = true
                suppressDisconnect = true
                try {
                    client.connect()
                } finally {
                    suppressDisconnect = false
                }
                Log.i(TAG, "log collector connecting")
            } else if (!want && (collectorConnected || connectInFlight)) {
                collectorConnected = false
                connectInFlight = false
                suppressDisconnect = true
                try {
                    client.disconnect()
                } finally {
                    suppressDisconnect = false
                }
                Log.i(TAG, "log collector disconnected")
            }
        } catch (e: Exception) {
            // Do not leave connectInFlight stuck: that would mute telemetry for
            // the rest of the session after one transient failure.
            connectInFlight = false
            Log.w(TAG, "log collector sync failed", e)
        }
    }

    private suspend fun uploadAvailable(reason: String, finalFlush: Boolean) {
        val flush = synchronized(stateLock) {
            val context = appContext ?: return
            val queue = buffer ?: return
            val currentUploader = uploader ?: return
            FlushContext(context, queue, currentUploader, coreVersion, sessionEpoch, session.current)
        }
        try {
            uploadContext(
                flush,
                reason,
                finalFlush,
                if (finalFlush) TelemetryPayload.MAX_STOP_BATCHES else TelemetryPayload.MAX_DRAIN_BATCHES,
            )
            persistIfCurrent(flush)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scheduled upload failed", e)
        }
    }

    /** Manual "upload now": drains logs, client events and unsent crash summaries. */
    suspend fun uploadNow(context: Context): String {
        val app = context.applicationContext
        if (!TelemetryPreferences.isEnabled(app)) return "Upload is off."
        if (!TelemetryPreferences.canUpload(app)) return "Save an https endpoint and token first."
        val flush = synchronized(stateLock) {
            val liveContext = appContext
            val queue = buffer
            val currentUploader = uploader
            if (liveContext != null && queue != null && currentUploader != null) {
                FlushContext(liveContext, queue, currentUploader, coreVersion, sessionEpoch, session.current)
            } else {
                FlushContext(
                    app,
                    newBuffer(app),
                    TelemetryUploader(app, null),
                    runCatching { Libbox.version() }.getOrDefault(""),
                    sessionEpoch,
                    session.current,
                )
            }
        }
        val outcome = try {
            val result = uploadContext(
                flush,
                "manual",
                finalFlush = false,
                maxBatches = TelemetryPayload.MAX_DRAIN_BATCHES,
            )
            persistIfCurrent(flush)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "manual upload failed", e)
            return "Upload failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return when {
            outcome.failure != null && outcome.batches == 0 -> "Upload failed: ${outcome.failure}"
            outcome.failure != null -> "Uploaded ${outcome.lines} lines, then failed: ${outcome.failure}"
            !outcome.hadPending -> "Nothing pending."
            else -> {
                val parts = buildList {
                    if (outcome.lines > 0) add("${outcome.lines} log lines")
                    if (outcome.crashReports > 0) add("${outcome.crashReports} crash report(s)")
                }
                if (parts.isEmpty()) {
                    "Uploaded pending telemetry events."
                } else {
                    "Uploaded ${parts.joinToString(" and ")}."
                }
            }
        }
    }

    private suspend fun uploadContext(
        flush: FlushContext,
        reason: String,
        finalFlush: Boolean,
        maxBatches: Int,
        deadline: Long = Long.MAX_VALUE,
    ): UploadOutcome = uploadMutex.withLock {
        drainLocked(flush, reason, finalFlush, maxBatches, deadline)
    }

    private suspend fun drainLocked(
        flush: FlushContext,
        reason: String,
        finalFlush: Boolean,
        maxBatches: Int,
        deadline: Long,
    ): UploadOutcome {
        val context = flush.context
        if (!TelemetryPreferences.canUpload(context)) {
            return UploadOutcome(0, 0, 0, false, "upload is disabled")
        }
        val token = TelemetryPreferences.ingestToken(context)
        // An explicit "upload now" retries even during the 401 back-off.
        if (reason != "manual" && TelemetryPreferences.isAuthPaused(context, token)) {
            Log.i(TAG, "upload paused after HTTP 401")
            return UploadOutcome(0, 0, 0, false, "paused after HTTP 401")
        }
        var crashPending = runCatching { CrashReportTelemetry.pending(context) }
            .getOrDefault(emptyList())
        val hadPending = !flush.queue.isEmpty() ||
            TelemetryEventLog.hasPending(context) ||
            crashPending.isNotEmpty()
        var batches = 0
        var lines = 0
        var crashReports = 0
        var failure: String? = null
        // Drain the whole backlog in sequential batches (previously an interval
        // flush sent a single 500-line batch, so a busy log fell behind and the
        // disk cap evicted the oldest lines).
        while (batches < maxBatches && System.currentTimeMillis() < deadline) {
            if (flush.queue.isEmpty() && !TelemetryEventLog.hasPending(context) && crashPending.isEmpty()) {
                break
            }
            val outcome = sendOne(flush, token, finalFlush, reason, crashPending)
            if (!outcome.sent) {
                failure = outcome.detail
                break
            }
            if (outcome.crashAcked) {
                crashReports += crashPending.size
                // Pick up the next page (if any) after these were marked sent.
                crashPending = runCatching { CrashReportTelemetry.pending(context) }
                    .getOrDefault(emptyList())
            }
            batches++
            lines += outcome.lines
        }
        return UploadOutcome(batches, lines, crashReports, hadPending, failure)
    }

    private fun persistIfCurrent(flush: FlushContext) {
        // A newer service session may have reloaded pending.log already.
        if (flush.epoch == sessionEpoch) {
            runCatching { flush.queue.persist() }
        }
    }

    private suspend fun sendOne(
        flush: FlushContext,
        token: String,
        finalFlush: Boolean,
        reason: String,
        crashEvents: List<CrashReportTelemetry.PendingEvent>,
    ): SendOutcome {
        val context = flush.context
        val queue = flush.queue
        val endpoint = TelemetryPreferences.endpointUrl(context)
        val device = TelemetryPreferences.deviceId(context)
        val appVersion = BuildConfig.VERSION_NAME
        var maxLines = TelemetryPayload.FLUSH_LINE_COUNT
        var maxRawBytes = TelemetryPayload.FLUSH_RAW_BYTES
        var attemptsLeftForSize = 3
        var crashToSend = crashEvents
        while (attemptsLeftForSize > 0) {
            val ts = System.currentTimeMillis()
            val pending = queue.peek()
            val clientEvents = TelemetryEventLog.pending(context)
            val includeCrash = crashToSend.isNotEmpty()
            if (pending.isEmpty() && clientEvents == null && !includeCrash) {
                val detail = if (crashEvents.isNotEmpty()) "HTTP 413: crash summary rejected" else null
                return SendOutcome(false, detail = detail)
            }
            val batch = if (pending.isEmpty()) emptyList() else TelemetryPayload.selectBatch(
                lines = pending,
                device = device,
                appVersion = appVersion,
                coreVersion = flush.coreVersion,
                tsMillis = ts,
                maxLines = maxLines,
                maxRawBytes = maxRawBytes,
            )
            if (batch.isEmpty() && pending.isNotEmpty()) {
                queue.dropPrefix(1)
                Log.w(TAG, "dropped a log line that could not fit in a batch")
                return SendOutcome(true)
            }
            // Crash summaries get their own batch id so a server-side dedup hit for
            // the same log lines cannot swallow a new crash event.
            val batchKey = if (crashToSend.isEmpty()) {
                batch
            } else {
                batch + crashToSend.map { "crash_report:${it.id}" }
            }
            val batchId = TelemetryPayload.batchId(device, batchKey)
            if (batch.isNotEmpty() && batchId == lastAckedId) {
                // Already accepted by the server (e.g. process died before the queue was persisted).
                queue.dropPrefix(batch.size)
                persistIfCurrent(flush)
                Log.i(TAG, "skipped ${batch.size} already-uploaded lines")
                return SendOutcome(true, batch.size)
            }
            val body = TelemetryPayload.build(
                device = device,
                appVersion = appVersion,
                coreVersion = flush.coreVersion,
                tsMillis = ts,
                lines = batch,
                batchId = batchId,
                droppedLines = queue.droppedCount(),
                more = (pending.size - batch.size).coerceAtLeast(0),
                eventsJson = eventsJsonFor(batch, crashToSend),
                clientEventsJson = clientEvents?.second,
            )
            val tags = buildMap {
                put("reason", reason)
                put("lines", batch.size)
                put("queue", pending.size)
                put("bytes", body.size)
                put("batch", batchId.take(12))
                put("crash", crashToSend.size)
                // Final flushes run after detach, so the live session field is
                // already cleared; keep the producing session on the upload event.
                flush.sessionId?.let { put(TelemetryClientEvent.SESSION_FIELD, it) }
            }
            val result = postWithRetry(flush.uploader, endpoint, token, body, finalFlush, tags)
            when (result) {
                UploadResult.Success -> {
                    // The server accepted the payload: this is the time the settings
                    // page must show, even if a later local disk write fails.
                    TelemetryPreferences.noteUploadOk(context)
                    clientEvents?.let { TelemetryEventLog.ack(context, it.first) }
                    if (includeCrash) {
                        TelemetryPreferences.markCrashIdsSent(context, crashToSend.map { it.id })
                    }
                    queue.dropPrefix(batch.size)
                    queue.resetDropped()
                    lastAckedId = batchId
                    runCatching { ackFile?.writeText(batchId) }
                    persistIfCurrent(flush)
                    Log.i(TAG, "uploaded ${batch.size} lines (${crashToSend.size} crash summaries)")
                    return SendOutcome(true, batch.size, crashAcked = includeCrash)
                }

                UploadResult.Unauthorized -> {
                    TelemetryPreferences.noteUnauthorized(context, token, TelemetryPayload.AUTH_PAUSE_MS)
                    Log.w(TAG, "upload rejected with HTTP 401; pausing")
                    return SendOutcome(false, detail = "HTTP 401: token rejected")
                }

                UploadResult.TooLarge -> {
                    attemptsLeftForSize--
                    if (includeCrash) {
                        // Keep the log batch/events useful if a crash summary is the outlier.
                        Log.w(TAG, "HTTP 413 with crash summary; retrying without it")
                        crashToSend = emptyList()
                        continue
                    }
                    if (batch.isEmpty()) {
                        Log.w(TAG, "HTTP 413 for an empty batch")
                        return SendOutcome(false, detail = "HTTP 413: payload rejected")
                    }
                    if (batch.size <= 1) {
                        queue.dropPrefix(1)
                        Log.w(TAG, "dropped a log line rejected with HTTP 413")
                        return SendOutcome(true, 1)
                    }
                    maxLines = (batch.size / 2).coerceAtLeast(1)
                    maxRawBytes = (maxRawBytes / 2).coerceAtLeast(1024)
                    Log.w(TAG, "HTTP 413; retrying with $maxLines lines")
                }

                is UploadResult.Failed -> {
                    Log.w(TAG, "upload failed: ${result.detail}")
                    return SendOutcome(false, detail = result.detail)
                }
            }
        }
        return SendOutcome(false, detail = "payload too large")
    }

    private fun eventsJsonFor(
        batch: List<String>,
        crashEvents: List<CrashReportTelemetry.PendingEvent>,
    ): String {
        val lineEvents = TelemetryEvents.toJson(batch)
        if (crashEvents.isEmpty()) return lineEvents
        val hasLineEvents = lineEvents.length > 2 // more than "[]"
        val out = StringBuilder(lineEvents.length + crashEvents.sumOf { it.json.length } + 8)
        if (hasLineEvents) {
            out.append(lineEvents, 0, lineEvents.length - 1)
        } else {
            out.append('[')
        }
        crashEvents.forEachIndexed { index, event ->
            if (hasLineEvents || index > 0) out.append(',')
            out.append(event.json)
        }
        out.append(']')
        return out.toString()
    }

    private suspend fun postWithRetry(
        uploader: TelemetryUploader,
        endpoint: String,
        token: String,
        body: ByteArray,
        finalFlush: Boolean,
        tags: Map<String, Any?>,
    ): UploadResult {
        val attempts = if (finalFlush) FINAL_ATTEMPTS else RETRY_ATTEMPTS
        val connectTimeout = if (finalFlush) FINAL_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
        val readTimeout = if (finalFlush) FINAL_READ_TIMEOUT_MS else READ_TIMEOUT_MS
        var delayMs = 15_000L
        var last: UploadResult = UploadResult.Failed(true, "not sent")
        repeat(attempts) { attempt ->
            val result = uploader.post(endpoint, token, body, connectTimeout, readTimeout, tags + ("attempt" to attempt + 1))
            last = result
            val retry = result is UploadResult.Failed && result.retryable && attempt < attempts - 1
            if (!retry) return result
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(TelemetryPayload.FLUSH_INTERVAL_MS)
        }
        return last
    }

    private fun schedulePersist() {
        val current = scope ?: return
        synchronized(stateLock) {
            if (persistQueued) return
            persistQueued = true
        }
        current.launch {
            delay(1_000)
            synchronized(stateLock) { persistQueued = false }
            val queue = synchronized(stateLock) { buffer }
            // Telemetry disk errors must never reach the coroutine's uncaught
            // handler: that would kill the process and take the VPN with it.
            runCatching { queue?.persist() }.onFailure {
                Log.w(TAG, "scheduled telemetry persist failed", it)
            }
        }
    }

    private fun newBuffer(context: Context): TelemetryLogBuffer {
        return TelemetryLogBuffer(
            File(context.filesDir, "telemetry/pending.log"),
            onPersistFailure = { error ->
                Log.w(TAG, "telemetry persist failed: ${error.message}", error)
                runCatching {
                    TelemetryEventLog.record(
                        context,
                        "telemetry_persist_failed",
                        mapOf("err" to (error.message ?: error.javaClass.simpleName).take(120)),
                    )
                }
            },
        )
    }
}
