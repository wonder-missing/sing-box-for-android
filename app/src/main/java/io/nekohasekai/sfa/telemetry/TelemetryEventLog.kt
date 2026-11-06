package io.nekohasekai.sfa.telemetry

import android.content.Context
import java.io.File

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

    private val lock = Any()
    private var file: File? = null
    private val events = ArrayDeque<Pair<Long, String>>() // seq to json
    private var nextSeq = 1L
    private var droppedEvents = 0

    private fun ensureLoaded(context: Context): File {
        file?.let { return it }
        val f = File(context.applicationContext.filesDir, "telemetry/events.log")
        if (f.isFile) {
            runCatching {
                f.readLines().forEach { line ->
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
        val json = StringBuilder("{\"type\":").append(TelemetryPayload.quote(type)).append(",\"ts\":").append(now)
        for ((k, v) in fields) {
            if (v == null) continue
            json.append(',').append(TelemetryPayload.quote(k)).append(':')
            when (v) {
                is Number, is Boolean -> json.append(v.toString())
                else -> json.append(TelemetryPayload.quote(v.toString().take(200)))
            }
        }
        json.append('}')
        synchronized(lock) {
            val f = ensureLoaded(context)
            events.addLast(nextSeq++ to json.toString())
            trimLocked(now)
            writeLocked(f)
        }
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
                .append(",\"n\":").append(droppedEvents).append('}')
        }
        out.append(']')
        (chosen.lastOrNull()?.first ?: 0L) to out.toString()
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) {
        ensureLoaded(context)
        events.isNotEmpty()
    }

    /** Called after the server accepted a payload containing events up to [seq]. */
    fun ack(context: Context, seq: Long) = synchronized(lock) {
        val f = ensureLoaded(context)
        while (events.isNotEmpty() && events.first().first <= seq) events.removeFirst()
        droppedEvents = 0
        writeLocked(f)
    }

    private fun trimLocked(now: Long) {
        while (events.size > MAX_EVENTS) {
            events.removeFirst()
            droppedEvents++
        }
        while (events.isNotEmpty() && tsOf(events.first().second) < now - MAX_AGE_MS) {
            events.removeFirst()
            droppedEvents++
        }
    }

    private fun tsOf(json: String): Long {
        val i = json.indexOf("\"ts\":")
        if (i < 0) return 0
        return json.substring(i + 5).takeWhile { it.isDigit() }.toLongOrNull() ?: 0
    }

    private fun writeLocked(f: File) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(events.joinToString("") { (seq, json) -> "$seq\t$json\n" })
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }
}
