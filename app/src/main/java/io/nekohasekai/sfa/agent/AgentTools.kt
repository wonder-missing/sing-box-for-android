package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * The three POC tools. All data goes through the interfaces above so the tool
 * layer can be exercised by plain JVM tests without a device or libbox.
 */
class AgentTools(
    private val nodeSource: NodeSnapshotSource,
    private val deviceSource: DeviceSnapshotSource,
    private val probePort: ProbePort,
) {
    private val listNodes =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "list_nodes",
                    description =
                        "List outbound groups and nodes reported by the running core through " +
                            "CommandClient, including selected state and URL-test latency when known.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                )

            override fun execute(args: JsonObject): ToolExecution {
                val snapshot = nodeSource.snapshot()
                val selectedOutbounds =
                    buildJsonObject {
                        snapshot.groups.forEach { group ->
                            if (group.selected.isNotBlank()) {
                                put(group.tag, group.selected)
                            }
                        }
                    }
                val output =
                    buildJsonObject {
                        put("source", "command_client")
                        put("coreRunning", snapshot.coreRunning)
                        put("remoteControl", snapshot.remoteControl)
                        if (!snapshot.coreRunning) {
                            put(
                                "message",
                                "sing-box core is not running or not reachable. " +
                                    "Start the service (or enter Remote Control) and try again.",
                            )
                        } else if (snapshot.groups.isEmpty() && snapshot.outbounds.isEmpty()) {
                            put(
                                "message",
                                "Core connected, but no Groups/Outbounds data has arrived yet. " +
                                    "Try again in a moment.",
                            )
                        }
                        put(
                            "selectedOutbound",
                            snapshot.groups.firstOrNull { it.selected.isNotBlank() }?.selected ?: "",
                        )
                        put("selectedOutbounds", selectedOutbounds)
                        put(
                            "groups",
                            buildJsonArray {
                                snapshot.groups.forEach { group ->
                                    add(
                                        buildJsonObject {
                                            put("tag", group.tag)
                                            put("type", group.type)
                                            put("displayType", group.displayType)
                                            put("selectable", group.selectable)
                                            put("selected", group.selected)
                                            put(
                                                "items",
                                                buildJsonArray {
                                                    group.items.forEach { add(nodeJson(it)) }
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "outbounds",
                            buildJsonArray {
                                snapshot.outbounds.forEach { add(nodeJson(it)) }
                            },
                        )
                        snapshot.error?.let { put("error", it) }
                    }
                return ToolExecution(output = output)
            }
        }

    private val networkSnapshot =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "network_snapshot",
                    description =
                        "Show the current network environment. Core/outbound state comes from " +
                            "CommandClient; wifi/cellular, VPN presence and battery signals are " +
                            "device-local observations (not visible over Remote Control).",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                )

            override fun execute(args: JsonObject): ToolExecution {
                val device = deviceSource.snapshot()
                val snapshot = nodeSource.snapshot()
                val selectedOutbounds =
                    buildJsonObject {
                        snapshot.groups.forEach { group ->
                            if (group.selected.isNotBlank()) {
                                put(group.tag, group.selected)
                            }
                        }
                    }
                val output =
                    buildJsonObject {
                        put("source", "mixed")
                        putJsonObject("local") {
                            put("source", "local_sense")
                            put("deviceOnly", true)
                            put("networkType", device.networkType)
                            device.metered?.let { put("metered", it) }
                            device.vpnActive?.let { put("vpnActive", it) }
                            device.serviceMode?.let { put("serviceMode", it) }
                            device.batteryOptimizationIgnored?.let { put("batteryOptimizationIgnored", it) }
                            device.powerSave?.let { put("powerSave", it) }
                            device.doze?.let { put("doze", it) }
                            device.error?.let { put("error", it) }
                        }
                        putJsonObject("core") {
                            put("source", "command_client")
                            put("running", snapshot.coreRunning)
                            put("remoteControl", snapshot.remoteControl)
                            put(
                                "selectedOutbound",
                                snapshot.groups.firstOrNull { it.selected.isNotBlank() }?.selected ?: "",
                            )
                            put("selectedOutbounds", selectedOutbounds)
                            put("groupCount", snapshot.groups.size)
                            put("outboundCount", snapshot.outbounds.size)
                            if (!snapshot.coreRunning) {
                                put(
                                    "message",
                                    "sing-box core is not running or not reachable; " +
                                        "core fields are empty until the service starts.",
                                )
                            }
                            snapshot.error?.let { put("error", it) }
                        }
                        if (snapshot.remoteControl) {
                            put(
                                "note",
                                "local.* is observed on this device and is not available over Remote Control.",
                            )
                        }
                    }
                return ToolExecution(output = output)
            }
        }

    private val probeViaNode =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "probe_via_node",
                    description =
                        "Probe an http(s) URL through the given outbound. Uses the existing " +
                            "CommandClient to select the node and the existing libbox HTTP client " +
                            "for one HEAD request, then restores the previous selection.",
                    permission = AgentPermission.NETWORK_PROBE,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "outbound",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Outbound/node tag to use as the exit.",
                            ),
                            AgentParameterSpec(
                                name = "url",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Absolute http:// or https:// URL to probe.",
                            ),
                        ),
                    needsConfirmation = true,
                    confirmationReason =
                        "The probe temporarily selects this outbound, sends one HTTP HEAD request, " +
                            "then restores the previous selection. App traffic may briefly use that node.",
                )

            override fun execute(args: JsonObject): ToolExecution {
                val outbound = AgentJson.string(args, "outbound") ?: return invalidArgument("outbound is required")
                val url = AgentJson.string(args, "url") ?: return invalidArgument("url is required")
                if (outbound.length > 256) {
                    return invalidArgument("outbound tag is too long")
                }
                if (!isAllowedProbeUrl(url)) {
                    return invalidArgument("url must be an absolute http(s) URL")
                }
                val outcome = probePort.probe(outbound, url)
                val error =
                    outcome.error
                        ?: if (outcome.statusCode == null) "probe returned no HTTP status" else null
                val output =
                    buildJsonObject {
                        put("ok", error == null)
                        put("outbound", outbound)
                        put("url", url)
                        put("via", "temporary_outbound_selection")
                        outcome.statusCode?.let { put("statusCode", it) }
                        outcome.latencyMs?.let { put("latencyMs", it) }
                        error?.let { put("error", it) }
                    }
                return if (error == null) {
                    ToolExecution(output = output)
                } else {
                    ToolExecution(
                        output = output,
                        errorCode = "probe_failed",
                        errorMessage = error,
                    )
                }
            }

            private fun invalidArgument(message: String) = ToolExecution(errorCode = "invalid_parameter", errorMessage = message)
        }

    val all: List<AgentTool>
        get() = listOf(listNodes, networkSnapshot, probeViaNode)

    private fun nodeJson(node: AgentNode): JsonObject =
        buildJsonObject {
            put("tag", node.tag)
            put("type", node.type)
            put("displayType", node.displayType)
            put("selected", node.selected)
            node.urlTestDelayMs?.let { put("urlTestDelayMs", it) }
        }
}

internal fun isAllowedProbeUrl(raw: String): Boolean {
    if (raw.length > 2048 || raw.any { it.isWhitespace() }) {
        return false
    }
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
