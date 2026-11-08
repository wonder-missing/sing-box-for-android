package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryManualUploadMessageTest {
    @Test
    fun `reports busy when the deadline leaves work pending`() {
        val message =
            TelemetryRuntime.manualUploadMessage(
                TelemetryRuntime.UploadOutcome(
                    batches = 0,
                    lines = 0,
                    crashReports = 0,
                    hadPending = true,
                    failure = null,
                    morePending = true,
                ),
            )

        assertTrue(message.contains("busy or timed out"))
    }

    @Test
    fun `reports partial success with remaining work`() {
        val message =
            TelemetryRuntime.manualUploadMessage(
                TelemetryRuntime.UploadOutcome(
                    batches = 1,
                    lines = 500,
                    crashReports = 0,
                    hadPending = true,
                    failure = "HTTP 500",
                    morePending = true,
                ),
            )

        assertTrue(message.contains("Uploaded 500 lines"))
        assertTrue(message.contains("HTTP 500"))
        assertTrue(message.contains("More pending"))
    }

    @Test
    fun `reports uploaded lines and crashes`() {
        val message =
            TelemetryRuntime.manualUploadMessage(
                TelemetryRuntime.UploadOutcome(
                    batches = 2,
                    lines = 1000,
                    crashReports = 1,
                    hadPending = true,
                    failure = null,
                    morePending = true,
                ),
            )

        assertTrue(message.contains("1000 log lines"))
        assertTrue(message.contains("1 crash report(s)"))
        assertTrue(message.contains("More pending"))
    }

    @Test
    fun `reports nothing pending`() {
        val message =
            TelemetryRuntime.manualUploadMessage(
                TelemetryRuntime.UploadOutcome(
                    batches = 0,
                    lines = 0,
                    crashReports = 0,
                    hadPending = false,
                    failure = null,
                ),
            )

        assertEquals("Nothing pending.", message)
    }

    @Test
    fun `reports failure when nothing was uploaded`() {
        val message =
            TelemetryRuntime.manualUploadMessage(
                TelemetryRuntime.UploadOutcome(
                    batches = 0,
                    lines = 0,
                    crashReports = 0,
                    hadPending = true,
                    failure = "HTTP 401: token rejected",
                ),
            )

        assertTrue(message.contains("Upload failed"))
        assertTrue(message.contains("401"))
    }
}
