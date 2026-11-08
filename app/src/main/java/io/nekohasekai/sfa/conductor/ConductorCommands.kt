package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentToolDefinition

enum class ConductorAction {
    HELP,
    CAPABILITIES,
    CLEAR,
    NODES,
    NETWORK,
    TAILSCALE,
    TELEMETRY_UPLOAD,
}

data class ConductorCommand(
    val name: String,
    val description: String,
    val usage: String,
    val takesArgs: Boolean = false,
    val action: ConductorAction,
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
                action = ConductorAction.HELP,
            ),
            ConductorCommand(
                name = "commands",
                description = "List all commands.",
                usage = "/commands",
                action = ConductorAction.HELP,
            ),
            ConductorCommand(
                name = "capabilities",
                description = "Show the capability registry: commands and tools.",
                usage = "/capabilities",
                action = ConductorAction.CAPABILITIES,
            ),
            ConductorCommand(
                name = "clear",
                description = "Clear the conversation.",
                usage = "/clear",
                action = ConductorAction.CLEAR,
            ),
            ConductorCommand(
                name = "nodes",
                description = "List outbound groups and nodes from the running core.",
                usage = "/nodes",
                action = ConductorAction.NODES,
            ),
            ConductorCommand(
                name = "network",
                description = "Show the current network snapshot.",
                usage = "/network",
                action = ConductorAction.NETWORK,
            ),
            ConductorCommand(
                name = "tailscale",
                description = "List Tailscale endpoints and peers.",
                usage = "/tailscale",
                action = ConductorAction.TAILSCALE,
            ),
            ConductorCommand(
                name = "telemetry-upload",
                description = "Upload pending telemetry now.",
                usage = "/telemetry-upload",
                action = ConductorAction.TELEMETRY_UPLOAD,
            ),
        )

    fun find(name: String): ConductorCommand? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun parse(input: String): ConductorInput? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        val body = text.removePrefix("/").trim()
        if (body.isEmpty()) return ConductorInput(command = null, args = emptyList(), raw = text)
        val token = body.substringBefore(' ')
        val command = find(token) ?: return ConductorInput(command = null, args = emptyList(), raw = text)
        val args =
            body
                .substringAfter(' ', "")
                .trim()
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }
        return ConductorInput(command = command, args = args, raw = text)
    }

    fun suggestions(input: String): List<ConductorCommand> {
        val text = input.trimStart()
        if (!text.startsWith("/")) return emptyList()
        val body = text.removePrefix("/")
        if (body.contains(' ')) return emptyList()
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
                val params = tool.parameters.joinToString { it.name }.ifBlank { "none" }
                appendLine("  ${tool.name} — ${tool.description}")
                appendLine("    permission: ${tool.permission.label}")
                appendLine("    params: $params")
                appendLine("    confirmation: ${if (tool.needsConfirmation) "required" else "no"}")
            }
        }

    fun unknownMessage(raw: String): String {
        val token = raw.trim().removePrefix("/").substringBefore(' ')
        val hint =
            all
                .filter { token.isNotEmpty() && it.name.startsWith(token.take(1), ignoreCase = true) }
                .take(3)
                .joinToString { "/${it.name}" }
                .ifBlank { "/help" }
        return "Unknown command '/$token'. Try $hint."
    }
}
