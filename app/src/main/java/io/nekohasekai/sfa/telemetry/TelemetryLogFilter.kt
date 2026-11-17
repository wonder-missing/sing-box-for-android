package io.nekohasekai.sfa.telemetry

/**
 * Verbose Tailscale TRACE lines contain peer addresses, DERP regions and
 * netcheck details. Keep them out of the upload queue; INFO/WARN state lines
 * still feed the existing ts_* events.
 */
internal object TelemetryLogFilter {
    private val TAILSCALE_TRACE = Regex("""\bTRACE\[\d+\]\s+endpoint/tailscale\[tailscale\]:""")

    fun shouldUpload(raw: String): Boolean = !TAILSCALE_TRACE.containsMatchIn(raw)
}
