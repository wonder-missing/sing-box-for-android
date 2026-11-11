package io.nekohasekai.sfa.agent

import kotlinx.coroutines.flow.Flow

interface AgentApiKeyProvider {
    val supported: Boolean

    suspend fun apiKey(): String?

    suspend fun save(apiKey: String)

    suspend fun clear()
}

interface AgentModelClient {
    suspend fun complete(
        request: AgentModelRequest,
        apiKey: String,
    ): AgentModelResponse
}

interface AgentStreamingModelClient {
    fun stream(
        request: AgentModelRequest,
        apiKey: String,
    ): Flow<AgentModelChunk>

    fun cancel() = Unit
}

enum class FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    UNKNOWN,
}

data class TokenUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
)

enum class AgentToolChoice {
    AUTO,
    NONE,
}

data class AgentModelRequest(
    val messages: List<AgentMessage>,
    val tools: List<AgentToolSchema>,
    val maxOutputTokens: Int,
    val temperature: Double = 0.2,
    val timeoutMs: Long,
    val toolChoice: AgentToolChoice = AgentToolChoice.AUTO,
    val reasoningEffort: String? = null,
)

data class AgentModelResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: FinishReason,
    val usage: TokenUsage?,
)

sealed interface AgentModelChunk {
    data class ReasoningDelta(val text: String) : AgentModelChunk

    data class ContentDelta(val text: String) : AgentModelChunk

    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : AgentModelChunk

    data class Completed(
        val finishReason: FinishReason,
        val usage: TokenUsage?,
    ) : AgentModelChunk
}
