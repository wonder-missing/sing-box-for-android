package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AgentMessage {
    data class System(
        val content: String,
    ) : AgentMessage

    data class User(
        val content: String,
    ) : AgentMessage

    data class Assistant(
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : AgentMessage

    data class Tool(
        val toolCallId: String,
        val name: String,
        val content: String,
    ) : AgentMessage
}

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolObservation(
    val ok: Boolean,
    val tool: String,
    val output: JsonObject? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val truncated: Boolean = false,
    val originalChars: Int? = null,
    val degraded: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("ok", ok)
            put("tool", tool)
            output?.let { put("output", it) }
            errorCode?.let { put("errorCode", it) }
            message?.let { put("message", it) }
            if (truncated) put("truncated", true)
            if (degraded) put("degraded", true)
            originalChars?.let { put("originalChars", it) }
        }

    fun asToolMessage(name: String, toolCallId: String): AgentMessage.Tool =
        AgentMessage.Tool(
            toolCallId = toolCallId,
            name = name,
            content = toJson().toString(),
        )
}

/**
 * OpenAI-compatible providers require every tool call to be followed by exactly
 * one tool message before the next assistant/user turn. A malformed transcript
 * would make every later request fail, so the runtime fails closed before any
 * request is sent.
 */
fun validateTranscriptPairing(messages: List<AgentMessage>): Boolean {
    val pending = LinkedHashSet<String>()
    messages.forEach { message ->
        when (message) {
            is AgentMessage.System,
            is AgentMessage.User -> if (pending.isNotEmpty()) return false

            is AgentMessage.Assistant -> {
                if (pending.isNotEmpty()) return false
                val ids = message.toolCalls.map { it.id }
                if (ids.any { it.isBlank() } || ids.size != ids.toSet().size) return false
                pending.addAll(ids)
            }

            is AgentMessage.Tool -> {
                if (!pending.remove(message.toolCallId)) return false
            }
        }
    }
    return pending.isEmpty()
}
