package io.nekohasekai.sfa.telemetry

/**
 * One-minute rollup of real user connections from the connection stream.
 * This is not a dial attempt: one proxy entry can carry many user flows, and
 * a flow can close with zero bytes after the entry was already up.
 *
 * [firstByteMs] is the time from NEW until the first positive downlink delta.
 * The stream interval is 1s, so the value is quantized to that interval.
 * [errorClass] is set only when an ERROR log for the same outbound lands
 * within [ERROR_WINDOW_MS] of a zero-downlink close. Otherwise it stays empty.
 */
internal enum class ConnKind { NEW, UPDATE, CLOSED, OTHER }

internal data class ConnSample(
    val reset: Boolean = false,
    val kind: ConnKind,
    val id: String,
    val outbound: String = "",
    val outboundType: String = "",
    val network: String = "",
    val destination: String = "",
    val protocol: String = "",
    val createdAt: Long = 0,
    val closedAt: Long = 0,
    val uplinkDelta: Long = 0,
    val downlinkDelta: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
)

internal data class MinuteFlush(
    val minuteTs: Long,
    val rowsJson: String,
    val truncated: Int,
)

internal fun destCategory(protocol: String, destination: String, network: String): String {
    val proto = protocol.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(16)
    if (proto.isNotEmpty()) {
        return when (proto) {
            "tls" -> "https"
            "http" -> "http"
            "https" -> "https"
            "dns" -> "dns"
            "quic" -> "quic"
            else -> proto
        }
    }
    val port = destination.substringAfterLast(':', "").toIntOrNull()
    return when (port) {
        443 -> "https"
        80 -> "http"
        53 -> "dns"
        853 -> "dot"
        else -> if (network.equals("udp", ignoreCase = true)) "udp" else "other"
    }
}

internal class ConnectionMinuteAggregator {
    private val lock = Any()
    private val open = LinkedHashMap<String, Open>()
    private val awaiting = ArrayList<Awaiting>()
    private val closed = ArrayList<Closed>()
    private val recentErrors = ArrayDeque<RecentError>()
    private var entries: Map<String, String> = emptyMap()

    fun reset() {
        synchronized(lock) {
            open.clear()
            awaiting.clear()
            closed.clear()
            recentErrors.clear()
            entries = emptyMap()
        }
    }

    fun setEntries(next: Map<String, String>) {
        synchronized(lock) { entries = next }
    }

    fun noteFailure(outbound: String, errorClass: String, at: Long) {
        if (outbound.isBlank() || errorClass.isBlank()) return
        synchronized(lock) {
            expireErrors(at)
            val match = awaiting
                .filter { it.errorClass == null && it.outbound == outbound && kotlin.math.abs(at - it.closedAt) <= ERROR_WINDOW_MS }
                .minByOrNull { kotlin.math.abs(at - it.closedAt) }
            if (match != null) {
                match.errorClass = errorClass
            } else {
                recentErrors.addLast(RecentError(outbound, errorClass, at))
                while (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeFirst()
            }
        }
    }

    fun observe(sample: ConnSample, observedAt: Long) {
        synchronized(lock) {
            if (sample.reset) open.clear()
            if (sample.id.isBlank() || sample.kind == ConnKind.OTHER) return
            when (sample.kind) {
                ConnKind.NEW -> onNew(sample, observedAt)
                ConnKind.UPDATE -> onUpdate(sample, observedAt)
                ConnKind.CLOSED -> onClosed(sample, observedAt)
                ConnKind.OTHER -> Unit
            }
            if (open.size > MAX_OPEN) {
                val drop = open.keys.firstOrNull()
                if (drop != null) open.remove(drop)
            }
        }
    }

    /**
     * Emits completed minutes. [includeCurrent] also emits the minute in
     * progress and any connection still open, used when the service stops.
     */
    fun flush(now: Long, includeCurrent: Boolean): List<MinuteFlush> {
        synchronized(lock) {
            if (includeCurrent) {
                val snapshot = open.values.toList()
                open.clear()
                for (row in snapshot) {
                    finish(row, if (row.createdAt > 0) row.createdAt else now, now, now)
                }
            }
            settle(now, force = includeCurrent)
            val currentMinute = minuteOf(now)
            val ready = ArrayList<Closed>()
            val keep = ArrayList<Closed>()
            for (row in closed) {
                if (includeCurrent || row.minuteTs < currentMinute) ready.add(row) else keep.add(row)
            }
            closed.clear()
            closed.addAll(keep)
            if (ready.isEmpty()) return emptyList()
            return ready.groupBy { it.minuteTs }.toSortedMap().map { (minute, rows) ->
                toFlush(minute, rows)
            }
        }
    }

    private fun onNew(sample: ConnSample, observedAt: Long) {
        if (sample.outboundType == "dns") return
        val created = sample.createdAt.takeIf { it > 0 } ?: observedAt
        val down = sample.downlinkTotal.coerceAtLeast(0)
        open[sample.id] = Open(
            outbound = sample.outbound,
            dest = destCategory(sample.protocol, sample.destination, sample.network),
            createdAt = created,
            up = sample.uplinkTotal.coerceAtLeast(0),
            down = down,
            firstByteAt = if (down > 0) created else null,
        )
    }

    private fun onUpdate(sample: ConnSample, observedAt: Long) {
        val row = open[sample.id] ?: return
        val downDelta = sample.downlinkDelta.coerceAtLeast(0)
        row.up += sample.uplinkDelta.coerceAtLeast(0)
        row.down += downDelta
        if (row.firstByteAt == null && downDelta > 0) row.firstByteAt = observedAt
    }

    private fun onClosed(sample: ConnSample, observedAt: Long) {
        val existing = open.remove(sample.id)
        val row = existing ?: synthetic(sample, observedAt) ?: return
        if (sample.downlinkTotal > row.down) row.down = sample.downlinkTotal
        if (sample.uplinkTotal > row.up) row.up = sample.uplinkTotal
        val closedAt = sample.closedAt.takeIf { it > 0 } ?: observedAt
        finish(row, row.createdAt, closedAt, observedAt)
    }

    private fun synthetic(sample: ConnSample, observedAt: Long): Open? {
        if (sample.outboundType == "dns") return null
        if (sample.outbound.isBlank() && sample.destination.isBlank() && sample.protocol.isBlank()) return null
        val down = sample.downlinkTotal.coerceAtLeast(0)
        return Open(
            outbound = sample.outbound,
            dest = destCategory(sample.protocol, sample.destination, sample.network),
            createdAt = sample.createdAt.takeIf { it > 0 } ?: observedAt,
            up = sample.uplinkTotal.coerceAtLeast(0),
            down = down,
            firstByteAt = null,
        )
    }

    private fun finish(row: Open, createdAt: Long, closedAt: Long, observedAt: Long) {
        val duration = (closedAt - createdAt).coerceAtLeast(0)
        val first = row.firstByteAt?.let { (it - createdAt).coerceAtLeast(0) }
        val down = row.down > 0
        val entry = entries[row.outbound]
        if (down) {
            closed.add(
                Closed(
                    minuteTs = minuteOf(closedAt),
                    outbound = row.outbound,
                    entry = entry,
                    dest = row.dest,
                    down = true,
                    errorClass = "",
                    up = row.up,
                    downBytes = row.down,
                    durationMs = duration,
                    firstByteMs = first,
                ),
            )
            return
        }
        expireErrors(observedAt)
        val matched = recentErrors.lastOrNull {
            it.outbound == row.outbound && kotlin.math.abs(it.at - closedAt) <= ERROR_WINDOW_MS
        }
        if (matched != null) {
            recentErrors.remove(matched)
            closed.add(closedRow(row, closedAt, duration, matched.errorClass, entry))
        } else {
            awaiting.add(
                Awaiting(
                    outbound = row.outbound,
                    entry = entry,
                    dest = row.dest,
                    closedAt = closedAt,
                    holdUntil = closedAt + ERROR_WINDOW_MS,
                    up = row.up,
                    downBytes = row.down,
                    durationMs = duration,
                    errorClass = null,
                ),
            )
        }
    }

    private fun settle(now: Long, force: Boolean) {
        val iter = awaiting.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (force || item.holdUntil <= now) {
                iter.remove()
                closed.add(
                    Closed(
                        minuteTs = minuteOf(item.closedAt),
                        outbound = item.outbound,
                        entry = item.entry,
                        dest = item.dest,
                        down = false,
                        errorClass = item.errorClass.orEmpty(),
                        up = item.up,
                        downBytes = item.downBytes,
                        durationMs = item.durationMs,
                        firstByteMs = null,
                    ),
                )
            }
        }
    }

    private fun expireErrors(now: Long) {
        while (recentErrors.isNotEmpty() && now - recentErrors.first().at > ERROR_WINDOW_MS) {
            recentErrors.removeFirst()
        }
    }

    private fun closedRow(row: Open, closedAt: Long, duration: Long, errorClass: String, entry: String?) = Closed(
        minuteTs = minuteOf(closedAt),
        outbound = row.outbound,
        entry = entry,
        dest = row.dest,
        down = false,
        errorClass = errorClass,
        up = row.up,
        downBytes = row.down,
        durationMs = duration,
        firstByteMs = null,
    )

    private fun toFlush(minuteTs: Long, rows: List<Closed>): MinuteFlush {
        val buckets = LinkedHashMap<String, Bucket>()
        for (row in rows) {
            val key = listOf(row.outbound, row.entry.orEmpty(), row.dest, row.down.toString(), row.errorClass).joinToString("|")
            val bucket = buckets.getOrPut(key) {
                Bucket(row.outbound, row.entry, row.dest, row.down, row.errorClass)
            }
            bucket.n++
            bucket.up += row.up
            bucket.downBytes += row.downBytes
            bucket.durationSum += row.durationMs
            if (row.firstByteMs != null) {
                bucket.firstSum += row.firstByteMs
                bucket.firstN++
            }
        }
        val ordered = buckets.values.sortedByDescending { it.n }
        val kept = ordered.take(MAX_ROWS)
        val truncated = (ordered.size - kept.size).coerceAtLeast(0)
        return MinuteFlush(minuteTs, rowsJson(kept), truncated)
    }

    private fun rowsJson(buckets: List<Bucket>): String {
        val out = StringBuilder("[")
        buckets.forEachIndexed { index, bucket ->
            if (index > 0) out.append(',')
            out.append("{\"outbound\":").append(TelemetryPayload.quote(bucket.outbound))
            out.append(",\"dest\":").append(TelemetryPayload.quote(bucket.dest))
            out.append(",\"down\":").append(bucket.down)
            out.append(",\"n\":").append(bucket.n)
            out.append(",\"bytes_up\":").append(bucket.up)
            out.append(",\"bytes_down\":").append(bucket.downBytes)
            val duration = if (bucket.n == 0) 0 else bucket.durationSum / bucket.n
            out.append(",\"duration_ms\":").append(duration)
            if (!bucket.entry.isNullOrBlank()) {
                out.append(",\"entry\":").append(TelemetryPayload.quote(bucket.entry))
            }
            if (bucket.firstN > 0) {
                out.append(",\"first_byte_ms\":").append(bucket.firstSum / bucket.firstN)
            }
            if (bucket.errorClass.isNotEmpty()) {
                out.append(",\"error_class\":").append(TelemetryPayload.quote(bucket.errorClass))
            }
            out.append('}')
        }
        out.append(']')
        return out.toString()
    }

    private fun minuteOf(ts: Long): Long {
        if (ts <= 0) return 0
        return ts / MINUTE_MS * MINUTE_MS
    }

    private class Open(
        val outbound: String,
        val dest: String,
        val createdAt: Long,
        var up: Long,
        var down: Long,
        var firstByteAt: Long?,
    )

    private class Awaiting(
        val outbound: String,
        val entry: String?,
        val dest: String,
        val closedAt: Long,
        val holdUntil: Long,
        val up: Long,
        val downBytes: Long,
        val durationMs: Long,
        var errorClass: String?,
    )

    private data class Closed(
        val minuteTs: Long,
        val outbound: String,
        val entry: String?,
        val dest: String,
        val down: Boolean,
        val errorClass: String,
        val up: Long,
        val downBytes: Long,
        val durationMs: Long,
        val firstByteMs: Long?,
    )

    private data class RecentError(val outbound: String, val errorClass: String, val at: Long)

    private class Bucket(
        val outbound: String,
        val entry: String?,
        val dest: String,
        val down: Boolean,
        val errorClass: String,
        var n: Int = 0,
        var up: Long = 0,
        var downBytes: Long = 0,
        var durationSum: Long = 0,
        var firstSum: Long = 0,
        var firstN: Int = 0,
    )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val ERROR_WINDOW_MS = 2_000L
        const val MAX_ROWS = 40
        const val MAX_OPEN = 4096
        const val MAX_RECENT_ERRORS = 64
    }
}
