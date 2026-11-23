package io.nekohasekai.sfa.telemetry

import java.util.concurrent.TimeUnit

internal enum class GroupHealthAlertKind {
    NO_AVAILABLE_NODES,
    STUCK_ON_UNHEALTHY,
}

internal data class GroupHealthAlert(
    val group: String,
    val kind: GroupHealthAlertKind,
    val selected: String?,
    /** Null when the snapshot did not include a full item scan. */
    val availableCount: Int?,
    val itemCount: Int,
    /** When the episode began, not when it crossed the reporting threshold. */
    val since: Long,
)

/**
 * Turns successive URLTest group snapshots into the two anomalies that matter:
 * a group with no usable node, and a group that keeps routing through a node
 * that fails its own health check. A single node failing its test is how
 * URLTest chooses a better one, so it is deliberately not an anomaly.
 *
 * Full item summaries are requested only periodically; the selected item's
 * state is present in every snapshot, so stuck detection stays cheap.
 */
internal class GroupHealthMonitor(
    private val stuckAfterMs: Long = TimeUnit.MINUTES.toMillis(10),
    /** Startup snapshots can show zero delays before the first test completes. */
    private val graceMs: Long = TimeUnit.MINUTES.toMillis(1),
    /** Require the zero-available state to survive one more summary. */
    private val noNodesAfterMs: Long = TimeUnit.MINUTES.toMillis(1),
) {
    private class State {
        var firstSeen: Long? = null
        var selected: String? = null
        var downSince: Long? = null
        var downReported: Boolean = false
        var noNodesSince: Long? = null
        var noNodesReported: Boolean = false
    }

    private val states = LinkedHashMap<String, State>()

    fun observe(groups: List<OutboundGroupSelection>, now: Long): List<GroupHealthAlert> {
        val alerts = ArrayList<GroupHealthAlert>()
        val seen = HashSet<String>()
        for (group in groups) {
            if (!group.isUrlTest || group.selected.isBlank()) continue
            seen.add(group.tag)
            val state = states.getOrPut(group.tag) { State() }
            if (state.firstSeen == null) state.firstSeen = now
            val ready = now - state.firstSeen!! >= graceMs
            if (state.selected != null && state.selected != group.selected) {
                state.downSince = null
                state.downReported = false
            }
            state.selected = group.selected

            if (group.hasItemSummary && ready) {
                val noNodes = group.itemCount > 0 && group.availableCount == 0
                if (noNodes) {
                    if (state.noNodesSince == null) state.noNodesSince = now
                    val since = state.noNodesSince!!
                    if (!state.noNodesReported && now - since >= noNodesAfterMs) {
                        state.noNodesReported = true
                        alerts.add(
                            GroupHealthAlert(
                                group = group.tag,
                                kind = GroupHealthAlertKind.NO_AVAILABLE_NODES,
                                selected = group.selected,
                                availableCount = 0,
                                itemCount = group.itemCount,
                                since = since,
                            ),
                        )
                    }
                } else {
                    state.noNodesSince = null
                    state.noNodesReported = false
                }
            }

            // The no-node alert already covers this episode; a second
            // "stuck on the selected node" alert would be duplicate noise.
            if (state.noNodesSince != null) {
                state.downSince = null
                state.downReported = false
                continue
            }
            if (group.selectedAvailable == false) {
                if (state.downSince == null) state.downSince = now
                val since = state.downSince!!
                if (!state.downReported && now - since >= stuckAfterMs) {
                    state.downReported = true
                    alerts.add(
                        GroupHealthAlert(
                            group = group.tag,
                            kind = GroupHealthAlertKind.STUCK_ON_UNHEALTHY,
                            selected = group.selected,
                            availableCount = group.availableCount.takeIf { group.hasItemSummary },
                            itemCount = group.itemCount,
                            since = since,
                        ),
                    )
                }
            } else {
                state.downSince = null
                state.downReported = false
            }
        }
        states.keys.retainAll(seen)
        return alerts
    }

    fun reset() {
        states.clear()
    }
}
