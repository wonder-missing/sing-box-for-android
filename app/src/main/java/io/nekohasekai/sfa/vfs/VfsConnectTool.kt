package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.AgentPermission
import io.nekohasekai.sfa.agent.AgentTool
import io.nekohasekai.sfa.agent.AgentToolDefinition
import io.nekohasekai.sfa.agent.ConfirmationPolicy
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.agent.ToolExecution
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The agent-facing local tool. The app-level consent dialog is the only gate;
 * the registry must not add its own chat confirmation on top.
 */
class VfsConnectTool(
    private val manager: VfsConnectPort,
    private val fullWorkspaceIntent: () -> Boolean = { false },
) : AgentTool {
    override val definition =
        AgentToolDefinition(
            name = "vfs_connect",
            description =
                "Connect to a VFS Link returned by vfs_link_request. The app shows its own consent dialog; " +
                    "this call waits for the user's decision and then connects. Use it only with a link " +
                    "issued by a configured MCP server. The result lists visibleRoots; if a path is missing, " +
                    "request a new Link with the needed scope and never ask the user to name scopes.",
            permission = AgentPermission.READ_FILES,
            parameters = emptyList(),
            confirmationPolicy = ConfirmationPolicy.NEVER,
            category = "vfs",
            aliases = listOf("vfs", "virtual directory", "connect vfs"),
            parametersSchemaOverride =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("link") {
                            put("type", "object")
                            put(
                                "description",
                                "The vfs-link object returned by vfs_link_request.",
                            )
                        }
                    }
                    put(
                        "required",
                        buildJsonArray {
                            add("link")
                        },
                    )
                    put("additionalProperties", false)
                },
            passthroughArguments = true,
            timeoutMs = 5L * 60L * 1000L + 30_000L,
        )

    override suspend fun execute(
        args: JsonObject,
        cancellation: ToolCancellation,
    ): ToolExecution {
        val linkObject = args["link"] as? JsonObject
            ?: return failure(
                "VFS_LINK_INVALID",
                "The link argument must be a vfs-link object.",
            )
        return try {
            val link = VfsLinkParser.parse(linkObject)
            if (fullWorkspaceIntent() && !link.scope.containsAll(VfsScope.entries)) {
                return incompleteScope(link.scope)
            }
            val result = manager.connect(link)
            val missing = VfsScope.entries.filter { it !in result.scope }
            ToolExecution(
                output =
                    buildJsonObject {
                        put("connected", true)
                        put("issuer", result.issuer)
                        put(
                            "scope",
                            buildJsonArray {
                                result.scope.map { it.wire }.sorted().forEach { add(it) }
                            },
                        )
                        put(
                            "visibleRoots",
                            buildJsonArray {
                                result.scope.map { it.path }.sorted().forEach { add(it) }
                            },
                        )
                        if (missing.isNotEmpty()) {
                            put(
                                "missingScopes",
                                buildJsonArray {
                                    missing.map { it.wire }.sorted().forEach { add(it) }
                                },
                            )
                            put(
                                "hint",
                                "If a path is missing, request a new vfs_link_request with the needed scope. " +
                                    "Never ask the user to name scopes.",
                            )
                        }
                        result.expiresAt?.let { put("expiresAt", it) }
                    },
            )
        } catch (error: VfsConnectException) {
            failure(error.code, error.message ?: error.code, error)
        } catch (error: Exception) {
            failure("VFS_CONNECT_FAILED", error.message ?: "Could not connect to the VFS provider.")
        }
    }

    private fun incompleteScope(granted: Set<VfsScope>): ToolExecution {
        val missing = VfsScope.entries.filter { it !in granted }.sortedBy { it.wire }
        val missingLabels = missing.map { it.label }
        return ToolExecution(
            output =
                buildJsonObject {
                    put("error", "VFS_SCOPE_INCOMPLETE")
                    put(
                        "missingScopes",
                        buildJsonArray {
                            missing.map { it.wire }.sorted().forEach { add(it) }
                        },
                    )
                    put(
                        "missingScopeLabels",
                        buildJsonArray {
                            missingLabels.forEach { add(it) }
                        },
                    )
                    put(
                        "hint",
                        "The user asked for the whole workspace. Request a new vfs_link_request with " +
                            "all scopes, then vfs_connect again. Never ask the user to name scopes.",
                    )
                },
            errorCode = "VFS_SCOPE_INCOMPLETE",
            errorMessage =
                "The Link does not cover the whole workspace. Missing: " +
                    missingLabels.joinToString(", ") + ".",
        )
    }

    private fun failure(
        code: String,
        message: String,
        error: VfsConnectException? = null,
    ): ToolExecution =
        ToolExecution(
            output =
                buildJsonObject {
                    put("error", code)
                    error?.retryAfterMs?.let { put("retryAfterMs", it) }
                    error?.cooldownUntil?.let { put("cooldownUntil", it) }
                },
            errorCode = code,
            errorMessage = message,
        )
}
