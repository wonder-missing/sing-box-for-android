package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMinuteTest {
    private val now = 1_000_000_000_000L

    @Test
    fun `chain is carried into the conn_minute row`() {
        val aggregator = ConnectionMinuteAggregator()
        aggregator.observe(
            ConnSample(
                kind = ConnKind.NEW,
                id = "c1",
                outbound = "M-日本JP-HY2",
                outboundType = "hysteria2",
                network = "tcp",
                destination = "1.2.3.4:443",
                protocol = "tls",
                chain = "Google>M-日本JP-HY2",
                createdAt = now,
            ),
            now,
        )
        aggregator.observe(
            ConnSample(kind = ConnKind.UPDATE, id = "c1", downlinkDelta = 16),
            now + 1_000,
        )
        aggregator.observe(
            ConnSample(kind = ConnKind.CLOSED, id = "c1", closedAt = now + 2_000, downlinkTotal = 16),
            now + 3_000,
        )

        val flushed = aggregator.flush(now + 60_000, includeCurrent = true)
        assertEquals(1, flushed.size)
        assertTrue(flushed.single().rowsJson.contains("\"chain\":\"Google>M-日本JP-HY2\""))
        assertTrue(flushed.single().rowsJson.contains("\"down\":true"))
    }

    @Test
    fun `bounded chain keeps four tags and drops blanks`() {
        assertEquals(
            "Google>a>b>c",
            boundedChain(listOf("Google", " ", "a", "b", "c", "d")),
        )
        assertEquals("", boundedChain(null))
    }
}
