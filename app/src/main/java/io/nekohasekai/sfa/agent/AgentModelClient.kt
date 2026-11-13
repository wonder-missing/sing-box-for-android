package io.nekohasekai.sfa.agent

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Starts a new cancel generation. A cancel that arrived before the turn
     * began must not abort this turn, and a cancel during it must not be
     * cleared by the next stream.
     */
    fun resetCancel() = Unit
}

/**
 * A cancel is tied to one stream generation. A late cancel from the previous
 * turn cannot leak into the next one, and a cancel that arrives before a new
 * stream starts is not cleared when that stream begins.
 */
internal class CancelGeneration {
    private val generation = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)

    fun current(): Long = generation.get()

    fun cancel() {
        cancelled.set(true)
        generation.incrementAndGet()
    }

    fun reset() {
        cancelled.set(false)
        generation.incrementAndGet()
    }

    fun isCancelled(at: Long): Boolean = cancelled.get() || generation.get() != at
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
    /** null omits max_tokens and lets the provider use its own default. */
    val maxOutputTokens: Int?,
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
