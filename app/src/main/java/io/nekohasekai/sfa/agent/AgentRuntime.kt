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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AgentConfig(
    val maxToolRounds: Int = 12,
    val maxToolCallsPerRound: Int = 8,
    val modelTimeoutMs: Long = 120_000,
    val turnTimeoutMs: Long = 1_800_000,
    val toolTimeoutMs: Long = 120_000,
    val confirmationTimeoutMs: Long = 300_000,
    val maxMessages: Int = 200,
    val trimTargetMessages: Int = 160,
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
    val pendingEvent: TurnEvent? = null,
)

class AgentRuntime(
    private val inputGuard: AgentInputGuard,
    private val model: AgentModelClient,
    private val tools: AgentToolExecutor,
    private val apiKeys: AgentApiKeyProvider,
    private val config: AgentConfig = AgentConfig(),
    private val streamingModel: AgentStreamingModelClient? = null,
    private val toolRoutingEnabled: () -> Boolean = { config.toolRoutingEnabled },
    private val reasoningEffort: () -> String? = { null },
    private val modelLimits: () -> ModelLimits = { ModelLimits.FALLBACK },
) {
    private val confirmationIds = AtomicLong(0)

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var preparedHistoryForTurn: List<AgentMessage>? = null

    private var compactFailures = 0

    /** Last actual prompt size; a lower bound for the next request's estimate. */
    private var lastPromptTokens = 0

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
        initialEvent: TurnEvent? = null,
    ): TurnResult {
        preparedHistoryForTurn = null
        streamingModel?.resetCancel()
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
                return finish(
                    history.toMutableList(),
                    history.size,
                    TurnOutcome.Cancelled(guard.reason),
                    onEvent,
                    pendingEvent = initialEvent,
                )
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
                        pendingEvent = initialEvent,
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
                pendingEvent = initialEvent,
            )
        }

        val preparedHistory =
            AgentContext.compactOldObservations(history, config.maxHistoricalObservationChars)
        preparedHistoryForTurn = preparedHistory.takeIf { it !== history }
        val transcript =
            (preparedHistory + AgentMessage.User(formatUserMessage(userText, initialEvent))).toMutableList()
        var startSize = preparedHistory.size
        val routingEnabled = toolRoutingEnabled()
        val turnReasoningEffort = reasoningEffort()?.takeIf { it.isNotBlank() }
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
        var budgetNoticeShown = false
        val partialContent = StringBuilder()
        lastPromptTokens = 0

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

            val limits = modelLimits()
            val contextWindow = limits.contextWindow.coerceAtLeast(MIN_CONTEXT_WINDOW)
            var estimatedContext =
                maxOf(estimateRequestTokens(systemMessage, requestTools, transcript), lastPromptTokens)
            if (shouldCompact(estimatedContext, contextWindow)) {
                val before = estimatedContext
                val summary = compactTranscript(transcript, systemMessage, requestTools, apiKey, limits, onEvent)
                if (summary != null) {
                    // The summary replaces the older history. Persist it at the
                    // head and keep only the current turn as new messages.
                    startSize = 1
                    preparedHistoryForTurn = listOf(summary)
                    lastPromptTokens = 0
                    estimatedContext = estimateRequestTokens(systemMessage, requestTools, transcript)
                    onEvent(
                        AgentEvent.Notice(
                            "Context compacted from ${contextPercent(before, contextWindow)}% " +
                                "to ${contextPercent(estimatedContext, contextWindow)}%.",
                        ),
                    )
                }
            }
            val trimmedMessages = AgentContext.trim(transcript, config.maxMessages, config.trimTargetMessages)
            if (trimmedMessages.size < transcript.size) lastPromptTokens = 0
            val systemTokens = AgentContext.estimateTokens(systemMessage.content)
            val toolTokens =
                requestTools.sumOf {
                    AgentContext.estimateTokens(it.name) +
                        AgentContext.estimateTokens(it.description) +
                        AgentContext.estimateTokens(it.parameters.toString())
                }
            val outputReserve =
                minOf(limits.maxOutputTokens ?: COMPACT_SUMMARY_TOKENS, COMPACTION_OUTPUT_RESERVE)
            val inputBudget = (contextWindow - outputReserve).coerceAtLeast(MIN_CONTEXT_WINDOW)
            val messageBudget = (inputBudget - systemTokens - toolTokens).coerceAtLeast(256)
            val fittedMessages = AgentContext.fitToBudget(trimmedMessages, messageBudget)
            if (fittedMessages.size < trimmedMessages.size) {
                lastPromptTokens = 0
                if (!budgetNoticeShown) {
                    budgetNoticeShown = true
                    onEvent(
                        AgentEvent.Notice(
                            "Earlier conversation was not sent to the model because the context budget is full.",
                        ),
                    )
                }
            }
            val sentInput = systemTokens + toolTokens + AgentContext.estimateMessagesTokens(fittedMessages)
            val capInput = maxOf(sentInput, lastPromptTokens)
            val request =
                AgentModelRequest(
                    messages = listOf(systemMessage) + fittedMessages,
                    tools = requestTools,
                    maxOutputTokens = outputTokenCap(limits, capInput),
                    timeoutMs = config.modelTimeoutMs,
                    toolChoice = if (toolsEnabled) AgentToolChoice.AUTO else AgentToolChoice.NONE,
                    reasoningEffort = turnReasoningEffort,
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
            val usedTokens = response.usage?.inputTokens ?: sentInput
            lastPromptTokens = usedTokens
            onEvent(
                AgentEvent.ContextUsage(
                    usedTokens = usedTokens,
                    limitTokens = contextWindow,
                    percent = contextPercent(usedTokens, contextWindow),
                ),
            )
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
                val limitHint = request.maxOutputTokens?.let { " ($it tokens)" }.orEmpty()
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(
                            AgentTurnErrorCode.OUTPUT_TRUNCATED,
                            "The model hit its output limit$limitHint before finishing a tool call. " +
                                "Nothing in that reply was run. Split the work into smaller steps " +
                                "or lower the reasoning effort.",
                        ),
                    ),
                    onEvent,
                )
            }

            if (response.toolCalls.isEmpty() && response.content.isNullOrBlank()) {
                return finish(
                    transcript,
                    startSize,
                    TurnOutcome.Failed(
                        AgentError(
                            AgentTurnErrorCode.INVALID_MODEL_RESPONSE,
                            "The model returned an empty answer.",
                            retryable = true,
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
                                    if (prepared.query.isBlank()) {
                                        catalog.catalogOutput(config.maxObservationChars)
                                    } else {
                                        catalog.searchOutput(
                                            prepared.query,
                                            config.searchLimit,
                                            config.maxObservationChars,
                                        )
                                    },
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
                            allowTurnApproval = true,
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
                delay(e.retryAfterMs ?: (500L * (1 shl (attempt - 1))).coerceAtMost(8_000))
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
            if (cancelRequested) throw CancellationException("cancelled")
            partialContent.clear()
            try {
                return collectStream(request, apiKey, onEvent, partialContent)
            } catch (e: AgentModelException) {
                if (!e.retryable || attempt >= config.maxRetries || cancelRequested) throw e
                if (attempt == 0) {
                    onEvent(AgentEvent.Notice("The provider request failed; retrying. ${e.message}"))
                }
                onEvent(AgentEvent.StreamReset)
                attempt += 1
                delay(e.retryAfterMs ?: (500L * (1 shl (attempt - 1))).coerceAtMost(8_000))
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
        pendingEvent: TurnEvent? = null,
    ): TurnResult {
        val reason =
            when (outcome) {
                is TurnOutcome.Completed -> "completed"
                is TurnOutcome.Cancelled -> outcome.reason
                is TurnOutcome.Failed -> outcome.error.message
            }
        ensurePairing(transcript, reason)
        val event = prepareUnfinishedTurn(transcript, startSize, outcome) ?: pendingEvent
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
        cancelRequested = false
        return TurnResult(
            messages = transcript.drop(startSize),
            outcome = finalOutcome,
            compactedHistory = preparedHistoryForTurn,
            pendingEvent = event,
        )
    }

    private fun estimateRequestTokens(
        systemMessage: AgentMessage.System,
        tools: List<AgentToolSchema>,
        messages: List<AgentMessage>,
    ): Int =
        AgentContext.estimateTokens(systemMessage.content) +
            tools.sumOf {
                AgentContext.estimateTokens(it.name) +
                    AgentContext.estimateTokens(it.description) +
                    AgentContext.estimateTokens(it.parameters.toString())
            } +
            AgentContext.estimateMessagesTokens(messages)

    private fun shouldCompact(
        estimatedTokens: Int,
        contextWindow: Int,
    ): Boolean =
        estimatedTokens.toLong() * 100L >= contextWindow.toLong() * AUTO_COMPACT_PERCENT

    private fun contextPercent(
        usedTokens: Int,
        limitTokens: Int,
    ): Int =
        if (limitTokens <= 0) {
            0
        } else {
            ((usedTokens.toLong() * 100L) / limitTokens).toInt().coerceIn(0, 100)
        }

    private fun outputTokenCap(
        limits: ModelLimits,
        inputTokens: Int,
    ): Int? {
        val modelMax = limits.maxOutputTokens ?: return null
        val room = limits.contextWindow - inputTokens - OUTPUT_CONTEXT_HEADROOM
        return minOf(modelMax, room.coerceAtLeast(MIN_OUTPUT_TOKENS))
    }

    /**
     * Replaces the history before the current user turn with one model-written
     * summary. The current turn (including its tool pairs) is kept intact.
     * A failure falls back to the normal trim path; three failures disable
     * compaction for this runtime instance.
     */
    private suspend fun compactTranscript(
        transcript: MutableList<AgentMessage>,
        systemMessage: AgentMessage.System,
        requestTools: List<AgentToolSchema>,
        apiKey: String,
        limits: ModelLimits,
        onEvent: (AgentEvent) -> Unit,
    ): AgentMessage.System? {
        if (compactFailures >= MAX_COMPACT_FAILURES || cancelRequested) return null
        val lastUserIndex = transcript.indexOfLast { it is AgentMessage.User }
        if (lastUserIndex <= 0) return null
        val older = transcript.subList(0, lastUserIndex)
        if (older.none { it !is AgentMessage.System }) return null
        val limit = limits.contextWindow.coerceAtLeast(MIN_CONTEXT_WINDOW)
        val before = estimateRequestTokens(systemMessage, requestTools, transcript)
        onEvent(
            AgentEvent.Notice(
                "Context is at ${contextPercent(before, limit)}%; compacting earlier conversation.",
            ),
        )
        val rendered = renderForSummary(older)
        if (rendered.isBlank()) return null
        val summaryText =
            try {
                model.complete(
                    AgentModelRequest(
                        messages =
                            listOf(
                                AgentMessage.System(COMPACT_SYSTEM_PROMPT),
                                AgentMessage.User("Conversation to summarize:\n\n$rendered"),
                            ),
                        tools = emptyList(),
                        maxOutputTokens = minOf(COMPACT_SUMMARY_TOKENS, limits.maxOutputTokens ?: COMPACT_SUMMARY_TOKENS),
                        timeoutMs = config.modelTimeoutMs,
                        toolChoice = AgentToolChoice.NONE,
                    ),
                    apiKey,
                ).content?.trim()?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (summaryText == null) {
            compactFailures += 1
            onEvent(AgentEvent.Notice("Context compaction failed; older messages will be trimmed instead."))
            return null
        }
        if (cancelRequested) return null
        compactFailures = 0
        val summary =
            AgentMessage.System(
                "Conversation summary (auto-compacted to save context; use it as background):\n" +
                    AgentSanitizer.scrubForModelRead(summaryText).take(COMPACT_SUMMARY_MAX_CHARS),
            )
        val kept = transcript.subList(lastUserIndex, transcript.size).toList()
        transcript.clear()
        transcript.add(summary)
        transcript.addAll(kept)
        val after = estimateRequestTokens(systemMessage, requestTools, transcript)
        onEvent(
            AgentEvent.ContextUsage(
                usedTokens = after,
                limitTokens = limit,
                percent = contextPercent(after, limit),
            ),
        )
        return summary
    }

    private fun renderForSummary(messages: List<AgentMessage>): String {
        val text =
            buildString {
                messages.forEach { message ->
                    when (message) {
                        is AgentMessage.System ->
                            appendLine("System note: ${message.content.take(COMPACT_MESSAGE_CHARS)}")
                        is AgentMessage.User ->
                            appendLine("User: ${message.content.take(COMPACT_MESSAGE_CHARS)}")
                        is AgentMessage.Assistant -> {
                            message.content
                                ?.takeIf { it.isNotBlank() }
                                ?.let { appendLine("Assistant: ${it.take(COMPACT_MESSAGE_CHARS)}") }
                            message.toolCalls.forEach { call ->
                                appendLine(
                                    "Tool call ${call.name}: ${call.argumentsJson.take(COMPACT_MESSAGE_CHARS)}",
                                )
                            }
                        }
                        is AgentMessage.Tool ->
                            appendLine("Tool ${message.name}: ${message.content.take(COMPACT_MESSAGE_CHARS)}")
                    }
                }
            }
        if (text.length <= COMPACT_MAX_INPUT_CHARS) return text
        val head = COMPACT_MAX_INPUT_CHARS / 6
        return text.take(head) +
            "\n[... middle of the conversation omitted ...]\n" +
            text.takeLast(COMPACT_MAX_INPUT_CHARS - head)
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
     * A cancelled or failed turn can leave an unanswered user message. Keep that
     * message in the model transcript so the next request still sees what the
     * user asked; the UI already shows it. Queue a runtime event for the next
     * real user message instead of inventing an assistant reply.
     */
    private fun prepareUnfinishedTurn(
        transcript: MutableList<AgentMessage>,
        startSize: Int,
        outcome: TurnOutcome,
    ): TurnEvent? {
        if (outcome is TurnOutcome.Completed) return null
        if (transcript.size <= startSize) return null
        return when (outcome) {
            is TurnOutcome.Cancelled -> TurnEvent("turn_interrupted", "user_stop")
            is TurnOutcome.Failed ->
                TurnEvent(
                    "turn_failed",
                    outcome.error.code.name.lowercase(Locale.US),
                    AgentSanitizer.sanitizeText(outcome.error.message).ifBlank { null },
                )

            is TurnOutcome.Completed -> null
        }
    }

    private companion object {
        const val MIN_CONTEXT_WINDOW = 4_000
        const val AUTO_COMPACT_PERCENT = 80
        const val COMPACT_SUMMARY_TOKENS = 4_096
        const val COMPACT_SUMMARY_MAX_CHARS = 12_000
        const val COMPACTION_OUTPUT_RESERVE = 20_000
        const val COMPACT_MAX_INPUT_CHARS = 120_000
        const val COMPACT_MESSAGE_CHARS = 4_000
        const val MAX_COMPACT_FAILURES = 3
        const val OUTPUT_CONTEXT_HEADROOM = 8_000
        const val MIN_OUTPUT_TOKENS = 256

        val COMPACT_SYSTEM_PROMPT =
            """
            You compact an agent conversation so it can continue in a smaller context.
            Write a concise brief in the conversation's language. Preserve the user's goals,
            constraints and decisions; tool names and outcomes; file paths; unresolved errors
            and next steps. Omit secrets, tokens, subscription URLs, raw logs and the system prompt.
            Output only the summary.
            """.trimIndent()
    }
}
