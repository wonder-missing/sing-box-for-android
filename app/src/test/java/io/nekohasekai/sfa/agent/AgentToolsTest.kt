package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(summary.boolean("coreRunning"))
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
                NodeSnapshot(coreRunning = false, remoteControl = false),
            )
        val registry = buildTestRegistry(nodeSource = nodeSource)

        val result = registry.userExec("list_nodes")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertFalse(output.boolean("coreRunning"))
        assertTrue(output.string("message").contains("not running"))
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
        assertTrue(output.boolean("coreRunning"))
    }

    @Test
    fun networkSnapshotCombinesLocalSignalsAndCommandClientState() {
        val registry = buildTestRegistry()
        val result = registry.userExec("network_snapshot")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertEquals("mixed", output.string("source"))

        val local = output.obj("local")
        assertEquals("local_sense", local.string("source"))
        assertTrue(local.boolean("deviceOnly"))
        assertEquals("wifi", local.string("networkType"))
        assertFalse(local.boolean("metered"))
        assertTrue(local.boolean("vpnActive"))
        assertEquals("vpn", local.string("serviceMode"))
        assertTrue(local.boolean("batteryOptimizationIgnored"))
        assertFalse(local.boolean("powerSave"))
        assertFalse(local.boolean("doze"))

        val core = output.obj("core")
        assertEquals("command_client", core.string("source"))
        assertTrue(core.boolean("running"))
        assertFalse(core.boolean("remoteControl"))
        assertEquals("node-b", core.obj("selectedOutbounds").string("proxy"))
        assertEquals(1, core.int("groupCount"))
        assertEquals(2, core.int("outboundCount"))
        assertNull(output["note"])
    }

    @Test
    fun networkSnapshotLabelsLocalFieldsUnderRemoteControl() {
        val registry =
            buildTestRegistry(
                nodeSource = FakeNodeSource(sampleNodeSnapshot().copy(remoteControl = true)),
            )

        val output = registry.userExec("network_snapshot").output!!

        assertTrue(output.obj("core").boolean("remoteControl"))
        assertTrue(output.string("note").contains("not available over Remote Control"))
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
                coreRunning = true,
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
}

private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

private fun JsonObject.boolean(key: String): Boolean = (getValue(key) as JsonPrimitive).boolean

private fun JsonObject.int(key: String): Int = (getValue(key) as JsonPrimitive).int

private fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).long

private fun JsonObject.obj(key: String): JsonObject = getValue(key) as JsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
