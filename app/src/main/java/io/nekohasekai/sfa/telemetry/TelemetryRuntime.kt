package io.nekohasekai.sfa.telemetry

import android.content.Context
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.utils.CommandClient
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread

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

    private val stateLock = Any()
    private val uploadMutex = Mutex()
    private val lifecycleMutex = Mutex()

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

    @Volatile
    private var flushRequested = false

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
            val context = appContext ?: return
            if (!TelemetryPreferences.canUpload(context)) return
            val lines = message.mapNotNull { entry ->
                TelemetryPayload.normalizeLine(entry.message ?: return@mapNotNull null)
            }
            if (lines.isEmpty()) return
            val queue = buffer ?: return
            val crossed = queue.append(lines)
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
    }

    fun attach(vpn: VpnService) {
        synchronized(stateLock) {
            if (attached) return
            attached = true
            val context = vpn.applicationContext
            appContext = context
            coreVersion = runCatching { Libbox.version() }.getOrDefault("")
            buffer = TelemetryLogBuffer(File(context.filesDir, "telemetry/pending.log")).also {
                if (TelemetryPreferences.canUpload(context)) {
                    it.append(listOf("INFO[0000] WONDER service start app=${BuildConfig.VERSION_NAME}"))
                }
            }
            ackFile = File(context.filesDir, "telemetry/last_ack")
            lastAckedId = runCatching { ackFile?.readText()?.trim() }.getOrNull()
            uploader = TelemetryUploader(context) { socket -> vpn.protect(socket) }
            collectorConnected = false
            connectInFlight = false
            firstFlushScheduled = false
            val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = created
            commandClient = CommandClient(
                scope = created,
                connectionType = CommandClient.ConnectionType.Log,
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
                attach(vpn)
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
        )
        return describe(result)
    }

    fun describe(result: UploadResult): String = when (result) {
        UploadResult.Success -> "Connected. The server accepted the probe."
        UploadResult.Unauthorized -> "HTTP 401. The ingest token was rejected."
        UploadResult.TooLarge -> "HTTP 413. The server rejected the payload size."
        is UploadResult.Failed -> result.detail
    }

    suspend fun detachAndFlush() {
        lifecycleMutex.withLock {
            val stop = synchronized(stateLock) {
                if (!attached) {
                    null
                } else {
                    attached = false
                    val connected = collectorConnected || connectInFlight
                    val current = commandClient
                    commandClient = null
                    collectorConnected = false
                    connectInFlight = false
                    connected to current
                }
            } ?: return@withLock
            if (stop.first) {
                delay(400)
            }
            stop.second?.let { client ->
                runCatching { client.disconnect() }
            }
            appContext?.let { context ->
                if (TelemetryPreferences.canUpload(context)) {
                    buffer?.append(listOf("INFO[0000] WONDER service stop"))
                }
            }
            withTimeoutOrNull(TelemetryPayload.FINAL_FLUSH_TIMEOUT_MS) {
                uploadAvailable("stop", finalFlush = true)
            }
            buffer?.persist()
            synchronized(stateLock) {
                scope?.cancel()
                scope = null
                buffer = null
                uploader = null
                appContext = null
            }
            Log.i(TAG, "detached")
        }
    }

    fun detachFromDestroy() {
        thread(name = "telemetry-stop", isDaemon = true) {
            runBlocking(Dispatchers.IO) {
                detachAndFlush()
            }
        }
    }

    private suspend fun runLoop() {
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
    }

    private suspend fun uploadAvailable(reason: String, finalFlush: Boolean) {
        uploadMutex.withLock {
            val context = appContext ?: return@withLock
            val queue = buffer ?: return@withLock
            if (!TelemetryPreferences.canUpload(context)) return@withLock
            val token = TelemetryPreferences.ingestToken(context)
            if (TelemetryPreferences.isAuthPaused(context, token)) {
                Log.i(TAG, "upload paused after HTTP 401")
                return@withLock
            }
            val maxBatches = if (finalFlush) {
                TelemetryPayload.MAX_STOP_BATCHES
            } else {
                TelemetryPayload.MAX_DRAIN_BATCHES
            }
            // Drain the whole backlog in sequential batches (previously an interval
            // flush sent a single 500-line batch, so a busy log fell behind and the
            // disk cap evicted the oldest lines).
            var sent = 0
            while (sent < maxBatches && !queue.isEmpty()) {
                val delivered = sendOne(context, queue, token, finalFlush)
                if (!delivered) break
                sent++
            }
            queue.persist()
        }
    }

    private suspend fun sendOne(
        context: Context,
        queue: TelemetryLogBuffer,
        token: String,
        finalFlush: Boolean,
    ): Boolean {
        val uploader = uploader ?: return false
        val endpoint = TelemetryPreferences.endpointUrl(context)
        val device = TelemetryPreferences.deviceId(context)
        val appVersion = BuildConfig.VERSION_NAME
        var maxLines = TelemetryPayload.FLUSH_LINE_COUNT
        var maxRawBytes = TelemetryPayload.FLUSH_RAW_BYTES
        var attemptsLeftForSize = 3
        while (attemptsLeftForSize > 0) {
            val ts = System.currentTimeMillis()
            val pending = queue.peek()
            if (pending.isEmpty()) return false
            val batch = TelemetryPayload.selectBatch(
                lines = pending,
                device = device,
                appVersion = appVersion,
                coreVersion = coreVersion,
                tsMillis = ts,
                maxLines = maxLines,
                maxRawBytes = maxRawBytes,
            )
            if (batch.isEmpty()) {
                queue.dropPrefix(1)
                Log.w(TAG, "dropped a log line that could not fit in a batch")
                return true
            }
            val batchId = TelemetryPayload.batchId(device, batch)
            if (batchId == lastAckedId) {
                // Already accepted by the server (e.g. process died before the queue was persisted).
                queue.dropPrefix(batch.size)
                queue.persist()
                Log.i(TAG, "skipped ${batch.size} already-uploaded lines")
                return true
            }
            val body = TelemetryPayload.build(
                device = device,
                appVersion = appVersion,
                coreVersion = coreVersion,
                tsMillis = ts,
                lines = batch,
                batchId = batchId,
                droppedLines = queue.droppedCount(),
                more = (pending.size - batch.size).coerceAtLeast(0),
                eventsJson = TelemetryEvents.toJson(batch),
            )
            val result = postWithRetry(uploader, endpoint, token, body, finalFlush)
            when (result) {
                UploadResult.Success -> {
                    queue.dropPrefix(batch.size)
                    queue.resetDropped()
                    lastAckedId = batchId
                    runCatching { ackFile?.writeText(batchId) }
                    queue.persist()
                    Log.i(TAG, "uploaded ${batch.size} lines")
                    return true
                }

                UploadResult.Unauthorized -> {
                    TelemetryPreferences.noteUnauthorized(context, token, TelemetryPayload.AUTH_PAUSE_MS)
                    Log.w(TAG, "upload rejected with HTTP 401; pausing")
                    return false
                }

                UploadResult.TooLarge -> {
                    attemptsLeftForSize--
                    if (batch.size <= 1) {
                        queue.dropPrefix(1)
                        Log.w(TAG, "dropped a log line rejected with HTTP 413")
                        return true
                    }
                    maxLines = (batch.size / 2).coerceAtLeast(1)
                    maxRawBytes = (maxRawBytes / 2).coerceAtLeast(1024)
                    Log.w(TAG, "HTTP 413; retrying with $maxLines lines")
                }

                is UploadResult.Failed -> {
                    Log.w(TAG, "upload failed: ${result.detail}")
                    return false
                }
            }
        }
        return false
    }

    private suspend fun postWithRetry(
        uploader: TelemetryUploader,
        endpoint: String,
        token: String,
        body: ByteArray,
        finalFlush: Boolean,
    ): UploadResult {
        val attempts = if (finalFlush) FINAL_ATTEMPTS else RETRY_ATTEMPTS
        val connectTimeout = if (finalFlush) FINAL_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
        val readTimeout = if (finalFlush) FINAL_READ_TIMEOUT_MS else READ_TIMEOUT_MS
        var delayMs = 15_000L
        var last: UploadResult = UploadResult.Failed(true, "not sent")
        repeat(attempts) { attempt ->
            val result = uploader.post(endpoint, token, body, connectTimeout, readTimeout)
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
            buffer?.persist()
        }
    }
}
