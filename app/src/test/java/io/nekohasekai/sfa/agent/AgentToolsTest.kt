package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private fun AgentToolRegistry.userExec(
    name: String,
    argsJson: String = "{}",
): AgentInvocationResult =
    runBlocking {
        val first = invokeUserCommand(name, argsJson)
        if (first.confirmation != null) confirmUserCommand(name, argsJson) else first
    }

class AgentToolsTest {
    @Test
    fun listNodesSummaryAndGroupDetail() {
        val registry = buildTestRegistry()

        val summary = registry.userExec("list_nodes").output!!
        assertEquals("command_client", summary.string("source"))
        assertTrue(summary.boolean("coreReachable"))
        assertFalse(summary.boolean("remoteControl"))
        assertEquals("node-b", summary.string("selectedOutbound"))
        assertEquals(1, summary.int("groupCount"))
        assertEquals(2, summary.int("outboundCount"))

        val groups = summary.array("groups")
        assertEquals(1, groups.size)
        val group = groups[0] as JsonObject
        assertEquals("proxy", group.string("tag"))
        assertEquals("selector", group.string("type"))
        assertEquals("Selector", group.string("displayType"))
        assertTrue(group.boolean("selectable"))
        assertEquals("node-b", group.string("selected"))
        assertEquals(2, group.int("itemCount"))
        assertNull(group["items"])
        assertNull(summary["outbounds"])

        val detail = registry.userExec("list_nodes", """{"group":"proxy"}""").output!!
        assertEquals("node-b", detail.string("selectedOutbound"))
        val detailGroup = detail.obj("group")
        assertEquals("proxy", detailGroup.string("tag"))
        assertEquals("node-b", detailGroup.string("selected"))
        assertEquals(2, detailGroup.int("itemCount"))

        val items = detailGroup.array("items")
        assertEquals(2, items.size)
        val nodeA = items[0] as JsonObject
        assertEquals("node-a", nodeA.string("tag"))
        assertEquals("shadowsocks", nodeA.string("type"))
        assertFalse(nodeA.boolean("selected"))
        assertEquals(120, nodeA.int("urlTestDelayMs"))
        val nodeB = items[1] as JsonObject
        assertTrue(nodeB.boolean("selected"))
        assertNull(nodeB["urlTestDelayMs"])
        assertNull(detail["outbounds"])
    }

    @Test
    fun listNodesRejectsUnknownGroup() {
        val registry = buildTestRegistry()

        val result = registry.userExec("list_nodes", """{"group":"missing"}""")

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(result.errorMessage.orEmpty().contains("Unknown group"))
    }

    @Test
    fun listNodesExplainsWhenCoreIsNotRunning() {
        val nodeSource =
            FakeNodeSource(
                NodeSnapshot(commandClientConnected = false, remoteControl = false),
            )
        val registry = buildTestRegistry(nodeSource = nodeSource)

        val result = registry.userExec("list_nodes")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertFalse(output.boolean("coreReachable"))
        assertTrue(output.string("message").contains("not reachable"))
        assertEquals(0, output.int("groupCount"))
        assertEquals(0, output.int("outboundCount"))
        assertEquals(0, output.array("groups").size)
    }

    @Test
    fun listNodesMarksRemoteControlSnapshots() {
        val registry =
            buildTestRegistry(
                nodeSource = FakeNodeSource(sampleNodeSnapshot().copy(remoteControl = true)),
            )

        val output = registry.userExec("list_nodes").output!!

        assertTrue(output.boolean("remoteControl"))
        assertTrue(output.boolean("coreReachable"))
    }

    @Test
    fun systemSnapshotCombinesDeviceSignalsAndCommandClientState() {
        val registry = buildTestRegistry()
        val result = registry.userExec("system_snapshot")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertEquals("mixed", output.string("source"))

        val device = output.obj("device")
        assertEquals("local_sense", device.string("source"))
        assertEquals("wifi", device.string("networkType"))
        assertFalse(device.boolean("metered"))
        assertTrue(device.boolean("serviceActive"))
        assertTrue(device.boolean("vpnServiceActive"))
        assertEquals("vpn", device.string("serviceMode"))
        assertTrue(device.boolean("batteryOptimizationExempt"))
        assertFalse(device.boolean("batterySaver"))
        assertFalse(device.boolean("dozeMode"))

        val core = output.obj("core")
        assertEquals("command_client", core.string("source"))
        assertTrue(core.boolean("reachable"))
        assertFalse(core.boolean("remoteControl"))
        assertEquals("node-b", core.obj("selectedOutbounds").string("proxy"))
        assertEquals(1, core.int("groupCount"))
        assertEquals(2, core.int("outboundCount"))
        assertNull(output["note"])
    }

    @Test
    fun systemSnapshotLabelsDeviceFieldsUnderRemoteControl() {
        val registry =
            buildTestRegistry(
                nodeSource = FakeNodeSource(sampleNodeSnapshot().copy(remoteControl = true)),
            )

        val output = registry.userExec("system_snapshot").output!!

        assertTrue(output.obj("core").boolean("remoteControl"))
        assertTrue(output.string("note").contains("not available over Remote Control"))
    }

    @Test
    fun waitWithZeroSecondsReturnsImmediately() {
        val result = buildTestRegistry().userExec("wait", """{"seconds":0}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(0L, result.output!!.long("waitedMs"))
    }

    @Test
    fun waitCanBeCancelled() =
        runBlocking {
            val registry = buildTestRegistry()
            val pending =
                async(Dispatchers.Default) {
                    registry.invokeUserCommand("wait", """{"seconds":2}""")
                }
            delay(100)
            registry.cancelActive()

            val result = pending.await()

            assertFalse(result.ok)
            assertEquals("cancelled", result.errorCode)
        }

    @Test
    fun probePassesOutboundAndUrlToPort() {
        val probe = FakeProbePort(ProbeOutcome(statusCode = 204, latencyMs = 26))
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":"node-a","url":"https://example.com/health"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(listOf("node-a" to "https://example.com/health"), probe.calls)
        val output = result.output!!
        assertTrue(output.boolean("reachable"))
        assertEquals(204, output.int("statusCode"))
        assertEquals(26, output.long("latencyMs"))
        assertEquals("temporary_outbound_selection", output.string("via"))
    }

    @Test
    fun probeRejectsNonHttpTargetsWithoutCallingPort() {
        val probe = FakeProbePort()
        val registry = buildTestRegistry(probePort = probe)
        val targets =
            listOf(
                "file:///etc/passwd",
                "ftp://example.com/file",
                "javascript:alert(1)",
                "ss://YWVzLTI1Ni1nY206c2VjcmV0@example.com:8388",
                "example.com/health",
                "http://",
            )

        targets.forEach { target ->
            val result =
                registry.userExec(
                    "probe_via_node",
                    """{"outbound":"node-a","url":"$target"}""",
                    )
            assertFalse("target '$target' must be rejected", result.ok)
            assertEquals("invalid_parameter", result.errorCode)
        }
        assertTrue(probe.calls.isEmpty())
    }

    @Test
    fun httpFetchPassesUrlHeadersAndBody() {
        val fetch = FakeHttpFetchPort(
            HttpFetchOutcome(
                statusCode = 200,
                latencyMs = 12,
                content = "hello",
                bytes = 5,
            ),
        )
        val registry = buildTestRegistry(httpFetchPort = fetch)

        val result =
            registry.userExec(
                "http_fetch",
                """{"url":"https://example.com/data","method":"post","body":"payload","headers":["X-Test: 1"],"max_bytes":32}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        val call = fetch.calls.single()
        assertEquals("https://example.com/data", call[0])
        assertEquals("POST", call[1])
        assertEquals(listOf("X-Test" to "1"), call[2])
        assertEquals("payload", call[3])
        assertEquals(32, call[4])
        val output = result.output!!
        assertEquals(200, output.int("statusCode"))
        assertEquals("POST", output.string("method"))
        assertEquals("hello", output.string("text"))
        assertNull(output["via"])
        assertFalse(output.boolean("truncated"))
    }

    @Test
    fun httpFetchCanSaveAnImageResourceWithoutReturningItsBody() {
        val root = Files.createTempDirectory("conductor-fetch-test").toFile()
        try {
            val store = ConductorFileStore(root)
            val body = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>"
            val fetch =
                FakeHttpFetchPort(
                    downloadOutcome =
                        HttpDownloadOutcome(
                            statusCode = 200,
                            contentType = "image/svg+xml",
                            bytes = body.toByteArray(),
                        ),
                )
            val registry = buildTestRegistry(httpFetchPort = fetch, fileStore = store)

            val result =
                registry.userExec(
                    "http_fetch",
                    """{"url":"https://example.com/icon.svg","output_path":"conductor/resources/icon.svg"}""",
                )

            assertTrue(result.errorMessage ?: "", result.ok)
            val output = result.output!!
            assertEquals("conductor/resources/icon.svg", output.string("path"))
            assertEquals("image/svg+xml", output.string("contentType"))
            assertNull(output["text"])
            assertEquals(body, store.resolveResource("conductor/resources/icon.svg").readText())
            assertEquals(1, fetch.downloadCalls.size)
            assertEquals(8 * 1024 * 1024, fetch.downloadCalls.single()[4])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun httpFetchRejectsBodyOnGetWithoutCallingThePort() {
        val fetch = FakeHttpFetchPort()
        val registry = buildTestRegistry(httpFetchPort = fetch)

        val result =
            registry.userExec(
                "http_fetch",
                """{"url":"https://example.com/","body":"payload"}""",
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun httpFetchReturnsStatusHeadersAndBodyForNon200() {
        val fetch = FakeHttpFetchPort(
            HttpFetchOutcome(
                statusCode = 404,
                statusText = "Not Found",
                latencyMs = 9,
                content = "missing",
                contentType = "text/plain; charset=utf-8",
                headers = mapOf("X-Test" to listOf("yes")),
                bytes = 7,
            ),
        )
        val registry = buildTestRegistry(httpFetchPort = fetch)

        val result = registry.userExec("http_fetch", """{"url":"https://example.com/missing"}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertEquals(404, output.int("statusCode"))
        assertEquals("Not Found", output.string("statusText"))
        assertEquals("missing", output.string("text"))
        assertEquals("text/plain; charset=utf-8", output.string("contentType"))
        assertEquals("yes", output.obj("headers").array("X-Test").single().let { (it as JsonPrimitive).content })
        assertNull(output["via"])
    }

    @Test
    fun webSearchIsHiddenWhenTheProviderDoesNotSupportIt() {
        val search = FakeWebSearchPort()
        val registry =
            buildTestRegistry(
                webSearchPort = search,
                webSearchAvailable = { false },
            )

        assertTrue(registry.definitions().none { it.name == "web_search" })
        assertTrue(AgentToolCatalog(registry.definitions()).search("web search").isEmpty())

        val result = registry.userExec("web_search", """{"query":"anything"}""")

        assertFalse(result.ok)
        assertEquals("unknown_tool", result.errorCode)
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun webSearchPassesQueryAndKeepsSourceUrls() {
        val search =
            FakeWebSearchPort(
                WebSearchOutcome(
                    answer = "A concise answer",
                    sources =
                        listOf(
                            WebSearchSource(
                                url = "https://example.com/a?x=1",
                                title = "Example result",
                                pageAge = "1 day",
                                citedText = "quoted text",
                            ),
                        ),
                    searchQueries = listOf("kotlin release"),
                    latencyMs = 5,
                ),
            )
        val registry = buildTestRegistry(webSearchPort = search)

        val result =
            registry.userExec(
                "web_search",
                """{"query":"kotlin release","max_results":4}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("kotlin release" to 4, search.calls.single())
        val output = result.output!!
        assertEquals("A concise answer", output.string("answer"))
        assertEquals("kotlin release", output.array("searchQueries").single().let { (it as JsonPrimitive).content })
        val source = output.array("sources").single() as JsonObject
        assertEquals("web_search_source", source.string("type"))
        assertEquals("https://example.com/a?x=1", source.string("url"))
        assertEquals("Example result", source.string("title"))
        assertEquals("1 day", source.string("pageAge"))
        assertEquals("quoted text", source.string("citedText"))
    }

    @Test
    fun webSearchKeepsAnswerCitationsWhenTheyMatchASource() {
        val search =
            FakeWebSearchPort(
                WebSearchOutcome(
                    answer = "See https://example.com/a?hl=zh-cn for details.",
                    sources = listOf(WebSearchSource("https://example.com/a")),
                ),
            )
        val registry = buildTestRegistry(webSearchPort = search)

        val result = registry.userExec("web_search", """{"query":"example"}""")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(
            "See https://example.com/a?hl=zh-cn for details.",
            result.output!!.string("answer"),
        )
    }

    @Test
    fun webSearchRequiresAgentConfirmation() {
        val search = FakeWebSearchPort()
        val registry = buildTestRegistry(webSearchPort = search)
        val call = ToolCall("call-1", "web_search", """{"query":"kotlin releases"}""")

        runBlocking {
            val pending = registry.invokeAgent(call, humanApproved = false)
            assertEquals("confirmation_required", pending.errorCode)
            assertTrue(search.calls.isEmpty())

            val confirmed = registry.invokeAgent(call, humanApproved = true)
            assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
            assertEquals(listOf("kotlin releases" to 8), search.calls)
        }
    }

    @Test
    fun webSearchRejectsBlankQueryWithoutCallingThePort() {
        val search = FakeWebSearchPort()
        val registry = buildTestRegistry(webSearchPort = search)

        val result = registry.userExec("web_search", """{"query":"  "}""")

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun webSearchReportsSearchErrorWhenThereIsNoContent() {
        val search =
            FakeWebSearchPort(
                WebSearchOutcome(searchErrors = listOf("max_uses_exceeded")),
            )
        val registry = buildTestRegistry(webSearchPort = search)

        val result = registry.userExec("web_search", """{"query":"anything"}""")

        assertFalse(result.ok)
        assertEquals("tool_failed", result.errorCode)
        assertTrue(result.errorMessage.orEmpty().contains("max_uses_exceeded"))
        assertTrue(result.output!!.string("error").contains("max_uses_exceeded"))
    }

    @Test
    fun httpFetchRejectsHeaderInjectionBeforeCallingThePort() {
        val fetch = FakeHttpFetchPort()
        val registry = buildTestRegistry(httpFetchPort = fetch)

        val result =
            registry.userExec(
                "http_fetch",
                """{"url":"https://example.com/","headers":["X-Test: ok\r\nInjected: yes"]}""",
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun probeErrorsAreSanitized() {
        val probe =
            FakeProbePort(
                ProbeOutcome(
                    statusCode = 502,
                    latencyMs = 99,
                    error = "HTTP 502: upstream https://sub.example.com/secret?token=SECRET failed token=SECRET",
                ),
            )
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":"node-a","url":"https://probe.example.com/health?token=SECRET"}""",
            )

        assertFalse(result.ok)
        val rendered = result.toString() + result.output.toString()
        assertFalse(rendered.contains("sub.example.com"))
        assertFalse(rendered.contains("SECRET"))
        assertEquals(
            "https://probe.example.com/health?token=***",
            result.output!!.string("url"),
        )
        assertFalse(result.output!!.boolean("reachable"))
        assertEquals(502, result.output!!.int("statusCode"))
    }

    @Test
    fun listNodesRedactsSubscriptionLikeTags() {
        val snapshot =
            NodeSnapshot(
                commandClientConnected = true,
                remoteControl = false,
                groups =
                    listOf(
                        AgentGroup(
                            tag = "proxy",
                            type = "selector",
                            displayType = "Selector",
                            selectable = true,
                            selected = "node-a",
                            items =
                                listOf(
                                    AgentNode(
                                        tag = "https://sub.example.com/link?token=SECRET",
                                        type = "vmess",
                                        displayType = "VMess",
                                        selected = false,
                                        urlTestDelayMs = null,
                                    ),
                                ),
                        ),
                    ),
                outbounds = emptyList(),
            )
        val registry = buildTestRegistry(nodeSource = FakeNodeSource(snapshot))

        val output = registry.userExec("list_nodes", """{"group":"proxy"}""").output!!
        val rendered = output.toString()

        assertFalse(rendered.contains("sub.example.com"))
        assertFalse(rendered.contains("SECRET"))
        assertTrue(rendered.contains("[redacted-url]"))
    }

    @Test
    fun probeResultIsKeptWhenCancellationArrivesAfterPortReturns() {
        lateinit var registry: AgentToolRegistry
        val probe =
            object : ProbePort {
                override fun probe(
                    outbound: String,
                    url: String,
                    cancellation: ToolCancellation?,
                ): ProbeOutcome {
                    registry.cancelActive()
                    return ProbeOutcome(statusCode = 200, latencyMs = 1)
                }
            }
        registry = buildTestRegistry(probePort = probe)

        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":"node-a","url":"https://example.com/"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(200, result.output!!.int("statusCode"))
    }

    @Test
    fun telemetryResultIsKeptWhenCancellationArrivesAfterPortReturns() {
        lateinit var registry: AgentToolRegistry
        val telemetry =
            object : TelemetryUploadPort {
                override suspend fun upload(cancellation: ToolCancellation?): String {
                    registry.cancelActive()
                    return "uploaded"
                }
            }
        registry =
            AgentToolRegistry(
                AgentTools(
                    nodeSource = FakeNodeSource(),
                    deviceSource = FakeDeviceSource(),
                    probePort = FakeProbePort(),
                    telemetryUploadPort = telemetry,
                ).all,
            )

        val result = registry.userExec("telemetry_upload")

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals("uploaded", result.output!!.string("result"))
    }

    @Test
    fun probePortFailureReturnsStructuredError() {
        val probe = FakeProbePort(ProbeOutcome(error = "outbound not selectable"))
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":"missing","url":"https://example.com/"}""",
            )

        assertFalse(result.ok)
        assertEquals("outbound not selectable", result.output!!.string("error"))
        assertFalse(result.output!!.boolean("reachable"))
    }

    @Test
    fun probeManyRunsRequestedNodesInOneCall() {
        val probe = FakeProbePort(ProbeOutcome(statusCode = 204, latencyMs = 12))
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.userExec(
                "probe_many",
                """{"group":"proxy","url":"https://example.com/","outbounds":"node-a,node-b"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(2, result.output!!.array("results").size)
        assertEquals(
            listOf("node-a" to "https://example.com/", "node-b" to "https://example.com/"),
            probe.calls,
        )
    }

    @Test
    fun probeManyRestoresTheBatchStartingSelection() {
        val selection = FakeNodeSelectionPort()
        val registry = buildTestRegistry(selectionPort = selection)

        val result =
            registry.userExec(
                "probe_many",
                """{"group":"proxy","url":"https://example.com/","outbounds":"node-a,node-b"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertTrue(result.output!!.boolean("restored"))
        assertEquals(listOf("proxy" to "node-b"), selection.calls)
    }

    @Test
    fun tailscaleSnapshotIsStructuredWithoutPersonalFields() {
        val tools =
            AgentTools(
                nodeSource = FakeNodeSource(),
                deviceSource = FakeDeviceSource(),
                probePort = FakeProbePort(),
            )
        val snapshot =
            AgentTailscaleSnapshot(
                listOf(
                    AgentTailscaleEndpoint(
                        tag = "tailscale",
                        state = "Running",
                        self = AgentTailscalePeer("phone", online = true),
                        peers =
                            listOf(
                                AgentTailscalePeer(
                                    name = "mini",
                                    online = false,
                                    exitNode = true,
                                    lastSeen = "2026-11-13 02:00",
                                ),
                            ),
                    ),
                ),
            )

        val output = tools.tailscaleSnapshotJson(snapshot)
        val endpoints = output.array("endpoints")
        assertEquals(1, endpoints.size)
        val endpoint = endpoints[0] as JsonObject
        assertEquals("tailscale", endpoint.string("tag"))
        val peers = endpoint.array("peers")
        assertEquals(1, peers.size)
        val peer = peers[0] as JsonObject
        assertEquals("mini", peer.string("name"))
        assertFalse(peer.boolean("online"))
        assertTrue(peer.boolean("exitNode"))
        assertEquals("2026-11-13 02:00", peer.string("lastSeen"))
        assertFalse(output.toString().contains("@"))
        assertFalse(output.toString().contains("100."))
    }

    @Test
    fun selectOutboundUsesTheSelectionPortAndReturnsPrevious() {
        val selection = FakeNodeSelectionPort()
        val registry = buildTestRegistry(selectionPort = selection)

        val result =
            registry.userExec(
                "select_outbound",
                """{"group":"proxy","outbound":"node-a"}""",
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(listOf("proxy" to "node-a"), selection.calls)
        assertEquals("node-b", result.output!!.string("previous"))
        assertEquals("node-a", result.output!!.string("selected"))
    }

    @Test
    fun tailscaleDevicesCarriesTheTaildropDiscoveryAlias() {
        val source =
            object : TailscaleStatusSource {
                override suspend fun status(cancellation: ToolCancellation?): AgentTailscaleSnapshot =
                    AgentTailscaleSnapshot(emptyList())
            }
        val definitions =
            AgentTools(
                nodeSource = FakeNodeSource(),
                deviceSource = FakeDeviceSource(),
                probePort = FakeProbePort(),
                tailscaleStatusSource = source,
            ).all.map { it.definition }

        val devices = definitions.first { it.name == "tailscale_devices" }
        assertTrue(devices.core)
        assertTrue(devices.aliases.contains("taildrop"))
        assertTrue(definitions.none { it.name == "taildrop_peers" })
    }

    @Test
    fun toolMetadataStaysEnglish() {
        val definitions =
            AgentTools(
                nodeSource = FakeNodeSource(),
                deviceSource = FakeDeviceSource(),
                probePort = FakeProbePort(),
            ).all.map { it.definition }

        definitions.forEach { definition ->
            val metadata = listOf(definition.name, definition.description) + definition.aliases
            assertTrue(
                "tool '${definition.name}' metadata must stay English: $metadata",
                metadata.none { text -> text.any { it.code in CJK_RANGE } },
            )
        }
    }
}

private val CJK_RANGE = 0x4E00..0x9FFF

private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

private fun JsonObject.boolean(key: String): Boolean = (getValue(key) as JsonPrimitive).boolean

private fun JsonObject.int(key: String): Int = (getValue(key) as JsonPrimitive).int

private fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).long

private fun JsonObject.obj(key: String): JsonObject = getValue(key) as JsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
