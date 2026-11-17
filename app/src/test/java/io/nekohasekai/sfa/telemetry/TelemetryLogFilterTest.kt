package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryLogFilterTest {
    @Test
    fun `drops verbose tailscale trace lines`() {
        assertFalse(
            TelemetryLogFilter.shouldUpload(
                "TRACE[0883] endpoint/tailscale[tailscale]: netcheck: [v1] report: udp=true derpdist=5v6:195ms",
            ),
        )
    }

    @Test
    fun `keeps tailscale state lines that feed ts events`() {
        assertTrue(
            TelemetryLogFilter.shouldUpload(
                "INFO[0001] endpoint/tailscale[tailscale]: state: Running",
            ),
        )
    }

    @Test
    fun `keeps unrelated trace lines`() {
        assertTrue(
            TelemetryLogFilter.shouldUpload(
                "TRACE[0001] inbound/tun[tun-in]: inbound connection",
            ),
        )
    }
}
