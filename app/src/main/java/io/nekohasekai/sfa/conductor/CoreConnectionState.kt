package io.nekohasekai.sfa.conductor

/**
 * Connection decision for the process-scoped Conductor CommandClient.
 *
 * The service can stay "started" while the core replaces its command socket
 * (for example after a service restart), so [onServiceState] must not treat
 * "the service is still started" as "the command channel is still alive".
 * Callers should keep calling [onServiceState] while the Conductor screen is
 * visible; the retry interval prevents a failed dial from hammering.
 */
internal class CoreConnectionState(
    private val connect: () -> Unit,
    private val disconnect: () -> Unit,
    private val isConnected: () -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
) {
    private var wantConnected = false
    private var lastConnectAt: Long? = null

    fun onServiceState(shouldConnect: Boolean) {
        if (!shouldConnect) {
            val wasWanted = wantConnected
            wantConnected = false
            lastConnectAt = null
            if (wasWanted) disconnect()
            return
        }
        wantConnected = true
        if (isConnected()) return
        val previous = lastConnectAt
        val now = nowMs()
        if (previous != null && now - previous < retryIntervalMs) return
        lastConnectAt = now
        connect()
    }

    companion object {
        const val DEFAULT_RETRY_INTERVAL_MS = 5_000L
    }
}
