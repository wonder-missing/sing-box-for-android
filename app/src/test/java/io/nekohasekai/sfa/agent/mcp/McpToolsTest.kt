package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolsTest {
    private fun unusedSession(): McpSession =
        McpSession(
            object : McpTransport {
                override suspend fun exchange(
                    body: String,
                    sessionId: String?,
                    cancellation: ToolCancellation?,
                ): McpExchange = error("not used")
            },
        )

    @Test
    fun mcpToolsGetTheLongCallBudget() {
        val tool =
            McpRemoteTool(
                name = "docker_run",
                description = "Run a container",
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = false,
            )

        val definition = mcpAgentTools("Demo", "demo", listOf(tool), unusedSession()).single().definition

        assertEquals(MCP_TOOL_TIMEOUT_MS, definition.timeoutMs)
    }

    @Test
    fun structuredSuccessIsNotWrappedInText() {
        val execution =
            mcpToolExecution(
                McpCallResult("""{"exit":0,"output":"ok","truncated":false}""", isError = false),
            )

        assertTrue(execution.ok)
        val output = execution.output!!
        assertEquals("0", (output["exit"] as JsonPrimitive).content)
        assertEquals("ok", (output["output"] as JsonPrimitive).content)
        assertFalse(output.containsKey("text"))
    }

    @Test
    fun plainSuccessKeepsTheTextShape() {
        val execution = mcpToolExecution(McpCallResult("hello", isError = false))

        assertTrue(execution.ok)
        assertEquals("hello", (execution.output!!["text"] as JsonPrimitive).content)
    }

    @Test
    fun structuredErrorKeepsThePayloadAndDoesNotDuplicateIt() {
        val execution =
            mcpToolExecution(
                McpCallResult("""{"exit":124,"error":"超时","output":""}""", isError = true),
            )

        assertFalse(execution.ok)
        assertEquals("mcp_error", execution.errorCode)
        assertEquals("124", ((execution.output!!["exit"]) as JsonPrimitive).content)
        assertEquals("MCP tool reported an error.", execution.errorMessage)
    }

    @Test
    fun plainErrorKeepsTheMessage() {
        val execution = mcpToolExecution(McpCallResult("参数不合法", isError = true))

        assertFalse(execution.ok)
        assertNull(execution.output)
        assertEquals("mcp_error", execution.errorCode)
        assertEquals("参数不合法", execution.errorMessage)
    }

    @Test
    fun emptyErrorHasAFallback() {
        val execution = mcpToolExecution(McpCallResult("   ", isError = true))

        assertFalse(execution.ok)
        assertEquals("MCP tool reported an error.", execution.errorMessage)
    }

    @Test
    fun jsonScalarIsStillTreatedAsText() {
        val execution = mcpToolExecution(McpCallResult("42", isError = false))

        assertTrue(execution.ok)
        assertEquals("42", (execution.output!!["text"] as JsonPrimitive).content)
    }
}
