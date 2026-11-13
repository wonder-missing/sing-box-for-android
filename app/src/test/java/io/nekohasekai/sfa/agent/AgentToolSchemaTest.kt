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

    @Test
    fun schemaCarriesIntegerBooleanAndStringArrayTypes() {
        val definition =
            AgentToolDefinition(
                name = "file_tool",
                description = "File tool",
                permission = AgentPermission.READ_FILES,
                parameters =
                    listOf(
                        AgentParameterSpec("max_bytes", AgentParameterType.INTEGER, true, "Maximum bytes"),
                        AgentParameterSpec("overwrite", AgentParameterType.BOOLEAN, false, "Overwrite"),
                        AgentParameterSpec("files", AgentParameterType.STRING_ARRAY, true, "Files"),
                    ),
                confirmationPolicy = ConfirmationPolicy.NEVER,
                category = "files",
            )

        val properties = definition.parametersSchema["properties"] as kotlinx.serialization.json.JsonObject
        assertEquals("integer", (properties["max_bytes"] as kotlinx.serialization.json.JsonObject).string("type"))
        assertEquals("boolean", (properties["overwrite"] as kotlinx.serialization.json.JsonObject).string("type"))
        val array = properties["files"] as kotlinx.serialization.json.JsonObject
        assertEquals("array", array.string("type"))
        assertEquals("string", (array["items"] as kotlinx.serialization.json.JsonObject).string("type"))
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        (getValue(key) as kotlinx.serialization.json.JsonPrimitive).content
}
