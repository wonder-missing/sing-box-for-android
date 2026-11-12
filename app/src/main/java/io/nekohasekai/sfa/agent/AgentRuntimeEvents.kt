package io.nekohasekai.sfa.agent

import kotlinx.serialization.Serializable

/**
 * A framework event that is carried by the next real user message instead of
 * being injected as a fake assistant message. Chat Completions has no control
 * role, so a reserved user-message prefix is the provider-compatible encoding.
 */
@Serializable
data class TurnEvent(
    val type: String,
    val reason: String,
    /**
     * Sanitized, one-line failure detail. It lets the next turn explain why a
     * previous request failed without asking the user to repeat the message.
     */
    val message: String? = null,
)

internal const val RUNTIME_EVENT_PREFIX = "[conductor:event"

/**
 * Prepends a runtime event to the user message and escapes user-authored lines
 * that try to look like framework events. The UI keeps the raw user text; only
 * the model transcript sees the envelope.
 */
internal fun formatUserMessage(
    text: String,
    event: TurnEvent?,
): String {
    val escaped =
        text.lines().joinToString("\n") { line ->
            // Runtime events are recognized at the start of a line; a user line
            // with leading spaces must not be able to impersonate one.
            if (line.trimStart().startsWith(RUNTIME_EVENT_PREFIX)) "\\$line" else line
        }
    if (event == null) return escaped
    val detail =
        event.message
            ?.take(MAX_EVENT_MESSAGE_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?.let { " message=\"${escapeEventValue(it)}\"" }
            .orEmpty()
    return "$RUNTIME_EVENT_PREFIX ${event.type} reason=${event.reason}$detail]\n$escaped"
}

private const val MAX_EVENT_MESSAGE_CHARS = 400

private fun escapeEventValue(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
