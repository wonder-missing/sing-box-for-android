package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool results cross the conversation boundary, so anything that looks like a
 * subscription link, share link or credential is redacted. The probe URL is
 * allowed through (the caller supplied it) but its sensitive query values are
 * still masked.
 */
internal object AgentSanitizer {
    private val URL_REGEX =
        Regex(
            """(?i)\b(?:https?|ss|vmess|vless|trojan|hysteria2?|hy2|tuic|wireguard)://[^\s"'<>]+""",
        )
    private val SENSITIVE_KV =
        Regex(
            """(?i)\b(token|access_token|secret|password|passwd|pwd|api[_-]?key|authorization|subscription)\b\s*[:=]\s*("?)([^"&\s,;}]+)\2""",
        )
    private val SENSITIVE_QUERY =
        Regex(
            """(?i)([?&](?:token|access_token|secret|password|passwd|pwd|api[_-]?key|auth|authorization)=)([^&#\s]+)""",
        )
    private val URL_USERINFO =
        Regex(
            """(?i)\b((?:https?|ss|vmess|vless|trojan|hysteria2?|hy2|tuic|wireguard)://)([^/@\s]+)@""",
        )
    private val SENSITIVE_KEY =
        Regex(
            """(?i)^(token|access_token|secret|password|passwd|pwd|api[_-]?key|authorization|subscription|sub_url|subscribe_url|configuration)$""",
        )
    private const val TRAILING_PUNCTUATION = ".,;:)]}\"'"

    fun sanitizeText(
        text: String,
        allowedUrls: Set<String> = emptySet(),
    ): String {
        var result =
            URL_REGEX.replace(text) { match ->
                val raw = match.value
                val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
                val core = if (trailing.isEmpty()) raw else raw.dropLast(trailing.length)
                if (core in allowedUrls) {
                    maskUrlQuery(core) + trailing
                } else {
                    "[redacted-url]"
                }
            }
        result =
            SENSITIVE_KV.replace(result) { match ->
                "${match.groupValues[1]}=***"
            }
        return result
    }

    fun maskUrlQuery(url: String): String {
        val maskedUserInfo = URL_USERINFO.replace(url) { match -> match.groupValues[1] + "***@" }
        return SENSITIVE_QUERY.replace(maskedUserInfo) { match ->
            match.groupValues[1] + "***"
        }
    }

    fun sanitizeForLog(text: String): String {
        val withoutUrls = URL_REGEX.replace(text) { "[redacted-url]" }
        return SENSITIVE_KV.replace(withoutUrls) { match ->
            "${match.groupValues[1]}=***"
        }
    }

    fun sanitizeElement(
        element: JsonElement,
        allowedUrls: Set<String> = emptySet(),
    ): JsonElement =
        when (element) {
            is JsonObject -> {
                buildJsonObject {
                    element.forEach { (key, value) ->
                        if (SENSITIVE_KEY.matches(key)) {
                            put(key, JsonPrimitive("***"))
                        } else {
                            put(key, sanitizeElement(value, allowedUrls))
                        }
                    }
                }
            }

            is JsonArray -> {
                buildJsonArray {
                    element.forEach { add(sanitizeElement(it, allowedUrls)) }
                }
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    JsonPrimitive(sanitizeText(element.content, allowedUrls))
                } else {
                    element
                }
            }

            else -> {
                element
            }
        }

    fun sanitizeArgsJson(
        raw: String,
        allowedUrls: Set<String> = emptySet(),
    ): String {
        val objectValue =
            AgentJson.parseObject(raw)
                ?: return sanitizeText(raw, allowedUrls)
        return sanitizeElement(objectValue, allowedUrls).toString()
    }
}
