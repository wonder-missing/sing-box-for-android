package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventLogTest {
    @Test
    fun `upload diagnostics are excluded from drain pending checks`() {
        assertTrue(
            TelemetryEventLog.isUploadDiagnosticEvent(
                """{"type":"telemetry_upload_ok","ts":1,"path":"direct"}""",
            ),
        )
        assertTrue(
            TelemetryEventLog.isUploadDiagnosticEvent(
                """{"type":"telemetry_upload_failed","ts":1,"status":500}""",
            ),
        )
        assertFalse(
            TelemetryEventLog.isUploadDiagnosticEvent(
                """{"type":"network_change","ts":1,"network":"wifi"}""",
            ),
        )
    }
}
