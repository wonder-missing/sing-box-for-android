package io.nekohasekai.sfa.telemetry

import android.content.Context
import io.nekohasekai.sfa.bg.CrashReport
import io.nekohasekai.sfa.bg.CrashReportManager
import org.json.JSONObject
import java.io.File

/**
 * Compact summaries of local Tools → Crash Report entries for telemetry.
 * Only metadata.json, go.log and jvm.log are read. configuration.json is never
 * opened: it may contain subscriptions/tokens. Each report id is sent once and
 * marked locally only after the server accepted the payload.
 */
internal object CrashReportTelemetry {
    private const val MAX_REPORTS_PER_UPLOAD = 3
    private const val MAX_LOG_CHARS = 2_500
    private const val MAX_FIELD_CHARS = 300
    private const val METADATA_FILE_NAME = "metadata.json"
    private const val GO_LOG_FILE_NAME = "go.log"
    private const val JVM_LOG_FILE_NAME = "jvm.log"

    data class PendingEvent(val id: String, val json: String)

    fun pending(context: Context): List<PendingEvent> {
        val sent = TelemetryPreferences.sentCrashIds(context)
        return CrashReportManager.listReports()
            .asSequence()
            .filterNot { it.id in sent }
            .take(MAX_REPORTS_PER_UPLOAD)
            .mapNotNull(::buildEvent)
            .toList()
    }

    private fun buildEvent(report: CrashReport): PendingEvent? {
        val metadata = readMetadata(report)
        val exception = metadata.optString("exceptionName").take(MAX_FIELD_CHARS)
        val reason = TelemetryEvents.scrub(metadata.optString("exceptionReason"), MAX_FIELD_CHARS)
        val goLog = readLog(File(report.directory, GO_LOG_FILE_NAME))
        val jvmLog = readLog(File(report.directory, JVM_LOG_FILE_NAME))
        if (exception.isBlank() && reason.isBlank() && goLog.isBlank() && jvmLog.isBlank()) {
            return null
        }
        val json = JSONObject().apply {
            put("type", "crash_report")
            put("id", report.id)
            put("ts", report.date.time)
            metadata.optString("crashedAt").takeIf { it.isNotBlank() }?.let { put("at", it.take(64)) }
            metadata.optString("source").takeIf { it.isNotBlank() }?.let { put("source", it.take(32)) }
            metadata.optString("processName").takeIf { it.isNotBlank() }?.let { put("process", it.take(128)) }
            if (exception.isNotBlank()) put("exception", exception)
            if (reason.isNotBlank()) put("reason", reason)
            metadata.optString("appMarketingVersion").takeIf { it.isNotBlank() }
                ?.let { put("app_version", it.take(64)) }
                ?: metadata.optString("appVersion").takeIf { it.isNotBlank() }
                    ?.let { put("app_version", it.take(64)) }
            metadata.optString("coreVersion").takeIf { it.isNotBlank() }
                ?.let { put("core_version", it.take(64)) }
            if (goLog.isNotEmpty()) put("go_log", goLog)
            if (jvmLog.isNotEmpty()) put("jvm_log", jvmLog)
        }.toString()
        return PendingEvent(report.id, json)
    }

    private fun readMetadata(report: CrashReport): JSONObject {
        val file = File(report.directory, METADATA_FILE_NAME)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun readLog(file: File): String {
        if (!file.isFile) return ""
        val head = runCatching {
            file.inputStream().bufferedReader().use { reader ->
                val buffer = CharArray(MAX_LOG_CHARS)
                var read = 0
                while (read < buffer.size) {
                    val count = reader.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                String(buffer, 0, read)
            }
        }.getOrDefault("")
        return TelemetryEvents.scrub(head.trim(), MAX_LOG_CHARS)
    }
}
