package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.mcp.McpListedTool
import java.util.Locale

/**
 * `/mcp use <tool>` turns one listed tool into a sentence in the input box.
 * The sentence is ordinary user text. It does not change the system prompt.
 */
internal object ConductorMcpUse {
    const val VERB = "use"

    fun isCommand(raw: String): Boolean {
        val parsed = ConductorCommands.parse(raw.trim()) ?: return false
        return parsed.command?.action == ConductorCommandKind.MCP && isUse(parsed.args)
    }

    fun isUse(args: List<String>): Boolean = args.firstOrNull()?.equals(VERB, ignoreCase = true) == true

    fun draft(tool: McpListedTool): String = "Use the MCP tool ${tool.remoteName} from ${tool.serverName}. "

    /** Offer the `use` chip while the user is still on `/mcp` and has not named a tool. */
    fun offerVerb(input: String): Boolean {
        val typed = argument(input) ?: return false
        return !typed.any { it.isWhitespace() } &&
            (typed.isEmpty() || VERB.startsWith(typed, ignoreCase = true))
    }

    fun toolSuggestions(
        input: String,
        tools: List<McpListedTool>,
    ): List<McpListedTool> {
        val typed = toolPrefix(input) ?: return emptyList()
        return tools
            .filter { tool ->
                typed.isEmpty() ||
                    tool.remoteName.startsWith(typed, ignoreCase = true) ||
                    tool.localName.startsWith(typed, ignoreCase = true)
            }.sortedWith(compareBy({ it.remoteName.lowercase(Locale.US) }, { it.serverName.lowercase(Locale.US) }))
    }

    fun resolve(
        args: List<String>,
        tools: List<McpListedTool>,
    ): Outcome {
        if (!isUse(args)) return Outcome.Rejected("Type /mcp use and a tool name. /mcp lists them.")
        val query = args.drop(1).joinToString(" ").trim()
        if (query.isEmpty()) {
            return Outcome.Rejected("Type /mcp use and a tool name. /mcp lists them.")
        }
        val folded = query.lowercase(Locale.US)
        val exactLocal = tools.filter { it.localName.lowercase(Locale.US) == folded }
        if (exactLocal.size == 1) return Outcome.Draft(draft(exactLocal.first()))
        val exactRemote = tools.filter { it.remoteName.lowercase(Locale.US) == folded }
        if (exactRemote.size == 1) return Outcome.Draft(draft(exactRemote.first()))
        if (exactRemote.size > 1) return Outcome.Rejected(ambiguous(query, exactRemote))
        val partial =
            tools.filter { tool ->
                tool.remoteName.lowercase(Locale.US).contains(folded) ||
                    tool.localName.lowercase(Locale.US).contains(folded)
            }
        return when {
            partial.size == 1 -> Outcome.Draft(draft(partial.first()))
            partial.isEmpty() -> Outcome.Rejected("No MCP tool matches \"$query\".\nType /mcp to see the list.")
            else -> Outcome.Rejected(ambiguous(query, partial))
        }
    }

    private fun argument(input: String): String? {
        val text = input.trimStart()
        if (!text.startsWith("/")) return null
        val body = text.removePrefix("/")
        val head = body.takeWhile { !it.isWhitespace() }
        if (!head.equals("mcp", ignoreCase = true)) return null
        val rest = body.substring(head.length)
        if (rest.none { it.isWhitespace() }) return null
        return rest.dropWhile { it.isWhitespace() }
    }

    private fun toolPrefix(input: String): String? {
        val typed = argument(input) ?: return null
        val verb = typed.takeWhile { !it.isWhitespace() }
        if (!verb.equals(VERB, ignoreCase = true)) return null
        val afterVerb = typed.substring(verb.length)
        if (afterVerb.none { it.isWhitespace() }) return null
        val name = afterVerb.dropWhile { it.isWhitespace() }
        if (name.any { it.isWhitespace() }) return null
        return name
    }

    private fun ambiguous(
        query: String,
        tools: List<McpListedTool>,
    ): String =
        buildString {
            appendLine("More than one MCP tool matches \"$query\":")
            tools
                .sortedWith(compareBy({ it.serverName.lowercase(Locale.US) }, { it.remoteName.lowercase(Locale.US) }))
                .forEach { tool ->
                    appendLine("  ${tool.serverName}: ${tool.remoteName}")
                }
            append("Type a more specific name.")
        }

    sealed class Outcome {
        data class Draft(
            val text: String,
        ) : Outcome()

        data class Rejected(
            val message: String,
        ) : Outcome()
    }
}
