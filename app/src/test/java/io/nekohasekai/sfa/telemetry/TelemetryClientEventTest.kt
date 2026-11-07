package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryClientEventTest {
    @Test
    fun sessionIdIsAppendedWhileTheSessionIsLive() {
        val json = TelemetryClientEvent.encode(
            type = "service_start",
            ts = 123,
            fields = mapOf("app" to "1.15.0-wonder.10", "sdk" to 35),
            sessionId = "0123456789ab",
        )

        assertTrue(json.contains("\"type\":\"service_start\""))
        assertTrue(json.contains("\"ts\":123"))
        assertTrue(json.contains("\"vpn_session_id\":\"0123456789ab\""))
        assertTrue(json.contains("\"app\":\"1.15.0-wonder.10\""))
        assertTrue(json.contains("\"sdk\":35"))
    }

    @Test
    fun detachedEventsHaveNoSessionField() {
        val json = TelemetryClientEvent.encode("network_lost", 1, emptyMap(), sessionId = null)

        assertFalse(json.contains("vpn_session_id"))
    }

    @Test
    fun explicitSessionFieldIsNotDuplicated() {
        val json = TelemetryClientEvent.encode(
            type = "telemetry_upload_ok",
            ts = 1,
            fields = mapOf(TelemetryClientEvent.SESSION_FIELD to "from-tag"),
            sessionId = "live-session",
        )

        assertEquals(1, Regex("\"vpn_session_id\"").findAll(json).count())
        assertTrue(json.contains("\"vpn_session_id\":\"from-tag\""))
        assertFalse(json.contains("live-session"))
    }

    @Test
    fun nullFieldsAreSkippedAndValuesAreBounded() {
        val longValue = "x".repeat(500)
        val json = TelemetryClientEvent.encode(
            type = "outbound_selected",
            ts = 1,
            fields = mapOf("from" to null, "to" to "node-a", "reason" to "urltest", "long" to longValue),
            sessionId = null,
        )

        assertFalse(json, json.contains("\"from\""))
        assertTrue(json, json.contains("\"to\":\"node-a\""))
        assertTrue(json, json.contains("\"reason\":\"urltest\""))
        val encodedLong = json.substringAfter("\"long\":\"").substringBefore('"')
        assertEquals(json, 200, encodedLong.length)
        assertFalse(json, json.contains("x".repeat(201)))
    }
}
