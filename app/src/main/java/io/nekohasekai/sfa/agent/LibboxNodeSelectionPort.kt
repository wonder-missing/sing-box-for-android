package io.nekohasekai.sfa.agent

import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-only selector change. The previous selection is restored if the
 * CommandClient call throws; callers must still confirm the change.
 */
class LibboxNodeSelectionPort(
    private val nodeSource: NodeSnapshotSource,
) : NodeSelectionPort {
    override suspend fun select(
        group: String,
        outbound: String,
    ): NodeSelectionResult =
        withContext(Dispatchers.IO) {
            val snapshot = nodeSource.snapshot()
            if (!snapshot.coreRunning) {
                return@withContext NodeSelectionResult(
                    ok = false,
                    group = group,
                    selected = outbound,
                    error = "sing-box core is not running; start the service before changing the outbound",
                )
            }
            if (snapshot.remoteControl) {
                return@withContext NodeSelectionResult(
                    ok = false,
                    group = group,
                    selected = outbound,
                    error = "select_outbound is local-only; Remote Control does not expose outbound selection",
                )
            }
            val target =
                snapshot.groups.firstOrNull { candidate ->
                    candidate.tag == group && candidate.selectable && candidate.items.any { it.tag == outbound }
                } ?: return@withContext NodeSelectionResult(
                    ok = false,
                    group = group,
                    selected = outbound,
                    error = "outbound '$outbound' is not in selectable group '$group'",
                )
            val previous = target.selected.takeIf { it.isNotBlank() }
            try {
                CommandTarget.standaloneClient().selectOutbound(group, outbound)
                NodeSelectionResult(ok = true, group = group, selected = outbound, previous = previous)
            } catch (e: CancellationException) {
                restore(group, previous)
                throw e
            } catch (e: Exception) {
                restore(group, previous)
                NodeSelectionResult(
                    ok = false,
                    group = group,
                    selected = outbound,
                    previous = previous,
                    error = e.message ?: "select_outbound failed",
                )
            }
        }

    private fun restore(
        group: String,
        previous: String?,
    ) {
        if (previous.isNullOrBlank()) return
        runCatching { CommandTarget.standaloneClient().selectOutbound(group, previous) }
    }
}
