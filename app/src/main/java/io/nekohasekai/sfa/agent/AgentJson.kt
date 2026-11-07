package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object AgentJson {
    private val prettyFormat = Json { prettyPrint = true }

    fun schema(parameters: List<AgentParameterSpec>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                parameters.forEach { spec ->
                    putJsonObject(spec.name) {
                        put("type", spec.type.schemaType)
                        put("description", spec.description)
                    }
                }
            }
            putJsonArray("required") {
                parameters.filter { it.required }.forEach { add(it.name) }
            }
            put("additionalProperties", false)
        }

    fun parseObject(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    fun pretty(element: JsonElement): String = prettyFormat.encodeToString(JsonElement.serializer(), element)

    fun string(
        args: JsonObject,
        key: String,
    ): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
