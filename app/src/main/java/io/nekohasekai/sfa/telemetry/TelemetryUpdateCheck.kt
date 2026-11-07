package io.nekohasekai.sfa.telemetry

import io.nekohasekai.sfa.Application

/**
 * Result of an in-app update check (GitHub Releases / F-Droid). Only the source,
 * outcome, version code and a scrubbed error class/message are recorded; the
 * GitHub token and release URLs never enter telemetry.
 */
internal object TelemetryUpdateCheck {
    fun record(source: String, versionCode: Int?, error: Throwable?) {
        // Telemetry is best effort; an update check must never fail because of it.
        runCatching {
            val fields = buildMap<String, Any?> {
                put("source", source)
                put("ok", error == null)
                if (error == null) put("has_update", versionCode != null)
                versionCode?.let { put("version_code", it) }
                error?.let {
                    put("error", it.javaClass.simpleName)
                    val detail = TelemetryEvents.scrub(it.message ?: "").take(120)
                    if (detail.isNotBlank()) put("detail", detail)
                }
            }
            TelemetryEventLog.record(Application.application, "update_check", fields)
        }
    }
}
