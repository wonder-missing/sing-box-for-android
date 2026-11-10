package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AgentConfig(
    val maxToolRounds: Int = 1,
    val maxToolCallsPerRound: Int = 4,
    val modelTimeoutMs: Long = 60_000,
    val turnTimeoutMs: Long = 300_000,
    val confirmationTimeoutMs: Long = 300_000,
    val maxOutputTokens: Int = 1_024,
    val maxInputTokens: Int = 16_000,
    val maxMessages: Int = 12,
    val maxObservationChars: Int = 4_000,
    val maxObservationCharsPerRound: Int = 8_000,
    val maxObservationCharsPerTurn: Int = 24_000,
    val maxRetries: Int = 2,
)

data class TurnResult(
    val messages: List<AgentMessage>,
    val outcome: TurnOutcome,
)

class AgentRuntime(
    private val inputGuard: AgentInputGuard,
    private val model: AgentModelClient,
    private val tools: AgentToolExecutor,
    private val apiKeys: AgentApiKeyProvider,
    private val config: AgentConfig = AgentConfig(),
) {
    private val confirmationIds = AtomicLong(0)

    @Volatile
    private var cancelRequested = false

    fun cancel(reason: String = "user") {
        cancelRequested = true
        tools.cancelActive()
    }

    suspend fun runTurn(
        userText: String,
        history: List<AgentMessage>,
        deviceSummary: String = "",
        onEvent: (AgentEvent) -> Unit,
        confirm: suspend (ConfirmationRequest) -> Boolean,
    ): TurnResult {
        cancelRequested = false
        val turnId = UUID.randomUUID().toString()
        onEvent(AgentEvent.TurnStarted(turnId))

        when (val guard = inputGuard.check(userText)) {
            is InputGuardResult.Blocked -> {
                onEvent(
                    AgentEvent.Notice(
                        "This message may contain a subscription link, token or log excerpt. " +
                            "It was not sent. Remove the sensitive part and try again.",
                    ),
                )
                return finish(history.toMutableList(), history.size, TurnOutcome.Cancelled(guard.reason), onEvent)
            }

            InputGuardResult.Allowed -> Unit
        }

        val apiKey =
            runCatching { apiKeys.apiKey() }
                .getOrElse {
                    return finish(
                        history.toMutableList(),
                        history.size,
                        TurnOutcome.Failed(AgentError(AgentTurnErrorCode.NO_API_KEY, "Could not read the API key.")),
                        onEvent,
                    )
                }
        if (apiKey.isNullOrBlank()) {
            return finish(
                history.toMutableList(),
                history.size,
                TurnOutcome.Failed(
                    AgentError(
                        AgentTurnErrorCode.NO_API_KEY,
                        "DeepSeek API key is not configured. Open Settings > Conductor.",
                    ),
                ),
                onEvent,
            )
        }

        val transcript = (history + AgentMessage.User(userText)).toMutableList()
        val startSize = history.size
        val startedAt = System.currentTimeMillis()
        var confirmationPausedMs = 0L
        var step = 0
        var toolRounds = 0
        var turnObservationChars = 0

        while (true) {
            if (cancelRequested) {
                return finish(transcript, startSize, TurnOutcome.Cancelled("user"), onEvent)
            }
            val activeElapsed = System.currentTimeMillis() - startedAt - confirmationPausedMs
            if (activeElapsed > config.turnTimeoutMs) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(AgentError(AgentTurnErrorCode.MODEL_TIMEOUT, "The turn timed out.", retryable = true)),
                    onEvent,
                )
            }
            if (!validateTranscriptPairing(transcript)) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(AgentError(AgentTurnErrorCode.INVALID_TRANSCRIPT, "The conversation history is malformed.")),
                    onEvent,
                )
            }

            val definitions = tools.definitions()
            val request =
                AgentModelRequest(
                    messages = listOf(AgentContext.systemPrompt(deviceSummary)) + AgentContext.trim(transcript, config.maxMessages),
                    tools = definitions.map { it.toToolSchema() },
                    maxOutputTokens = config.maxOutputTokens,
                    timeoutMs = config.modelTimeoutMs,
                )
            onEvent(AgentEvent.StateChanged(TurnState.CallingModel(step)))
            val response =
                try {
                    callModelWithRetry(request, apiKey)
                } catch (e: AgentModelException) {
                    return finish(
                        transcript,
                        startSize,
                        TurnOutcome.Failed(AgentError(e.code, e.message ?: "Model request failed.", e.retryable)),
                        onEvent,
                    )
                } catch (e: CancellationException) {
                    if (cancelRequested) {
                        return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                    }
                    throw e
                }
            step += 1

            if (response.finishReason == FinishReason.CONTENT_FILTER) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(AgentTurnErrorCode.CONTENT_FILTERED, "The model refused this request."),
                    ),
                    onEvent,
                )
            }

            if (response.finishReason == FinishReason.LENGTH && response.toolCalls.isNotEmpty()) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(
                            AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                            "The model response was cut off before its tool call was complete.",
                        ),
                    ),
                    onEvent,
                )
            }

            transcript.add(AgentMessage.Assistant(response.content, response.toolCalls))
            response.content?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.AssistantMessage(it)) }

            if (response.toolCalls.isEmpty()) {
                val outcome =
                    when (response.finishReason) {
                        FinishReason.CONTENT_FILTER ->
                            TurnOutcome.Failed(
                                AgentError(AgentTurnErrorCode.CONTENT_FILTERED, "The model refused this request."),
                            )

                        FinishReason.LENGTH -> {
                            onEvent(AgentEvent.Notice("The answer was truncated by the output limit."))
                            TurnOutcome.Completed(response.content.orEmpty())
                        }

                        else -> TurnOutcome.Completed(response.content.orEmpty())
                    }
                return finish(transcript, startSize, outcome, onEvent)
            }

            if (toolRounds >= config.maxToolRounds) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(AgentTurnErrorCode.STEP_BUDGET_EXCEEDED, "The tool-step limit was reached."),
                    ),
                    onEvent,
                )
            }

            val calls = response.toolCalls
            val executable = calls.take(config.maxToolCallsPerRound)
            val extra = calls.drop(config.maxToolCallsPerRound)
            var roundObservationChars = 0

            for (index in executable.indices) {
                val call = executable[index]
                if (cancelRequested) {
                    appendCancelled(transcript, executable.drop(index), "cancelled", onEvent)
                    return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                }
                val displayArgs = displayArgs(call, definitions)
                val view = ToolCallView(call.id, call.name, displayArgs)
                onEvent(AgentEvent.ToolStarted(view))
                onEvent(AgentEvent.StateChanged(TurnState.RunningTool(step, view)))

                var result =
                    try {
                        tools.invokeAgent(call, humanApproved = false)
                    } catch (e: CancellationException) {
                        appendCancelled(transcript, executable.drop(index), "cancelled", onEvent)
                        return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                    } catch (e: Exception) {
                        AgentInvocationResult(
                            tool = call.name,
                            ok = false,
                            errorCode = "internal_error",
                            errorMessage = e.message ?: "tool failed",
                        )
                    }

                if (result.confirmation != null) {
                    val request =
                        ConfirmationRequest(
                            id = confirmationIds.incrementAndGet(),
                            toolName = call.name,
                            displayArgsJson = result.confirmation!!.displayArgsJson,
                            reason = result.confirmation!!.reason,
                        )
                    onEvent(AgentEvent.ConfirmationRequested(request))
                    onEvent(AgentEvent.StateChanged(TurnState.AwaitingConfirmation(step, request)))
                    val confirmationStartedAt = System.currentTimeMillis()
                    val decision = withTimeoutOrNull(config.confirmationTimeoutMs) { confirm(request) }
                    confirmationPausedMs += System.currentTimeMillis() - confirmationStartedAt
                    if (decision == null) {
                        appendCancelled(transcript, executable.drop(index), "confirmation timed out", onEvent)
                        return finish(
                            transcript,
                            startSize,
                            TurnOutcome.Failed(
                                AgentError(AgentTurnErrorCode.CONFIRMATION_TIMEOUT, "Confirmation timed out."),
                            ),
                            onEvent,
                        )
                    }
                    if (!decision) {
                        appendCancelled(transcript, executable.drop(index), "cancelled by user", onEvent)
                        return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled by user"), onEvent)
                    }
                    result = tools.invokeAgent(call, humanApproved = true)
                }

                val rawObservation = result.toObservation()
                val remainingTurn = (config.maxObservationCharsPerTurn - turnObservationChars).coerceAtLeast(0)
                val remainingRound = (config.maxObservationCharsPerRound - roundObservationChars).coerceAtLeast(0)
                val budget = minOf(config.maxObservationChars, remainingTurn, remainingRound)
                val observation =
                    if (budget <= 0) {
                        ToolObservation(
                            ok = false,
                            tool = call.name,
                            errorCode = "observation_budget_exceeded",
                            message = "Tool output was not sent because the context budget is exhausted.",
                        )
                    } else {
                        AgentContext.truncateObservation(rawObservation, budget)
                    }
                transcript.add(observation.asToolMessage(call.name, call.id))
                val observationChars = observation.toJson().toString().length
                roundObservationChars += observationChars
                turnObservationChars += observationChars
                onEvent(
                    AgentEvent.ToolFinished(
                        ToolResultView(
                            callId = call.id,
                            name = call.name,
                            ok = observation.ok,
                            output = observation.output,
                            errorCode = observation.errorCode,
                            message = observation.message,
                            truncated = observation.truncated,
                            degraded = observation.degraded,
                        ),
                    ),
                )
            }

            extra.forEach { call ->
                val observation =
                    ToolObservation(
                        ok = false,
                        tool = call.name,
                        errorCode = "too_many_tool_calls",
                        message = "Only ${config.maxToolCallsPerRound} tool calls are allowed per round.",
                    )
                transcript.add(observation.asToolMessage(call.name, call.id))
                onEvent(
                    AgentEvent.ToolFinished(
                        ToolResultView(call.id, call.name, false, null, observation.errorCode, observation.message, false),
                    ),
                )
            }

            toolRounds += 1
        }
    }

    private suspend fun callModelWithRetry(
        request: AgentModelRequest,
        apiKey: String,
    ): AgentModelResponse {
        var attempt = 0
        while (true) {
            try {
                return model.complete(request, apiKey)
            } catch (e: AgentModelException) {
                if (!e.retryable || attempt >= config.maxRetries || cancelRequested) throw e
                attempt += 1
                delay(500L * attempt)
            }
        }
    }

    private fun displayArgs(
        call: ToolCall,
        definitions: List<AgentToolDefinition>,
    ): String {
        val definition = definitions.firstOrNull { it.name == call.name }
        val allowed =
            definition?.allowedUrlParameter
                ?.let { parameter ->
                    AgentJson.parseObject(call.argumentsJson)
                        ?.let { AgentJson.string(it, parameter) }
                        ?.let { setOf(it) }
                }
                .orEmpty()
        return AgentSanitizer.sanitizeArgsJson(call.argumentsJson, allowed)
    }

    private fun AgentInvocationResult.toObservation(): ToolObservation =
        ToolObservation(
            ok = ok,
            tool = tool,
            output = output,
            errorCode = errorCode,
            message = errorMessage,
            degraded = degraded,
        )

    private fun appendCancelled(
        transcript: MutableList<AgentMessage>,
        calls: List<ToolCall>,
        reason: String,
        onEvent: (AgentEvent) -> Unit,
    ) {
        calls.forEach { call ->
            val observation =
                ToolObservation(
                    ok = false,
                    tool = call.name,
                    errorCode = "cancelled",
                    message = reason,
                )
            transcript.add(observation.asToolMessage(call.name, call.id))
            onEvent(
                AgentEvent.ToolFinished(
                    ToolResultView(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = null,
                        errorCode = "cancelled",
                        message = reason,
                        truncated = false,
                    ),
                ),
            )
        }
    }

    private fun ensurePairing(
        transcript: MutableList<AgentMessage>,
        reason: String,
    ) {
        val assistantIndex = transcript.indexOfLast { it is AgentMessage.Assistant && it.toolCalls.isNotEmpty() }
        if (assistantIndex < 0) return
        val assistant = transcript[assistantIndex] as AgentMessage.Assistant
        val observed =
            transcript
                .subList(assistantIndex + 1, transcript.size)
                .filterIsInstance<AgentMessage.Tool>()
                .map { it.toolCallId }
                .toSet()
        assistant.toolCalls.forEach { call ->
            if (call.id !in observed) {
                val observation =
                    ToolObservation(
                        ok = false,
                        tool = call.name,
                        errorCode = "cancelled",
                        message = reason,
                    )
                transcript.add(observation.asToolMessage(call.name, call.id))
            }
        }
    }

    private fun finish(
        transcript: MutableList<AgentMessage>,
        startSize: Int,
        outcome: TurnOutcome,
        onEvent: (AgentEvent) -> Unit,
    ): TurnResult {
        val reason =
            when (outcome) {
                is TurnOutcome.Completed -> "completed"
                is TurnOutcome.Cancelled -> outcome.reason
                is TurnOutcome.Failed -> outcome.error.message
            }
        ensurePairing(transcript, reason)
        var finalOutcome = outcome
        if (!validateTranscriptPairing(transcript)) {
            val assistantIndex = transcript.indexOfLast { it is AgentMessage.Assistant && it.toolCalls.isNotEmpty() }
            if (assistantIndex >= 0) {
                while (transcript.size > assistantIndex) {
                    transcript.removeAt(transcript.lastIndex)
                }
            }
            finalOutcome =
                TurnOutcome.Failed(
                    AgentError(
                        AgentTurnErrorCode.INVALID_TRANSCRIPT,
                        "The conversation history is malformed; the last tool round was discarded.",
                    ),
                )
        }
        onEvent(AgentEvent.TurnFinished(finalOutcome))
        return TurnResult(transcript.drop(startSize), finalOutcome)
    }
}
