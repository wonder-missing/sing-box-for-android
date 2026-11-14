package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentTool
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolExecution
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

private val reservedToolNames = setOf("route_call", "search_tools")

/**
 * MCP tools may run containers or long remote operations. Keep the registry
 * default (120s) for app-local tools and give MCP calls a larger budget; the
 * user can still Stop the turn, which cancels the active call.
 */
internal const val MCP_TOOL_TIMEOUT_MS: Long = 10L * 60L * 1000L

fun mcpAgentTools(
    serverName: String,
    serverId: String,
    remote: List<McpRemoteTool>,
    session: McpSession,
): List<AgentTool> {
    val used = mutableSetOf<String>()
    return remote.map { tool ->
        val localName = allocateToolName(serverId, tool.name, used)
        McpBoundTool(
            definition =
                AgentToolDefinition(
                    name = localName,
                    description = describe(serverName, tool),
                    permission = AgentPermission.DATA_EGRESS,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "mcp",
                    aliases = aliases(serverName, serverId, tool.name, localName),
                    core = false,
                    confirmationReason = "This sends the arguments to the MCP server \"$serverName\".",
                    parametersSchemaOverride = tool.inputSchema,
                    passthroughArguments = true,
                    timeoutMs = MCP_TOOL_TIMEOUT_MS,
                ),
            remoteName = tool.name,
            session = session,
        )
    }
}

internal fun allocateToolName(
    serverId: String,
    remoteName: String,
    used: MutableSet<String>,
): String {
    val base = "mcp_${McpServerValidator.slug(serverId, 24)}_${McpServerValidator.slug(remoteName, 32)}"
    var name = base.take(60).trimEnd('_')
    if (name.isBlank()) name = "mcp_server_tool"
    var n = 2
    while (name in used || name in reservedToolNames) {
        val suffix = "_$n"
        name = base.take(60 - suffix.length).trimEnd('_') + suffix
        n += 1
    }
    used += name
    return name
}

private fun aliases(
    serverName: String,
    serverId: String,
    remoteName: String,
    localName: String,
): List<String> =
    listOf(remoteName, serverName, serverId, "mcp")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(localName, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.US) }

private fun describe(
    serverName: String,
    tool: McpRemoteTool,
): String {
    val text = "$serverName: ${tool.description.ifBlank { tool.name }}"
    return if (text.length <= 240) text else text.take(239) + "…"
}

private class McpBoundTool(
    override val definition: AgentToolDefinition,
    private val remoteName: String,
    private val session: McpSession,
) : AgentTool {
    override suspend fun execute(
        args: JsonObject,
        cancellation: ToolCancellation,
    ): ToolExecution {
        if (cancellation.isCancelled()) throw CancellationException("cancelled")
        val result =
            try {
                session.call(remoteName, args, cancellation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancellation.isCancelled()) throw CancellationException("cancelled")
                return ToolExecution(
                    errorCode = "mcp_failed",
                    errorMessage = (e.message ?: "MCP call failed.").take(300),
                )
            }
        return mcpToolExecution(result)
    }
}

/**
 * Keeps a structured MCP payload structured. Portal tools commonly return a
 * JSON object as their text content; wrapping it in `{"text": "..."}` hides the
 * fields and makes the transcript render the same payload twice.
 */
internal fun mcpToolExecution(result: McpCallResult): ToolExecution {
    val parsed =
        runCatching {
            Json.parseToJsonElement(result.text) as? JsonObject
        }.getOrNull()
    if (result.isError) {
        return if (parsed != null) {
            ToolExecution(
                output = parsed,
                errorCode = "mcp_error",
                errorMessage = "MCP tool reported an error.",
            )
        } else {
            ToolExecution(
                errorCode = "mcp_error",
                errorMessage = result.text.ifBlank { "MCP tool reported an error." }.take(300),
            )
        }
    }
    return ToolExecution(
        output = parsed ?: buildJsonObject { put("text", result.text) },
    )
}
