package io.nekohasekai.sfa.telemetry

import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox

/**
 * Copies the fields the minute aggregator needs. Domains are not read.
 * The connection id in an ERROR log is not the same id, so failures are
 * joined later by outbound and time, not by this id.
 */
internal fun ConnectionEvents.collectSamples(): List<ConnSample> {
    val out = ArrayList<ConnSample>()
    val wasReset = reset
    val iterator = runCatching { iterator() }.getOrNull()
    if (iterator == null) {
        if (wasReset) out.add(ConnSample(reset = true, kind = ConnKind.OTHER, id = ""))
        return out
    }
    var first = true
    while (iterator.hasNext()) {
        val event = iterator.next() ?: continue
        val conn = event.connection
        val kind = when (event.type.toInt()) {
            Libbox.ConnectionEventNew.toInt() -> ConnKind.NEW
            Libbox.ConnectionEventUpdate.toInt() -> ConnKind.UPDATE
            Libbox.ConnectionEventClosed.toInt() -> ConnKind.CLOSED
            else -> ConnKind.OTHER
        }
        val closedAt = when {
            event.closedAt > 0 -> event.closedAt
            conn != null && conn.closedAt > 0 -> conn.closedAt
            else -> 0L
        }
        out.add(
            ConnSample(
                reset = wasReset && first,
                kind = kind,
                id = event.id.orEmpty(),
                outbound = conn?.outbound.orEmpty(),
                outboundType = conn?.outboundType.orEmpty(),
                network = conn?.network.orEmpty(),
                destination = conn?.destination.orEmpty(),
                protocol = conn?.protocol.orEmpty(),
                createdAt = conn?.createdAt ?: 0L,
                closedAt = closedAt,
                uplinkDelta = event.uplinkDelta,
                downlinkDelta = event.downlinkDelta,
                uplinkTotal = conn?.uplinkTotal ?: 0L,
                downlinkTotal = conn?.downlinkTotal ?: 0L,
            ),
        )
        first = false
    }
    if (out.isEmpty() && wasReset) {
        out.add(ConnSample(reset = true, kind = ConnKind.OTHER, id = ""))
    }
    return out
}
