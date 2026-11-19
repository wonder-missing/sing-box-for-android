package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventsScrubTest {
    @Test
    fun scrubKeepsOnlySchemeAndHost() {
        val raw = "GET https://sub.example.com/api/v1/client/subscribe?token=SECRET&flag=1 failed"

        val scrubbed = TelemetryEvents.scrub(raw)

        assertEquals("GET https://sub.example.com/… failed", scrubbed)
        assertFalse(scrubbed.contains("SECRET"))
        assertFalse(scrubbed.contains("/api/v1"))
        assertFalse(scrubbed.contains("token="))
    }

    @Test
    fun scrubHandlesPlainHttpAndMultipleUrls() {
        val scrubbed = TelemetryEvents.scrub(
            "http://one.example/sub?token=a and https://two.example:8443/p?x=y",
        )

        assertEquals("http://one.example/… and https://two.example:8443/…", scrubbed)
        assertFalse(scrubbed.contains("token"))
        assertFalse(scrubbed.contains("x=y"))
    }

    @Test
    fun scrubBoundsTheOutput() {
        val scrubbed = TelemetryEvents.scrub("y".repeat(500), maxChars = 40)

        assertEquals(40, scrubbed.length)
    }

    @Test
    fun urltestEventsCarryWallClockRange() {
        val json = TelemetryEvents.toJson(
            listOf(
                "DEBUG[10] outbound/urltest[YouTube]: outbound node-a unavailable: context deadline exceeded",
                "DEBUG[10] outbound/urltest[YouTube]: outbound node-a unavailable: context deadline exceeded",
            ),
            listOf(1_700_000_000_000L, 1_700_000_060_000L),
        )

        assertTrue(json, json.contains("\"type\":\"urltest_fail\""))
        assertTrue(json, json.contains("\"ts0\":1700000000000"))
        assertTrue(json, json.contains("\"ts1\":1700000060000"))
    }

    @Test
    fun urltestEventsWithoutStampsKeepTheExistingShape() {
        val json = TelemetryEvents.toJson(
            listOf("DEBUG[10] outbound/urltest[YouTube]: outbound node-a available: 120ms"),
        )

        assertTrue(json, json.contains("\"type\":\"urltest_ok\""))
        assertFalse(json, json.contains("\"ts0\""))
        assertFalse(json, json.contains("\"ts1\""))
    }

    @Test
    fun aggregatedErrorEventsDoNotLeakSubscriptionPathsOrTokens() {
        val subscription = "https://sub.example.com/api/v1/client/subscribe?token=SECRET"
        val json = TelemetryEvents.toJson(
            listOf("ERROR[0000] dial $subscription: i/o timeout"),
        )

        assertTrue(json, json.contains("\"type\":\"error\""))
        assertTrue(json, json.contains("sub.example.com"))
        assertFalse(json, json.contains("SECRET"))
        assertFalse(json, json.contains("/api/v1"))
        assertFalse(json, json.contains("token="))
    }
}
