package io.nekohasekai.sfa.activity

import android.app.ApplicationExitInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {
    private val now = 1_000_000_000_000L

    private fun event(
        ts: Long,
        lane: ActivityLane,
        type: String,
        group: String? = null,
        ok: Boolean? = true,
        obstacle: Boolean = false,
        count: Int = 1,
    ) = ActivityEvent(
        ts = ts,
        lane = lane,
        type = type,
        group = group,
        ok = ok,
        obstacle = obstacle,
        count = count,
    )

    @Test
    fun `telemetry runs in one five minute bucket coalesce`() {
        val events = mutableListOf<ActivityEvent>()
        ActivityReducer.add(events, event(now - 4_000, ActivityLane.TELEMETRY, "telemetry_upload"), now)
        ActivityReducer.add(events, event(now - 2_000, ActivityLane.TELEMETRY, "telemetry_upload"), now)
        ActivityReducer.add(
            events,
            event(now - 1_000, ActivityLane.TELEMETRY, "telemetry_upload", ok = false, obstacle = true),
            now,
        )

        assertEquals(1, events.size)
        assertEquals(3, events.single().count)
        assertEquals(false, events.single().ok)
        assertTrue(events.single().obstacle)
    }

    @Test
    fun `different url test groups and buckets stay separate`() {
        val events = mutableListOf<ActivityEvent>()
        ActivityReducer.add(events, event(now - 4_000, ActivityLane.URLTEST, "urltest", group = "auto"), now)
        ActivityReducer.add(events, event(now - 3_000, ActivityLane.URLTEST, "urltest", group = "proxy"), now)
        ActivityReducer.add(events, event(now - 6 * 60_000, ActivityLane.URLTEST, "urltest", group = "auto"), now)

        assertEquals(3, events.size)
        assertEquals(2, events.count { it.group == "auto" })
        assertEquals(1, events.count { it.group == "proxy" })
    }

    @Test
    fun `connection errors merge inside their five minute bucket`() {
        val events = mutableListOf<ActivityEvent>()
        ActivityReducer.add(
            events,
            ActivityEvent(
                ts = now - 4_000,
                lane = ActivityLane.CONNECTION,
                type = ActivityTypes.CONNECTION_ERROR,
                group = "timeout",
                severity = ActivitySeverity.WARN,
                obstacle = true,
                count = 1,
            ),
            now,
        )
        ActivityReducer.add(
            events,
            ActivityEvent(
                ts = now - 1_000,
                lane = ActivityLane.CONNECTION,
                type = ActivityTypes.CONNECTION_ERROR,
                group = "timeout",
                severity = ActivitySeverity.ERROR,
                obstacle = true,
                count = 2,
            ),
            now,
        )

        assertEquals(1, events.size)
        assertEquals(3, events.single().count)
        assertEquals(ActivitySeverity.ERROR, events.single().severity)
        assertTrue(events.single().obstacle)
    }

    @Test
    fun `evidence survives a coalesced merge`() {
        val first =
            ActivityEvent(
                ts = now,
                lane = ActivityLane.SYSTEM,
                type = ActivityTypes.CRASH,
                evidence = ActivityEvidence(ActivityEvidenceKind.CRASH, "report-1"),
            )
        val merged = first.merge(first.copy(evidence = null, severity = ActivitySeverity.ERROR))

        assertEquals(ActivityEvidence(ActivityEvidenceKind.CRASH, "report-1"), merged.evidence)
        assertEquals(ActivitySeverity.ERROR, merged.severity)
    }

    @Test
    fun `process exit reasons map to stable names`() {
        assertEquals("anr", ProcessExitRecorder.reasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("low_memory", ProcessExitRecorder.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("native_crash", ProcessExitRecorder.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("reason_999", ProcessExitRecorder.reasonName(999))
    }

    @Test
    fun `power labels name the state a user notices first`() {
        assertEquals("doze", powerStateLabel(doze = true, powerSave = false, interactive = false))
        assertEquals("screen off", powerStateLabel(doze = false, powerSave = true, interactive = false))
        assertEquals("power save", powerStateLabel(doze = false, powerSave = true, interactive = true))
        assertEquals("awake", powerStateLabel(doze = false, powerSave = false, interactive = true))
    }

    @Test
    fun `retention drops events older than forty eight hours`() {
        val events = mutableListOf<ActivityEvent>()
        ActivityReducer.add(events, event(now - ActivityReducer.RETENTION_MS - 1, ActivityLane.NETWORK, "network_change"), now)
        ActivityReducer.add(events, event(now - 1_000, ActivityLane.NETWORK, "network_change"), now)

        assertEquals(1, events.size)
    }

    @Test
    fun `window keeps only the selected twenty four hours`() {
        val old = event(now - 25 * 60 * 60_000L, ActivityLane.SERVICE, "service_start")
        val current = event(now - 60_000, ActivityLane.SERVICE, "service_start")
        val future = event(now + 10 * 60_000, ActivityLane.SERVICE, "service_start")

        val windowed = ActivityReducer.window(listOf(old, current, future), now - 24 * 60 * 60_000L, now)

        assertEquals(listOf(current), windowed)
    }

    @Test
    fun `event round trips through json`() {
        val original =
            ActivityEvent(
                id = 7,
                ts = now,
                lane = ActivityLane.GROUP,
                type = "group_switch",
                group = "proxy",
                from = "HK-01",
                to = "JP-02",
                reason = "urltest",
                ms = 180,
                available = 7,
                total = 8,
                ok = true,
            )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(ActivityEvent.serializer(), original)
        val decoded = json.decodeFromString(ActivityEvent.serializer(), encoded)

        assertEquals(original, decoded)
        assertFalse(decoded.obstacle)
    }
}
