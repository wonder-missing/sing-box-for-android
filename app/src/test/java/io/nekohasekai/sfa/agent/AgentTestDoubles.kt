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
            vpnActive = true,
            serviceMode = "vpn",
            batteryOptimizationIgnored = true,
            powerSave = false,
            doze = false,
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
    ): ProbeOutcome {
        calls.add(outbound to url)
        return outcome
    }
}

fun sampleNodeSnapshot(): NodeSnapshot =
    NodeSnapshot(
        coreRunning = true,
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
    probePort: FakeProbePort = FakeProbePort(),
): AgentToolRegistry =
    AgentToolRegistry(
        AgentTools(
            nodeSource = nodeSource,
            deviceSource = deviceSource,
            probePort = probePort,
        ).all,
    )
