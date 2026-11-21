package io.nekohasekai.sfa.agent.mcp

import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentTool
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.BulkApproval
import io.nekohasekai.sfa.agent.ConfirmationKind
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolExecution
import io.nekohasekai.sfa.vfs.VfsScope
import io.nekohasekai.sfa.vfs.VfsScopeAliases
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Locale

private val reservedToolNames = setOf("route_call", "search_tools")

/**
 * MCP tools may run containers or long remote operations. Keep the registry
 * default (120s) for app-local tools and give MCP calls a larger budget; the
 * user can still Stop the turn, which cancels the active call.
 */
internal const val MCP_TOOL_TIMEOUT_MS: Long = 10L * 60L * 1000L
internal const val MCP_DOCKER_RUN_TIMEOUT_MS: Long = 3_700_000L

fun mcpAgentTools(
    serverName: String,
    serverPrefix: String,
    remote: List<McpRemoteTool>,
    session: McpSession,
    artifactSink: McpArtifactSink? = null,
    allowedResourceOrigins: Set<String> = emptySet(),
): List<AgentTool> {
    val used = mutableSetOf<String>()
    return remote.map { tool ->
        val localName = allocateToolName(serverPrefix, tool.name, used)
        McpBoundTool(
            definition =
                AgentToolDefinition(
                    name = localName,
                    description = describe(serverName, tool),
                    permission = AgentPermission.DATA_EGRESS,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "mcp",
                    aliases = aliases(serverName, serverPrefix, tool.name, localName),
                    core = false,
                    serverName = serverName,
                    namespace = serverPrefix,
                    confirmationReason = "This sends the arguments to the MCP server \"$serverName\".",
                    parametersSchemaOverride = withVfsScopeHint(tool.name, tool.inputSchema),
                    passthroughArguments = true,
                    timeoutMs =
                        if (tool.name == DOCKER_RUN_TOOL) {
                            MCP_DOCKER_RUN_TIMEOUT_MS
                        } else {
                            MCP_TOOL_TIMEOUT_MS
                        },
                    confirmationKind = mcpKind(tool.name, tool.readOnly),
                    bulkApproval = mcpBulk(tool.name, tool.readOnly),
                    approvalScope = mcpScope(tool.name, tool.readOnly),
                ),
            remoteName = tool.name,
            session = session,
            artifactSink = artifactSink,
            allowedResourceOrigins = allowedResourceOrigins,
        )
    }
}

internal fun allocateToolName(
    serverPrefix: String,
    remoteName: String,
    used: MutableSet<String>,
): String {
    val base = "${McpServerValidator.slug(serverPrefix, 16)}_${McpServerValidator.slug(remoteName, 40)}"
    var name = base.take(60).trimEnd('_')
    if (name.isBlank()) name = "server_tool"
    var n = 2
    while (name in used || name in reservedToolNames) {
        val suffix = "_$n"
        name = base.take(60 - suffix.length).trimEnd('_') + suffix
        n += 1
    }
    used += name
    return name
}

private val remoteExecNames = setOf(DOCKER_RUN_TOOL, "job_run", "run_command", "exec")
private val readOnlyMarks = setOf("job_file", "orb_status")

private fun isReadOnlyRemote(
    name: String,
    readOnly: Boolean,
): Boolean =
    readOnly ||
        name in readOnlyMarks ||
        name.endsWith("_status") ||
        name.endsWith("_list") ||
        name.endsWith("_logs") ||
        name.endsWith("_read") ||
        name.endsWith("_get")

private fun mcpKind(
    name: String,
    readOnly: Boolean,
): ConfirmationKind =
    when {
        name == VFS_LINK_REQUEST_TOOL -> ConfirmationKind.VFS_GRANT
        name in remoteExecNames -> ConfirmationKind.REMOTE_EXEC
        isReadOnlyRemote(name, readOnly) -> ConfirmationKind.MCP_READ
        else -> ConfirmationKind.MCP_WRITE
    }

private fun mcpBulk(
    name: String,
    readOnly: Boolean,
): BulkApproval =
    when (mcpKind(name, readOnly)) {
        ConfirmationKind.MCP_READ, ConfirmationKind.REMOTE_EXEC -> BulkApproval.TURN
        ConfirmationKind.MCP_WRITE -> BulkApproval.BATCH
        else -> BulkApproval.NONE
    }

private fun mcpScope(
    name: String,
    readOnly: Boolean,
): String =
    when (mcpKind(name, readOnly)) {
        ConfirmationKind.MCP_READ -> "mcp_read"
        ConfirmationKind.REMOTE_EXEC -> "remote_exec"
        ConfirmationKind.VFS_GRANT -> "vfs_grant"
        ConfirmationKind.MCP_WRITE -> "mcp_write"
        else -> "mcp"
    }

private fun aliases(
    serverName: String,
    serverPrefix: String,
    remoteName: String,
    localName: String,
): List<String> =
    listOf(remoteName, serverName, serverPrefix, "mcp")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(localName, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.US) }

/**
 * The remote description is sent whole. The model decides whether to call a
 * tool from this text, so a mid-sentence cap can hide the one constraint that
 * matters. The catalog bounds its own output by dropping whole tools and
 * marking `truncated`, rather than shortening a description.
 */
private fun describe(
    serverName: String,
    tool: McpRemoteTool,
): String {
    val base = "MCP server: $serverName. ${tool.description.ifBlank { tool.name }}"
    return if (tool.name == VFS_LINK_REQUEST_TOOL) "$base $VFS_SCOPE_HINT" else base
}

private val VFS_SCOPE_HINT =
    "Prefer a preset when it matches: " +
        "${VfsScopeAliases.ALL} = whole app (workdir + appfiles + trace + system_prompt + conversation); " +
        "${VfsScopeAliases.CONDUCTOR_SESSION} = Conductor workspace + trace/system_prompt/conversation. " +
        "Fine-grained roots: " +
        VfsScope.entries.joinToString(", ") { it.wire } +
        ". Prefer the narrowest grant. Never ask the user to name scopes."

/**
 * Puts the scope mapping next to the parameter the model actually fills in.
 * The remote schema is otherwise passed through unchanged.
 */
private fun withVfsScopeHint(
    name: String,
    schema: JsonObject,
): JsonObject {
    if (name != VFS_LINK_REQUEST_TOOL) return schema
    val properties = schema["properties"] as? JsonObject ?: return schema
    val scope = properties["scope"] as? JsonObject ?: return schema
    val updatedScope = JsonObject(scope + ("description" to JsonPrimitive(VFS_SCOPE_HINT)))
    val updatedProperties = JsonObject(properties + ("scope" to updatedScope))
    return JsonObject(schema + ("properties" to updatedProperties))
}

private class McpBoundTool(
    override val definition: AgentToolDefinition,
    private val remoteName: String,
    private val session: McpSession,
    private val artifactSink: McpArtifactSink?,
    private val allowedResourceOrigins: Set<String>,
) : AgentTool {
    override suspend fun preflight(args: JsonObject): ToolExecution? {
        if (remoteName != VFS_LINK_REQUEST_TOOL) return null
        val scope = args["scope"] as? JsonArray
            ?: return ToolExecution(
                errorCode = "invalid_parameter",
                errorMessage = "scope must be a non-empty array of scope names.",
            )
        if (scope.isEmpty() ||
            scope.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }
        ) {
            return ToolExecution(
                errorCode = "invalid_parameter",
                errorMessage = "scope must be a non-empty array of scope names.",
            )
        }
        return null
    }

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
                val rpcCode = (e as? McpException)?.rpcCode
                val message = e.message ?: "MCP call failed."
                return ToolExecution(
                    output = rpcCode?.let {
                        buildJsonObject { put("jsonrpc_code", it) }
                    },
                    errorCode = "mcp_failed",
                    errorMessage =
                        (rpcCode?.let { "JSON-RPC $it: " }.orEmpty() + message).take(300),
                )
            }
        return mcpToolExecution(
            result,
            artifactSink,
            remoteName,
            allowedResourceOrigins,
            cancellation,
        )
    }
}

/**
 * Keeps a structured MCP payload structured. Portal tools commonly return a
 * JSON object as their text content; wrapping it in `{"text": "..."}` hides the
 * fields and makes the transcript render the same payload twice.
 */
internal suspend fun mcpToolExecution(
    result: McpCallResult,
    artifactSink: McpArtifactSink?,
    toolName: String,
    allowedResourceOrigins: Set<String>,
    cancellation: ToolCancellation?,
): ToolExecution {
    if (result.isError || result.blocks.isEmpty()) return mcpTextExecution(result)
    val sink = artifactSink
        ?: return ToolExecution(
            errorCode = "mcp_artifact_failed",
            errorMessage = "Binary MCP results are not supported by this runtime.",
        )
    val parsed =
        runCatching {
            Json.parseToJsonElement(result.text) as? JsonObject
        }.getOrNull()
    val jobId = (parsed?.get("jobId") as? JsonPrimitive)?.contentOrNull
    val runId = jobId?.takeIf { it.isNotBlank() } ?: "$toolName-${System.currentTimeMillis()}"
    val artifacts = mutableListOf<JsonObject>()
    val usedNames = mutableSetOf<String>()
    fun uniqueName(name: String): String {
        if (usedNames.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base-$index$extension"
            if (usedNames.add(candidate)) return candidate
            index += 1
        }
    }
    for (block in result.blocks) {
        val artifact =
            try {
                when (block) {
                    is McpContentBlock.Binary ->
                        sink.saveBinary(block.bytes, uniqueName(block.name), block.mimeType, runId)

                    is McpContentBlock.ResourceLink ->
                        sink.saveResourceLink(
                            uri = block.uri,
                            name = uniqueName(block.name),
                            mimeType = block.mimeType,
                            runId = runId,
                            allowedOrigins = allowedResourceOrigins,
                            cancellation = cancellation,
                        )
                }
            } catch (e: Exception) {
                return ToolExecution(
                    errorCode = "mcp_artifact_failed",
                    errorMessage = (e.message ?: "Could not materialize the MCP result.").take(300),
                )
            }
        artifacts +=
            buildJsonObject {
                put("path", artifact.path)
                put("size", artifact.size)
                put("mime", artifact.mimeType)
                put("sha256", artifact.sha256)
            }
    }
    val output =
        buildJsonObject {
            if (result.text.isNotBlank()) put("text", result.text)
            result.structured?.let { put("structured", it) }
            put(
                "artifacts",
                buildJsonArray {
                    artifacts.forEach { add(it) }
                },
            )
        }
    return ToolExecution(output = output)
}

internal fun mcpToolExecution(result: McpCallResult): ToolExecution = mcpTextExecution(result)

private fun mcpTextExecution(result: McpCallResult): ToolExecution {
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
