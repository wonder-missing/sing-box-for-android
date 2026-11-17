package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
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
                    aliases = listOf("switch outbound", "select outbound", "select node"),
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
                        "Send one HTTP request from this device through the running core and return text. " +
                            "method defaults to GET; UTF-8 body is allowed for methods that permit one. " +
                            "With outbound, temporarily selects that node and restores the previous selection. " +
                            "Returns statusCode and text for HTTP 200; other HTTP statuses carry statusCode without " +
                            "a body because the core HTTP client throws for them. Response headers and content-type " +
                            "are not available. max_bytes truncates the returned text, not the download. " +
                            "Local-only; Remote Control is rejected.",
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
                                name = "outbound",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "Optional selectable outbound tag. Omit to use the current selection. " +
                                        "A named outbound is temporarily selected and then restored.",
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
                                description = "Maximum returned text bytes. Defaults to 65536; capped at 262144.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "network",
                    aliases = listOf("fetch", "http get", "download text", "request text"),
                    confirmationReason =
                        "This sends an HTTP request from this device through the running core. " +
                            "A named outbound temporarily changes the core's selection; app traffic may briefly " +
                            "use that node. If the app process is killed mid-fetch, the core can stay on that " +
                            "node until it restarts.",
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
                val outbound =
                    AgentJson.string(args, "outbound")?.trim()?.takeIf { it.isNotBlank() }
                if (outbound != null && outbound.length > 256) {
                    return invalidParameter("outbound tag is too long")
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
                val maxBytes =
                    (AgentJson.int(args, "max_bytes") ?: DEFAULT_FETCH_BYTES)
                        .coerceIn(1, MAX_FETCH_BYTES)
                if (cancellation.isCancelled()) {
                    return ToolExecution(errorCode = "cancelled", errorMessage = "http_fetch cancelled")
                }
                val result =
                    try {
                        port.fetch(outbound, url, method, headers, body, maxBytes, cancellation)
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
                        outbound?.let { put("outbound", it) }
                        result.via?.let { put("via", it) }
                        result.statusCode?.let { put("statusCode", it) }
                        put("bytes", result.bytes)
                        put("truncated", result.truncated)
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
                add(probeViaNode)
                add(probeMany)
                if (selectionPort != null) add(selectOutbound)
                if (tailscaleStatusSource != null) add(tailscaleDevices)
                if (httpFetchPort != null) add(httpFetch)
                if (telemetryUploadPort != null) add(telemetryUpload)
            }

    private fun unavailable(): ToolExecution =
        ToolExecution(errorCode = "tool_unavailable", errorMessage = "this tool is not available in the current build")

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

private const val DEFAULT_FETCH_BYTES = 64 * 1024
private const val MAX_FETCH_BYTES = 256 * 1024
private const val MAX_FETCH_BODY_BYTES = 256 * 1024
private const val MAX_FETCH_HEADERS = 16
private const val MAX_FETCH_HEADER_CHARS = 2048
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
