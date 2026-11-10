package io.nekohasekai.sfa.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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
