package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSchemaTest {
    @Test
    fun convertsDefinitionToOpenAiTool() {
        val definition =
            AgentToolDefinition(
                name = "probe_via_node",
                description = "Probe a URL",
                permission = AgentPermission.NETWORK_PROBE,
                parameters =
                    listOf(
                        AgentParameterSpec(
                            name = "url",
                            type = AgentParameterType.STRING,
                            required = true,
                            description = "URL",
                        ),
                    ),
                confirmationPolicy = ConfirmationPolicy.ALWAYS,
                category = "network",
                allowedUrlParameter = "url",
            )

        val schema = definition.toToolSchema()
        assertEquals("probe_via_node", schema.name)
        assertEquals("Probe a URL", schema.description)
        assertTrue(schema.parameters.toString().contains("url"))

        val json = schema.toOpenAiJson()
        assertEquals("function", json["type"]?.toString()?.trim('"'))
        assertEquals("probe_via_node", (json["function"] as kotlinx.serialization.json.JsonObject)["name"]?.toString()?.trim('"'))
    }
}
