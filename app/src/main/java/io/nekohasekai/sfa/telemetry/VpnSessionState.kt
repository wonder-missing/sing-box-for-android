package io.nekohasekai.sfa.telemetry

import java.util.UUID

/**
 * One short id per successful VPN service session. It is attached to the
 * service/selection/upload events recorded while the session is live, so
 * background analysis can group them without any device or subscription data.
 */
internal class VpnSessionState(
    private val idFactory: () -> String = { defaultId() },
) {
    @Volatile
    private var value: String? = null

    val current: String?
        get() = value

    @Synchronized
    fun start(): String {
        val next = idFactory()
        value = next
        return next
    }

    @Synchronized
    fun stop() {
        value = null
    }

    companion object {
        const val ID_LENGTH = 12

        /** UUID with dashes removed, truncated to 12 hex chars. */
        fun defaultId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)
    }
}
