package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.AgentApiKeyProvider
import io.nekohasekai.sfa.agent.AgentConfig
import io.nekohasekai.sfa.agent.AgentMessage
import io.nekohasekai.sfa.agent.AgentMetaTools
import io.nekohasekai.sfa.agent.AgentModelClient
import io.nekohasekai.sfa.agent.AgentModelRequest
import io.nekohasekai.sfa.agent.AgentModelResponse
import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentRuntime
import io.nekohasekai.sfa.agent.AgentTool
import io.nekohasekai.sfa.agent.AgentToolCatalog
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.AgentToolRegistry
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import io.nekohasekai.sfa.agent.DefaultAgentInputGuard
import io.nekohasekai.sfa.agent.DynamicToolExecutor
import io.nekohasekai.sfa.agent.FinishReason
import io.nekohasekai.sfa.agent.ToolCall
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolExecution
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class McpClientTest {
    @Test
    fun sseResultMatchesTheRequestIdAndDropsTheToken() {
        val body =
            """
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"ignored":true}}

            data: {"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"from-sse"}],"isError":false}}

            """.trimIndent()
        val result =
            McpMessages.rpcResult(
                McpExchange(200, "text/event-stream", body, "sess"),
                id = 7,
                token = "secret-token",
            ) as JsonObject
        assertEquals("from-sse", McpMessages.callText(result).text)

        val thrown =
            runCatching {
                McpMessages.rpcResult(
                    McpExchange(
                        200,
                        "application/json",
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"bad secret-token"}}""",
                        null,
                    ),
                    id = 1,
                    token = "secret-token",
                )
            }.exceptionOrNull()
        assertTrue(thrown is McpException)
        assertFalse(thrown!!.message!!.contains("secret-token"))
    }

    @Test
    fun expiredSessionReinitializesOnce() =
        runBlocking {
            val transport = ScriptTransport()
            val session = McpSession(transport, token = "secret-token")

            val tools = session.listTools()

            assertEquals(listOf("echo"), tools.map { it.name })
            assertEquals(2, transport.bodies.count { it.contains("\"method\":\"initialize\"") })
            val inits =
                transport.sessions.filterIndexed { index, _ ->
                    transport.bodies[index].contains("\"method\":\"initialize\"")
                }
            val lists =
                transport.sessions.filterIndexed { index, _ ->
                    transport.bodies[index].contains("\"method\":\"tools/list\"")
                }
            assertEquals(listOf<String?>(null, null), inits)
            assertEquals(listOf<String?>("sess", "sess"), lists)
        }

    @Test
    fun passthroughAcceptsANumberAndStillRequiresKeys() =
        runBlocking {
            var seen: JsonObject? = null
            val registry = AgentToolRegistry(listOf(passthroughTool { seen = it }))

            val accepted =
                registry.invokeAgent(
                    ToolCall("1", "mcp_demo_echo", """{"text":"hi","n":1,"extra":true}"""),
                    humanApproved = true,
                )
            assertTrue(accepted.errorMessage ?: "", accepted.ok)
            assertEquals("1", (seen!!["n"] as JsonPrimitive).content)

            val missing =
                registry.invokeAgent(
                    ToolCall("2", "mcp_demo_echo", """{"text":"   "}"""),
                    humanApproved = true,
                )
            assertEquals("missing_parameter", missing.errorCode)
            assertNull(missing.output)
        }

    @Test
    fun promptNamesMcpToolsOnlyWhenTheyAreActivated() {
        val builtin =
            AgentToolDefinition(
                name = "network_snapshot",
                description = "Show the current network environment.",
                permission = AgentPermission.READ_NETWORK_STATE,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NEVER,
                category = "network",
                core = true,
            )
        val without = AgentToolCatalog(listOf(builtin)).promptSection()
        assertFalse(without.contains("MCP tools are external"))
        assertTrue(without.contains("network_snapshot()"))

        val withMcp = AgentToolCatalog(listOf(builtin, passthroughTool { }.definition)).promptSection()
        assertTrue(withMcp.contains("- mcp: mcp_demo_echo"))
        assertTrue(withMcp.contains("MCP tools are external"))
        assertFalse(withMcp.contains("mcp_demo_echo("))
        assertTrue(withMcp.contains("network_snapshot()"))
    }

    @Test
    fun storeRoundTripKeepsTheTokenAndABadFileIsEmpty() {
        val dir = Files.createTempDirectory("mcp-store").toFile()
        try {
            val file = File(dir, "mcp-servers.json")
            val repo = FileMcpServerRepository(file)
            repo.save(
                listOf(
                    McpServerConfig(
                        id = "demo",
                        name = "Demo",
                        url = "https://example.com/mcp",
                        token = "secret-token",
                        enabled = true,
                    ),
                ),
            )
            val loaded = FileMcpServerRepository(file).load().single()
            assertEquals("secret-token", loaded.token)
            assertTrue(loaded.enabled)
            assertFalse(loaded.toString().contains("secret-token"))

            file.writeText("{", Charsets.UTF_8)
            assertTrue(FileMcpServerRepository(file).load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validatorRejectsUserinfoAndQuerySecrets() {
        assertEquals(
            McpConfigError.URL_USERINFO,
            McpServerValidator.validate("Portal", "https://user:pw@example.com/mcp", "", 0),
        )
        assertEquals(
            McpConfigError.URL_QUERY_SECRET,
            McpServerValidator.validate("Portal", "https://example.com/mcp?token=abc", "", 0),
        )
        assertNull(McpServerValidator.validate("Mini Portal", "http://127.0.0.1:9/mcp", "", 0))
        assertEquals("mini_portal", McpServerValidator.allocateId("Mini Portal", emptySet()))
        assertEquals(
            "mcp_mini_portal_create_note",
            allocateToolName("mini_portal", "create note", mutableSetOf()),
        )
    }

    @Test
    fun activatedServerIsSearchedAndCalledWithTheSession() =
        runBlocking {
            LoopbackMcp().use { server ->
                val runtime = runtime()
                val added = runtime.add("Demo", server.url, "test-token")
                assertTrue(added is McpAddResult.Saved)
                val view = runtime.views.value.single()
                assertTrue(view.error ?: "", view.enabled)
                assertEquals(2, view.toolCount)

                val executor = DynamicToolExecutor(emptyList())
                executor.setExtras(runtime.tools())
                val echo = executor.definitions().first { it.name == "mcp_demo_echo" }
                assertEquals(ConfirmationPolicy.NEVER, echo.confirmationPolicy)
                assertFalse(echo.core)
                assertTrue(echo.parametersSchema.toString().contains("\"n\""))

                val search = ToolCall("call_1", AgentMetaTools.SEARCH_TOOLS, """{"query":"echo"}""")
                val route =
                    ToolCall(
                        "call_2",
                        AgentMetaTools.ROUTE_CALL,
                        """{"tool":"mcp_demo_echo","args":{"text":"hello","n":1}}""",
                    )
                val model =
                    ScriptedModel(
                        mutableListOf(
                            AgentModelResponse(null, listOf(search), FinishReason.TOOL_CALLS, null),
                            AgentModelResponse(null, listOf(route), FinishReason.TOOL_CALLS, null),
                            AgentModelResponse("called", emptyList(), FinishReason.STOP, null),
                        ),
                    )
                val agent =
                    AgentRuntime(
                        inputGuard = DefaultAgentInputGuard,
                        model = model,
                        tools = executor,
                        apiKeys = FakeKey("sk-test"),
                        config = AgentConfig(maxToolRounds = 4),
                    )
                val result =
                    agent.runTurn(
                        userText = "echo hello",
                        history = emptyList(),
                        onEvent = {},
                        confirm = { error("read-only MCP tool must not ask for confirmation") },
                    )

                val toolMessages = result.messages.filterIsInstance<AgentMessage.Tool>()
                assertEquals(2, toolMessages.size)
                assertTrue(toolMessages[0].content.contains("mcp_demo_echo"))
                assertTrue(toolMessages[0].content.contains("\"n\""))
                assertTrue(toolMessages[1].content.contains("echo:hello:1"))
                val prompt = (model.requests.first().messages.first() as AgentMessage.System).content
                assertTrue(prompt.contains("- mcp: mcp_demo_echo"))
                assertFalse(prompt.contains("mcp_demo_echo("))
                assertEquals(
                    listOf(AgentMetaTools.ROUTE_CALL, AgentMetaTools.SEARCH_TOOLS),
                    model.requests.first().tools.map { it.name },
                )
                val call = server.records.last { it.method == "tools/call" }
                assertEquals("sess-1", call.session)
                assertEquals("Bearer test-token", call.authorization)
                assertTrue(call.body.contains("\"name\":\"echo\""))
                assertFalse(call.body.contains("mcp_demo_echo"))
            }
        }

    @Test
    fun nonReadonlyToolDoesNotCallUntilApproved() =
        runBlocking {
            LoopbackMcp().use { server ->
                val runtime = runtime()
                runtime.add("Demo", server.url, "test-token")
                val write = runtime.tools().first { it.definition.name == "mcp_demo_write" }
                assertEquals(ConfirmationPolicy.AGENT_ONLY, write.definition.confirmationPolicy)
                val registry = AgentToolRegistry(listOf(write))
                val args = """{"text":"hi"}"""

                val pending = registry.invokeAgent(ToolCall("1", write.definition.name, args), humanApproved = false)
                assertEquals("confirmation_required", pending.errorCode)
                assertEquals(0, server.writes.get())

                val confirmed = registry.invokeAgent(ToolCall("1", write.definition.name, args), humanApproved = true)
                assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
                assertEquals(1, server.writes.get())
                assertTrue(confirmed.output.toString().contains("wrote"))
            }
        }

    @Test
    fun freshListIsReusedUntilStaleAndFailureStaysOff() =
        runBlocking {
            val transport = ScriptTransport()
            var now = 1_000L
            val repo = MemoryRepository()
            val runtime =
                McpRuntime(
                    repository = repo,
                    transportFactory = { transport },
                    clock = { now },
                    staleAfterMs = 60_000,
                )
            runtime.add("Demo", "https://example.com/mcp", "secret-token")
            assertTrue(runtime.views.value.single().enabled)
            assertEquals("secret-token", repo.saved.single().token)
            val lists = { transport.bodies.count { it.contains("\"method\":\"tools/list\"") } }
            val afterAdd = lists()
            assertEquals(2, afterAdd)
            runtime.refreshIfStale()
            assertEquals(afterAdd, lists())
            now += 60_000
            runtime.refreshIfStale()
            assertEquals(afterAdd + 1, lists())

            transport.failInitialize = true
            val brokenRepo = MemoryRepository()
            val broken =
                McpRuntime(
                    repository = brokenRepo,
                    transportFactory = { transport },
                )
            broken.add("Demo", "https://example.com/mcp", "secret-token")
            val view = broken.views.value.single()
            assertFalse(view.enabled)
            assertEquals(McpPhase.ERROR, view.phase)
            assertFalse(view.error.orEmpty().contains("secret-token"))
            assertEquals("secret-token", brokenRepo.saved.single().token)
            assertTrue(broken.tools().isEmpty())
        }

    @Test
    fun extrasCannotReplaceABuiltinName() =
        runBlocking {
            var builtinCalled = false
            val builtin =
                object : AgentTool {
                    override val definition =
                        AgentToolDefinition(
                            name = "list_nodes",
                            description = "nodes",
                            permission = AgentPermission.READ_NETWORK_STATE,
                            parameters = emptyList(),
                            confirmationPolicy = ConfirmationPolicy.NEVER,
                            category = "network",
                        )

                    override suspend fun execute(
                        args: JsonObject,
                        cancellation: ToolCancellation,
                    ): ToolExecution {
                        builtinCalled = true
                        return ToolExecution(output = buildJsonObject { put("ok", true) })
                    }
                }
            var extraCalled = false
            val extra =
                object : AgentTool {
                    override val definition = builtin.definition.copy(description = "extra")

                    override suspend fun execute(
                        args: JsonObject,
                        cancellation: ToolCancellation,
                    ): ToolExecution {
                        extraCalled = true
                        return ToolExecution(output = buildJsonObject { put("ok", true) })
                    }
                }
            val executor = DynamicToolExecutor(listOf(builtin))
            executor.setExtras(listOf(extra))
            val result = executor.invokeAgent(ToolCall("1", "list_nodes", "{}"), humanApproved = true)
            assertTrue(result.ok)
            assertTrue(builtinCalled)
            assertFalse(extraCalled)
        }

    @Test
    fun cancelStillReachesAReplacedRegistry() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val tool =
                object : AgentTool {
                    override val definition =
                        AgentToolDefinition(
                            name = "mcp_demo_echo",
                            description = "Echo",
                            permission = AgentPermission.DATA_EGRESS,
                            parameters = emptyList(),
                            confirmationPolicy = ConfirmationPolicy.NEVER,
                            category = "mcp",
                            passthroughArguments = true,
                        )

                    override suspend fun execute(
                        args: JsonObject,
                        cancellation: ToolCancellation,
                    ): ToolExecution {
                        cancellation.onCancel { gate.complete(Unit) }
                        started.complete(Unit)
                        gate.await()
                        return ToolExecution(output = buildJsonObject { put("ok", true) })
                    }
                }
            val executor = DynamicToolExecutor(emptyList())
            executor.setExtras(listOf(tool))
            val job =
                launch {
                    executor.invokeAgent(ToolCall("1", "mcp_demo_echo", "{}"), humanApproved = true)
                }
            started.await()
            executor.setExtras(emptyList())
            assertTrue(executor.definitions().isEmpty())
            executor.cancelActive()
            withTimeout(2_000) { gate.await() }
            job.join()
        }

    private fun runtime(): McpRuntime =
        McpRuntime(
            repository = MemoryRepository(),
            transportFactory = { config ->
                McpHttpTransport(config.url, config.token, connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
            },
        )

    private fun passthroughTool(onExecute: (JsonObject) -> Unit): AgentTool =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "mcp_demo_echo",
                    description = "Echo text",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "mcp",
                    aliases = listOf("echo"),
                    parametersSchemaOverride =
                        buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("text") { put("type", "string") }
                                putJsonObject("n") { put("type", "number") }
                            }
                            putJsonArray("required") { add("text") }
                        },
                    passthroughArguments = true,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                onExecute(args)
                return ToolExecution(output = buildJsonObject { put("ok", true) })
            }
        }
}

private class MemoryRepository : McpServerRepository {
    var saved: List<McpServerConfig> = emptyList()

    override fun load(): List<McpServerConfig> = saved

    override fun save(servers: List<McpServerConfig>) {
        saved = servers
    }
}

private class ScriptTransport : McpTransport {
    val bodies = mutableListOf<String>()
    val sessions = mutableListOf<String?>()
    var failInitialize = false
    private var lists = 0

    override suspend fun exchange(
        body: String,
        sessionId: String?,
        cancellation: ToolCancellation?,
    ): McpExchange {
        bodies += body
        sessions += sessionId
        val parsed = Json.parseToJsonElement(body).jsonObject
        val method = parsed["method"]!!.jsonPrimitive.content
        val id = (parsed["id"] as? JsonPrimitive)?.contentOrNull
        if (method == "initialize") {
            if (failInitialize) {
                return McpExchange(401, "application/json", """{"error":"no"}""", null)
            }
            return McpExchange(
                200,
                "application/json",
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""",
                "sess",
            )
        }
        if (method == "notifications/initialized") {
            return McpExchange(202, "application/json", "", "sess")
        }
        if (method == "tools/list") {
            lists += 1
            if (lists == 1) return McpExchange(404, "application/json", "missing", null)
            return McpExchange(
                200,
                "application/json",
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"annotations":{"readOnlyHint":true}}]}}""",
                "sess",
            )
        }
        error("unexpected $method")
    }
}

private class LoopbackMcp : AutoCloseable {
    data class Recorded(
        val method: String,
        val session: String?,
        val authorization: String?,
        val body: String,
    )

    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val records: MutableList<Recorded> = java.util.Collections.synchronizedList(mutableListOf())
    val writes = AtomicInteger()
    val url: String

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
        url = "http://127.0.0.1:${server.address.port}/mcp"
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
        val session = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        val parsed = Json.parseToJsonElement(body).jsonObject
        val method = parsed["method"]!!.jsonPrimitive.content
        val id = (parsed["id"] as? JsonPrimitive)?.contentOrNull
        records += Recorded(method, session, authorization, body)
        if (authorization != "Bearer test-token") {
            send(exchange, 401, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32001,"message":"unauthorized"}}""", null)
            return
        }
        when (method) {
            "initialize" ->
                send(
                    exchange,
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
                    "sess-1",
                )

            "notifications/initialized" -> {
                exchange.sendResponseHeaders(202, 0)
                exchange.close()
            }

            "tools/list" -> {
                if (session != "sess-1") {
                    send(exchange, 400, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"no session"}}""", null)
                    return
                }
                send(exchange, 200, listJson(id), "sess-1")
            }

            "tools/call" -> {
                if (session != "sess-1") {
                    send(exchange, 400, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"no session"}}""", null)
                    return
                }
                val params = parsed["params"]!!.jsonObject
                val name = params["name"]!!.jsonPrimitive.content
                val args = params["arguments"]!!.jsonObject
                val text = args["text"]!!.jsonPrimitive.content
                val payload =
                    if (name == "echo") {
                        val n = args["n"]!!.jsonPrimitive.content
                        """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"echo:$text:$n"}]}}"""
                    } else {
                        writes.incrementAndGet()
                        """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"wrote"}]}}"""
                    }
                send(exchange, 200, payload, "sess-1")
            }

            else -> send(exchange, 400, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"nope"}}""", null)
        }
    }

    private fun listJson(id: String?): String =
        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"},"n":{"type":"number"}},"required":["text"]},"annotations":{"readOnlyHint":true}},{"name":"write","description":"Write something","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}"""

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: String,
        session: String?,
    ) {
        if (session != null) exchange.responseHeaders.add("Mcp-Session-Id", session)
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
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

private class FakeKey(
    private val key: String?,
) : AgentApiKeyProvider {
    override val supported: Boolean = true

    override suspend fun apiKey(): String? = key

    override suspend fun save(apiKey: String) = Unit

    override suspend fun clear() = Unit
}
