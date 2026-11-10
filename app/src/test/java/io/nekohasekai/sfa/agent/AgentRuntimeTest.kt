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
        )

    private val probe =
        AgentToolDefinition(
            name = "probe_via_node",
            description = "Probe a URL",
            permission = AgentPermission.NETWORK_PROBE,
            parameters = emptyList(),
            confirmationPolicy = ConfirmationPolicy.ALWAYS,
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
        assertTrue(model.requests.last().tools.isEmpty())
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

    private fun runtime(
        model: AgentModelClient,
        executor: AgentToolExecutor,
        key: String?,
    ): AgentRuntime =
        AgentRuntime(
            inputGuard = DefaultAgentInputGuard,
            model = model,
            tools = executor,
            apiKeys = FakeKeyProvider(key),
            config = AgentConfig(maxToolRounds = 1),
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
