package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
    @Test
    fun listNodesReturnsGroupsNodesSelectionAndLatency() {
        val registry = buildTestRegistry()
        val result = registry.invoke("list_nodes")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertEquals("command_client", output.string("source"))
        assertTrue(output.boolean("coreRunning"))
        assertFalse(output.boolean("remoteControl"))
        assertEquals("node-b", output.string("selectedOutbound"))

        val groups = output.array("groups")
        assertEquals(1, groups.size)
        val group = groups[0] as JsonObject
        assertEquals("proxy", group.string("tag"))
        assertEquals("selector", group.string("type"))
        assertEquals("Selector", group.string("displayType"))
        assertTrue(group.boolean("selectable"))
        assertEquals("node-b", group.string("selected"))

        val items = group.array("items")
        assertEquals(2, items.size)
        val nodeA = items[0] as JsonObject
        assertEquals("node-a", nodeA.string("tag"))
        assertEquals("shadowsocks", nodeA.string("type"))
        assertFalse(nodeA.boolean("selected"))
        assertEquals(120, nodeA.int("urlTestDelayMs"))
        val nodeB = items[1] as JsonObject
        assertTrue(nodeB.boolean("selected"))
        assertNull(nodeB["urlTestDelayMs"])

        val outbounds = output.array("outbounds")
        assertEquals(2, outbounds.size)
        assertEquals(88, (outbounds[1] as JsonObject).int("urlTestDelayMs"))
    }

    @Test
    fun listNodesExplainsWhenCoreIsNotRunning() {
        val nodeSource =
            FakeNodeSource(
                NodeSnapshot(coreRunning = false, remoteControl = false),
            )
        val registry = buildTestRegistry(nodeSource = nodeSource)

        val result = registry.invoke("list_nodes")

        assertTrue(result.errorMessage ?: "", result.ok)
        val output = result.output!!
        assertFalse(output.boolean("coreRunning"))
        assertTrue(output.string("message").contains("not running"))
        assertEquals(0, output.array("groups").size)
        assertEquals(0, output.array("outbounds").size)
    }

    @Test
    fun listNodesMarksRemoteControlSnapshots() {
        val registry =
            buildTestRegistry(
                nodeSource = FakeNodeSource(sampleNodeSnapshot().copy(remoteControl = true)),
            )

        val output = registry.invoke("list_nodes").output!!

        assertTrue(output.boolean("remoteControl"))
        assertTrue(output.boolean("coreRunning"))
    }

    @Test
    fun networkSnapshotCombinesLocalSignalsAndCommandClientState() {
        val registry = buildTestRegistry()
        val result = registry.invoke("network_snapshot")

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
        assertEquals("node-b", core.string("selectedOutbound"))
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

        val output = registry.invoke("network_snapshot").output!!

        assertTrue(output.obj("core").boolean("remoteControl"))
        assertTrue(output.string("note").contains("not available over Remote Control"))
    }

    @Test
    fun probePassesOutboundAndUrlToPort() {
        val probe = FakeProbePort(ProbeOutcome(statusCode = 204, latencyMs = 26))
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.invoke(
                "probe_via_node",
                """{"outbound":"node-a","url":"https://example.com/health"}""",
                confirmed = true,
            )

        assertTrue(result.errorMessage ?: "", result.ok)
        assertEquals(listOf("node-a" to "https://example.com/health"), probe.calls)
        val output = result.output!!
        assertTrue(output.boolean("ok"))
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
                registry.invoke(
                    "probe_via_node",
                    """{"outbound":"node-a","url":"$target"}""",
                    confirmed = true,
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
            registry.invoke(
                "probe_via_node",
                """{"outbound":"node-a","url":"https://probe.example.com/health?token=SECRET"}""",
                confirmed = true,
            )

        assertFalse(result.ok)
        val rendered = result.toString() + result.output.toString()
        assertFalse(rendered.contains("sub.example.com"))
        assertFalse(rendered.contains("SECRET"))
        assertEquals(
            "https://probe.example.com/health?token=***",
            result.output!!.string("url"),
        )
        assertFalse(result.output!!.boolean("ok"))
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

        val output = registry.invoke("list_nodes").output!!
        val rendered = output.toString()

        assertFalse(rendered.contains("sub.example.com"))
        assertFalse(rendered.contains("SECRET"))
        assertTrue(rendered.contains("[redacted-url]"))
    }

    @Test
    fun probePortFailureReturnsStructuredError() {
        val probe = FakeProbePort(ProbeOutcome(error = "outbound not selectable"))
        val registry = buildTestRegistry(probePort = probe)

        val result =
            registry.invoke(
                "probe_via_node",
                """{"outbound":"missing","url":"https://example.com/"}""",
                confirmed = true,
            )

        assertFalse(result.ok)
        assertEquals("outbound not selectable", result.output!!.string("error"))
        assertFalse(result.output!!.boolean("ok"))
    }
}

private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

private fun JsonObject.boolean(key: String): Boolean = (getValue(key) as JsonPrimitive).boolean

private fun JsonObject.int(key: String): Int = (getValue(key) as JsonPrimitive).int

private fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).long

private fun JsonObject.obj(key: String): JsonObject = getValue(key) as JsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key) as JsonArray
