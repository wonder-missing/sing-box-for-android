package io.nekohasekai.sfa.agent

sealed interface InputGuardResult {
    data object Allowed : InputGuardResult

    data class Blocked(val reason: String) : InputGuardResult
}

fun interface AgentInputGuard {
    fun check(text: String): InputGuardResult
}

/**
 * Best-effort local guard for natural-language input. It catches the common
 * ways a subscription link, credential or log excerpt reaches the composer.
 * It is not a content classifier and must stay local: a match is never sent to
 * the model.
 */
object DefaultAgentInputGuard : AgentInputGuard {
    private val SHARE_LINK =
        Regex(
            """(?i)\b(?:ss|vmess|vless|trojan|hysteria2?|hy2|tuic|wireguard)://[^\s"'<>]+""",
        )
    private val HTTP_USERINFO =
        Regex("""(?i)\bhttps?://[^/\s@]+:[^/\s@]+@""")
    private val SENSITIVE_QUERY =
        Regex(
            """(?i)[?&](?:token|access_token|secret|password|passwd|pwd|api[_-]?key|auth|authorization|subscription|sub_url)=[^&#\s]+""",
        )
    private val SENSITIVE_KV =
        Regex(
            """(?i)\b(?:token|access_token|secret|password|passwd|pwd|api[_-]?key|authorization)\b\s*[:=]\s*["']?[^"'\s,;}]+""",
        )
    private val BEARER = Regex("""(?i)\bauthorization\s*:\s*bearer\s+\S+""")
    private val LOG_LIKE = Regex("""(?im)^\s*(?:time|level|msg)=|\bsing-box\b.*\berror\b""")

    override fun check(text: String): InputGuardResult {
        val patterns =
            listOf(
                "subscription_link" to SHARE_LINK,
                "url_credentials" to HTTP_USERINFO,
                "sensitive_url" to SENSITIVE_QUERY,
                "credential" to SENSITIVE_KV,
                "authorization_header" to BEARER,
                "log_excerpt" to LOG_LIKE,
            )
        patterns.forEach { (reason, pattern) ->
            if (pattern.containsMatchIn(text)) {
                return InputGuardResult.Blocked(reason)
            }
        }
        return InputGuardResult.Allowed
    }
}
