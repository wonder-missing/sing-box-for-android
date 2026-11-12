package io.nekohasekai.sfa.telemetry

/**
 * Encodes one `client_events` entry. Pure so the session tagging can be covered
 * by JVM tests without an Android Context.
 */
internal object TelemetryClientEvent {
    fun encode(
        type: String,
        ts: Long,
        fields: Map<String, Any?>,
        sessionId: String? = null,
    ): String {
        val json = StringBuilder("{\"type\":").append(TelemetryPayload.quote(type)).append(",\"ts\":").append(ts)
        var hasSession = false
        for ((k, v) in fields) {
            if (v == null) continue
            if (k == SESSION_FIELD) hasSession = true
            json.append(',').append(TelemetryPayload.quote(k)).append(':')
            when (v) {
                is Number, is Boolean -> json.append(v.toString())
                is JsonRaw -> json.append(v.json)
                else -> json.append(TelemetryPayload.quote(v.toString().take(200)))
            }
        }
        if (sessionId != null && !hasSession) {
            json.append(',').append(TelemetryPayload.quote(SESSION_FIELD)).append(':')
                .append(TelemetryPayload.quote(sessionId))
        }
        json.append('}')
        return json.toString()
    }

    const val SESSION_FIELD = "vpn_session_id"
}

/** A JSON object or array inserted without quoting. The caller builds it. */
internal class JsonRaw(json: String) {
    val json: String = if (json.startsWith("{") || json.startsWith("[")) json else "null"
}
