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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
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
    fun vfsLinkRequestSendsTheCurrentDeviceHeader() =
        runBlocking {
            val transport = ScriptTransport()
            var next = 0
            val session =
                McpSession(
                    transport,
                    token = "secret-token",
                    deviceId = { "device-${next++}" },
                )

            session.call(VFS_LINK_REQUEST_TOOL, buildJsonObject {})
            assertEquals("device-0", transport.headers.last()[VFS_DEVICE_HEADER])

            session.call(VFS_LINK_REQUEST_TOOL, buildJsonObject {})
            assertEquals("device-1", transport.headers.last()[VFS_DEVICE_HEADER])
        }

    @Test
    fun dockerRunUsesTheLongReadTimeoutOnly() =
        runBlocking {
            val transport = ScriptTransport()
            val session = McpSession(transport, token = "secret-token")

            session.call(DOCKER_RUN_TOOL, buildJsonObject {})
            assertEquals(MCP_DOCKER_RUN_READ_TIMEOUT_MS, transport.readTimeouts.last())

            session.call("orb_status", buildJsonObject {})
            assertNull(transport.readTimeouts.last())
        }

    @Test
    fun nonVfsToolsDoNotSendADeviceHeader() =
        runBlocking {
            val transport = ScriptTransport()
            val session = McpSession(transport, token = "secret-token", deviceId = { "device-0" })

            session.call("orb_status", buildJsonObject {})

            assertFalse(transport.headers.last().containsKey(VFS_DEVICE_HEADER))
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
    fun promptStaysTheSameWhenMcpToolsAreActivated() {
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
        val withMcp = AgentToolCatalog(listOf(builtin, passthroughTool { }.definition)).promptSection()
        assertEquals(without, withMcp)
        assertFalse(withMcp.contains("mcp_demo_echo"))
        assertFalse(withMcp.contains("MCP"))
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
    fun storeEncryptsTokensAndMigratesPlaintext() {
        val dir = Files.createTempDirectory("mcp-cipher").toFile()
        try {
            val file = File(dir, "mcp-servers.json")
            val cipher = XorMcpTokenCipher()
            val repo = FileMcpServerRepository(file, cipher)
            repo.save(
                listOf(
                    McpServerConfig(
                        id = "demo",
                        name = "Demo",
                        url = "https://example.com/mcp",
                        token = "secret-token",
                    ),
                ),
            )
            assertFalse(file.readText(Charsets.UTF_8).contains("secret-token"))
            assertTrue(file.readText(Charsets.UTF_8).contains("\"tokensEncrypted\": true"))
            assertEquals("secret-token", FileMcpServerRepository(file, cipher).load().single().token)

            file.writeText(
                """{"servers":[{"id":"demo","name":"Demo","url":"https://example.com/mcp","token":"secret-token"}]}""",
                Charsets.UTF_8,
            )
            assertEquals("secret-token", FileMcpServerRepository(file, cipher).load().single().token)
            assertFalse(file.readText(Charsets.UTF_8).contains("secret-token"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private class XorMcpTokenCipher : McpTokenCipher {
        override val supported: Boolean = true

        override fun encrypt(
            serverId: String,
            token: String,
        ): String = "enc:$serverId:" + token.reversed()

        override fun decrypt(
            serverId: String,
            blob: String,
        ): String? {
            val prefix = "enc:$serverId:"
            return if (blob.startsWith(prefix)) blob.removePrefix(prefix).reversed() else null
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
                val listed = runtime.listedTools()
                assertEquals(listOf("echo", "write"), listed.map { it.remoteName })
                assertEquals(listOf("text"), listed.first().required)
                assertTrue(listed.first().asksFirst)
                assertTrue(listed[1].asksFirst)

                val executor = DynamicToolExecutor(emptyList())
                executor.setExtras(runtime.tools())
                val echo = executor.definitions().first { it.name == "mcp_demo_echo" }
                assertEquals(ConfirmationPolicy.AGENT_ONLY, echo.confirmationPolicy)
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
                        confirm = { true },
                    )

                val toolMessages = result.messages.filterIsInstance<AgentMessage.Tool>()
                assertEquals(2, toolMessages.size)
                assertTrue(toolMessages[0].content.contains("mcp_demo_echo"))
                assertTrue(toolMessages[0].content.contains("\"n\""))
                assertTrue(toolMessages[1].content.contains("echo:hello:1"))
                val prompt = (model.requests.first().messages.first() as AgentMessage.System).content
                assertFalse(prompt.contains("mcp_demo_echo"))
                assertFalse(prompt.contains("- mcp:"))
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
    fun readOnlyMcpToolStillAsksForConfirmation() =
        runBlocking {
            LoopbackMcp().use { server ->
                val runtime = runtime()
                runtime.add("Demo", server.url, "test-token")
                val echo = runtime.tools().first { it.definition.name == "mcp_demo_echo" }
                val registry = AgentToolRegistry(listOf(echo))

                val pending = registry.invokeAgent(ToolCall("1", echo.definition.name, """{"text":"hi"}"""), humanApproved = false)

                assertEquals("confirmation_required", pending.errorCode)
                assertEquals(0, server.records.count { it.method == "tools/call" })
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
    val headers = mutableListOf<Map<String, String>>()
    val readTimeouts = mutableListOf<Int?>()
    var failInitialize = false
    private var lists = 0

    override suspend fun exchange(
        body: String,
        sessionId: String?,
        cancellation: ToolCancellation?,
        extraHeaders: Map<String, String>,
        readTimeoutMs: Int?,
    ): McpExchange {
        bodies += body
        sessions += sessionId
        headers += extraHeaders
        readTimeouts += readTimeoutMs
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
        if (method == "tools/call") {
            return McpExchange(
                200,
                "application/json",
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"{}"}]}}""",
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

    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val records: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    val writes = AtomicInteger()
    val url = "http://127.0.0.1:${server.localPort}/mcp"

    private val thread =
        Thread {
            while (running.get()) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                try {
                    handle(socket)
                } catch (_: Exception) {
                    // A bad request should not kill the accept loop.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }.apply {
            isDaemon = true
            name = "mcp-loopback"
            start()
        }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        if (reader.readLine() == null) return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val count = reader.read(chars, read, length - read)
            if (count < 0) break
            read += count
        }
        val body = String(chars, 0, read)
        val session = headers["mcp-session-id"]
        val authorization = headers["authorization"]
        val parsed = Json.parseToJsonElement(body).jsonObject
        val method = parsed["method"]!!.jsonPrimitive.content
        val id = (parsed["id"] as? JsonPrimitive)?.contentOrNull
        records += Recorded(method, session, authorization, body)
        if (authorization != "Bearer test-token") {
            send(socket, 401, "Unauthorized", """{"jsonrpc":"2.0","id":$id,"error":{"code":-32001,"message":"unauthorized"}}""", null)
            return
        }
        when (method) {
            "initialize" ->
                send(
                    socket,
                    200,
                    "OK",
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
                    "sess-1",
                )

            "notifications/initialized" -> send(socket, 202, "Accepted", "", "sess-1")

            "tools/list" -> {
                if (session != "sess-1") {
                    send(socket, 400, "Bad Request", """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"no session"}}""", null)
                    return
                }
                send(socket, 200, "OK", listJson(id), "sess-1")
            }

            "tools/call" -> {
                if (session != "sess-1") {
                    send(socket, 400, "Bad Request", """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"no session"}}""", null)
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
                send(socket, 200, "OK", payload, "sess-1")
            }

            else -> send(socket, 400, "Bad Request", """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"nope"}}""", null)
        }
    }

    private fun listJson(id: String?): String =
        """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"},"n":{"type":"number"}},"required":["text"]},"annotations":{"readOnlyHint":true}},{"name":"write","description":"Write something","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}"""

    private fun send(
        socket: Socket,
        status: Int,
        reason: String,
        body: String,
        session: String?,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header =
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: application/json\r\n")
                if (session != null) append("Mcp-Session-Id: $session\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
        val output = socket.getOutputStream()
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        runCatching { thread.join(1_000) }
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
