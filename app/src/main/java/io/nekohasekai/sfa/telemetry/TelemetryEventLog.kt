package io.nekohasekai.sfa.telemetry

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Small persistent ring of client-side events (upload failures/successes,
 * network changes, service lifecycle, profile updates...). Recorded locally
 * regardless of whether uploads work, attached to the next successful upload
 * as `client_events`, and removed only after the server accepted them.
 * Capped by count and age so it cannot grow forever.
 */
internal object TelemetryEventLog {
    private const val MAX_EVENTS = 300
    private const val MAX_AGE_MS = 48L * 3600L * 1000L
    const val MAX_PER_UPLOAD = 150

    /** events.log is ~300 small JSON lines; anything larger is corrupt/hostile. */
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L

    private val lock = Any()
    private val writer =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "telemetry-events").apply { isDaemon = true }
        }
    private var file: File? = null
    private val events = ArrayDeque<Pair<Long, String>>() // seq to json
    private var nextSeq = 1L
    private var droppedEvents = 0
    private var droppedByCap = 0
    private var droppedByAge = 0

    private fun ensureLoaded(context: Context): File {
        file?.let { return it }
        val f = File(context.applicationContext.filesDir, "telemetry/events.log")
        // Skip an oversized/corrupt file instead of trying to parse it all; the
        // next write rewrites it from the current in-memory ring.
        runCatching {
            if (f.isFile && f.length() <= MAX_FILE_BYTES) {
                f.readLines(Charsets.UTF_8).forEach { line ->
                    val tab = line.indexOf('\t')
                    val seq = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
                    if (seq != null) {
                        events.addLast(seq to line.substring(tab + 1))
                        if (seq >= nextSeq) nextSeq = seq + 1
                    }
                }
            }
        }
        file = f
        return f
    }

    /**
     * Records an event. Values must be String, Number or Boolean; nulls are skipped.
     * Never pass URLs with paths/queries, tokens or AuthURLs; use [TelemetryEvents.scrub].
     */
    fun record(context: Context, type: String, fields: Map<String, Any?> = emptyMap()) {
        if (!TelemetryPreferences.isEnabled(context)) return
        val now = System.currentTimeMillis()
        // While a VPN session is live every event carries its id, so service,
        // network, selection and upload events can be grouped after the fact.
        val json = TelemetryClientEvent.encode(type, now, fields, TelemetryRuntime.currentSessionId())
        synchronized(lock) {
            val f = ensureLoaded(context)
            events.addLast(nextSeq++ to json)
            trimLocked(now)
            writeLocked(f)
        }
    }

    /**
     * Records an event off the caller's thread. Use this for UI/main-thread
     * signals so writing the event file never becomes the jank it measures.
     */
    fun recordAsync(context: Context, type: String, fields: Map<String, Any?> = emptyMap()) {
        val app = context.applicationContext
        runCatching { writer.execute { record(app, type, fields) } }
    }

    /** Pending events (oldest first) and the highest seq included. */
    fun pending(context: Context, limit: Int = MAX_PER_UPLOAD): Pair<Long, String>? = synchronized(lock) {
        ensureLoaded(context)
        trimLocked(System.currentTimeMillis())
        if (events.isEmpty() && droppedEvents == 0) return null
        val chosen = events.take(limit)
        val out = StringBuilder("[")
        chosen.forEachIndexed { i, (_, json) ->
            if (i > 0) out.append(',')
            out.append(json)
        }
        if (droppedEvents > 0) {
            if (chosen.isNotEmpty()) out.append(',')
            out.append("{\"type\":\"client_events_dropped\",\"ts\":").append(System.currentTimeMillis())
                .append(",\"n\":").append(droppedEvents)
                .append(",\"capped\":").append(droppedByCap)
                .append(",\"expired\":").append(droppedByAge)
                .append('}')
        }
        out.append(']')
        (chosen.lastOrNull()?.first ?: 0L) to out.toString()
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) {
        ensureLoaded(context)
        events.isNotEmpty() || droppedEvents > 0
    }

    /**
     * Upload attempts record diagnostics while draining. Those events must not
     * keep a drain loop alive, or every successful batch would create another
     * batch forever. They still ride along with the next real payload.
     */
    fun hasPendingNonUpload(context: Context): Boolean = synchronized(lock) {
        ensureLoaded(context)
        trimLocked(System.currentTimeMillis())
        events.any { (_, json) -> !isUploadDiagnosticEvent(json) } || droppedEvents > 0
    }

    internal fun isUploadDiagnosticEvent(json: String): Boolean =
        json.contains("\"type\":\"telemetry_upload_ok\"") ||
            json.contains("\"type\":\"telemetry_upload_failed\"")

    /** Called after the server accepted a payload containing events up to [seq]. */
    fun ack(context: Context, seq: Long) = synchronized(lock) {
        val f = ensureLoaded(context)
        while (events.isNotEmpty() && events.first().first <= seq) events.removeFirst()
        droppedEvents = 0
        droppedByCap = 0
        droppedByAge = 0
        writeLocked(f)
    }

    private fun trimLocked(now: Long) {
        while (events.size > MAX_EVENTS) {
            events.removeFirst()
            droppedEvents++
            droppedByCap++
        }
        while (events.isNotEmpty() && tsOf(events.first().second) < now - MAX_AGE_MS) {
            events.removeFirst()
            droppedEvents++
            droppedByAge++
        }
    }

    private fun tsOf(json: String): Long {
        val i = json.indexOf("\"ts\":")
        if (i < 0) return 0
        return json.substring(i + 5).takeWhile { it.isDigit() }.toLongOrNull() ?: 0
    }

    private fun writeLocked(f: File) {
        // Telemetry events are best effort: any IO error is swallowed here so a
        // bad disk can never propagate into the service/upload threads.
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            // A directory squatting on the temp path would make every write fail.
            if (tmp.isDirectory) tmp.deleteRecursively()
            tmp.writeText(events.joinToString("") { (seq, json) -> "$seq\t$json\n" }, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }
}
