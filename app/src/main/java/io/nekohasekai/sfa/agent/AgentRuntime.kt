package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AgentConfig(
    val maxToolRounds: Int = 12,
    val maxToolCallsPerRound: Int = 8,
    val modelTimeoutMs: Long = 120_000,
    val turnTimeoutMs: Long = 1_800_000,
    val confirmationTimeoutMs: Long = 300_000,
    val maxOutputTokens: Int = 4_096,
    val maxInputTokens: Int = 64_000,
    val maxMessages: Int = 48,
    val trimTargetMessages: Int = 36,
    val maxHistoricalObservationChars: Int = 8_000,
    val toolRoutingEnabled: Boolean = true,
    val searchLimit: Int = 5,
    val maxSearchesPerRound: Int = 2,
    val maxObservationChars: Int = 24_000,
    val maxObservationCharsPerRound: Int = 64_000,
    val maxObservationCharsPerTurn: Int = 128_000,
    val maxRetries: Int = 3,
)

data class TurnResult(
    val messages: List<AgentMessage>,
    val outcome: TurnOutcome,
    val compactedHistory: List<AgentMessage>? = null,
)

class AgentRuntime(
    private val inputGuard: AgentInputGuard,
    private val model: AgentModelClient,
    private val tools: AgentToolExecutor,
    private val apiKeys: AgentApiKeyProvider,
    private val config: AgentConfig = AgentConfig(),
    private val streamingModel: AgentStreamingModelClient? = null,
    private val toolRoutingEnabled: () -> Boolean = { config.toolRoutingEnabled },
) {
    private val confirmationIds = AtomicLong(0)

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var preparedHistoryForTurn: List<AgentMessage>? = null

    fun cancel(reason: String = "user") {
        cancelRequested = true
        streamingModel?.cancel()
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
        preparedHistoryForTurn = null
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
                        "API key is not configured for the selected provider. Open Settings > Conductor.",
                    ),
                ),
                onEvent,
            )
        }

        val preparedHistory =
            AgentContext.compactOldObservations(history, config.maxHistoricalObservationChars)
        preparedHistoryForTurn = preparedHistory.takeIf { it !== history }
        val transcript = (preparedHistory + AgentMessage.User(userText)).toMutableList()
        val startSize = preparedHistory.size
        val routingEnabled = toolRoutingEnabled()
        val catalog = AgentToolCatalog(tools.definitions())
        val systemMessage =
            AgentContext.systemPrompt(
                deviceSummary,
                if (routingEnabled) catalog.promptSection() else null,
            )
        val requestTools =
            if (routingEnabled) {
                AgentMetaTools.schemas()
            } else {
                catalog.definitions.map { it.toToolSchema() }
            }
        val startedAt = System.currentTimeMillis()
        var confirmationPausedMs = 0L
        var step = 0
        var toolRounds = 0
        var turnObservationChars = 0
        var toolsEnabled = true
        val partialContent = StringBuilder()

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

            val request =
                AgentModelRequest(
                    messages =
                        listOf(systemMessage) +
                            AgentContext.trim(transcript, config.maxMessages, config.trimTargetMessages),
                    tools = requestTools,
                    maxOutputTokens = config.maxOutputTokens,
                    timeoutMs = config.modelTimeoutMs,
                    toolChoice = if (toolsEnabled) AgentToolChoice.AUTO else AgentToolChoice.NONE,
                )
            onEvent(AgentEvent.StateChanged(TurnState.CallingModel(step)))
            val response =
                try {
                    if (streamingModel != null) {
                        streamModelWithRetry(request, apiKey, onEvent, partialContent)
                    } else {
                        callModelWithRetry(request, apiKey)
                    }
                } catch (e: AgentModelException) {
                    appendPartialAssistant(transcript, partialContent)
                    return finish(
                        transcript,
                        startSize,
                        TurnOutcome.Failed(AgentError(e.code, e.message ?: "Model request failed.", e.retryable)),
                        onEvent,
                    )
                } catch (e: CancellationException) {
                    if (cancelRequested) {
                        appendPartialAssistant(transcript, partialContent)
                        return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                    }
                    throw e
                }
            step += 1

            if (cancelRequested) {
                appendPartialAssistant(transcript, partialContent)
                return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
            }

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
            partialContent.clear()
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

            if (!toolsEnabled) {
                appendBudgetObservations(transcript, response.toolCalls, onEvent)
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(AgentTurnErrorCode.STEP_BUDGET_EXCEEDED, "The tool-step limit was reached."),
                    ),
                    onEvent,
                )
            }

            if (toolRounds >= config.maxToolRounds) {
                appendBudgetObservations(transcript, response.toolCalls, onEvent)
                onEvent(
                    AgentEvent.Notice(
                        "No more tool steps are available. Answering with the information already collected.",
                    ),
                )
                toolsEnabled = false
                continue
            }

            val calls = response.toolCalls
            val executable = calls.take(config.maxToolCallsPerRound)
            val extra = calls.drop(config.maxToolCallsPerRound)
            var roundObservationChars = 0
            var searchesThisRound = 0

            fun recordObservation(
                call: ToolCall,
                raw: ToolObservation,
            ) {
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
                        AgentContext.truncateObservation(raw, budget)
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

            for (index in executable.indices) {
                val call = executable[index]
                if (cancelRequested) {
                    appendCancelled(transcript, executable.drop(index), "cancelled", onEvent)
                    return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                }
                val prepared =
                    if (routingEnabled) {
                        prepareToolCall(call, catalog)
                    } else {
                        PreparedCall.Direct(call)
                    }

                if (prepared is PreparedCall.Search) {
                    searchesThisRound += 1
                    val view =
                        ToolCallView(
                            call.id,
                            AgentMetaTools.SEARCH_TOOLS,
                            displayArgs(call, catalog.definitions),
                        )
                    onEvent(AgentEvent.ToolStarted(view))
                    onEvent(AgentEvent.StateChanged(TurnState.RunningTool(step, view)))
                    val observation =
                        if (searchesThisRound > config.maxSearchesPerRound) {
                            ToolObservation(
                                ok = false,
                                tool = AgentMetaTools.SEARCH_TOOLS,
                                errorCode = "search_budget_exceeded",
                                message = "Only ${config.maxSearchesPerRound} tool searches are allowed per round.",
                            )
                        } else {
                            ToolObservation(
                                ok = true,
                                tool = AgentMetaTools.SEARCH_TOOLS,
                                output =
                                    catalog.searchOutput(
                                        prepared.query,
                                        config.searchLimit,
                                        config.maxObservationChars,
                                    ),
                            )
                        }
                    recordObservation(call, observation)
                    continue
                }

                if (prepared is PreparedCall.Invalid) {
                    val view = ToolCallView(call.id, call.name, displayArgs(call, catalog.definitions))
                    onEvent(AgentEvent.ToolStarted(view))
                    onEvent(AgentEvent.StateChanged(TurnState.RunningTool(step, view)))
                    recordObservation(call, prepared.observation)
                    continue
                }

                val executed = (prepared as PreparedCall.Direct).call
                val displayArgsJson = displayArgs(executed, catalog.definitions)
                val view = ToolCallView(executed.id, executed.name, displayArgsJson)
                onEvent(AgentEvent.ToolStarted(view))
                onEvent(AgentEvent.StateChanged(TurnState.RunningTool(step, view)))

                var result =
                    try {
                        tools.invokeAgent(executed, humanApproved = false)
                    } catch (e: CancellationException) {
                        appendCancelled(transcript, executable.drop(index), "cancelled", onEvent)
                        return finish(transcript, startSize, TurnOutcome.Cancelled("cancelled"), onEvent)
                    } catch (e: Exception) {
                        AgentInvocationResult(
                            tool = executed.name,
                            ok = false,
                            errorCode = "internal_error",
                            errorMessage = e.message ?: "tool failed",
                        )
                    }

                if (result.confirmation != null) {
                    val request =
                        ConfirmationRequest(
                            id = confirmationIds.incrementAndGet(),
                            toolName = executed.name,
                            displayArgsJson = result.confirmation!!.displayArgsJson,
                            reason = result.confirmation!!.reason,
                            batchRemaining = executable.size - index,
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
                    result = tools.invokeAgent(executed, humanApproved = true)
                }

                recordObservation(executed, result.toObservation())
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

    private sealed interface PreparedCall {
        data class Direct(val call: ToolCall) : PreparedCall

        data class Search(val query: String) : PreparedCall

        data class Invalid(val observation: ToolObservation) : PreparedCall
    }

    private fun prepareToolCall(
        call: ToolCall,
        catalog: AgentToolCatalog,
    ): PreparedCall {
        if (call.name == AgentMetaTools.SEARCH_TOOLS) {
            val args =
                AgentJson.parseObject(call.argumentsJson)
                    ?: return PreparedCall.Invalid(
                        metaError(
                            AgentMetaTools.SEARCH_TOOLS,
                            "invalid_arguments",
                            "search_tools arguments must be a JSON object.",
                        ),
                    )
            val query = AgentJson.string(args, "query")?.trim().orEmpty()
            if (query.isBlank()) {
                return PreparedCall.Invalid(
                    metaError(
                        AgentMetaTools.SEARCH_TOOLS,
                        "missing_parameter",
                        "search_tools requires a non-blank 'query'.",
                    ),
                )
            }
            return PreparedCall.Search(query)
        }
        if (call.name != AgentMetaTools.ROUTE_CALL) return PreparedCall.Direct(call)

        val args =
            AgentJson.parseObject(call.argumentsJson)
                ?: return PreparedCall.Invalid(
                    metaError(
                        AgentMetaTools.ROUTE_CALL,
                        "invalid_arguments",
                        "route_call arguments must be a JSON object.",
                    ),
                )
        val target = AgentJson.string(args, "tool")?.trim().orEmpty()
        if (target.isBlank()) {
            return PreparedCall.Invalid(
                metaError(
                    AgentMetaTools.ROUTE_CALL,
                    "missing_parameter",
                    "route_call requires a non-blank 'tool'.",
                ),
            )
        }
        if (AgentMetaTools.isMetaTool(target)) {
            return PreparedCall.Invalid(
                metaError(
                    AgentMetaTools.ROUTE_CALL,
                    "unknown_tool",
                    "route_call cannot target $target.",
                ),
            )
        }
        val definition =
            catalog.get(target)
                ?: return PreparedCall.Invalid(
                    metaError(
                        AgentMetaTools.ROUTE_CALL,
                        "unknown_tool",
                        "No tool named '$target'.",
                        target = target,
                        suggestions = catalog.suggestions(target),
                    ),
                )
        val rawArgs = args["args"]
        val innerArgs =
            when (rawArgs) {
                null, is JsonNull -> "{}"
                is JsonObject -> rawArgs.toString()
                is JsonPrimitive ->
                    if (rawArgs.isString) {
                        rawArgs.content
                    } else {
                        return PreparedCall.Invalid(
                            metaError(
                                AgentMetaTools.ROUTE_CALL,
                                "invalid_arguments",
                                "route_call 'args' must be an object.",
                                target = target,
                            ),
                        )
                    }

                else ->
                    return PreparedCall.Invalid(
                        metaError(
                            AgentMetaTools.ROUTE_CALL,
                            "invalid_arguments",
                            "route_call 'args' must be an object.",
                            target = target,
                        ),
                    )
            }
        return PreparedCall.Direct(ToolCall(call.id, definition.name, innerArgs))
    }

    private fun metaError(
        tool: String,
        code: String,
        message: String,
        target: String? = null,
        suggestions: List<String> = emptyList(),
    ): ToolObservation {
        val output =
            buildJsonObject {
                if (target != null) put("target", target)
                if (suggestions.isNotEmpty()) {
                    put(
                        "suggestions",
                        buildJsonArray {
                            suggestions.forEach { add(it) }
                        },
                    )
                }
            }
        return ToolObservation(
            ok = false,
            tool = tool,
            errorCode = code,
            message = message,
            output = output.takeIf { it.isNotEmpty() },
        )
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

    private suspend fun streamModelWithRetry(
        request: AgentModelRequest,
        apiKey: String,
        onEvent: (AgentEvent) -> Unit,
        partialContent: StringBuilder,
    ): AgentModelResponse {
        var attempt = 0
        while (true) {
            partialContent.clear()
            try {
                return collectStream(request, apiKey, onEvent, partialContent)
            } catch (e: AgentModelException) {
                if (!e.retryable || attempt >= config.maxRetries || cancelRequested) throw e
                attempt += 1
                delay(500L * attempt)
            }
        }
    }

    private suspend fun collectStream(
        request: AgentModelRequest,
        apiKey: String,
        onEvent: (AgentEvent) -> Unit,
        partialContent: StringBuilder,
    ): AgentModelResponse {
        val client = streamingModel ?: error("streaming model is not configured")
        val content = StringBuilder()
        val calls = LinkedHashMap<Int, MutableToolCall>()
        var finishReason = FinishReason.UNKNOWN
        var usage: TokenUsage? = null
        client.stream(request, apiKey).collect { chunk ->
            when (chunk) {
                is AgentModelChunk.ReasoningDelta -> {
                    onEvent(AgentEvent.ReasoningDelta(chunk.text))
                }

                is AgentModelChunk.ContentDelta -> {
                    content.append(chunk.text)
                    partialContent.append(chunk.text)
                    onEvent(AgentEvent.AssistantDelta(chunk.text))
                }

                is AgentModelChunk.ToolCallDelta -> {
                    val call = calls.getOrPut(chunk.index) { MutableToolCall() }
                    chunk.id?.let { call.id = it }
                    chunk.name?.let { call.name = it }
                    chunk.argumentsDelta?.let { call.arguments.append(it) }
                }

                is AgentModelChunk.Completed -> {
                    finishReason = chunk.finishReason
                    usage = chunk.usage
                }
            }
        }
        val toolCalls =
            calls
                .toSortedMap()
                .values
                .mapNotNull { call ->
                    val name = call.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ToolCall(
                        id = call.id?.takeIf { it.isNotBlank() } ?: "call_${UUID.randomUUID()}",
                        name = name,
                        argumentsJson = call.arguments.toString().ifBlank { "{}" },
                    )
                }
        return AgentModelResponse(
            content = content.toString().takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private class MutableToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
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

    private fun appendBudgetObservations(
        transcript: MutableList<AgentMessage>,
        calls: List<ToolCall>,
        onEvent: (AgentEvent) -> Unit,
    ) {
        calls.forEach { call ->
            val observation =
                ToolObservation(
                    ok = false,
                    tool = call.name,
                    errorCode = "step_budget_exceeded",
                    message = "No more tool steps are available. Answer with the information already collected.",
                )
            transcript.add(observation.asToolMessage(call.name, call.id))
            onEvent(
                AgentEvent.ToolFinished(
                    ToolResultView(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = null,
                        errorCode = observation.errorCode,
                        message = observation.message,
                        truncated = false,
                    ),
                ),
            )
        }
    }

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
        closeUnfinishedTurn(transcript, startSize, outcome)
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
        return TurnResult(transcript.drop(startSize), finalOutcome, preparedHistoryForTurn)
    }

    private fun appendPartialAssistant(
        transcript: MutableList<AgentMessage>,
        partialContent: StringBuilder,
    ) {
        val text = partialContent.toString()
        if (text.isNotBlank()) {
            transcript.add(AgentMessage.Assistant(text, emptyList()))
        }
    }

    /**
     * A cancelled or failed turn can end with an unanswered user message or a
     * cancelled tool observation. Chat completions expects the next request to
     * see a closed turn, so add a synthetic assistant message. Without it the
     * next user message becomes a second consecutive user turn and models tend
     * to answer both. Input-guard and missing-key exits never added the user
     * message, so their transcript is not touched.
     */
    private fun closeUnfinishedTurn(
        transcript: MutableList<AgentMessage>,
        startSize: Int,
        outcome: TurnOutcome,
    ) {
        if (outcome is TurnOutcome.Completed) return
        if (transcript.size <= startSize) return
        val last = transcript.lastOrNull()
        if (last !is AgentMessage.User && last !is AgentMessage.Tool) return
        val marker =
            when (outcome) {
                is TurnOutcome.Cancelled -> "Stopped."
                is TurnOutcome.Failed -> "[The turn ended before an answer.]"
                is TurnOutcome.Completed -> return
            }
        transcript.add(AgentMessage.Assistant(marker, emptyList()))
    }
}
