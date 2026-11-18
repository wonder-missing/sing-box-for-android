package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.util.Locale

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
    private val httpFetchPort: HttpFetchPort? = null,
    private val fileStore: ConductorFileStore? = null,
    private val webSearchPort: WebSearchPort? = null,
    private val webSearchAvailable: () -> Boolean = { true },
) {
    private val wait =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "wait",
                    description =
                        "Pause for a bounded time between tool calls. Use it after select_outbound or " +
                            "before retrying an operation whose effect is asynchronous. This is a blind " +
                            "delay; when a concrete state can be read back, read it instead.",
                    permission = AgentPermission.UTILITY,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "seconds",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Delay in whole seconds, 0-10. Defaults to 1; 0 is a no-op.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "utility",
                    aliases = listOf("sleep", "delay", "pause", "settle", "wait for"),
                    timeoutMs = WAIT_TIMEOUT_MS,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val seconds = (AgentJson.int(args, "seconds") ?: 1).coerceIn(0, MAX_WAIT_SECONDS)
                val durationMs = seconds * 1000L
                val deadlineNanos = System.nanoTime() + durationMs * 1_000_000L
                while (true) {
                    if (cancellation.isCancelled()) {
                        return ToolExecution(errorCode = "cancelled", errorMessage = "wait cancelled")
                    }
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) break
                    delay(minOf(100L, maxOf(1L, remainingNanos / 1_000_000L)))
                }
                return ToolExecution(
                    output =
                        buildJsonObject {
                            put("waitedMs", durationMs)
                        },
                )
            }
        }

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
                    aliases = listOf("nodes", "outbounds", "node list"),
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
                    if (requestedGroup == null || !snapshot.commandClientConnected) {
                        buildJsonObject {
                            put("source", "command_client")
                            put("coreReachable", snapshot.commandClientConnected)
                            put("remoteControl", snapshot.remoteControl)
                            if (!snapshot.commandClientConnected) {
                                put(
                                    "message",
                                    "sing-box core is not reachable through CommandClient. " +
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
                            put("coreReachable", snapshot.commandClientConnected)
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
                return ToolExecution(output = output, degraded = !snapshot.commandClientConnected)
            }
        }

    private val systemSnapshot =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "system_snapshot",
                    description =
                        "Show the current system state. The device section is observed locally: " +
                            "time, network type, metered state, service/VPN state and battery signals. " +
                            "The core section comes from CommandClient: reachability, selected outbounds " +
                            "and group/outbound counts. Device fields are not visible over Remote Control.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "system",
                    aliases =
                        listOf(
                            "network",
                            "network_snapshot",
                            "status",
                            "snapshot",
                            "system",
                            "device state",
                            "system state",
                        ),
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
                        putJsonObject("device") {
                            put("source", "local_sense")
                            put("networkType", device.networkType)
                            device.localTime?.let { put("localTime", it) }
                            device.metered?.let { put("metered", it) }
                            device.serviceActive?.let { put("serviceActive", it) }
                            device.vpnServiceActive?.let { put("vpnServiceActive", it) }
                            device.serviceMode?.let { put("serviceMode", it) }
                            device.batteryOptimizationExempt?.let { put("batteryOptimizationExempt", it) }
                            device.batterySaver?.let { put("batterySaver", it) }
                            device.dozeMode?.let { put("dozeMode", it) }
                            device.error?.let { put("error", it) }
                        }
                        putJsonObject("core") {
                            put("source", "command_client")
                            put("reachable", snapshot.commandClientConnected)
                            put("remoteControl", snapshot.remoteControl)
                            put("selectedOutbounds", selectedOutbounds)
                            put("groupCount", snapshot.groups.size)
                            put("outboundCount", snapshot.outbounds.size)
                            if (!snapshot.commandClientConnected) {
                                put(
                                    "message",
                                    "sing-box core is not reachable through CommandClient; " +
                                        "core fields are empty until the service starts or reconnects.",
                                )
                            }
                            snapshot.error?.let { put("error", it) }
                        }
                        if (snapshot.remoteControl) {
                            put(
                                "note",
                                "device.* is observed on this device and is not available over Remote Control.",
                            )
                        }
                    }
                return ToolExecution(output = output, degraded = !snapshot.commandClientConnected)
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
                    aliases = listOf("probe", "latency", "url test"),
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
                if (!isAllowedHttpUrl(url)) {
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
                            "changing the global selection and restoring it afterwards; the batch captures its " +
                            "starting selection and restores that selection again at the end. The batch stops after " +
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
                    aliases = listOf("batch probe", "probe all", "batch test"),
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
                if (!isAllowedHttpUrl(url)) {
                    return ToolExecution(errorCode = "invalid_parameter", errorMessage = "url must be an absolute http(s) URL")
                }
                val snapshot = nodeSource.snapshot()
                if (!snapshot.commandClientConnected) {
                    return ToolExecution(
                        errorCode = "tool_unavailable",
                        errorMessage = "sing-box core is not reachable; start the service before probing",
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
                val originalSelection = group.selected.takeIf { it.isNotBlank() }
                val startedAt = System.nanoTime()
                val results = mutableListOf<JsonObject>()
                var truncated = false
                var restoreError: String? = null
                try {
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
                } finally {
                    val selection = selectionPort
                    if (originalSelection != null && selection != null) {
                        try {
                            val restored = selection.select(groupTag, originalSelection)
                            if (!restored.ok) {
                                restoreError = restored.error ?: "could not restore the previous selection"
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            restoreError = e.message ?: e.toString()
                        }
                    }
                }
                val output =
                    buildJsonObject {
                        put("group", groupTag)
                        put("url", url)
                        put("truncated", truncated)
                        if (originalSelection != null && selectionPort != null) {
                            put("restored", restoreError == null)
                            restoreError?.let { put("restoreError", AgentSanitizer.scrubForModelRead(it)) }
                        }
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
                            "This is a persistent change; a successful change is not automatically restored. " +
                            "The previous selection is restored only if the change fails. For a temporary connectivity check, prefer probe_via_node or probe_many. " +
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
                    aliases = listOf("switch outbound", "select outbound", "select node"),
                    core = true,
                    confirmationReason =
                        "This changes the running core's selected outbound and keeps it changed. App traffic may switch immediately. " +
                            "The previous selection is restored if the call fails, not after a successful change.",
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

    private val tailscaleDevices =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "tailscale_devices",
                    description =
                        "List Tailscale endpoints and devices visible to the running service. " +
                            "Device names are the peer names accepted by taildrop_send and taildrop_send_selected. " +
                            "Read-only; returns structured endpoint/peer state without account email or peer IPs.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "devices",
                    aliases =
                        listOf(
                            "tailscale",
                            "tailscale_status",
                            "devices",
                            "peers",
                            "taildrop",
                            "file transfer",
                            "send files",
                        ),
                    core = true,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val source = tailscaleStatusSource ?: return unavailable()
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "tailscale status cancelled")
                }
                return try {
                    ToolExecution(output = tailscaleSnapshotJson(source.status(cancellation)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolExecution(errorCode = "tool_failed", errorMessage = e.message ?: e.toString())
                }
            }
        }

    private val httpFetch =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "http_fetch",
                    description =
                        "Send one standard HTTP request from this device using the Android HTTP client. " +
                            "method defaults to GET; UTF-8 body is allowed for methods that permit one. " +
                            "Returns statusCode, statusText, response headers, contentType and body text for any " +
                            "HTTP status. max_bytes caps the body bytes read. The request uses the device's " +
                            "current network; use probe_via_node when the exit node matters.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "url",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Absolute http:// or https:// URL to fetch.",
                            ),
                            AgentParameterSpec(
                                name = "method",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "HTTP method, default GET. Known methods are normalized to uppercase; " +
                                        "CONNECT, TRACE and TRACK are rejected.",
                            ),
                            AgentParameterSpec(
                                name = "headers",
                                type = AgentParameterType.STRING_ARRAY,
                                required = false,
                                description =
                                    "Optional request headers as \"Name: value\" strings. " +
                                        "Values are not written to logs.",
                            ),
                            AgentParameterSpec(
                                name = "body",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "UTF-8 request body. Rejected for GET and HEAD, matching web fetch semantics.",
                                allowBlank = true,
                            ),
                            AgentParameterSpec(
                                name = "max_bytes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description =
                                    "Maximum body bytes read. Defaults to 65536; capped at 262144 for " +
                                        "text fetch and 8 MiB when output_path is set.",
                            ),
                            AgentParameterSpec(
                                name = "output_path",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "Optional path under conductor/resources/. Saves the response body " +
                                        "to the workspace and returns path metadata instead of the body.",
                            ),
                            AgentParameterSpec(
                                name = "overwrite",
                                type = AgentParameterType.BOOLEAN,
                                required = false,
                                description = "Allow replacing an existing output_path resource. Defaults to false.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "network",
                    aliases =
                        listOf(
                            "fetch",
                            "http get",
                            "download text",
                            "download image",
                            "download svg",
                            "download file",
                            "save file",
                            "request text",
                        ),
                    confirmationReason =
                        "This sends an HTTP request from this device to the URL you provide.",
                    allowedUrlParameter = "url",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = httpFetchPort ?: return unavailable()
                val url =
                    AgentJson.string(args, "url")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return invalidParameter("url is required")
                if (!isAllowedHttpUrl(url)) {
                    return invalidParameter("url must be an absolute http(s) URL")
                }
                val method =
                    normalizeFetchMethod(AgentJson.string(args, "method") ?: "GET")
                        ?: return invalidParameter(
                            "method must be a valid HTTP token other than CONNECT, TRACE or TRACK",
                        )
                val bodyProvided = args.containsKey("body")
                if (bodyProvided && AgentJson.string(args, "body") == null) {
                    return invalidParameter("body must be a string")
                }
                val body = if (bodyProvided) AgentJson.string(args, "body") else null
                if (body != null && (method == "GET" || method == "HEAD")) {
                    return invalidParameter("GET and HEAD requests cannot carry a body")
                }
                if (body != null && body.toByteArray(Charsets.UTF_8).size > MAX_FETCH_BODY_BYTES) {
                    return invalidParameter("body is larger than $MAX_FETCH_BODY_BYTES bytes")
                }
                val headers =
                    parseFetchHeaders(AgentJson.stringList(args, "headers"))
                        ?: return invalidParameter(
                            "headers must be \"Name: value\" strings without CR or LF",
                        )
                val outputPath =
                    AgentJson.string(args, "output_path")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                val overwrite = AgentJson.boolean(args, "overwrite") ?: false
                val maxBytes =
                    (AgentJson.int(args, "max_bytes") ?: if (outputPath == null) DEFAULT_FETCH_BYTES else MAX_RESOURCE_BYTES)
                        .coerceIn(1, if (outputPath == null) MAX_FETCH_BYTES else MAX_RESOURCE_BYTES)
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "http_fetch cancelled")
                }
                if (outputPath != null) {
                    val store = fileStore ?: return unavailable("Resource storage is unavailable.")
                    val result =
                        try {
                            port.download(url, method, headers, body, maxBytes, cancellation)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return ToolExecution(
                                errorCode = "tool_failed",
                                errorMessage = e.message ?: e.toString(),
                            )
                        }
                    val failure =
                        when {
                            result.error != null -> result.error
                            result.statusCode == null -> "The server returned no HTTP status."
                            result.statusCode !in 200..299 -> "HTTP ${result.statusCode} ${result.statusText.orEmpty()}".trim()
                            result.truncated -> "Response exceeded max_bytes."
                            else -> null
                        }
                    if (failure != null) {
                        return ToolExecution(
                            output =
                                buildJsonObject {
                                    put("ok", false)
                                    put("url", url)
                                    result.statusCode?.let { put("statusCode", it) }
                                    result.contentType?.let { put("contentType", it) }
                                    put("bytes", result.bytes.size)
                                    put("error", failure)
                                },
                            errorCode = "tool_failed",
                            errorMessage = failure,
                        )
                    }
                    val entry =
                        try {
                            store.writeResourceBytes(outputPath, result.bytes, overwrite)
                        } catch (e: Exception) {
                            return invalidParameter(e.message ?: "Could not write output_path.")
                        }
                    val output =
                        buildJsonObject {
                            put("ok", true)
                            put("url", url)
                            put("statusCode", result.statusCode)
                            put("contentType", result.contentType)
                            put("bytes", result.bytes.size)
                            put("path", entry.path)
                            put("sha256", store.sha256(store.resolveResource(entry.path)))
                            result.latencyMs?.let { put("latencyMs", it) }
                        }
                    return ToolExecution(output = output)
                }
                val result =
                    try {
                        port.fetch(url, method, headers, body, maxBytes, cancellation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return ToolExecution(errorCode = "tool_failed", errorMessage = e.message ?: e.toString())
                    }
                val output =
                    buildJsonObject {
                        put("ok", result.error == null)
                        put("url", url)
                        put("method", method)
                        result.statusCode?.let { put("statusCode", it) }
                        result.statusText?.let { put("statusText", it) }
                        result.contentType?.let { put("contentType", it) }
                        if (result.headers.isNotEmpty()) {
                            putJsonObject("headers") {
                                result.headers.forEach { (name, values) ->
                                    put(
                                        name,
                                        buildJsonArray {
                                            values.forEach { add(it) }
                                        },
                                    )
                                }
                            }
                        }
                        put("bytes", result.bytes)
                        put("truncated", result.truncated)
                        if (result.binary) put("binary", true)
                        result.content?.let { put("text", AgentSanitizer.scrubForModelRead(it)) }
                        result.latencyMs?.let { put("latencyMs", it) }
                        result.error?.let { put("error", it) }
                    }
                return if (result.error == null) {
                    ToolExecution(output = output)
                } else {
                    ToolExecution(
                        output = output,
                        errorCode = "tool_failed",
                        errorMessage = result.error,
                    )
                }
            }

            private fun normalizeFetchMethod(raw: String): String? {
                val method = raw.trim()
                if (method.isEmpty() || method.length > 32) return null
                if (!HTTP_METHOD_TOKEN.matches(method)) return null
                val upper = method.uppercase(Locale.US)
                if (upper in FETCH_FORBIDDEN_METHODS) return null
                return if (upper in FETCH_NORMALIZED_METHODS) upper else method
            }

            private fun parseFetchHeaders(raw: List<String>?): List<Pair<String, String>>? {
                if (raw == null) return emptyList()
                if (raw.size > MAX_FETCH_HEADERS) return null
                val headers = mutableListOf<Pair<String, String>>()
                for (line in raw) {
                    if (line.length > MAX_FETCH_HEADER_CHARS) return null
                    if (line.any { it == '\r' || it == '\n' }) return null
                    val colon = line.indexOf(':')
                    if (colon <= 0 || colon == line.lastIndex) return null
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (name.isEmpty() || name.length > 64) return null
                    if (!HEADER_NAME.matches(name)) return null
                    if (value.length > 1024) return null
                    headers += name to value
                }
                return headers
            }
        }

    private val webSearch =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "web_search",
                    description =
                        "Search the public web through the configured DeepSeek web-search service. " +
                            "Returns a concise answer, source URLs and cited excerpts. Use it for current " +
                            "events, facts, documentation, prices or anything outside the device state. " +
                            "Use http_fetch when you already know the page URL.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "query",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Search query or question. Do not pass a URL.",
                            ),
                            AgentParameterSpec(
                                name = "max_results",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum source entries returned. Defaults to 8; capped at 20.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "web",
                    aliases =
                        listOf(
                            "search web",
                            "web search",
                            "internet search",
                            "search the internet",
                            "look up",
                            "latest news",
                            "news search",
                            "google",
                        ),
                    confirmationReason =
                        "This sends the search query to the configured DeepSeek web-search service.",
                    timeoutMs = WEB_SEARCH_TIMEOUT_MS,
                )

            override fun isAvailable(): Boolean = webSearchAvailable()

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = webSearchPort ?: return unavailable()
                val query =
                    AgentJson.string(args, "query")?.trim()?.takeIf { it.isNotBlank() }
                        ?: return invalidParameter("query is required")
                if (query.length > MAX_WEB_SEARCH_QUERY_CHARS) {
                    return invalidParameter("query is longer than $MAX_WEB_SEARCH_QUERY_CHARS characters")
                }
                val maxResults =
                    (AgentJson.int(args, "max_results") ?: DEFAULT_WEB_SEARCH_RESULTS)
                        .coerceIn(1, MAX_WEB_SEARCH_RESULTS)
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "web_search cancelled")
                }
                val result =
                    try {
                        port.search(query, maxResults, cancellation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return ToolExecution(errorCode = "tool_failed", errorMessage = e.message ?: e.toString())
                    }
                val hasContent = !result.answer.isNullOrBlank() || result.sources.isNotEmpty()
                val error =
                    result.error
                        ?: result.searchErrors
                            .takeIf { !hasContent && it.isNotEmpty() }
                            ?.joinToString("; ")
                val output =
                    buildJsonObject {
                        put("ok", error == null)
                        put("query", query)
                        result.answer
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let {
                                put(
                                    "answer",
                                    AgentSanitizer.scrubForModelRead(
                                        it.take(MAX_WEB_SEARCH_ANSWER_CHARS),
                                    ),
                                )
                            }
                        put(
                            "sources",
                            buildJsonArray {
                                result.sources.forEach { source ->
                                    add(
                                        buildJsonObject {
                                            put("type", "web_search_source")
                                            put("url", source.url)
                                            source.title
                                                ?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { put("title", AgentSanitizer.scrubForModelRead(it)) }
                                            source.pageAge
                                                ?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { put("pageAge", it) }
                                            source.citedText
                                                ?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let {
                                                    put(
                                                        "citedText",
                                                        AgentSanitizer.scrubForModelRead(
                                                            it.take(MAX_WEB_SEARCH_CITED_CHARS),
                                                        ),
                                                    )
                                                }
                                        },
                                    )
                                }
                            },
                        )
                        if (result.searchQueries.isNotEmpty()) {
                            put(
                                "searchQueries",
                                buildJsonArray {
                                    result.searchQueries.forEach { add(it) }
                                },
                            )
                        }
                        if (result.searchErrors.isNotEmpty()) {
                            put(
                                "searchErrors",
                                buildJsonArray {
                                    result.searchErrors.forEach {
                                        add(AgentSanitizer.scrubForModelRead(it))
                                    }
                                },
                            )
                        }
                        result.latencyMs?.let { put("latencyMs", it) }
                        error?.let { put("error", AgentSanitizer.scrubForModelRead(it)) }
                    }
                val allowedUrls =
                    result.sources.map { it.url }.toSet() +
                        allowedWebSearchUrls(result.answer, result.sources) +
                        webSearchAnswerUrls(result.answer)
                return if (error == null) {
                    ToolExecution(output = output, allowedUrls = allowedUrls)
                } else {
                    ToolExecution(
                        output = output,
                        errorCode = "tool_failed",
                        errorMessage = error,
                        allowedUrls = allowedUrls,
                    )
                }
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
                    aliases = listOf("telemetry", "upload"),
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
                add(systemSnapshot)
                add(wait)
                add(probeViaNode)
                add(probeMany)
                if (selectionPort != null) add(selectOutbound)
                if (tailscaleStatusSource != null) add(tailscaleDevices)
                if (httpFetchPort != null) add(httpFetch)
                if (webSearchPort != null) add(webSearch)
                if (telemetryUploadPort != null) add(telemetryUpload)
            }

    private fun unavailable(message: String = "this tool is not available in the current build"): ToolExecution =
        ToolExecution(errorCode = "tool_unavailable", errorMessage = message)

    private fun invalidParameter(message: String): ToolExecution =
        ToolExecution(errorCode = "invalid_parameter", errorMessage = message)

    internal fun tailscaleSnapshotJson(snapshot: AgentTailscaleSnapshot): JsonObject =
        buildJsonObject {
            put(
                "endpoints",
                buildJsonArray {
                    snapshot.endpoints.forEach { endpoint ->
                        add(
                            buildJsonObject {
                                put("tag", endpoint.tag)
                                put("state", endpoint.state)
                                endpoint.self?.let { put("self", tailscalePeerJson(it)) }
                                put(
                                    "peers",
                                    buildJsonArray {
                                        endpoint.peers.forEach { add(tailscalePeerJson(it)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun tailscalePeerJson(peer: AgentTailscalePeer): JsonObject =
        buildJsonObject {
            put("name", peer.name)
            put("online", peer.online)
            if (peer.exitNode) put("exitNode", true)
            if (peer.exitNodeOption) put("exitNodeOption", true)
            peer.lastSeen?.let { put("lastSeen", it) }
        }

    private fun nodeJson(node: AgentNode): JsonObject =
        buildJsonObject {
            put("tag", node.tag)
            put("type", node.type)
            put("displayType", node.displayType)
            put("selected", node.selected)
            node.urlTestDelayMs?.let { put("urlTestDelayMs", it) }
        }
}

private const val MAX_PROBE_MANY = 20
private const val DEFAULT_PROBE_MANY = 10
private const val MAX_PROBE_MANY_MS = 90_000L
private const val MAX_WAIT_SECONDS = 10
private const val WAIT_TIMEOUT_MS = 15_000L

private const val DEFAULT_FETCH_BYTES = 64 * 1024
private const val MAX_FETCH_BYTES = 256 * 1024
private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
private const val MAX_FETCH_BODY_BYTES = 256 * 1024
private const val MAX_FETCH_HEADERS = 16
private const val MAX_FETCH_HEADER_CHARS = 2048
private const val DEFAULT_WEB_SEARCH_RESULTS = 8
private const val MAX_WEB_SEARCH_RESULTS = 20
private const val MAX_WEB_SEARCH_QUERY_CHARS = 2000
private const val MAX_WEB_SEARCH_ANSWER_CHARS = 16000
private const val MAX_WEB_SEARCH_CITED_CHARS = 800
private const val WEB_SEARCH_TIMEOUT_MS = 75_000L
private val HEADER_NAME = Regex("[A-Za-z0-9-]+")
private val HTTP_METHOD_TOKEN = Regex("[A-Za-z0-9!#%&'*+.^_`|~-]+")
private val FETCH_FORBIDDEN_METHODS = setOf("CONNECT", "TRACE", "TRACK")
private val FETCH_NORMALIZED_METHODS = setOf("DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT")

internal fun isAllowedHttpUrl(raw: String): Boolean {
    if (raw.length > 2048 || raw.any { it.isWhitespace() }) {
        return false
    }
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo.isNullOrBlank()
}
