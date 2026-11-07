package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentToolRegistryTest {
    private lateinit var probePort: FakeProbePort
    private lateinit var registry: AgentToolRegistry

    @Before
    fun setUp() {
        probePort = FakeProbePort()
        registry = buildTestRegistry(probePort = probePort)
    }

    @Test
    fun unknownToolFailsWithoutSideEffects() {
        val result = registry.invoke("ssh", """{"host":"example"}""")

        assertFalse(result.ok)
        assertEquals("unknown_tool", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun machineLoginToolsAreNotRegistered() {
        val names = registry.definitions().map { it.name }
        val forbidden = listOf("ssh", "shell", "exec", "login_host", "run_command", "terminal")
        forbidden.forEach { name ->
            assertFalse("tool '$name' must not be registered", names.contains(name))
            val result = registry.invoke(name, "{}")
            assertFalse(result.ok)
            assertEquals("unknown_tool", result.errorCode)
        }
        assertTrue(
            "every registered tool must stay inside the POC permission set",
            registry.definitions().all {
                it.permission == AgentPermission.READ_NETWORK_STATE ||
                    it.permission == AgentPermission.NETWORK_PROBE
            },
        )
        assertTrue(AgentPermission.values().none { it.label.contains("shell") || it.label.contains("ssh") })
    }

    @Test
    fun missingParameterFailsBeforeProbe() {
        val result = registry.invoke("probe_via_node", """{"url":"https://example.com/"}""", confirmed = true)

        assertFalse(result.ok)
        assertEquals("missing_parameter", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun wrongParameterTypeFailsBeforeProbe() {
        val result =
            registry.invoke(
                "probe_via_node",
                """{"outbound":123,"url":"https://example.com/"}""",
                confirmed = true,
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter_type", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun blankParameterFailsBeforeProbe() {
        val result =
            registry.invoke(
                "probe_via_node",
                """{"outbound":"   ","url":"https://example.com/"}""",
                confirmed = true,
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun malformedArgumentsFailBeforeProbe() {
        val result = registry.invoke("probe_via_node", "not-json", confirmed = true)

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun confirmationIsRequiredBeforeExecution() {
        val args = """{"outbound":"node-a","url":"https://example.com/health"}"""

        val pending = registry.invoke("probe_via_node", args)
        assertFalse(pending.ok)
        assertEquals("confirmation_required", pending.errorCode)
        assertNotNull(pending.confirmation)
        assertEquals("probe_via_node", pending.confirmation?.tool)
        assertTrue(probePort.calls.isEmpty())

        val confirmed = registry.invoke("probe_via_node", args, confirmed = true)
        assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
        assertEquals(1, probePort.calls.size)
        assertEquals("node-a" to "https://example.com/health", probePort.calls.first())
    }

    @Test
    fun confirmationKeepsRawArgumentsForExecutionButMasksDisplay() {
        val rawUrl = "https://probe.example.com/health?token=SECRET"
        val args = """{"outbound":"node-a","url":"$rawUrl"}"""

        val pending = registry.invoke("probe_via_node", args)
        val confirmation = pending.confirmation!!
        assertEquals(args, confirmation.argsJson)
        assertFalse(confirmation.displayArgsJson.contains("SECRET"))
        assertTrue(confirmation.displayArgsJson.contains("token=***"))

        val confirmed = registry.invoke(confirmation.tool, confirmation.argsJson, confirmed = true)
        assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
        assertEquals(rawUrl, probePort.calls.single().second)
    }

    @Test
    fun definitionsExposeJsonSchemas() {
        val definitions = registry.definitions()
        assertEquals(listOf("list_nodes", "network_snapshot", "probe_via_node"), definitions.map { it.name })

        val probe = definitions.first { it.name == "probe_via_node" }
        val schema = probe.parametersSchema
        assertEquals("object", schema.stringValue("type"))
        val properties = schema["properties"] as kotlinx.serialization.json.JsonObject
        assertTrue(properties.containsKey("outbound"))
        assertTrue(properties.containsKey("url"))
        assertTrue(probe.needsConfirmation)
        assertEquals(AgentPermission.NETWORK_PROBE, probe.permission)

        val required = schema["required"] as kotlinx.serialization.json.JsonArray
        assertEquals(setOf("outbound", "url"), required.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toSet())
    }

    @Test
    fun resultForUnknownToolHasNoOutput() {
        val result = registry.invoke("probe_via_node_v2", "{}")
        assertFalse(result.ok)
        assertNull(result.output)
    }
}

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    (getValue(key) as kotlinx.serialization.json.JsonPrimitive).content
