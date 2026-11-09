package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AgentToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

fun AgentToolDefinition.toToolSchema(): AgentToolSchema =
    AgentToolSchema(
        name = name,
        description = description,
        parameters = parametersSchema,
    )

fun AgentToolSchema.toOpenAiJson(): JsonObject =
    buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        }
    }
