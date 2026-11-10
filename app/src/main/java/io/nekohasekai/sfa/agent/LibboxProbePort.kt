package io.nekohasekai.sfa.agent

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Local-only probe. It reuses the existing CommandClient to select the node and
 * the existing libbox HTTP client for the request; no second tunnel or dialer
 * is introduced.
 *
 * Remote Control exposes commands, not an outbound-bound HTTP client, and this
 * process' HTTP traffic does not pass through the remote core, so remote mode
 * is rejected instead of returning a misleading direct result.
 */
class LibboxProbePort(
    private val nodeSource: NodeSnapshotSource,
    private val appTrafficUsesVpn: () -> Boolean? = { null },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ProbePort {
    override fun probe(
        outbound: String,
        url: String,
        cancellation: ToolCancellation?,
    ): ProbeOutcome {
        if (cancellation?.isCancelled() == true) return ProbeOutcome(error = "cancelled")
        val snapshot = nodeSource.snapshot()
        if (!snapshot.coreRunning) {
            return ProbeOutcome(error = "sing-box core is not running; start the service before probing")
        }
        if (snapshot.remoteControl) {
            return ProbeOutcome(
                error =
                    "probe_via_node is local-only: Remote Control does not expose an outbound-bound " +
                        "HTTP client, and this device's HTTP traffic does not pass through the remote core",
            )
        }
        if (appTrafficUsesVpn() != true) {
            return ProbeOutcome(
                error =
                    "this app's HTTP traffic is not routed through the local core (the service must " +
                        "be running in VPN mode); refusing to probe without the selected node",
            )
        }
        val group =
            snapshot.groups.firstOrNull { group ->
                group.selectable && group.items.any { it.tag == outbound }
            } ?: return ProbeOutcome(
                error = "outbound '$outbound' is not a selectable node in any outbound group",
            )
        if (group.selected.isBlank()) {
            return ProbeOutcome(
                error = "outbound group '${group.tag}' has no current selection to restore; refusing to change its route",
            )
        }

        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "agent-probe").apply { isDaemon = true }
            }
        cancellation?.onCancel { executor.shutdownNow() }
        if (cancellation?.isCancelled() == true) {
            executor.shutdownNow()
            return ProbeOutcome(error = "cancelled")
        }
        return try {
            executor
                .submit<ProbeOutcome> { runProbe(group, outbound, url, cancellation) }
                .get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Best effort: the worker may still be blocked in the Go HTTP call.
            restoreSelection(group.tag, group.selected)
            ProbeOutcome(error = "probe timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            restoreSelection(group.tag, group.selected)
            ProbeOutcome(error = "cancelled")
        } catch (e: ExecutionException) {
            ProbeOutcome(error = e.cause?.message ?: e.message ?: "probe failed")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ProbeOutcome(error = "probe interrupted")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runProbe(
        group: AgentGroup,
        outbound: String,
        url: String,
        cancellation: ToolCancellation?,
    ): ProbeOutcome {
        val client = CommandTarget.standaloneClient()
        var selectionChanged = false
        try {
            if (cancellation?.isCancelled() == true) return ProbeOutcome(error = "cancelled")
            if (group.selected != outbound) {
                client.selectOutbound(group.tag, outbound)
                selectionChanged = true
            }
            val http = Libbox.newHTTPClient()
            cancellation?.onCancel { runCatching { http.close() } }
            try {
                if (cancellation?.isCancelled() == true) return ProbeOutcome(error = "cancelled")
                val request = http.newRequest()
                request.setURL(url)
                request.setUserAgent(HTTPClient.userAgent)
                request.setMethod("HEAD")
                val started = System.nanoTime()
                return try {
                    request.execute()
                    ProbeOutcome(statusCode = 200, latencyMs = elapsedMs(started))
                } catch (e: Exception) {
                    if (cancellation?.isCancelled() == true) {
                        ProbeOutcome(error = "cancelled")
                    } else {
                        classifyProbeFailure(e.message, elapsedMs(started))
                    }
                }
            } finally {
                runCatching { http.close() }
            }
        } catch (e: Exception) {
            return if (cancellation?.isCancelled() == true) {
                ProbeOutcome(error = "cancelled")
            } else {
                ProbeOutcome(error = e.message ?: e.toString())
            }
        } finally {
            if (selectionChanged) {
                restoreSelection(group.tag, group.selected)
            }
        }
    }

    private fun restoreSelection(
        groupTag: String,
        previous: String,
    ) {
        if (previous.isBlank()) {
            return
        }
        runCatching {
            CommandTarget.standaloneClient().selectOutbound(groupTag, previous)
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
