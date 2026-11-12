package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.mcp.McpListedTool
import io.nekohasekai.sfa.agent.mcp.McpPhase
import io.nekohasekai.sfa.agent.mcp.McpServerView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

enum class ConductorCommandKind {
    HELP,
    CAPABILITIES,
    MCP,
    CLEAR,
    REASONING,
    NODES,
    NETWORK,
    TAILSCALE,
    TELEMETRY_UPLOAD,
    PROBE,
}

data class ConductorCommand(
    val name: String,
    val description: String,
    val usage: String,
    val takesArgs: Boolean = false,
    val action: ConductorCommandKind,
)

data class ConductorInput(
    val command: ConductorCommand?,
    val args: List<String>,
    val raw: String,
)

/**
 * The Phase 1 command set. Slash commands are the deterministic layer; the
 * future LLM will use the same capability registry rather than a second path.
 */
object ConductorCommands {
    val all =
        listOf(
            ConductorCommand(
                name = "help",
                description = "Show this help, or details for one command.",
                usage = "/help [command]",
                takesArgs = true,
                action = ConductorCommandKind.HELP,
            ),
            ConductorCommand(
                name = "commands",
                description = "List all commands.",
                usage = "/commands",
                action = ConductorCommandKind.HELP,
            ),
            ConductorCommand(
                name = "capabilities",
                description = "Show the capability registry: commands and tools.",
                usage = "/capabilities",
                action = ConductorCommandKind.CAPABILITIES,
            ),
            ConductorCommand(
                name = "mcp",
                description = "List MCP servers and tools. /mcp use <tool> puts a preference in the input.",
                usage = "/mcp [name]",
                takesArgs = true,
                action = ConductorCommandKind.MCP,
            ),
            ConductorCommand(
                name = "clear",
                description = "Clear the conversation.",
                usage = "/clear",
                action = ConductorCommandKind.CLEAR,
            ),
            ConductorCommand(
                name = "reasoning",
                description = "Set reasoning effort: default, low, medium, or high.",
                usage = ConductorReasoningCommand.USAGE,
                takesArgs = true,
                action = ConductorCommandKind.REASONING,
            ),
            ConductorCommand(
                name = "nodes",
                description = "List outbound groups and nodes from the running core.",
                usage = "/nodes",
                action = ConductorCommandKind.NODES,
            ),
            ConductorCommand(
                name = "network",
                description = "Show the current network snapshot.",
                usage = "/network",
                action = ConductorCommandKind.NETWORK,
            ),
            ConductorCommand(
                name = "probe",
                description = "Probe an http(s) URL through an outbound node.",
                usage = "/probe <outbound> <url>",
                takesArgs = true,
                action = ConductorCommandKind.PROBE,
            ),
            ConductorCommand(
                name = "tailscale",
                description = "List Tailscale endpoints and peers.",
                usage = "/tailscale",
                action = ConductorCommandKind.TAILSCALE,
            ),
            ConductorCommand(
                name = "telemetry-upload",
                description = "Upload pending telemetry now.",
                usage = "/telemetry-upload",
                action = ConductorCommandKind.TELEMETRY_UPLOAD,
            ),
        )

    fun find(name: String): ConductorCommand? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun parse(input: String): ConductorInput? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        val body = text.removePrefix("/").trim()
        if (body.isEmpty()) return ConductorInput(command = null, args = emptyList(), raw = text)
        val parts = body.split(Regex("""\s+"""), limit = 2)
        val command = find(parts[0]) ?: return ConductorInput(command = null, args = emptyList(), raw = text)
        val args =
            parts
                .getOrNull(1)
                ?.split(Regex("""\s+"""))
                ?.filter { it.isNotBlank() }
                .orEmpty()
        return ConductorInput(command = command, args = args, raw = text)
    }

    fun suggestions(input: String): List<ConductorCommand> {
        val text = input.trimStart()
        if (!text.startsWith("/")) return emptyList()
        val body = text.removePrefix("/")
        if (body.any { it.isWhitespace() }) return emptyList()
        return all.filter { body.isEmpty() || it.name.startsWith(body, ignoreCase = true) }
    }

    fun helpText(command: ConductorCommand? = null): String {
        if (command != null) return "${command.usage}\n${command.description}"
        return buildString {
            appendLine("Commands:")
            all.forEach { appendLine("  ${it.usage.padEnd(24)} ${it.description}") }
            append("Type /help <command> for details.")
        }
    }

    fun capabilitiesText(tools: List<AgentToolDefinition>): String =
        buildString {
            appendLine("Commands:")
            all.forEach { command ->
                appendLine("  ${command.usage.padEnd(24)} ${command.description}")
            }
            appendLine()
            appendLine("Tools:")
            tools.forEach { tool ->
                val params = toolParameterNames(tool)
                appendLine("  ${tool.name} — ${tool.description}")
                appendLine("    permission: ${tool.permission.label}")
                appendLine("    params: $params")
                appendLine("    confirmation: ${tool.confirmationPolicy}")
            }
        }

    private fun toolParameterNames(tool: AgentToolDefinition): String {
        if (tool.parameters.isNotEmpty()) {
            return tool.parameters.joinToString { spec ->
                if (spec.required) spec.name else "${spec.name}?"
            }
        }
        val schema = tool.parametersSchema
        val properties = schema["properties"] as? JsonObject ?: return "none"
        val required =
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
        return properties.keys
            .joinToString { if (it in required) it else "$it?" }
            .ifBlank { "none" }
    }

    fun mcpText(
        servers: List<McpServerView>,
        tools: List<McpListedTool>,
        query: String = "",
    ): String {
        if (servers.isEmpty()) {
            return "No MCP server yet.\nAdd one in Settings → Conductor → MCP servers, then turn it on."
        }
        val needle = query.trim()
        val folded = needle.lowercase(Locale.US)
        val byServer = tools.groupBy { it.serverId }
        val blocks =
            servers.mapNotNull { server ->
                val own = byServer[server.id].orEmpty()
                val serverMatches =
                    folded.isEmpty() ||
                        server.name.lowercase(Locale.US).contains(folded) ||
                        server.id.lowercase(Locale.US).contains(folded)
                val shown =
                    if (folded.isEmpty() || serverMatches) {
                        own
                    } else {
                        own.filter { tool ->
                            tool.remoteName.lowercase(Locale.US).contains(folded) ||
                                tool.summary.lowercase(Locale.US).contains(folded)
                        }
                    }
                if (folded.isNotEmpty() && !serverMatches && shown.isEmpty()) return@mapNotNull null
                renderMcpServer(server, shown)
            }
        if (blocks.isEmpty()) return "No MCP server or tool matches \"$needle\"."
        return buildString {
            appendLine("MCP servers:")
            appendLine()
            append(blocks.joinToString("\n\n"))
            appendLine()
            appendLine()
            appendLine("Type /mcp use <tool> to put that tool in the input.")
            append("Then say what you want. Conductor calls it; you do not type the tool name.")
        }
    }

    private fun renderMcpServer(
        server: McpServerView,
        tools: List<McpListedTool>,
    ): String =
        buildString {
            val status =
                when {
                    server.phase == McpPhase.CONNECTING -> "connecting"
                    !server.enabled -> "off"
                    tools.isEmpty() -> "on · no tools"
                    tools.size == 1 -> "1 tool"
                    else -> "${tools.size} tools"
                }
            append(server.name)
            append(" — ")
            append(status)
            if (!server.error.isNullOrBlank()) {
                appendLine()
                append(server.error)
            }
            tools.forEach { tool ->
                appendLine()
                append("  ")
                append(tool.remoteName)
                append(if (tool.asksFirst) " — asks first" else " — runs immediately")
                if (tool.required.isNotEmpty()) {
                    appendLine()
                    append("    required: ")
                    append(tool.required.joinToString(", "))
                }
                if (tool.summary.isNotBlank() && !tool.summary.equals(tool.remoteName, ignoreCase = true)) {
                    appendLine()
                    append("    ")
                    append(tool.summary)
                }
            }
        }

    fun unknownMessage(raw: String): String = "Unknown command. Type /help to see the command list."
}
