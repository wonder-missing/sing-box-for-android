package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Static routing primitives. These are the only tools exposed to the model in
 * routing mode; real capability schemas are discovered through search_tools and
 * injected as tool messages, never by mutating the tools array.
 */
object AgentMetaTools {
    const val ROUTE_CALL = "route_call"
    const val SEARCH_TOOLS = "search_tools"

    val routeCall: AgentToolSchema =
        AgentToolSchema(
            name = ROUTE_CALL,
            description =
                "Invoke a registered Conductor capability by name. Pass the exact tool name and " +
                    "an arguments object that matches that tool's schema. Call search_tools first " +
                    "when you do not know the tool name or its parameters.",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("tool") {
                            put("type", "string")
                            put("description", "Exact registered tool name.")
                        }
                        putJsonObject("args") {
                            put("type", "object")
                            put(
                                "description",
                                "Arguments matching the target tool's parameters schema. " +
                                    "Pass {} when it takes none.",
                            )
                            put("additionalProperties", true)
                        }
                    }
                    putJsonArray("required") {
                        add("tool")
                        add("args")
                    }
                    put("additionalProperties", false)
                },
        )

    val searchTools: AgentToolSchema =
        AgentToolSchema(
            name = SEARCH_TOOLS,
            description =
                "Search the registered capability catalog. Returns matching tools with their " +
                    "parameter schemas. Call with an empty query to list the catalog by name, " +
                    "category and description without schemas. When the user asks what you can do, " +
                    "call this with an empty query first and answer from the returned catalog; the " +
                    "system prompt's capability list is not exhaustive. Results are appended to the " +
                    "conversation as tool output; they do not change the system prompt or the tools list.",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "Tool name, capability keywords, or an empty string to list the catalog.")
                        }
                    }
                    putJsonArray("required") {
                        add("query")
                    }
                    put("additionalProperties", false)
                },
        )

    fun schemas(): List<AgentToolSchema> = listOf(routeCall, searchTools)

    fun isMetaTool(name: String): Boolean = name == ROUTE_CALL || name == SEARCH_TOOLS
}
