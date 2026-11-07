package io.nekohasekai.sfa.telemetry

/**
 * libbox-free view of one outbound group. Keeping it independent of
 * `io.nekohasekai.libbox` lets the selection logic run in JVM unit tests.
 */
internal data class OutboundGroupSelection(
    val tag: String,
    val type: String,
    val selectable: Boolean,
    val selected: String,
    val selectedDelayMs: Int? = null,
    val itemTags: List<String> = emptyList(),
)

internal enum class SwitchReason(
    val wireValue: String,
) {
    /** Initial selection seen when a session first receives a Groups snapshot. */
    START("start"),

    /** Picked by the core's URL-test (automatic). */
    URLTEST("urltest"),

    /** Picked through the UI / Remote Control on a selectable group. */
    USER("user"),

    UNKNOWN("unknown"),
}

internal data class SelectionChange(
    val group: String,
    val from: String?,
    val to: String,
    val reason: SwitchReason,
    val root: Boolean,
    val ms: Int? = null,
)

/**
 * Diffs successive Groups snapshots into `outbound_selected` events.
 *
 * The first snapshot of a session reports `start` for every group that has a
 * selection (nested groups included: a selector may point at an urltest group,
 * and the leaf tag is what actually carries traffic). Later snapshots only
 * report groups whose selected tag changed, so periodic Groups pushes do not
 * flood the event ring.
 *
 * Reason is best effort: URL-test groups switch automatically, changes inside a
 * selectable/selector group can only come from the UI or Remote Control, and
 * anything else is left as `unknown` rather than guessed.
 */
internal class OutboundSelectionTracker {
    private val lastSelected = LinkedHashMap<String, String>()

    fun update(groups: List<OutboundGroupSelection>): List<SelectionChange> {
        val childTags = HashSet<String>()
        for (group in groups) childTags.addAll(group.itemTags)
        val changes = ArrayList<SelectionChange>(groups.size)
        for (group in groups) {
            val selected = group.selected
            if (selected.isBlank()) continue
            val previous = lastSelected[group.tag]
            if (previous == selected) continue
            lastSelected[group.tag] = selected
            val root = group.tag !in childTags
            val reason = if (previous == null) SwitchReason.START else reasonFor(group)
            changes.add(
                SelectionChange(
                    group = group.tag,
                    from = previous,
                    to = selected,
                    reason = reason,
                    root = root,
                    ms = group.selectedDelayMs,
                ),
            )
        }
        return changes
    }

    /** Called when a new VPN session starts so the next snapshot speaks again. */
    fun reset() {
        lastSelected.clear()
    }

    private fun reasonFor(group: OutboundGroupSelection): SwitchReason = when {
        group.type.contains("urltest", ignoreCase = true) -> SwitchReason.URLTEST
        group.type.equals("selector", ignoreCase = true) || group.selectable -> SwitchReason.USER
        else -> SwitchReason.UNKNOWN
    }
}
