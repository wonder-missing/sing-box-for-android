package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject

enum class AgentTurnErrorCode {
    NO_API_KEY,
    AUTH_FAILED,
    INSUFFICIENT_BALANCE,
    RATE_LIMITED,
    MODEL_TIMEOUT,
    MODEL_UNAVAILABLE,
    MODEL_NOT_FOUND,
    INVALID_REQUEST,
    CONFLICT,
    INVALID_MODEL_RESPONSE,
    INVALID_TRANSCRIPT,
    CONFIRMATION_TIMEOUT,
    TURN_CANCELLED,
    TOOL_CANCEL_TIMEOUT,
    STEP_BUDGET_EXCEEDED,
    CONTENT_FILTERED,
    OUTPUT_TRUNCATED,
    UNKNOWN,
}

data class AgentError(
    val code: AgentTurnErrorCode,
    val message: String,
    val retryable: Boolean = false,
)

sealed interface TurnState {
    data object Idle : TurnState

    data class CallingModel(val step: Int) : TurnState

    data class RunningTool(val step: Int, val call: ToolCallView) : TurnState

    data class AwaitingConfirmation(
        val step: Int,
        val request: ConfirmationRequest,
    ) : TurnState

    data class Cancelling(val reason: String) : TurnState

    data class Completed(val answer: String) : TurnState

    data class Cancelled(val reason: String) : TurnState

    data class Failed(val error: AgentError) : TurnState
}

sealed interface TurnOutcome {
    data class Completed(val answer: String) : TurnOutcome

    data class Cancelled(val reason: String) : TurnOutcome

    data class Failed(val error: AgentError) : TurnOutcome
}

enum class ToolStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class ToolCallView(
    val id: String,
    val name: String,
    val displayArgsJson: String,
)

data class ToolResultView(
    val callId: String,
    val name: String,
    val ok: Boolean,
    val output: JsonObject?,
    val errorCode: String?,
    val message: String?,
    val truncated: Boolean,
)

data class ConfirmationRequest(
    val id: Long,
    val toolName: String,
    val displayArgsJson: String,
    val reason: String,
)

sealed interface AgentEvent {
    data class TurnStarted(val turnId: String) : AgentEvent

    data class StateChanged(val state: TurnState) : AgentEvent

    data class AssistantMessage(val text: String) : AgentEvent

    data class AssistantDelta(val text: String) : AgentEvent

    data class ToolStarted(val call: ToolCallView) : AgentEvent

    data class ToolFinished(val result: ToolResultView) : AgentEvent

    data class ConfirmationRequested(val request: ConfirmationRequest) : AgentEvent

    data class Notice(val text: String) : AgentEvent

    data class TurnFinished(val outcome: TurnOutcome) : AgentEvent
}

class AgentModelException(
    val code: AgentTurnErrorCode,
    override val message: String,
    val retryable: Boolean = false,
) : Exception(message)
