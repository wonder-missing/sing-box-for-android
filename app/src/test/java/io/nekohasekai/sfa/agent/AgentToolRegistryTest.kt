package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        val result = registry.userExec("ssh", """{"host":"example"}""")

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
            val result = registry.userExec(name, "{}")
            assertFalse(result.ok)
            assertEquals("unknown_tool", result.errorCode)
        }
        assertTrue(
            "every registered tool must stay inside the POC permission set",
            registry.definitions().all {
                it.permission == AgentPermission.READ_NETWORK_STATE ||
                    it.permission == AgentPermission.NETWORK_PROBE ||
                    it.permission == AgentPermission.NETWORK_CHANGE ||
                    it.permission == AgentPermission.DATA_EGRESS
            },
        )
        assertTrue(AgentPermission.values().none { it.label.contains("shell") || it.label.contains("ssh") })
    }

    @Test
    fun missingParameterFailsBeforeProbe() {
        val result = registry.userExec("probe_via_node", """{"url":"https://example.com/"}""")

        assertFalse(result.ok)
        assertEquals("missing_parameter", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun wrongParameterTypeFailsBeforeProbe() {
        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":123,"url":"https://example.com/"}""",
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter_type", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun blankParameterFailsBeforeProbe() {
        val result =
            registry.userExec(
                "probe_via_node",
                """{"outbound":"   ","url":"https://example.com/"}""",
            )

        assertFalse(result.ok)
        assertEquals("invalid_parameter", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun malformedArgumentsFailBeforeProbe() {
        val result = registry.userExec("probe_via_node", "not-json")

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.errorCode)
        assertTrue(probePort.calls.isEmpty())
    }

    @Test
    fun confirmationIsRequiredBeforeExecution() {
        val args = """{"outbound":"node-a","url":"https://example.com/health"}"""

        val pending = runBlocking { registry.invokeUserCommand("probe_via_node", args) }
        assertFalse(pending.ok)
        assertEquals("confirmation_required", pending.errorCode)
        assertNotNull(pending.confirmation)
        assertEquals("probe_via_node", pending.confirmation?.tool)
        assertTrue(probePort.calls.isEmpty())

        val confirmed = runBlocking { registry.confirmUserCommand("probe_via_node", args) }
        assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
        assertEquals(1, probePort.calls.size)
        assertEquals("node-a" to "https://example.com/health", probePort.calls.first())
    }

    @Test
    fun confirmationKeepsRawArgumentsForExecutionButMasksDisplay() {
        val rawUrl = "https://probe.example.com/health?token=SECRET"
        val args = """{"outbound":"node-a","url":"$rawUrl"}"""

        val pending = runBlocking { registry.invokeUserCommand("probe_via_node", args) }
        val confirmation = pending.confirmation!!
        assertEquals(args, confirmation.argsJson)
        assertFalse(confirmation.displayArgsJson.contains("SECRET"))
        assertTrue(confirmation.displayArgsJson.contains("token=***"))

        val confirmed = runBlocking { registry.confirmUserCommand(confirmation.tool, confirmation.argsJson) }
        assertTrue(confirmed.errorMessage ?: "", confirmed.ok)
        assertEquals(rawUrl, probePort.calls.single().second)
    }

    @Test
    fun definitionsExposeJsonSchemas() {
        val definitions = registry.definitions()
        assertEquals(
            listOf("list_nodes", "network_snapshot", "probe_via_node", "probe_many"),
            definitions.map { it.name },
        )

        val probe = definitions.first { it.name == "probe_via_node" }
        val schema = probe.parametersSchema
        assertEquals("object", schema.stringValue("type"))
        val properties = schema["properties"] as kotlinx.serialization.json.JsonObject
        assertTrue(properties.containsKey("outbound"))
        assertTrue(properties.containsKey("url"))
        assertEquals(ConfirmationPolicy.ALWAYS, probe.confirmationPolicy)
        assertEquals(AgentPermission.NETWORK_PROBE, probe.permission)

        val required = schema["required"] as kotlinx.serialization.json.JsonArray
        assertEquals(setOf("outbound", "url"), required.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toSet())
    }

    @Test
    fun resultForUnknownToolHasNoOutput() {
        val result = registry.userExec("probe_via_node_v2", "{}")
        assertFalse(result.ok)
        assertNull(result.output)
    }

    @Test
    fun slowToolReturnsATimeoutObservation() {
        val slow =
            object : AgentTool {
                override val definition =
                    AgentToolDefinition(
                        name = "slow_tool",
                        description = "slow",
                        permission = AgentPermission.READ_NETWORK_STATE,
                        parameters = emptyList(),
                        confirmationPolicy = ConfirmationPolicy.NEVER,
                        category = "test",
                    )

                override suspend fun execute(
                    args: kotlinx.serialization.json.JsonObject,
                    cancellation: ToolCancellation,
                ): ToolExecution {
                    delay(1_000)
                    return ToolExecution(output = kotlinx.serialization.json.buildJsonObject { })
                }
            }
        val slowRegistry = AgentToolRegistry(listOf(slow), toolTimeoutMs = 50)

        val result = runBlocking { slowRegistry.invokeUserCommand("slow_tool", "{}") }

        assertFalse(result.ok)
        assertEquals("tool_timeout", result.errorCode)
    }
}

private fun AgentToolRegistry.userExec(
    name: String,
    argsJson: String = "{}",
): AgentInvocationResult =
    runBlocking {
        val first = invokeUserCommand(name, argsJson)
        if (first.confirmation != null) confirmUserCommand(name, argsJson) else first
    }

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    (getValue(key) as kotlinx.serialization.json.JsonPrimitive).content