package io.nekohasekai.sfa.telemetry

/**
 * Wall-clock stamps for sing-box log lines. The line itself only carries core
 * uptime seconds (`INFO[123]`). Stamps stay in memory: the on-disk queue is
 * unchanged, and lines restored after a process death have no event time.
 *
 * Within one callback the newest uptime is anchored at [receivedAt]. Older
 * lines in that callback are `receivedAt - (max - n) seconds`. A backwards
 * jump (core restart) starts a new anchor.
 */
internal class TelemetryLogClock {
    private val head = Regex("^([A-Z]+)\\[(\\d+)]")
    private var anchorUptime: Long? = null
    private var anchorWall: Long? = null

    fun reset() {
        synchronized(this) {
            anchorUptime = null
            anchorWall = null
        }
    }

    fun stamp(lines: List<String>, receivedAt: Long): List<Long?> = synchronized(this) {
        val uptimes = lines.map { uptimeOf(it) }
        val maxN = uptimes.maxOrNullSkipNull()
        if (maxN != null) {
            // This callback's newest uptime is "now", including after a core
            // restart that sends the counter backwards.
            anchorUptime = maxN
            anchorWall = receivedAt
        }
        val baseN = anchorUptime ?: return lines.map { null }
        val baseW = anchorWall ?: return lines.map { null }
        uptimes.map { n ->
            if (n == null) null else baseW - (baseN - n) * 1000L
        }
    }

    private fun uptimeOf(line: String): Long? {
        val match = head.find(line) ?: return null
        return match.groupValues[2].toLongOrNull()
    }

    private fun List<Long?>.maxOrNullSkipNull(): Long? {
        var max: Long? = null
        for (value in this) {
            if (value != null && (max == null || value > max)) max = value
        }
        return max
    }
}
