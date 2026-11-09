package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentToolDefinition

enum class ConductorCommandKind {
    HELP,
    CAPABILITIES,
    CLEAR,
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
                name = "clear",
                description = "Clear the conversation.",
                usage = "/clear",
                action = ConductorCommandKind.CLEAR,
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
                val params = tool.parameters.joinToString { it.name }.ifBlank { "none" }
                appendLine("  ${tool.name} — ${tool.description}")
                appendLine("    permission: ${tool.permission.label}")
                appendLine("    params: $params")
                appendLine("    confirmation: ${tool.confirmationPolicy}")
            }
        }

    fun unknownMessage(raw: String): String = "Unknown command. Type /help to see the command list."
}
