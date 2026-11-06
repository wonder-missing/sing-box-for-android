package io.nekohasekai.sfa.telemetry

/**
 * Turns raw sing-box log lines into small aggregated events so success/failure
 * rates can be computed server-side without grepping. Mirrors classify() in the
 * ingest Worker (CFG/telemetry-ingest/index.js). URLs are reduced to scheme+host;
 * Tailscale AuthURLs are never included.
 */
internal object TelemetryEvents {
    private const val MAX_EVENTS = 200

    private data class Event(
        val type: String,
        val t: Int?,
        val group: String? = null,
        val outbound: String? = null,
        val key: String? = null,
        val sample: String? = null,
        val ms: Int? = null,
    )

    private class Agg(val first: Event) {
        var n = 0
        var t1: Int? = null
        var msSum = 0L
        var msMax = 0
        var msCount = 0
    }

    private val HEAD = Regex("^([A-Z]+)\\[(\\d+)\\]\\s*(.*)$")
    private val CONN_ID = Regex("^\\[\\d+ [\\d.]+\\w*\\]\\s*")
    private val URL = Regex("(https?://[^\\s/\"?#]+)[^\\s\"]*")
    private val IPN_STATE = Regex("Switching ipn state (\\w+) -> (\\w+)")
    private val HTTP_ERR = Regex("http (\\d{3})[: ]+(.{0,80})", RegexOption.IGNORE_CASE)
    private val URLTEST = Regex("outbound/urltest\\[([^\\]]+)\\]: outbound (.+?) (available|unavailable): (.*)$")
    private val OPEN_CONN = Regex("open connection to (\\S+) using outbound/\\w+\\[([^\\]]+)\\]: (.*)$")
    private val CATEGORY = Regex("^\\s*([\\w/-]+)(\\[[^\\]]*\\])?:")
    private val DURATION = Regex("\\(([\\d.]+m?s)\\)")
    private val LEADING_INT = Regex("^\\d+")

    fun scrub(value: String): String = URL.replace(value) { it.groupValues[1] + "/…" }.take(200)

    fun reasonOf(text: String): String {
        val s = text.lowercase()
        return when {
            "i/o timeout" in s -> "io_timeout"
            "deadline exceeded" in s -> "deadline"
            "reset by peer" in s -> "reset"
            "unexpected eof" in s -> "unexpected_eof"
            Regex("\\beof\\b").containsMatchIn(s) -> "eof"
            "refused" in s -> "refused"
            "no route" in s || "unreachable" in s -> "unreachable"
            "tls" in s || "certificate" in s -> "tls"
            else -> "other"
        }
    }

    private fun classify(line: String): Event? {
        val head = HEAD.find(line)
        val level = head?.groupValues?.get(1).orEmpty()
        val t = head?.groupValues?.get(2)?.toIntOrNull()
        val msg = CONN_ID.replace(head?.groupValues?.get(3) ?: line, "")
        if (msg.startsWith("WONDER service start")) return Event("service_start", t)
        if (msg.startsWith("WONDER service stop")) return Event("service_stop", t)
        if ("sing-box started" in msg) return Event("core_started", t, key = DURATION.find(msg)?.groupValues?.get(1))
        if ("endpoint/tailscale" in msg) {
            IPN_STATE.find(msg)?.let {
                val to = it.groupValues[2]
                return Event(if (to == "Running") "ts_logged_in" else "ts_state", t, key = to)
            }
            if ("AuthURL is" in msg) return Event("ts_auth_url", t)
            HTTP_ERR.find(msg)?.let {
                val code = it.groupValues[1].toInt()
                if (code >= 400) return Event("ts_login_error", t, key = "http_$code", sample = scrub(it.groupValues[2]))
            }
            if (("netmap: " in msg || "netmap packet filter: [" in msg) && "not ready" !in msg) return Event("ts_netmap", t)
            return null
        }
        URLTEST.find(msg)?.let {
            val (group, outbound, state, rest) = it.destructured
            return if (state == "available") {
                Event("urltest_ok", t, group = group, outbound = outbound, ms = LEADING_INT.find(rest)?.value?.toIntOrNull())
            } else {
                Event("urltest_fail", t, group = group, outbound = outbound, key = reasonOf(rest))
            }
        }
        if ("reset by peer" in msg && "handshake" in msg) return Event("handshake_reset", t)
        if ("unexpected EOF" in msg && msg.contains("dns", ignoreCase = true)) return Event("dns_eof", t)
        if (level == "ERROR" || level == "FATAL") {
            OPEN_CONN.find(msg)?.let {
                return Event("conn_error", t, outbound = it.groupValues[2], key = reasonOf(it.groupValues[3]), sample = it.groupValues[1])
            }
            val category = CATEGORY.find(msg)?.groupValues?.get(1) ?: "unknown"
            return Event("error", t, key = "$category:${reasonOf(msg)}", sample = scrub(msg.take(160)))
        }
        return null
    }

    /** Returns a JSON array of aggregated events for [lines]. */
    fun toJson(lines: List<String>): String {
        val aggs = LinkedHashMap<String, Agg>()
        for (line in lines) {
            val event = classify(line) ?: continue
            val k = listOf(event.type, event.group.orEmpty(), event.outbound.orEmpty(), event.key.orEmpty()).joinToString("|")
            val agg = aggs[k] ?: if (aggs.size >= MAX_EVENTS) continue else Agg(event).also { aggs[k] = it }
            agg.n++
            if (event.t != null) agg.t1 = event.t
            if (event.ms != null) {
                agg.msSum += event.ms
                agg.msCount++
                if (event.ms > agg.msMax) agg.msMax = event.ms
            }
        }
        val out = StringBuilder("[")
        var first = true
        for (agg in aggs.values) {
            if (!first) out.append(',')
            first = false
            val e = agg.first
            out.append("{\"type\":").append(TelemetryPayload.quote(e.type)).append(",\"n\":").append(agg.n)
            e.group?.let { out.append(",\"group\":").append(TelemetryPayload.quote(it)) }
            e.outbound?.let { out.append(",\"outbound\":").append(TelemetryPayload.quote(it)) }
            e.key?.let { out.append(",\"key\":").append(TelemetryPayload.quote(it)) }
            e.sample?.let { out.append(",\"sample\":").append(TelemetryPayload.quote(it)) }
            e.t?.let { out.append(",\"t0\":").append(it) }
            agg.t1?.let { out.append(",\"t1\":").append(it) }
            if (agg.msCount > 0) out.append(",\"ms_sum\":").append(agg.msSum).append(",\"ms_max\":").append(agg.msMax)
            out.append('}')
        }
        return out.append(']').toString()
    }
}
