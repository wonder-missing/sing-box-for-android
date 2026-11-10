package io.nekohasekai.sfa.agent

private val HTTP_STATUS = Regex("""\bHTTP\s+(\d{3})\b""")

internal fun parseProbeHttpStatus(message: String?): Int? =
    message?.let {
        HTTP_STATUS
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

/**
 * libbox throws for non-200 responses, including 204, and puts the status in
 * the exception message. Any parsed HTTP status means the path reached a
 * server, so it is a reachable probe; only exceptions without a status are
 * transport failures.
 */
internal fun classifyProbeFailure(
    message: String?,
    latencyMs: Long,
): ProbeOutcome {
    val status = parseProbeHttpStatus(message)
    return if (status != null && status in 100..599) {
        ProbeOutcome(statusCode = status, latencyMs = latencyMs)
    } else {
        ProbeOutcome(latencyMs = latencyMs, error = message ?: "probe failed")
    }
}
