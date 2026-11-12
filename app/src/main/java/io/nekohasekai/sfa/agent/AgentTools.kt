package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * The built-in tools. All data goes through the interfaces above so the tool
 * layer can be exercised by plain JVM tests without a device or libbox.
 */
class AgentTools(
    private val nodeSource: NodeSnapshotSource,
    private val deviceSource: DeviceSnapshotSource,
    private val probePort: ProbePort,
    private val selectionPort: NodeSelectionPort? = null,
    private val tailscaleStatusSource: TailscaleStatusSource? = null,
    private val telemetryUploadPort: TelemetryUploadPort? = null,
) {
    private val listNodes =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "list_nodes",
                    description =
                        "List outbound groups and nodes reported by the running core through CommandClient. " +
                            "With no arguments, returns a compact summary: group tags, selected outbounds, " +
                            "counts and the selected node's URL-test latency. " +
                            "Pass group=<tag> to list that group's nodes with their tags and latencies. " +
                            "Use the summary first, then request one group's detail before probe_via_node.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "group",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Optional outbound group tag. Omit for a compact summary of all groups.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "network",
                    aliases = listOf("nodes", "outbounds", "节点", "出站", "节点列表"),
                    core = true,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val snapshot = nodeSource.snapshot()
                val requestedGroup = AgentJson.string(args, "group")?.trim()?.takeIf { it.isNotBlank() }
                val selectedOutbound =
                    snapshot.groups.firstOrNull { it.selected.isNotBlank() }?.selected ?: ""
                val selectedOutbounds =
                    buildJsonObject {
                        snapshot.groups.forEach { group ->
                            if (group.selected.isNotBlank()) {
                                put(group.tag, group.selected)
                            }
                        }
                    }
                val output =
                    if (requestedGroup == null || !snapshot.coreRunning) {
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
                            put("selectedOutbound", selectedOutbound)
                            put("selectedOutbounds", selectedOutbounds)
                            put("groupCount", snapshot.groups.size)
                            put("outboundCount", snapshot.outbounds.size)
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
                                                put("itemCount", group.items.size)
                                                group.items
                                                    .firstOrNull { it.tag == group.selected }
                                                    ?.urlTestDelayMs
                                                    ?.let { put("selectedDelayMs", it) }
                                            },
                                        )
                                    }
                                },
                            )
                            snapshot.error?.let { put("error", it) }
                        }
                    } else {
                        val group = snapshot.groups.firstOrNull { it.tag == requestedGroup }
                        if (group == null) {
                            return ToolExecution(
                                errorCode = "invalid_parameter",
                                errorMessage =
                                    "Unknown group '$requestedGroup'. " +
                                        "Call list_nodes with no arguments to list group tags.",
                            )
                        }
                        buildJsonObject {
                            put("source", "command_client")
                            put("coreRunning", snapshot.coreRunning)
                            put("remoteControl", snapshot.remoteControl)
                            put("selectedOutbound", selectedOutbound)
                            put("selectedOutbounds", selectedOutbounds)
                            putJsonObject("group") {
                                put("tag", group.tag)
                                put("type", group.type)
                                put("displayType", group.displayType)
                                put("selectable", group.selectable)
                                put("selected", group.selected)
                                put("itemCount", group.items.size)
                                put(
                                    "items",
                                    buildJsonArray {
                                        group.items.forEach { add(nodeJson(it)) }
                                    },
                                )
                            }
                            snapshot.error?.let { put("error", it) }
                        }
                    }
                return ToolExecution(output = output, degraded = !snapshot.coreRunning)
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
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "network",
                    aliases = listOf("network", "status", "snapshot", "网络状态", "网络快照"),
                    core = true,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
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
                            device.localTime?.let { put("localTime", it) }
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
                return ToolExecution(output = output, degraded = !snapshot.coreRunning)
            }
        }

    private val probeViaNode =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "probe_via_node",
                    description =
                        "Check HTTP HEAD reachability of an http(s) URL through the given outbound. " +
                            "Returns status and latency only; it does not fetch page content. " +
                            "Any HTTP status (including 204, 3xx, 4xx, 5xx) means the URL was reached. " +
                            "Transport failures such as DNS, timeout or EOF, and local precheck failures, return ok=false. " +
                            "Some servers reject HEAD requests, so EOF does not always mean the node cannot reach the URL. " +
                            "The outbound must be a node listed in a selectable selector group; URLTest-only members " +
                            "cannot be targeted individually, so use list_nodes to find a selectable tag first. " +
                            "Multiple probe calls in one assistant message are executed one at a time, in order, " +
                            "and each restores the previous selection before the next starts. " +
                            "The previous outbound selection is restored in a finally block; " +
                            "killing the app process mid-probe can leave the running core on the probe node until it restarts.",
                    permission = AgentPermission.NETWORK_PROBE,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "outbound",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Selectable outbound/node tag from a selector group to use as the exit.",
                            ),
                            AgentParameterSpec(
                                name = "url",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Absolute http:// or https:// URL to probe.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    category = "network",
                    aliases = listOf("probe", "latency", "url test", "探测", "测速", "延迟"),
                    core = true,
                    confirmationReason =
                        "The probe temporarily selects this outbound, sends one HTTP HEAD request, " +
                            "then restores the previous selection. App traffic may briefly use that node. " +
                            "If the app process is killed mid-probe, the core can stay on the probe node until it restarts.",
                    allowedUrlParameter = "url",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val outbound = AgentJson.string(args, "outbound")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return invalidArgument("outbound is required")
                val url = AgentJson.string(args, "url")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return invalidArgument("url is required")
                if (outbound.length > 256) {
                    return invalidArgument("outbound tag is too long")
                }
                if (!isAllowedProbeUrl(url)) {
                    return invalidArgument("url must be an absolute http(s) URL")
                }
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "probe cancelled")
                }
                val outcome = probePort.probe(outbound, url, cancellation)
                val error =
                    outcome.error
                        ?: if (outcome.statusCode == null) "probe returned no HTTP status" else null
                val output =
                    buildJsonObject {
                        put("reachable", error == null)
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
                        errorCode = "tool_failed",
                        errorMessage = error,
                    )
                }
            }

            private fun invalidArgument(message: String) = ToolExecution(errorCode = "invalid_parameter", errorMessage = message)
        }

    private val probeMany =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "probe_many",
                    description =
                        "Probe one http(s) URL through several outbounds in one call. " +
                            "Use list_nodes(group=<tag>) first to get tags. If outbounds is omitted, " +
                            "probes the group's items up to limit. Probes run one at a time, each temporarily " +
                            "changing the global selection and restoring it afterwards. The batch stops after " +
                            "about 90 seconds and marks truncated=true. Returns per-node reachable/statusCode/latency/error.",
                    permission = AgentPermission.NETWORK_PROBE,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "group",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Selectable selector group tag.",
                            ),
                            AgentParameterSpec(
                                name = "url",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Absolute http:// or https:// URL to probe.",
                            ),
                            AgentParameterSpec(
                                name = "outbounds",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Optional comma or space separated outbound tags. Omit to use the group's items.",
                            ),
                            AgentParameterSpec(
                                name = "limit",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Optional maximum nodes, 1 to 20. Default 10.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    category = "network",
                    aliases = listOf("batch probe", "probe all", "批量探测", "批量测速"),
                    core = true,
                    confirmationReason =
                        "This probes several outbounds by temporarily changing the running core's selected outbound " +
                            "for each one. It restores the previous selection after each probe and may take about 90 seconds.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val groupTag =
                    AgentJson.string(args, "group")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return ToolExecution(errorCode = "missing_parameter", errorMessage = "group is required")
                val url =
                    AgentJson.string(args, "url")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return ToolExecution(errorCode = "missing_parameter", errorMessage = "url is required")
                if (!isAllowedProbeUrl(url)) {
                    return ToolExecution(errorCode = "invalid_parameter", errorMessage = "url must be an absolute http(s) URL")
                }
                val snapshot = nodeSource.snapshot()
                if (!snapshot.coreRunning) {
                    return ToolExecution(
                        errorCode = "tool_unavailable",
                        errorMessage = "sing-box core is not running; start the service before probing",
                    )
                }
                if (snapshot.remoteControl) {
                    return ToolExecution(
                        errorCode = "tool_unavailable",
                        errorMessage = "probe_many is local-only; Remote Control does not expose an outbound-bound HTTP client",
                    )
                }
                val group =
                    snapshot.groups.firstOrNull { it.tag == groupTag && it.selectable }
                        ?: return ToolExecution(
                            errorCode = "invalid_parameter",
                            errorMessage = "Unknown selectable group '$groupTag'. Use list_nodes to list groups.",
                        )
                val limit =
                    AgentJson.string(args, "limit")
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceIn(1, MAX_PROBE_MANY)
                        ?: DEFAULT_PROBE_MANY
                val requested =
                    AgentJson.string(args, "outbounds")
                        ?.split(Regex("[,\\s]+"))
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        .orEmpty()
                val tags =
                    (if (requested.isEmpty()) group.items.map { it.tag } else requested)
                        .distinct()
                        .take(limit)
                if (tags.isEmpty()) {
                    return ToolExecution(errorCode = "invalid_parameter", errorMessage = "No outbounds to probe.")
                }
                val startedAt = System.nanoTime()
                val results = mutableListOf<JsonObject>()
                var truncated = false
                for (tag in tags) {
                    if (cancellation.isCancelled()) {
                        truncated = true
                        break
                    }
                    if ((System.nanoTime() - startedAt) / 1_000_000 > MAX_PROBE_MANY_MS) {
                        truncated = true
                        break
                    }
                    val outcome = probePort.probe(tag, url, cancellation)
                    val error =
                        outcome.error
                            ?: if (outcome.statusCode == null) "probe returned no HTTP status" else null
                    results.add(
                        buildJsonObject {
                            put("outbound", tag)
                            put("reachable", error == null)
                            outcome.statusCode?.let { put("statusCode", it) }
                            outcome.latencyMs?.let { put("latencyMs", it) }
                            error?.let { put("error", it) }
                        },
                    )
                }
                val output =
                    buildJsonObject {
                        put("group", groupTag)
                        put("url", url)
                        put("truncated", truncated)
                        put("results", buildJsonArray { results.forEach { add(it) } })
                    }
                return ToolExecution(output = output)
            }
        }

    private val selectOutbound =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "select_outbound",
                    description =
                        "Change the running core's selected outbound in a selectable selector group. " +
                            "Local-only; Remote Control is rejected. The previous selection is restored if the change fails. " +
                            "Use list_nodes to find a selectable group and tag. App traffic may switch immediately.",
                    permission = AgentPermission.NETWORK_CHANGE,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "group",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Selectable selector group tag.",
                            ),
                            AgentParameterSpec(
                                name = "outbound",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Outbound tag inside that group.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    category = "network",
                    aliases = listOf("switch outbound", "select outbound", "切换节点", "选择出口"),
                    core = true,
                    confirmationReason =
                        "This changes the running core's selected outbound. App traffic may switch immediately. " +
                            "The previous selection is restored if the call fails.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = selectionPort ?: return unavailable()
                val group =
                    AgentJson.string(args, "group")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return ToolExecution(errorCode = "missing_parameter", errorMessage = "group is required")
                val outbound =
                    AgentJson.string(args, "outbound")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return ToolExecution(errorCode = "missing_parameter", errorMessage = "outbound is required")
                if (group.length > 256 || outbound.length > 256) {
                    return ToolExecution(errorCode = "invalid_parameter", errorMessage = "group or outbound tag is too long")
                }
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "select_outbound cancelled")
                }
                val result = port.select(group, outbound)
                val output =
                    buildJsonObject {
                        put("group", result.group)
                        put("selected", result.selected)
                        result.previous?.let { put("previous", it) }
                        result.error?.let { put("error", it) }
                    }
                return if (result.ok) {
                    ToolExecution(output = output)
                } else {
                    ToolExecution(
                        output = output,
                        errorCode = "tool_failed",
                        errorMessage = result.error ?: "select_outbound failed",
                    )
                }
            }
        }

    private val tailscaleStatus =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "tailscale_status",
                    description =
                        "List Tailscale endpoints and peers visible to the running service. " +
                            "Read-only; returns the same status text as the /tailscale command.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "devices",
                    aliases = listOf("tailscale", "devices", "peers", "设备", "设备列表"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val source = tailscaleStatusSource ?: return unavailable()
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "tailscale status cancelled")
                }
                return runCatching { source.status(cancellation) }
                    .fold(
                        onSuccess = { ToolExecution(output = buildJsonObject { put("result", it) }) },
                        onFailure = { ToolExecution(errorCode = "tool_failed", errorMessage = it.message ?: it.toString()) },
                    )
            }
        }

    private val telemetryUpload =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "telemetry_upload",
                    description =
                        "Upload pending anonymous telemetry now. This sends data off the device; " +
                            "the Agent must ask for confirmation first.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "telemetry",
                    aliases = listOf("telemetry", "upload", "遥测", "上传"),
                    confirmationReason =
                        "This uploads pending telemetry from this device to the configured ingest endpoint.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = telemetryUploadPort ?: return unavailable()
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "telemetry upload cancelled")
                }
                val result =
                    try {
                        port.upload(cancellation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return ToolExecution(errorCode = "tool_failed", errorMessage = e.message ?: e.toString())
                    }
                return ToolExecution(output = buildJsonObject { put("result", result) })
            }
        }

    val all: List<AgentTool>
        get() =
            buildList {
                add(listNodes)
                add(networkSnapshot)
                add(probeViaNode)
                add(probeMany)
                if (selectionPort != null) add(selectOutbound)
                if (tailscaleStatusSource != null) add(tailscaleStatus)
                if (telemetryUploadPort != null) add(telemetryUpload)
            }

    private companion object {
        private const val MAX_PROBE_MANY = 20
        private const val DEFAULT_PROBE_MANY = 10
        private const val MAX_PROBE_MANY_MS = 90_000L
    }

    private fun unavailable(): ToolExecution =
        ToolExecution(errorCode = "tool_unavailable", errorMessage = "this tool is not available in the current build")

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
    return (scheme == "http" || scheme == "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo.isNullOrBlank()
}
