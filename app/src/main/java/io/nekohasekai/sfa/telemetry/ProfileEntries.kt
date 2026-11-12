package io.nekohasekai.sfa.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * tag → configured `server:port` from a profile. This is the address written
 * in the profile, not the IP the dialer actually used. Passwords, UUIDs and
 * other secrets are never read.
 */
internal object ProfileEntries {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(config: String): Map<String, String> {
        if (config.isBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(config) }.getOrNull() as? JsonObject ?: return emptyMap()
        val outbounds = root["outbounds"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (element in outbounds) {
            val obj = element as? JsonObject ?: continue
            val tag = obj.string("tag")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: continue
            val server = obj.string("server") ?: continue
            if (!serverOk(server)) continue
            val port = obj.int("server_port") ?: continue
            if (port !in 1..65535) continue
            val entry = "$server:$port"
            if (entry.length > 160) continue
            out[tag] = entry
        }
        return out
    }

    private fun serverOk(server: String): Boolean {
        if (server.isBlank() || server.length > 253) return false
        if (server.any { it.isWhitespace() || it == '/' || it == '@' || it == '\\' || it == '"' }) return false
        return true
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.contentOrNull
    }

    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }
}
