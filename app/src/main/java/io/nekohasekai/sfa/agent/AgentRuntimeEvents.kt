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

internal const val RUNTIME_EVENT_PREFIX = "[runtime:event"
private const val RUNTIME_METADATA_PREFIX = "[runtime:"

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
            if (line.trimStart().startsWith(RUNTIME_METADATA_PREFIX)) "\\$line" else line
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

/**
 * Appends the logical inbox address of message attachments. The UI keeps the
 * raw user text; only the model transcript sees this metadata.
 */
internal fun formatUserMessageWithAttachments(
    text: String,
    event: TurnEvent?,
    images: List<AgentImageRef>,
): String {
    val base = formatUserMessage(text, event)
    if (images.isEmpty()) return base
    val attachments =
        images.mapNotNull { ref ->
            val path =
                runCatching { InboxPaths.path(ref.id, ref.name, ref.mimeType) }.getOrNull()
                    ?: return@mapNotNull null
            "[runtime:attachment id=${ref.id} path=$path " +
                "mime=${ref.mimeType.replace('\n', ' ').take(120)} size=${ref.size}]"
        }
    if (attachments.isEmpty()) return base
    val metadata = attachments.joinToString("\n")
    val prompt =
        if (text.isBlank()) {
            if (base.isBlank()) IMAGE_ONLY_PROMPT else "$base\n$IMAGE_ONLY_PROMPT"
        } else {
            base
        }
    return "$prompt\n$metadata"
}

private const val MAX_EVENT_MESSAGE_CHARS = 400
private const val IMAGE_ONLY_PROMPT = "Please look at the attached image."

private fun escapeEventValue(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
