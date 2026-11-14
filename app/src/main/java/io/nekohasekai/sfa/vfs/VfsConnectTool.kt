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
) : AgentTool {
    override val definition =
        AgentToolDefinition(
            name = "vfs_connect",
            description =
                "Connect to a VFS Link returned by vfs_link_request. The app shows its own consent dialog; " +
                    "this call waits for the user's decision and then connects. Use it only with a link " +
                    "issued by a configured MCP server.",
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
            val result = manager.connect(link)
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
                        result.expiresAt?.let { put("expiresAt", it) }
                    },
            )
        } catch (error: VfsConnectException) {
            failure(error.code, error.message ?: error.code, error)
        } catch (error: Exception) {
            failure("VFS_CONNECT_FAILED", error.message ?: "Could not connect to the VFS provider.")
        }
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
