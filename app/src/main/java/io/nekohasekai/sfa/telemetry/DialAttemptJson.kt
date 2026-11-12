package io.nekohasekai.sfa.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses the JSON array from `Libbox.readDialAttempts()`. Pure so unit tests
 * never load the native library. Phone network identity is not in this JSON;
 * the runtime stamps it when the attempt is copied into a client event.
 */
internal object DialAttemptJson {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_ATTEMPTS = 256

    fun parse(raw: String?): List<Map<String, Any?>> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>(minOf(array.size, MAX_ATTEMPTS))
        for (element in array) {
            if (out.size >= MAX_ATTEMPTS) break
            val obj = element as? JsonObject ?: continue
            val outbound = obj.string("outbound")?.take(80)?.takeIf { it.isNotBlank() } ?: continue
            val fields = LinkedHashMap<String, Any?>()
            fields["outbound"] = outbound
            obj.string("network")?.take(16)?.let { fields["network"] = it }
            obj.string("entry")?.take(160)?.let { fields["entry"] = it }
            when (obj.string("result")) {
                "ok" -> fields["result"] = "ok"
                "fail" -> fields["result"] = "fail"
            }
            obj.string("error_class")?.take(32)?.takeIf { it.isNotBlank() }?.let { fields["error_class"] = it }
            obj.string("cause")?.take(16)?.takeIf { it.isNotBlank() }?.let { fields["cause"] = it }
            obj.long("duration_ms")?.let { fields["duration_ms"] = it.coerceAtLeast(0) }
            obj.long("ts")?.let { fields["dial_ts"] = it }
            out.add(fields)
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
