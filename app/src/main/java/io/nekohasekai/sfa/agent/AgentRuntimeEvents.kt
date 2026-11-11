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
            if (line.startsWith(RUNTIME_EVENT_PREFIX)) "\\$line" else line
        }
    if (event == null) return escaped
    return "$RUNTIME_EVENT_PREFIX ${event.type} reason=${event.reason}]\n$escaped"
}
