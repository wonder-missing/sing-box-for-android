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
 * registry and never changes the tools array sent to the model. MCP tools stay
 * searchable and are omitted from the roster, so connecting a server does not
 * change the system prompt.
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
        val named = definitions.filter { it.name.lowercase(Locale.US) == normalized }
        if (named.isNotEmpty()) return named.take(cappedLimit)
        val aliased =
            definitions.filter { tool ->
                tool.aliases.any { it.lowercase(Locale.US) == normalized }
            }
        if (aliased.isNotEmpty()) return aliased.sortedBy { it.name }.take(cappedLimit)
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
        val normalized = query.trim().lowercase(Locale.US)
        val weak =
            matches.isEmpty() ||
                matches.none { tool ->
                    tool.name.equals(normalized, ignoreCase = true) ||
                        tool.aliases.any { it.equals(normalized, ignoreCase = true) } ||
                        score(tool, normalized) >= WEAK_MATCH_SCORE
                }
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
        val categories = definitions.groupingBy { it.category }.eachCount().toSortedMap()
        var output = buildOutput(query, matches.size, objects, categories, truncated = false, weak = weak)
        while (objects.size > 1 && output.toString().length > maxChars) {
            objects.removeAt(objects.lastIndex)
            output = buildOutput(query, matches.size, objects, categories, truncated = true, weak = weak)
        }
        return output
    }

    fun promptSection(): String =
        buildString {
            appendLine("Registered capabilities for this app version:")
            definitions
                .filter { it.category != "mcp" }
                .groupBy { it.category }
                .toSortedMap()
                .forEach { (category, tools) ->
                    appendLine("- $category: ${tools.joinToString(", ") { it.name }}")
                }
            val core = definitions.filter { it.core && it.category != "mcp" }
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
        categories: Map<String, Int>,
        truncated: Boolean,
        weak: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("query", query)
            put("total", total)
            put("truncated", truncated)
            put("weak", weak)
            put(
                "categories",
                buildJsonObject {
                    categories.forEach { (name, count) -> put(name, count) }
                },
            )
            put(
                "matches",
                buildJsonArray {
                    matches.forEach { add(it) }
                },
            )
            if (matches.isEmpty()) {
                put("hint", "No matching tools. Categories above show where capabilities live; search by category or capability name.")
            } else if (weak) {
                put("hint", "These are weak matches, not confirmed capabilities. Check categories and search again before inventing a tool.")
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
        private const val WEAK_MATCH_SCORE = 600
        private const val DEFAULT_MAX_CHARS = 12_000
        private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    }
}
