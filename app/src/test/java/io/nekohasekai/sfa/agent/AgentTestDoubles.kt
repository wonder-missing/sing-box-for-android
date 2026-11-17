package io.nekohasekai.sfa.agent

class FakeNodeSource(
    var snapshot: NodeSnapshot = sampleNodeSnapshot(),
) : NodeSnapshotSource {
    override fun snapshot(): NodeSnapshot = snapshot
}

class FakeDeviceSource(
    var snapshot: DeviceSnapshot =
        DeviceSnapshot(
            networkType = "wifi",
            metered = false,
            serviceActive = true,
            vpnServiceActive = true,
            serviceMode = "vpn",
            batteryOptimizationExempt = true,
            batterySaver = false,
            dozeMode = false,
        ),
) : DeviceSnapshotSource {
    override fun snapshot(): DeviceSnapshot = snapshot
}

class FakeProbePort(
    var outcome: ProbeOutcome = ProbeOutcome(statusCode = 200, latencyMs = 42),
) : ProbePort {
    val calls = mutableListOf<Pair<String, String>>()

    override fun probe(
        outbound: String,
        url: String,
        cancellation: ToolCancellation?,
    ): ProbeOutcome {
        calls.add(outbound to url)
        return outcome
    }
}

class FakeHttpFetchPort(
    var outcome: HttpFetchOutcome = HttpFetchOutcome(statusCode = 200, latencyMs = 12, content = "hello", bytes = 5),
) : HttpFetchPort {
    val calls = mutableListOf<List<Any?>>()

    override fun fetch(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpFetchOutcome {
        calls += listOf(url, method, headers, body, maxBytes)
        return outcome
    }
}

class FakeWebSearchPort(
    var outcome: WebSearchOutcome =
        WebSearchOutcome(
            answer = "hello",
            sources = listOf(WebSearchSource("https://example.com/source", title = "Example")),
        ),
) : WebSearchPort {
    val calls = mutableListOf<Pair<String, Int>>()

    override suspend fun search(
        query: String,
        maxResults: Int,
        cancellation: ToolCancellation?,
    ): WebSearchOutcome {
        calls += query to maxResults
        return outcome
    }
}

class FakeNodeSelectionPort(
    var result: ((String, String) -> NodeSelectionResult)? = null,
) : NodeSelectionPort {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun select(
        group: String,
        outbound: String,
    ): NodeSelectionResult {
        calls.add(group to outbound)
        return result?.invoke(group, outbound)
            ?: NodeSelectionResult(ok = true, group = group, selected = outbound, previous = "node-b")
    }
}

fun sampleNodeSnapshot(): NodeSnapshot =
    NodeSnapshot(
        commandClientConnected = true,
        remoteControl = false,
        groups =
            listOf(
                AgentGroup(
                    tag = "proxy",
                    type = "selector",
                    displayType = "Selector",
                    selectable = true,
                    selected = "node-b",
                    items =
                        listOf(
                            AgentNode("node-a", "shadowsocks", "Shadowsocks", selected = false, urlTestDelayMs = 120),
                            AgentNode("node-b", "vmess", "VMess", selected = true, urlTestDelayMs = null),
                        ),
                ),
            ),
        outbounds =
            listOf(
                AgentNode("node-a", "shadowsocks", "Shadowsocks", selected = false, urlTestDelayMs = 120),
                AgentNode("node-b", "vmess", "VMess", selected = true, urlTestDelayMs = 88),
            ),
    )

fun buildTestRegistry(
    nodeSource: NodeSnapshotSource = FakeNodeSource(),
    deviceSource: DeviceSnapshotSource = FakeDeviceSource(),
    probePort: ProbePort = FakeProbePort(),
    selectionPort: NodeSelectionPort? = null,
    httpFetchPort: HttpFetchPort? = null,
    webSearchPort: WebSearchPort? = null,
    webSearchAvailable: () -> Boolean = { true },
): AgentToolRegistry =
    AgentToolRegistry(
        AgentTools(
            nodeSource = nodeSource,
            deviceSource = deviceSource,
            probePort = probePort,
            selectionPort = selectionPort,
            httpFetchPort = httpFetchPort,
            webSearchPort = webSearchPort,
            webSearchAvailable = webSearchAvailable,
        ).all,
    )
