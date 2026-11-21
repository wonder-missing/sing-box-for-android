package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicToolExecutorTest {
    @Test
    fun cancelActiveReachesARegistryThatWasSwappedOut() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val blocked =
                object : AgentTool {
                    override val definition =
                        AgentToolDefinition(
                            name = "block",
                            description = "Block until cancelled",
                            permission = AgentPermission.UTILITY,
                            parameters = emptyList(),
                            confirmationPolicy = ConfirmationPolicy.NEVER,
                            category = "utility",
                        )

                    override suspend fun execute(
                        args: JsonObject,
                        cancellation: ToolCancellation,
                    ): ToolExecution {
                        val done = CompletableDeferred<Unit>()
                        cancellation.onCancel { done.complete(Unit) }
                        started.complete(Unit)
                        done.await()
                        return ToolExecution(errorCode = "cancelled", errorMessage = "cancelled")
                    }
                }
            val executor = DynamicToolExecutor(listOf(blocked), toolTimeoutMs = 10_000)
            val call = async { executor.invokeAgent(ToolCall("call_1", "block", "{}"), humanApproved = false) }
            withTimeout(5_000) { started.await() }

            repeat(5) { index ->
                executor.setExtras(
                    listOf(
                        object : AgentTool {
                            override val definition =
                                AgentToolDefinition(
                                    name = "extra_$index",
                                    description = "extra",
                                    permission = AgentPermission.UTILITY,
                                    parameters = emptyList(),
                                    confirmationPolicy = ConfirmationPolicy.NEVER,
                                    category = "mcp",
                                )

                            override suspend fun execute(
                                args: JsonObject,
                                cancellation: ToolCancellation,
                            ): ToolExecution = ToolExecution()
                        },
                    ),
                )
            }
            executor.cancelActive()

            val result = withTimeout(5_000) { call.await() }
            assertEquals("cancelled", result.errorCode)
        }
}
