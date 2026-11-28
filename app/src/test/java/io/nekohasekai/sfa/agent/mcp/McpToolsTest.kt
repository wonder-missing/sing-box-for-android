package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.BulkApproval
import io.nekohasekai.sfa.agent.ConfirmationKind
import io.nekohasekai.sfa.agent.ToolCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    fun mcpToolsGetRiskKindsBeforeTheCard() {
        val remote =
            listOf(
                McpRemoteTool("job_run", "run", buildJsonObject { put("type", "object") }, false),
                McpRemoteTool("job_status", "status", buildJsonObject { put("type", "object") }, false),
                McpRemoteTool("note_create", "write", buildJsonObject { put("type", "object") }, false),
            )
        val tools = mcpAgentTools("Portal", "portal", remote, unusedSession()).associateBy { it.definition.name }

        assertEquals(ConfirmationKind.REMOTE_EXEC, tools.getValue("portal_job_run").definition.confirmationKind)
        assertEquals(BulkApproval.TURN, tools.getValue("portal_job_run").definition.bulkApproval)
        assertEquals(ConfirmationKind.MCP_READ, tools.getValue("portal_job_status").definition.confirmationKind)
        assertEquals(BulkApproval.TURN, tools.getValue("portal_job_status").definition.bulkApproval)
        assertEquals(ConfirmationKind.MCP_WRITE, tools.getValue("portal_note_create").definition.confirmationKind)
        assertEquals(BulkApproval.BATCH, tools.getValue("portal_note_create").definition.bulkApproval)
    }

    @Test
    fun toolNamesUseTheNamespaceWithoutTheMcpPrefix() {
        val tool =
            McpRemoteTool(
                name = "docker_run",
                description = "Run a container",
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = false,
            )

        val definition =
            mcpAgentTools("Clawdy Portal Knocks", "portal", listOf(tool), unusedSession()).single().definition

        assertEquals("portal_docker_run", definition.name)
        assertFalse(definition.name.startsWith("mcp_"))
        assertEquals("Clawdy Portal Knocks", definition.serverName)
        assertEquals("portal", definition.namespace)
        assertTrue(definition.description.startsWith("MCP server: Clawdy Portal Knocks."))
    }

    @Test
    fun renameChangesTheDisplayNameAndNamespace() =
        runBlocking {
            var saved =
                listOf(
                    McpServerConfig(
                        id = "portal",
                        name = "Portal",
                        url = "https://example.com/mcp",
                        namespace = "portal",
                    ),
                )
            val repository =
                object : McpServerRepository {
                    override fun load(): List<McpServerConfig> = saved

                    override fun save(servers: List<McpServerConfig>) {
                        saved = servers
                    }
                }
            val runtime = McpRuntime(repository, { error("transport is not used") })

            val renamed = runtime.rename("portal", "Clawdy Portal", "clawdy")
            val duplicate = runtime.add("Other", "clawdy", "https://example.org/mcp", "")

            assertTrue(renamed is McpAddResult.Saved)
            assertEquals("Clawdy Portal", runtime.views.value.single().name)
            assertEquals("clawdy", runtime.views.value.single().namespace)
            assertTrue(duplicate is McpAddResult.Invalid)
            assertEquals(McpConfigError.NAMESPACE_DUPLICATE, (duplicate as McpAddResult.Invalid).error)
        }

    @Test
    fun namespaceIsValidatedAndAllocated() {
        assertEquals("portal", McpServerValidator.allocateNamespace("Portal", emptySet()))
        assertEquals("portal_2", McpServerValidator.allocateNamespace("Portal", setOf("portal")))
        assertEquals("s123_server", McpServerValidator.allocateNamespace("123 Server", emptySet()))
        assertNull(
            McpServerValidator.validate("Portal", "https://example.com/mcp", "", 0, namespace = "portal"),
        )
        assertEquals(
            McpConfigError.NAMESPACE,
            McpServerValidator.validate("Portal", "https://example.com/mcp", "", 0, namespace = "1bad"),
        )
        assertEquals(
            McpConfigError.NAMESPACE_DUPLICATE,
            McpServerValidator.validate(
                "Portal",
                "https://example.com/mcp",
                "",
                0,
                namespace = "portal",
                existingNamespaces = setOf("portal"),
            ),
        )
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
    fun longDescriptionsArePassedThroughUnchanged() {
        val description =
            "Run a command in an allowlisted container. Public internet is allowed; this Mac and LAN are " +
                "blocked. Images: alpine, ubuntu, debian, python, node, busybox. command is argv. " +
                "vfs true mounts the caller VFS at /vfs and output at /work."
        val tool =
            McpRemoteTool(
                name = "docker_run",
                description = description,
                inputSchema = buildJsonObject { put("type", "object") },
                readOnly = false,
            )

        val definition = mcpAgentTools("Demo", "demo", listOf(tool), unusedSession()).single().definition

        assertEquals("MCP server: Demo. $description", definition.description)
        assertFalse(definition.description.contains("…"))
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

    @Test
    fun jsonRpcErrorCodeIsPreserved() =
        runBlocking {
            val tool =
                mcpAgentTools(
                    serverName = "Demo",
                    serverPrefix = "demo",
                    remote =
                        listOf(
                            McpRemoteTool(
                                name = "job_file",
                                description = "Read a job file",
                                inputSchema = buildJsonObject { put("type", "object") },
                                readOnly = true,
                            ),
                        ),
                    session = McpSession(RpcErrorTransport()),
                ).single()
            val execution =
                tool.execute(
                    buildJsonObject { put("path", "../jobs.json") },
                    object : ToolCancellation {
                        override fun isCancelled(): Boolean = false

                        override fun onCancel(action: () -> Unit) = Unit
                    },
                )

            assertFalse(execution.ok)
            assertEquals("mcp_failed", execution.errorCode)
            assertEquals("-32602", (execution.output!!["jsonrpc_code"] as JsonPrimitive).content)
            assertTrue(execution.errorMessage.orEmpty().contains("JSON-RPC -32602"))
        }

    private class RpcErrorTransport : McpTransport {
        override suspend fun exchange(
            body: String,
            sessionId: String?,
            cancellation: ToolCancellation?,
            extraHeaders: Map<String, String>,
            readTimeoutMs: Int?,
        ): McpExchange {
            val request = Json.parseToJsonElement(body).jsonObject
            val method = request["method"]?.jsonPrimitive?.content
            val id = request["id"]?.jsonPrimitive?.content.orEmpty()
            val response =
                when (method) {
                    "initialize" ->
                        """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"demo","version":"1"}}}"""
                    "notifications/initialized" -> ""
                    "tools/call" ->
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"Invalid path"}}"""
                    else -> """{"jsonrpc":"2.0","id":$id,"result":{}}"""
                }
            return McpExchange(200, "application/json", response, sessionId = null)
        }
    }
}
