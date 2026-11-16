package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentParameterType
import io.nekohasekai.sfa.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * `/tool` argument completion. It only describes the command grammar and the
 * runtime registry; invocation still goes through the normal tool executor.
 */
internal object ConductorToolUse {
    fun isCommand(raw: String): Boolean {
        val parsed = ConductorCommands.parse(raw.trim()) ?: return false
        return parsed.command?.action == ConductorCommandKind.TOOL
    }

    fun toolSuggestions(
        input: String,
        tools: List<AgentToolDefinition>,
    ): List<AgentToolDefinition> {
        val typed = namePrefix(input) ?: return emptyList()
        if (tools.isEmpty()) return emptyList()
        if (typed.isEmpty()) {
            return tools.sortedWith(
                compareBy(
                    { it.serverName != null },
                    { it.name.lowercase(Locale.US) },
                ),
            )
        }
        return tools
            .mapNotNull { tool ->
                val score =
                    (listOf(tool.name) + tool.aliases)
                        .mapNotNull { SlashFuzzy.score(typed, it) }
                        .minOrNull() ?: return@mapNotNull null
                tool to score
            }.sortedWith(
                compareBy(
                    { it.second },
                    { it.first.name.length },
                    { it.first.name.lowercase(Locale.US) },
                ),
            ).map { it.first }
    }

    /**
     * The menu line for the current `/tool` argument position:
     * - `/tool ` explains the grammar and lists names.
     * - `/tool <name> ` shows parameters and an example.
     * - `/tool <name> {` stays quiet so JSON typing is not interrupted.
     */
    fun hint(
        input: String,
        tools: List<AgentToolDefinition>,
    ): String? {
        val after = argument(input) ?: return null
        val trimmed = after.dropWhile { it.isWhitespace() }
        val name = trimmed.takeWhile { !it.isWhitespace() }
        if (name.isEmpty()) {
            return "Type /tool <name> [json]. Pick a tool below; sending only the name shows its schema."
        }
        val rest = trimmed.substring(name.length)
        if (rest.isEmpty()) return null
        if (rest.any { !it.isWhitespace() }) return null
        val tool = resolve(name, tools)
            ?: return "No registered tool matches \"$name\". Type /tool to list tools."
        return hintFor(tool)
    }

    private fun hintFor(tool: AgentToolDefinition): String {
        val parameters = parametersOf(tool)
        if (parameters.isEmpty()) {
            if (tool.passthroughArguments) {
                return "Accepts free-form JSON. Send /tool ${tool.name} {} to run it."
            }
            return "No arguments. Send /tool ${tool.name} {} to run it."
        }
        val required = parameters.filter { it.required }
        val optional = parameters.filterNot { it.required }
        val parts =
            buildList {
                required.forEach { add("${it.name}${typeSuffix(it)} (required)") }
                optional.forEach { add("${it.name}${typeSuffix(it)}?") }
            }
        val example = exampleJson(required)
        return if (required.isEmpty()) {
            "All arguments are optional: ${parts.joinToString(", ")}. " +
                "Send /tool ${tool.name} {} or add keys."
        } else {
            "Arguments: ${parts.joinToString(", ")}. " +
                "Example: /tool ${tool.name} $example"
        }
    }

    private fun exampleJson(required: List<Parameter>): String {
        if (required.isEmpty()) return "{}"
        return required.joinToString(prefix = "{", postfix = "}") { parameter ->
            "\"${parameter.name}\":${exampleValue(parameter.type)}"
        }
    }

    private fun exampleValue(type: AgentParameterType?): String =
        when (type) {
            AgentParameterType.INTEGER -> "0"
            AgentParameterType.BOOLEAN -> "true"
            AgentParameterType.STRING_ARRAY -> "[]"
            else -> "\"...\""
        }

    private fun typeSuffix(parameter: Parameter): String =
        parameter.type?.let { ": ${it.schemaType}" }.orEmpty()

    private fun parametersOf(tool: AgentToolDefinition): List<Parameter> {
        if (tool.parameters.isNotEmpty()) {
            return tool.parameters.map {
                Parameter(it.name, it.type, it.required)
            }
        }
        val schema = tool.parametersSchema
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        val required =
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
        return properties.keys.map { name ->
            Parameter(name, null, name in required)
        }
    }

    private fun resolve(
        name: String,
        tools: List<AgentToolDefinition>,
    ): AgentToolDefinition? {
        val exact = tools.filter { it.name.equals(name, ignoreCase = true) }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        val aliases =
            tools.filter { tool ->
                tool.aliases.any { it.equals(name, ignoreCase = true) }
            }
        return aliases.singleOrNull()
    }

    private fun namePrefix(input: String): String? {
        val after = argument(input) ?: return null
        val typed = after.dropWhile { it.isWhitespace() }
        if (typed.isEmpty()) return ""
        if (typed.any { it.isWhitespace() }) return null
        return typed
    }

    private fun argument(input: String): String? {
        val text = input.trimStart()
        if (!text.startsWith("/")) return null
        val body = text.removePrefix("/")
        val head = body.takeWhile { !it.isWhitespace() }
        if (!head.equals("tool", ignoreCase = true)) return null
        val rest = body.substring(head.length)
        if (rest.isEmpty()) return null
        return rest
    }

    private data class Parameter(
        val name: String,
        val type: AgentParameterType?,
        val required: Boolean,
    )
}
