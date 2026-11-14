package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
                    extraHeaders: Map<String, String>,
                    readTimeoutMs: Int?,
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

        assertEquals(MCP_DOCKER_RUN_TIMEOUT_MS, definition.timeoutMs)
    }

    @Test
    fun onlyDockerRunGetsTheExtendedBudget() {
        val docker =
            McpRemoteTool(
                name = "docker_run",
                description = "Run a container",
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = false,
            )
        val other =
            McpRemoteTool(
                name = "orb_status",
                description = "Orb status",
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = true,
            )

        val definitions = mcpAgentTools("Demo", "demo", listOf(docker, other), unusedSession())
            .map { it.definition }

        assertEquals(
            MCP_DOCKER_RUN_TIMEOUT_MS,
            definitions.first { it.name.endsWith("docker_run") }.timeoutMs,
        )
        assertEquals(
            MCP_TOOL_TIMEOUT_MS,
            definitions.first { it.name.endsWith("orb_status") }.timeoutMs,
        )
    }

    @Test
    fun vfsLinkRequestCarriesTheWholeWorkspaceScopeHint() {
        val tool =
            McpRemoteTool(
                name = "vfs_link_request",
                description = "Request a VFS Link.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("scope") { put("type", "array") }
                        }
                    },
                readOnly = false,
            )

        val definition = mcpAgentTools("Portal", "portal", listOf(tool), unusedSession()).single().definition

        assertTrue(definition.description.contains("Whole workspace"))
        assertTrue(definition.description.contains("conversation"))
        val scope =
            ((definition.parametersSchemaOverride["properties"] as JsonObject)["scope"] as JsonObject)
        val hint = (scope["description"] as JsonPrimitive).content
        assertTrue(hint.contains("conductor, trace, system_prompt, conversation"))
        assertTrue(hint.contains("Never ask the user to name scopes"))
    }

    @Test
    fun nonVfsToolsDoNotGetTheScopeHint() {
        val tool =
            McpRemoteTool(
                name = "orb_status",
                description = "Orb status",
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = true,
            )

        val definition = mcpAgentTools("Demo", "demo", listOf(tool), unusedSession()).single().definition

        assertFalse(definition.description.contains("Whole workspace"))
    }

    @Test
    fun aWholeWorkspaceTurnExpandsASubsetScope() {
        val arguments =
            buildJsonObject {
                put(
                    "scope",
                    buildJsonArray {
                        add("conductor")
                    },
                )
            }

        val expanded = expandVfsScopeArguments(arguments)

        assertEquals(
            listOf("conductor", "trace", "system_prompt", "conversation"),
            (expanded["scope"] as JsonArray).map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun aMissingScopeBecomesFullAndAStringScopeIsLeftAlone() {
        val expanded = expandVfsScopeArguments(buildJsonObject {})
        assertEquals(4, (expanded["scope"] as JsonArray).size)

        val raw = buildJsonObject { put("scope", "conductor") }
        assertEquals(raw, expandVfsScopeArguments(raw))
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
