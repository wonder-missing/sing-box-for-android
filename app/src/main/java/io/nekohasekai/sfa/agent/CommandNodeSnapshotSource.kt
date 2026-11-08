package io.nekohasekai.sfa.agent

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem

data class CommandNodeState(
    val connected: Boolean = false,
    val groups: List<AgentGroup> = emptyList(),
    val outbounds: List<AgentNode> = emptyList(),
    val error: String? = null,
)

/**
 * Reads the cache maintained by the conductor runtime's CommandClient. This reuses the
 * same Groups/Outbounds commands as the dashboard, locally and under Remote
 * Control, instead of opening a parallel query channel.
 */
class CommandNodeSnapshotSource(
    private val state: () -> CommandNodeState,
    private val remoteControl: () -> Boolean,
) : NodeSnapshotSource {
    override fun snapshot(): NodeSnapshot {
        val current = state()
        return NodeSnapshot(
            coreRunning = current.connected,
            remoteControl = remoteControl(),
            groups = current.groups,
            outbounds = current.outbounds,
            error = current.error,
        )
    }
}

internal fun buildCommandNodeState(
    connected: Boolean,
    groups: List<OutboundGroup>,
    outbounds: List<OutboundGroupItem>,
    error: String? = null,
): CommandNodeState {
    val selectedTags = groups.map { it.selected }.filter { it.isNotBlank() }.toSet()
    val agentGroups = groups.map { it.toAgentGroup() }
    val agentOutbounds = outbounds.map { it.toAgentNode(selected = it.tag in selectedTags) }
    return CommandNodeState(
        connected = connected,
        groups = agentGroups,
        outbounds = agentOutbounds,
        error = error,
    )
}

private fun OutboundGroup.toAgentGroup(): AgentGroup {
    val agentItems = mutableListOf<AgentNode>()
    val iterator = items
    while (iterator.hasNext()) {
        val item = iterator.next()
        agentItems.add(item.toAgentNode(selected = item.tag == selected))
    }
    return AgentGroup(
        tag = tag,
        type = type,
        displayType = Libbox.proxyDisplayType(type),
        selectable = selectable,
        selected = selected,
        items = agentItems,
    )
}

private fun OutboundGroupItem.toAgentNode(selected: Boolean): AgentNode =
    AgentNode(
        tag = tag,
        type = type,
        displayType = Libbox.proxyDisplayType(type),
        selected = selected,
        urlTestDelayMs = urlTestDelay.takeIf { it > 0 },
    )
