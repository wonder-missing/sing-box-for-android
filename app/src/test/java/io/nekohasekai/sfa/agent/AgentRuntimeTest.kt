package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    private val listNodes =
        AgentToolDefinition(
            name = "list_nodes",
            description = "List nodes",
            permission = AgentPermission.READ_NETWORK_STATE,
            parameters = emptyList(),
            confirmationPolicy = ConfirmationPolicy.NEVER,
            category = "network",
        )

    private val probe =
        AgentToolDefinition(
            name = "probe_via_node",
            description = "Probe a URL",
            permission = AgentPermission.NETWORK_PROBE,
            parameters = emptyList(),
            confirmationPolicy = ConfirmationPolicy.ALWAYS,
            category = "network",
        )

    @Test
    fun directAnswerDoesNotCallTools() {
        val model = ScriptedModel(mutableListOf(response("hello")))
        val executor = FakeExecutor(listOf(listNodes)) { _, _ -> ok("list_nodes") }
        val runtime = runtime(model, executor, key = "sk-test")

        val result = runBlocking { runtime.runTurn("hi", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Completed)
        assertEquals("hello", (result.outcome as TurnOutcome.Completed).answer)
        assertEquals(1, model.requests.size)
        assertTrue(model.requests.single().messages.first() is AgentMessage.System)
        assertTrue(validateTranscriptPairing(result.messages))
        assertEquals(2, result.messages.size)
    }

    @Test
    fun executesOneToolRoundAndFeedsObservationBack() {
        val call = ToolCall("call_1", "list_nodes", "{}")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(call), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executor = FakeExecutor(listOf(listNodes)) { toolCall, _ -> ok(toolCall.name) }
        val runtime = runtime(model, executor, key = "sk-test")

        val result = runBlocking { runtime.runTurn("show nodes", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Completed)
        assertEquals(2, model.requests.size)
        assertTrue(result.messages.any { it is AgentMessage.Assistant && it.toolCalls.isNotEmpty() })
        assertTrue(result.messages.any { it is AgentMessage.Tool && it.toolCallId == "call_1" })
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun stepBudgetStillProducesAnAnswer() {
        val first = ToolCall("call_1", "list_nodes", "{}")
        val second = ToolCall("call_2", "list_nodes", "{}")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(first), FinishReason.TOOL_CALLS, null),
                    AgentModelResponse(null, listOf(second), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime = runtime(model, executor, key = "sk-test")

        val result = runBlocking { runtime.runTurn("show nodes", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Completed)
        assertEquals("done", (result.outcome as TurnOutcome.Completed).answer)
        assertEquals(3, model.requests.size)
        assertTrue(model.requests.last().tools.isNotEmpty())
        assertEquals(AgentToolChoice.NONE, model.requests.last().toolChoice)
        assertEquals(
            model.requests.first().tools.map { it.name },
            model.requests.last().tools.map { it.name },
        )
        assertTrue(validateTranscriptPairing(result.messages))
        assertTrue(
            result.messages
                .filterIsInstance<AgentMessage.Tool>()
                .any { it.content.contains("step_budget_exceeded") },
        )
    }

    @Test
    fun cancellationAfterConfirmationLeavesPairedTranscript() {
        val call = ToolCall("call_1", "probe_via_node", """{"url":"https://example.com/"}""")
        val model = ScriptedModel(mutableListOf(AgentModelResponse(null, listOf(call), FinishReason.TOOL_CALLS, null)))
        val executor =
            FakeExecutor(listOf(probe)) { toolCall, approved ->
                if (!approved) {
                    AgentInvocationResult(
                        tool = toolCall.name,
                        ok = false,
                        errorCode = "confirmation_required",
                        confirmation =
                            AgentConfirmation(
                                tool = toolCall.name,
                                argsJson = toolCall.argumentsJson,
                                displayArgsJson = toolCall.argumentsJson,
                                reason = "test",
                            ),
                    )
                } else {
                    ok(toolCall.name)
                }
            }
        val runtime = runtime(model, executor, key = "sk-test")

        val result = runBlocking { runtime.runTurn("probe", emptyList(), onEvent = {}, confirm = { false }) }

        assertTrue(result.outcome is TurnOutcome.Cancelled)
        assertTrue(validateTranscriptPairing(result.messages))
        assertTrue(
            result.messages
                .filterIsInstance<AgentMessage.Tool>()
                .single()
                .content
                .contains("cancelled"),
        )
    }

    @Test
    fun sensitiveInputNeverReachesTheModel() {
        val model = ScriptedModel(mutableListOf(response("should not run")))
        val executor = FakeExecutor(listOf(listNodes)) { _, _ -> ok("list_nodes") }
        val runtime = runtime(model, executor, key = "sk-test")

        val result = runBlocking { runtime.runTurn("ss://secret", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Cancelled)
        assertTrue(model.requests.isEmpty())
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun missingKeyFailsClosed() {
        val model = ScriptedModel(mutableListOf(response("should not run")))
        val executor = FakeExecutor(listOf(listNodes)) { _, _ -> ok("list_nodes") }
        val runtime = runtime(model, executor, key = null)

        val result = runBlocking { runtime.runTurn("hi", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Failed)
        assertEquals(AgentTurnErrorCode.NO_API_KEY, (result.outcome as TurnOutcome.Failed).error.code)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun routingModeSendsStaticMetaToolsAndUnwrapsRouteCall() {
        val search = ToolCall("call_1", AgentMetaTools.SEARCH_TOOLS, """{"query":"nodes"}""")
        val route = ToolCall("call_2", AgentMetaTools.ROUTE_CALL, """{"tool":"list_nodes","args":{}}""")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(search), FinishReason.TOOL_CALLS, null),
                    AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executed = mutableListOf<ToolCall>()
        val executor =
            FakeExecutor(listOf(listNodes)) { call, _ ->
                executed += call
                ok(call.name)
            }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 3))

        val result = runBlocking { runtime.runTurn("show nodes", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Completed)
        assertEquals(
            listOf(AgentMetaTools.ROUTE_CALL, AgentMetaTools.SEARCH_TOOLS),
            model.requests.first().tools.map { it.name },
        )
        assertTrue(
            (model.requests.first().messages.first() as AgentMessage.System)
                .content
                .contains("list_nodes"),
        )
        assertEquals(listOf("list_nodes"), executed.map { it.name })
        assertTrue(
            result.messages
                .filterIsInstance<AgentMessage.Tool>()
                .any { it.toolCallId == "call_1" && it.content.contains("list_nodes") },
        )
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun unknownRouteTargetReturnsSuggestionsWithoutExecuting() {
        val route = ToolCall("call_1", AgentMetaTools.ROUTE_CALL, """{"tool":"list_node","args":{}}""")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        var executed = false
        val executor =
            FakeExecutor(listOf(listNodes)) { call, _ ->
                executed = true
                ok(call.name)
            }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 2))

        val result = runBlocking { runtime.runTurn("show nodes", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(!executed)
        val observation = result.messages.filterIsInstance<AgentMessage.Tool>().single().content
        assertTrue(observation.contains("unknown_tool"))
        assertTrue(observation.contains("list_nodes"))
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun routeCallCannotTargetMetaTools() {
        val route = ToolCall("call_1", AgentMetaTools.ROUTE_CALL, """{"tool":"search_tools","args":{}}""")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        var executed = false
        val executor =
            FakeExecutor(listOf(listNodes)) { call, _ ->
                executed = true
                ok(call.name)
            }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 2))

        val result = runBlocking { runtime.runTurn("x", emptyList(), onEvent = {}, confirm = { true }) }

        assertTrue(!executed)
        assertTrue(
            result.messages
                .filterIsInstance<AgentMessage.Tool>()
                .single()
                .content
                .contains("unknown_tool"),
        )
    }

    @Test
    fun confirmationRequestReportsRemainingCallsInTheBatch() {
        val first = ToolCall("call_1", "probe_via_node", """{"url":"https://example.com/1"}""")
        val second = ToolCall("call_2", "probe_via_node", """{"url":"https://example.com/2"}""")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(first, second), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executor =
            FakeExecutor(listOf(probe)) { call, humanApproved ->
                if (humanApproved) {
                    ok(call.name)
                } else {
                    AgentInvocationResult(
                        tool = call.name,
                        ok = false,
                        errorCode = "confirmation_required",
                        confirmation =
                            AgentConfirmation(
                                tool = call.name,
                                argsJson = call.argumentsJson,
                                displayArgsJson = call.argumentsJson,
                                reason = "network probe",
                            ),
                    )
                }
            }
        val runtime = runtime(model, executor, key = "sk-test")
        val remaining = mutableListOf<Int>()

        val result =
            runBlocking {
                runtime.runTurn(
                    "probe twice",
                    emptyList(),
                    onEvent = { event ->
                        if (event is AgentEvent.ConfirmationRequested) {
                            remaining += event.request.batchRemaining
                        }
                    },
                    confirm = { true },
                )
            }

        assertEquals(listOf(2, 1), remaining)
        assertTrue(result.outcome is TurnOutcome.Completed)
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun routeCallPreservesConfirmationForInnerTool() {
        val route =
            ToolCall(
                "call_1",
                AgentMetaTools.ROUTE_CALL,
                """{"tool":"probe_via_node","args":{"outbound":"node-a","url":"https://example.com/"}}""",
            )
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        var confirmationTool: String? = null
        var approved = false
        val executor =
            FakeExecutor(listOf(probe)) { call, humanApproved ->
                if (!humanApproved) {
                    AgentInvocationResult(
                        tool = call.name,
                        ok = false,
                        errorCode = "confirmation_required",
                        confirmation =
                            AgentConfirmation(
                                tool = call.name,
                                argsJson = call.argumentsJson,
                                displayArgsJson = call.argumentsJson,
                                reason = "test",
                            ),
                    )
                } else {
                    approved = true
                    ok(call.name)
                }
            }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 2))

        val result =
            runBlocking {
                runtime.runTurn(
                    "probe",
                    emptyList(),
                    onEvent = { event ->
                        if (event is AgentEvent.StateChanged && event.state is TurnState.AwaitingConfirmation) {
                            confirmationTool = (event.state as TurnState.AwaitingConfirmation).request.toolName
                        }
                    },
                    confirm = { true },
                )
            }

        assertEquals("probe_via_node", confirmationTool)
        assertTrue(approved)
        assertTrue(result.outcome is TurnOutcome.Completed)
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun searchBudgetStopsRunawaySearching() {
        val searches =
            (1..3).map {
                ToolCall("call_$it", AgentMetaTools.SEARCH_TOOLS, """{"query":"nodes"}""")
            }
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, searches, FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime =
            runtime(
                model,
                executor,
                key = "sk-test",
                config = AgentConfig(maxToolRounds = 2, maxSearchesPerRound = 2),
            )

        val result = runBlocking { runtime.runTurn("x", emptyList(), onEvent = {}, confirm = { true }) }

        val toolMessages = result.messages.filterIsInstance<AgentMessage.Tool>()
        assertEquals(3, toolMessages.size)
        assertTrue(toolMessages.last().content.contains("search_budget_exceeded"))
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun routingCanBeToggledBetweenTurns() {
        var enabled = false
        val model = ScriptedModel(mutableListOf(response("one"), response("two")))
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime =
            AgentRuntime(
                inputGuard = DefaultAgentInputGuard,
                model = model,
                tools = executor,
                apiKeys = FakeKeyProvider("sk-test"),
                config = AgentConfig(maxToolRounds = 1),
                toolRoutingEnabled = { enabled },
            )

        runBlocking { runtime.runTurn("one", emptyList(), onEvent = {}, confirm = { true }) }
        assertEquals(listOf("list_nodes"), model.requests[0].tools.map { it.name })

        enabled = true
        runBlocking { runtime.runTurn("two", emptyList(), onEvent = {}, confirm = { true }) }
        assertEquals(
            listOf(AgentMetaTools.ROUTE_CALL, AgentMetaTools.SEARCH_TOOLS),
            model.requests[1].tools.map { it.name },
        )
    }

    @Test
    fun nativeModeStillSendsEveryToolSchema() {
        val model = ScriptedModel(mutableListOf(response("hello")))
        val executor = FakeExecutor(listOf(listNodes, probe)) { call, _ -> ok(call.name) }
        val runtime =
            runtime(
                model,
                executor,
                key = "sk-test",
                config = AgentConfig(maxToolRounds = 1, toolRoutingEnabled = false),
            )

        runBlocking { runtime.runTurn("hi", emptyList(), onEvent = {}, confirm = { true }) }

        assertEquals(
            listOf("list_nodes", "probe_via_node"),
            model.requests.single().tools.map { it.name },
        )
    }

    @Test
    fun requestsKeepAnAppendOnlyPrefixWithinATurn() {
        val route = ToolCall("call_1", AgentMetaTools.ROUTE_CALL, """{"tool":"list_nodes","args":{}}""")
        val model =
            ScriptedModel(
                mutableListOf(
                    AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                    response("done"),
                ),
            )
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 2))

        runBlocking { runtime.runTurn("nodes", emptyList(), onEvent = {}, confirm = { true }) }

        val first = model.requests[0]
        val second = model.requests[1]
        assertEquals(first.messages.first(), second.messages.first())
        assertEquals(first.tools, second.tools)
        assertEquals(first.messages, second.messages.take(first.messages.size))
        assertEquals(
            listOf(AgentMetaTools.ROUTE_CALL, AgentMetaTools.SEARCH_TOOLS),
            first.tools.map { it.name },
        )
    }

    @Test
    fun nextTurnKeepsTheSameSystemAndToolsPrefix() {
        val model = ScriptedModel(mutableListOf(response("one"), response("two")))
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime = runtime(model, executor, key = "sk-test", config = AgentConfig(maxToolRounds = 1))

        val firstTurn = runBlocking { runtime.runTurn("one", emptyList(), onEvent = {}, confirm = { true }) }
        runBlocking { runtime.runTurn("two", firstTurn.messages, onEvent = {}, confirm = { true }) }

        val first = model.requests[0]
        val second = model.requests[1]
        assertEquals(first.messages.first(), second.messages.first())
        assertEquals(first.tools, second.tools)
        assertEquals(firstTurn.messages, second.messages.drop(1).take(firstTurn.messages.size))
    }

    @Test
    fun turnResultCarriesCompactedHistory() {
        val big = "x".repeat(5_000)
        val history =
            listOf(
                AgentMessage.User("old"),
                AgentMessage.Assistant(null, listOf(ToolCall("c1", "list_nodes", "{}"))),
                AgentMessage.Tool("c1", "list_nodes", """{"ok":true,"tool":"list_nodes","output":{"data":"$big"}}"""),
                AgentMessage.Assistant("old answer"),
                AgentMessage.User("new"),
            )
        val model = ScriptedModel(mutableListOf(response("done")))
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime =
            runtime(
                model,
                executor,
                key = "sk-test",
                config = AgentConfig(maxToolRounds = 1, maxHistoricalObservationChars = 4_000),
            )

        val result = runBlocking { runtime.runTurn("again", history, onEvent = {}, confirm = { true }) }

        assertNotNull(result.compactedHistory)
        val compactedTool = result.compactedHistory!![2] as AgentMessage.Tool
        assertTrue(compactedTool.content.length < 1_000)
        assertTrue(validateTranscriptPairing(result.compactedHistory!!))
    }

    @Test
    fun streamingAccumulatesContentAndToolCallDeltas() {
        val streaming =
            StreamingScriptedModel(
                mutableListOf(
                    listOf(
                        AgentModelChunk.ReasoningDelta("think "),
                        AgentModelChunk.ContentDelta("Hel"),
                        AgentModelChunk.ContentDelta("lo"),
                        AgentModelChunk.ToolCallDelta(0, id = "call_1", name = "list_nodes", argumentsDelta = "{}"),
                        AgentModelChunk.Completed(FinishReason.TOOL_CALLS, null),
                    ),
                    listOf(
                        AgentModelChunk.ContentDelta("done"),
                        AgentModelChunk.Completed(FinishReason.STOP, null),
                    ),
                ),
            )
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime =
            AgentRuntime(
                inputGuard = DefaultAgentInputGuard,
                model = ScriptedModel(mutableListOf()),
                tools = executor,
                apiKeys = FakeKeyProvider("sk-test"),
                config = AgentConfig(maxToolRounds = 2),
                streamingModel = streaming,
            )

        val events = mutableListOf<AgentEvent>()
        val result = runBlocking { runtime.runTurn("hi", emptyList(), onEvent = { events += it }, confirm = { true }) }

        assertTrue(result.outcome is TurnOutcome.Completed)
        assertEquals(
            listOf("think "),
            events.filterIsInstance<AgentEvent.ReasoningDelta>().map { it.text },
        )
        assertEquals(
            listOf("Hel", "lo", "done"),
            events.filterIsInstance<AgentEvent.AssistantDelta>().map { it.text },
        )
        assertTrue(result.messages.any { it is AgentMessage.Assistant && it.content == "Hello" })
        assertTrue(validateTranscriptPairing(result.messages))
    }

    @Test
    fun stopCancelsAStreamingTurn() {
        val streaming = BlockingStreamingModel()
        val executor = FakeExecutor(listOf(listNodes)) { call, _ -> ok(call.name) }
        val runtime =
            AgentRuntime(
                inputGuard = DefaultAgentInputGuard,
                model = ScriptedModel(mutableListOf()),
                tools = executor,
                apiKeys = FakeKeyProvider("sk-test"),
                config = AgentConfig(maxToolRounds = 1),
                streamingModel = streaming,
            )

        val events = mutableListOf<AgentEvent>()
        val result =
            runBlocking {
                val turn =
                    async {
                        runtime.runTurn("hi", emptyList(), onEvent = { events += it }, confirm = { true })
                    }
                streaming.started.await()
                runtime.cancel()
                turn.await()
            }

        assertTrue(result.outcome is TurnOutcome.Cancelled)
        assertEquals(listOf("partial"), events.filterIsInstance<AgentEvent.AssistantDelta>().map { it.text })
        assertTrue(validateTranscriptPairing(result.messages))
    }

    private fun runtime(
        model: AgentModelClient,
        executor: AgentToolExecutor,
        key: String?,
        config: AgentConfig = AgentConfig(maxToolRounds = 1),
    ): AgentRuntime =
        AgentRuntime(
            inputGuard = DefaultAgentInputGuard,
            model = model,
            tools = executor,
            apiKeys = FakeKeyProvider(key),
            config = config,
        )

    private fun response(text: String): AgentModelResponse =
        AgentModelResponse(text, emptyList(), FinishReason.STOP, null)

    private fun ok(name: String): AgentInvocationResult =
        AgentInvocationResult(
            tool = name,
            ok = true,
            output = buildJsonObject { put("ok", true) },
        )
}

private class ScriptedModel(
    private val responses: MutableList<AgentModelResponse>,
) : AgentModelClient {
    val requests = mutableListOf<AgentModelRequest>()

    override suspend fun complete(
        request: AgentModelRequest,
        apiKey: String,
    ): AgentModelResponse {
        requests += request
        return responses.removeAt(0)
    }
}

private class FakeExecutor(
    private val definitions: List<AgentToolDefinition>,
    private val execute: (ToolCall, Boolean) -> AgentInvocationResult,
) : AgentToolExecutor {
    override fun definitions(): List<AgentToolDefinition> = definitions

    override fun cancelActive() = Unit

    override suspend fun invokeUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = execute(ToolCall("user", name, argsJson), true)

    override suspend fun confirmUserCommand(
        name: String,
        argsJson: String,
    ): AgentInvocationResult = execute(ToolCall("user", name, argsJson), true)

    override suspend fun invokeAgent(
        call: ToolCall,
        humanApproved: Boolean,
    ): AgentInvocationResult = execute(call, humanApproved)
}

private class StreamingScriptedModel(
    private val responses: MutableList<List<AgentModelChunk>>,
) : AgentStreamingModelClient {
    override fun stream(
        request: AgentModelRequest,
        apiKey: String,
    ): Flow<AgentModelChunk> =
        flow {
            responses.removeAt(0).forEach { emit(it) }
        }
}

private class BlockingStreamingModel : AgentStreamingModelClient {
    val started = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()

    override fun stream(
        request: AgentModelRequest,
        apiKey: String,
    ): Flow<AgentModelChunk> =
        flow {
            emit(AgentModelChunk.ContentDelta("partial"))
            started.complete(Unit)
            stopped.await()
        }

    override fun cancel() {
        stopped.complete(Unit)
    }
}

private class FakeKeyProvider(
    private var key: String?,
) : AgentApiKeyProvider {
    override val supported: Boolean = true

    override suspend fun apiKey(): String? = key

    override suspend fun save(apiKey: String) {
        key = apiKey
    }

    override suspend fun clear() {
        key = null
    }
}
