package io.nekohasekai.sfa.agent

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Local-only text fetch through the running core's HTTP client. It deliberately
 * shares the probe's temporary-selection and restore semantics, so a named
 * outbound changes global app routing for the duration of the request.
 *
 * The libbox wrapper has no response-status or header API. A non-200 response
 * arrives as an exception containing "HTTP <code>", which
 * [classifyProbeFailure] already parses; those responses have no body here.
 */
class LibboxHttpFetchPort(
    private val nodeSource: NodeSnapshotSource,
    private val appTrafficUsesVpn: () -> Boolean? = { null },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : HttpFetchPort {
    override fun fetch(
        outbound: String?,
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpFetchOutcome {
        if (cancellation?.isCancelled() == true) return HttpFetchOutcome(error = "cancelled")
        val snapshot = nodeSource.snapshot()
        if (!snapshot.commandClientConnected) {
            return HttpFetchOutcome(error = "sing-box core is not reachable; start the service before fetching")
        }
        if (snapshot.remoteControl) {
            return HttpFetchOutcome(
                error =
                    "http_fetch is local-only: Remote Control does not expose an outbound-bound " +
                        "HTTP client, and this device's HTTP traffic does not pass through the remote core",
            )
        }
        val group =
            if (outbound == null) {
                null
            } else {
                if (appTrafficUsesVpn() != true) {
                    return HttpFetchOutcome(
                        error =
                            "this app's HTTP traffic is not routed through the local core (the service must " +
                                "be running in VPN mode); refusing to fetch through a named outbound",
                    )
                }
                val selectable =
                    snapshot.groups.firstOrNull { candidate ->
                        candidate.selectable && candidate.items.any { it.tag == outbound }
                    } ?: return HttpFetchOutcome(
                        error = "outbound '$outbound' is not a selectable node in any outbound group",
                    )
                if (selectable.selected.isBlank()) {
                    return HttpFetchOutcome(
                        error =
                            "outbound group '${selectable.tag}' has no current selection to restore; " +
                                "refusing to change its route",
                    )
                }
                selectable
            }

        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "agent-http-fetch").apply { isDaemon = true }
            }
        cancellation?.onCancel { executor.shutdownNow() }
        if (cancellation?.isCancelled() == true) {
            executor.shutdownNow()
            return HttpFetchOutcome(error = "cancelled")
        }
        return try {
            executor
                .submit<HttpFetchOutcome> {
                    runFetch(
                        group,
                        outbound,
                        url,
                        method,
                        headers,
                        body,
                        maxBytes.coerceIn(1, MAX_BYTES),
                        cancellation,
                    )
                }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            restoreSelection(group)
            HttpFetchOutcome(error = "http_fetch timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            restoreSelection(group)
            HttpFetchOutcome(error = "cancelled")
        } catch (e: ExecutionException) {
            restoreSelection(group)
            HttpFetchOutcome(error = e.cause?.message ?: e.message ?: "http_fetch failed")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            HttpFetchOutcome(error = "http_fetch interrupted")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runFetch(
        group: AgentGroup?,
        outbound: String?,
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpFetchOutcome {
        val client = CommandTarget.standaloneClient()
        var selectionChanged = false
        try {
            if (cancellation?.isCancelled() == true) return HttpFetchOutcome(error = "cancelled")
            if (group != null && outbound != null && group.selected != outbound) {
                client.selectOutbound(group.tag, outbound)
                selectionChanged = true
            }
            val http = Libbox.newHTTPClient()
            cancellation?.onCancel { runCatching { http.close() } }
            try {
                if (cancellation?.isCancelled() == true) return HttpFetchOutcome(error = "cancelled")
                val request = http.newRequest()
                request.setURL(url)
                request.setUserAgent(HTTPClient.userAgent)
                request.setMethod(method)
                headers.forEach { (name, value) -> request.setHeader(name, value) }
                body?.let { request.setContentString(it) }
                val started = System.nanoTime()
                return try {
                    val response = request.execute()
                    val content = response.content.unwrap
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    HttpFetchOutcome(
                        statusCode = 200,
                        latencyMs = elapsedMs(started),
                        content = truncateUtf8(bytes, maxBytes),
                        bytes = bytes.size,
                        truncated = bytes.size > maxBytes,
                        via = if (group == null) "current_selection" else "temporary_outbound_selection",
                    )
                } catch (e: Exception) {
                    val latency = elapsedMs(started)
                    if (cancellation?.isCancelled() == true) {
                        HttpFetchOutcome(latencyMs = latency, error = "cancelled")
                    } else {
                        val classified = classifyProbeFailure(e.message, latency)
                        if (classified.statusCode != null) {
                            HttpFetchOutcome(
                                statusCode = classified.statusCode,
                                latencyMs = latency,
                                via = if (group == null) "current_selection" else "temporary_outbound_selection",
                            )
                        } else {
                            HttpFetchOutcome(
                                latencyMs = latency,
                                via = if (group == null) "current_selection" else "temporary_outbound_selection",
                                error = classified.error,
                            )
                        }
                    }
                }
            } finally {
                runCatching { http.close() }
            }
        } catch (e: Exception) {
            return if (cancellation?.isCancelled() == true) {
                HttpFetchOutcome(error = "cancelled")
            } else {
                HttpFetchOutcome(error = e.message ?: e.toString())
            }
        } finally {
            if (selectionChanged) {
                restoreSelection(group)
            }
        }
    }

    private fun restoreSelection(group: AgentGroup?) {
        val previous = group?.selected?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            CommandTarget.standaloneClient().selectOutbound(group.tag, previous)
        }
    }

    private fun truncateUtf8(
        bytes: ByteArray,
        maxBytes: Int,
    ): String {
        if (bytes.size <= maxBytes) return String(bytes, Charsets.UTF_8)
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end -= 1
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val MAX_BYTES = 256 * 1024
    }
}
