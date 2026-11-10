package io.nekohasekai.sfa.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeOutcomeClassifierTest {
    @Test
    fun http204CountsAsReachable() {
        val outcome = classifyProbeFailure("HTTP 204 No Content: &{}", 1385)

        assertEquals(204, outcome.statusCode ?: 0)
        assertEquals(1385L, outcome.latencyMs ?: 0L)
        assertNull(outcome.error)
    }

    @Test
    fun httpErrorStatusStillCountsAsReachable() {
        val outcome = classifyProbeFailure("HTTP 502 Bad Gateway", 99)

        assertEquals(502, outcome.statusCode ?: 0)
        assertNull(outcome.error)
    }

    @Test
    fun transportErrorStaysFailure() {
        val outcome = classifyProbeFailure("""Head "https://api.github.com": unexpected EOF""", 1202)

        assertNull(outcome.statusCode)
        assertNotNull(outcome.error)
    }

    @Test
    fun parseStatusReadsLibboxMessages() {
        assertEquals(204, parseProbeHttpStatus("HTTP 204 No Content: &{}") ?: 0)
        assertEquals(502, parseProbeHttpStatus("HTTP 502 Bad Gateway") ?: 0)
        assertNull(parseProbeHttpStatus("unexpected EOF"))
    }
}
