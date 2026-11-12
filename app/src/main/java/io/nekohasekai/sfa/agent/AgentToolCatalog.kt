package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Static snapshot of the registered tool definitions. The catalog is the single
 * source for the system prompt roster and for tool search; it never mutates the
 * registry and never changes the tools array sent to the model.
 */
class AgentToolCatalog(
    definitions: List<AgentToolDefinition>,
) {
    val definitions: List<AgentToolDefinition> = definitions.sortedBy { it.name }
    private val byName: Map<String, AgentToolDefinition> = this.definitions.associateBy { it.name }
    private val byNormalizedName: Map<String, AgentToolDefinition> =
        this.definitions.associateBy { it.name.lowercase(Locale.US) }

    init {
        require(this.definitions.map { it.name }.distinct().size == this.definitions.size) {
            "Tool names must be unique"
        }
    }

    fun get(name: String): AgentToolDefinition? {
        val trimmed = name.trim()
        return byName[trimmed] ?: byNormalizedName[trimmed.lowercase(Locale.US)]
    }

    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<AgentToolDefinition> {
        val normalized = query.trim().lowercase(Locale.US)
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        if (normalized.isEmpty()) return definitions.take(cappedLimit)
        return definitions
            .map { it to score(it, normalized) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<AgentToolDefinition, Int>> { it.second }
                    .thenBy { it.first.name },
            ).take(cappedLimit)
            .map { it.first }
    }

    fun suggestions(
        name: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<String> {
        val normalized = name.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return emptyList()
        return definitions
            .map { tool ->
                val toolName = tool.name.lowercase(Locale.US)
                val distance = editDistance(normalized, toolName)
                val contained = toolName.contains(normalized) || normalized.contains(toolName)
                tool to if (contained) 0 else distance
            }.filter { (_, score) -> score <= MAX_SUGGESTION_DISTANCE }
            .sortedWith(
                compareBy<Pair<AgentToolDefinition, Int>> { it.second }
                    .thenBy { it.first.name },
            ).take(limit)
            .map { it.first.name }
    }

    fun searchOutput(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): JsonObject {
        val matches = search(query, limit)
        val objects =
            matches
                .map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("category", tool.category)
                        put("description", tool.description)
                        put("parameters", tool.parametersSchema)
                        put("requiresConfirmation", tool.confirmationPolicy != ConfirmationPolicy.NEVER)
                    }
                }.toMutableList()
        var output = buildOutput(query, matches.size, objects, truncated = false)
        while (objects.size > 1 && output.toString().length > maxChars) {
            objects.removeAt(objects.lastIndex)
            output = buildOutput(query, matches.size, objects, truncated = true)
        }
        return output
    }

    fun promptSection(): String =
        buildString {
            appendLine("Registered capabilities for this app version:")
            definitions
                .groupBy { it.category }
                .toSortedMap()
                .forEach { (category, tools) ->
                    appendLine("- $category: ${tools.joinToString(", ") { it.name }}")
                }
            if (definitions.any { it.category == "mcp" }) {
                appendLine(
                    "MCP tools are external servers activated in Settings. They are not core. Call search_tools for a schema before route_call.",
                )
            }
            val core = definitions.filter { it.core }
            if (core.isNotEmpty()) {
                appendLine()
                appendLine("Core tools. Call them directly with route_call; do not search first:")
                core.forEach { tool ->
                    appendLine("- ${usage(tool)}")
                }
            }
            appendLine()
            appendLine("If you need a capability's parameter schema, call search_tools.")
            appendLine("Run a capability with route_call(tool=<name>, args=<arguments object>).")
            appendLine("Never target route_call or search_tools with route_call.")
        }.trim()

    private fun usage(tool: AgentToolDefinition): String {
        val parameters =
            tool.parameters.joinToString(", ") { spec ->
                val optional = if (spec.required) "" else "?"
                "${spec.name}: ${spec.type.schemaType}$optional"
            }
        return "${tool.name}($parameters): ${tool.description}"
    }

    private fun buildOutput(
        query: String,
        total: Int,
        matches: List<JsonObject>,
        truncated: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("query", query)
            put("total", total)
            put("truncated", truncated)
            put(
                "matches",
                buildJsonArray {
                    matches.forEach { add(it) }
                },
            )
            if (matches.isEmpty()) {
                put("hint", "No matching tools. Search by capability, or use a tool name from the roster.")
            }
        }

    private fun score(
        tool: AgentToolDefinition,
        query: String,
    ): Int {
        val name = tool.name.lowercase(Locale.US)
        val aliases = tool.aliases.map { it.lowercase(Locale.US) }
        val description = tool.description.lowercase(Locale.US)
        val category = tool.category.lowercase(Locale.US)
        var score = 0
        if (name == query) score = maxOf(score, 1_000)
        if (aliases.any { it == query }) score = maxOf(score, 900)
        if (name.startsWith(query)) score = maxOf(score, 800)
        if (aliases.any { it.startsWith(query) }) score = maxOf(score, 700)
        if (name.contains(query)) score = maxOf(score, 600)
        if (aliases.any { it.contains(query) }) score = maxOf(score, 500)
        if (description.contains(query)) score = maxOf(score, 300)
        if (category.contains(query)) score = maxOf(score, 250)
        val tokens = query.split(TOKEN_SEPARATOR).filter { it.isNotBlank() }
        if (tokens.isNotEmpty()) {
            val haystack = "$name ${aliases.joinToString(" ")} $description $category"
            val matched = tokens.count { haystack.contains(it) }
            if (matched > 0) score = maxOf(score, 200 + matched * 20)
        }
        return score
    }

    private fun editDistance(
        left: String,
        right: String,
    ): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 8
        const val SUGGESTION_LIMIT = 3
        private const val MAX_SUGGESTION_DISTANCE = 3
        private const val DEFAULT_MAX_CHARS = 12_000
        private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    }
}
