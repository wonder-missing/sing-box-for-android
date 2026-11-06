package io.nekohasekai.sfa.telemetry

internal object TelemetryPayload {
    const val FLUSH_INTERVAL_MS = 5L * 60L * 1000L
    const val FLUSH_LINE_COUNT = 500
    const val FLUSH_RAW_BYTES = 200 * 1024
    const val MAX_BATCH_JSON_BYTES = 256 * 1024
    const val MAX_GZIP_BYTES = 900 * 1024
    const val MAX_DISK_BYTES = 2 * 1024 * 1024
    const val MAX_DISK_LINES = 12000
    const val MAX_LINE_CHARS = 16 * 1024
    const val COLLECTOR_POLL_MS = 15_000L
    const val AUTH_PAUSE_MS = 30L * 60L * 1000L
    const val FINAL_FLUSH_TIMEOUT_MS = 12_000L
    const val MAX_STOP_BATCHES = 6
    const val MAX_DRAIN_BATCHES = 24

    fun normalizeLine(raw: String): String? {
        val stripped = raw.replace(ANSI_COLOR, "")
        val trimmed = stripped.trimEnd()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_LINE_CHARS) {
            trimmed
        } else {
            trimmed.substring(0, MAX_LINE_CHARS)
        }
    }

    fun build(
        device: String,
        appVersion: String,
        coreVersion: String,
        tsMillis: Long,
        lines: List<String>,
        batchId: String? = null,
        droppedLines: Int = 0,
        more: Int = 0,
        eventsJson: String? = null,
        clientEventsJson: String? = null,
    ): ByteArray {
        val json = StringBuilder(lines.sumOf { it.length } + 128 + (eventsJson?.length ?: 0))
        json.append("{\"device\":").append(quote(device))
        json.append(",\"app_version\":").append(quote(appVersion))
        json.append(",\"core_version\":").append(quote(coreVersion))
        json.append(",\"ts\":").append(tsMillis)
        if (batchId != null) json.append(",\"batch_id\":").append(quote(batchId))
        if (droppedLines > 0) json.append(",\"dropped_lines\":").append(droppedLines)
        if (more > 0) json.append(",\"more\":").append(more)
        if (eventsJson != null) json.append(",\"events\":").append(eventsJson)
        if (clientEventsJson != null) json.append(",\"client_events\":").append(clientEventsJson)
        json.append(",\"lines\":[")
        lines.forEachIndexed { index, line ->
            if (index > 0) json.append(',')
            json.append(quote(line))
        }
        json.append("]}")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun selectBatch(
        lines: List<String>,
        device: String,
        appVersion: String,
        coreVersion: String,
        tsMillis: Long,
        maxLines: Int = FLUSH_LINE_COUNT,
        maxRawBytes: Int = FLUSH_RAW_BYTES,
    ): List<String> {
        if (lines.isEmpty() || maxLines <= 0 || maxRawBytes <= 0) return emptyList()
        val chosen = ArrayList<String>(minOf(lines.size, maxLines))
        var rawBytes = 0
        for (line in lines) {
            if (chosen.size >= maxLines) break
            val lineBytes = line.toByteArray(Charsets.UTF_8).size
            if (chosen.isNotEmpty() && rawBytes + lineBytes > maxRawBytes) break
            chosen.add(line)
            val jsonSize = build(device, appVersion, coreVersion, tsMillis, chosen).size
            if (jsonSize > MAX_BATCH_JSON_BYTES) {
                chosen.removeAt(chosen.lastIndex)
                break
            }
            rawBytes += lineBytes
            if (chosen.size == 1 && rawBytes > maxRawBytes) break
        }
        return chosen
    }

    /** Stable id for a batch; matches the Worker's fallback hash for old clients. */
    fun batchId(device: String, lines: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(device.toByteArray(Charsets.UTF_8))
        digest.update('\n'.code.toByte())
        lines.forEachIndexed { index, line ->
            if (index > 0) digest.update('\n'.code.toByte())
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
            }
        }
        out.append('"')
        return out.toString()
    }

    private val ANSI_COLOR = Regex("\u001B\\[[;\\d]*m")
}
