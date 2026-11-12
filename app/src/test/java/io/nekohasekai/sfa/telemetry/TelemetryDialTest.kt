package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryDialTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun logClockAnchorsNewestUptimeAndResetsWhenCoreRestarts() {
        val clock = TelemetryLogClock()
        val first = clock.stamp(
            listOf("INFO[10] older", "INFO[12] newer", "not a core line"),
            receivedAt = 1_000_000,
        )
        assertEquals(listOf(998_000L, 1_000_000L, null), first)

        val second = clock.stamp(listOf("ERROR[15] later"), receivedAt = 1_005_000)
        assertEquals(listOf(1_005_000L), second)

        val restarted = clock.stamp(listOf("INFO[1] core restarted"), receivedAt = 2_000_000)
        assertEquals(listOf(2_000_000L), restarted)
    }

    @Test
    fun diskReloadDropsMemoryStamps() {
        val file = File(folder.root, "telemetry/pending.log")
        val buffer = TelemetryLogBuffer(file)
        buffer.append(listOf("INFO[3] hello"), listOf(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, buffer.peekStamped().single().second)
        assertTrue(buffer.persist())

        val reloaded = TelemetryLogBuffer(file)
        assertEquals("INFO[3] hello", reloaded.peek().single())
        assertNull(reloaded.peekStamped().single().second)
    }

    @Test
    fun batchCarriesUplinkAndUploadLag() {
        val json = TelemetryPayload.build(
            device = "phone",
            appVersion = "1.15.0-alpha.13-wonder.43",
            coreVersion = "1.15.0-alpha.13",
            tsMillis = 9_000,
            lines = listOf("INFO[1] hi"),
            batch = BatchMeta(
                uploadTs = 9_000,
                transport = "cellular",
                networkId = "cellular:ab",
                metered = true,
                validated = false,
                eventTsMin = 5_000,
                eventTsMax = 8_000,
            ),
        ).toString(Charsets.UTF_8)

        assertTrue(json.contains("\"ts\":9000"))
        assertTrue(json.contains("\"upload_ts\":9000"))
        assertTrue(json.contains("\"transport\":\"cellular\""))
        assertTrue(json.contains("\"network_id\":\"cellular:ab\""))
        assertTrue(json.contains("\"metered\":true"))
        assertTrue(json.contains("\"validated\":false"))
        assertTrue(json.contains("\"event_ts_min\":5000"))
        assertTrue(json.contains("\"event_ts_max\":8000"))
        assertTrue(json.contains("\"upload_lag_ms\":4000"))
    }

    @Test
    fun unstampedBatchOmitsLag() {
        val json = TelemetryPayload.build(
            device = "phone",
            appVersion = "app",
            coreVersion = "core",
            tsMillis = 9_000,
            lines = listOf("restored"),
            batch = BatchMeta(uploadTs = 9_000, transport = "wifi"),
        ).toString(Charsets.UTF_8)

        assertTrue(json.contains("\"upload_ts\":9000"))
        assertTrue(json.contains("\"transport\":\"wifi\""))
        assertFalse(json.contains("upload_lag_ms"))
        assertFalse(json.contains("event_ts_min"))
    }

    @Test
    fun connMinuteRowsStayRawJson() {
        val json = TelemetryClientEvent.encode(
            type = "conn_minute",
            ts = 1,
            fields = mapOf("minute_ts" to 60_000L, "rows" to JsonRaw("[{\"n\":1}]")),
            sessionId = null,
        )
        assertTrue(json.contains("\"rows\":[{\"n\":1}]"))
        assertFalse(json.contains("\"rows\":\"["))
    }

    @Test
    fun dialAttemptParserKeepsCauseAndDropsGarbage() {
        val parsed = DialAttemptJson.parse(
            """[{"ts":10,"outbound":"proxy","network":"tcp","entry":"1.2.3.4:443","result":"fail","error_class":"refused","duration_ms":12,"cause":"urltest"}]""",
        )
        assertEquals(1, parsed.size)
        assertEquals("proxy", parsed[0]["outbound"])
        assertEquals("fail", parsed[0]["result"])
        assertEquals("refused", parsed[0]["error_class"])
        assertEquals("urltest", parsed[0]["cause"])
        assertEquals(10L, parsed[0]["dial_ts"])
        assertTrue(DialAttemptJson.parse("not-json").isEmpty())
        assertTrue(DialAttemptJson.parse("[]").isEmpty())
    }

    @Test
    fun profileEntriesKeepServerPortOnly() {
        val config = """
            {
              "inbounds": [{"type":"tun","tag":"tun","server":"ignored.example","server_port":1}],
              "outbounds": [
                {"type":"vless","tag":"proxy","server":"203.0.113.8","server_port":443,"uuid":"secret-uuid","password":"secret-pass"},
                {"type":"direct","tag":"direct"},
                {"type":"urltest","tag":"auto","outbounds":["proxy"]}
              ]
            }
        """.trimIndent()
        val entries = ProfileEntries.extract(config)
        assertEquals(mapOf("proxy" to "203.0.113.8:443"), entries)
        assertFalse(entries.values.joinToString().contains("secret"))
        assertFalse(entries.values.joinToString().contains("ignored"))
    }

    @Test
    fun connFailureMatchesErrorLineOnly() {
        val failure = TelemetryEvents.connFailure(
            "ERROR[12] [9 1ms] open connection to 1.2.3.4:443 using outbound/vless[proxy-a]: dial tcp 9.9.9.9:443: connect: connection refused",
        )
        assertEquals("proxy-a" to "refused", failure)
        assertNull(TelemetryEvents.connFailure("INFO[1] open connection to 1.2.3.4:443 using outbound/vless[proxy-a]: no"))
    }

    @Test
    fun minuteAggregateSeparatesDownlinkFromDialAndHidesDomain() {
        val agg = ConnectionMinuteAggregator()
        agg.setEntries(mapOf("proxy" to "203.0.113.8:443"))
        val created = 1_000_000L
        agg.observe(
            ConnSample(
                kind = ConnKind.NEW,
                id = "c1",
                outbound = "proxy",
                outboundType = "vless",
                network = "tcp",
                destination = "secret.example:443",
                createdAt = created,
            ),
            observedAt = created,
        )
        agg.observe(
            ConnSample(kind = ConnKind.UPDATE, id = "c1", downlinkDelta = 80, uplinkDelta = 10),
            observedAt = created + 1_500,
        )
        agg.observe(
            ConnSample(kind = ConnKind.CLOSED, id = "c1", closedAt = created + 2_000),
            observedAt = created + 2_000,
        )
        agg.observe(
            ConnSample(
                kind = ConnKind.NEW,
                id = "dns1",
                outbound = "dns-out",
                outboundType = "dns",
                destination = "1.1.1.1:53",
                createdAt = created,
            ),
            observedAt = created,
        )
        agg.observe(
            ConnSample(kind = ConnKind.CLOSED, id = "dns1", outboundType = "dns", closedAt = created + 10),
            observedAt = created + 10,
        )

        assertTrue(agg.flush(created + 10_000, includeCurrent = false).isEmpty())
        val flushed = agg.flush(created + 80_000, includeCurrent = false)
        assertEquals(1, flushed.size)
        val rows = flushed.single().rowsJson
        assertTrue(rows.contains("\"outbound\":\"proxy\""))
        assertTrue(rows.contains("\"dest\":\"https\""))
        assertTrue(rows.contains("\"down\":true"))
        assertTrue(rows.contains("\"bytes_down\":80"))
        assertTrue(rows.contains("\"bytes_up\":10"))
        assertTrue(rows.contains("\"first_byte_ms\":1500"))
        assertTrue(rows.contains("\"duration_ms\":2000"))
        assertTrue(rows.contains("\"entry\":\"203.0.113.8:443\""))
        assertFalse(rows.contains("secret.example"))
        assertFalse(rows.contains("dns-out"))
        assertFalse(rows.contains("dial_attempt"))
    }

    @Test
    fun zeroDownlinkTakesNearbyErrorAndLeavesUnmatchedEmpty() {
        val agg = ConnectionMinuteAggregator()
        val created = 1_000_000L
        fun close(id: String, at: Long) {
            agg.observe(
                ConnSample(
                    kind = ConnKind.NEW,
                    id = id,
                    outbound = "proxy",
                    outboundType = "vless",
                    network = "tcp",
                    destination = "10.0.0.1:443",
                    createdAt = created,
                ),
                observedAt = created,
            )
            agg.observe(
                ConnSample(kind = ConnKind.CLOSED, id = id, closedAt = at),
                observedAt = at,
            )
        }

        close("matched", created + 1_000)
        agg.noteFailure("proxy", "refused", created + 1_500)
        close("late", created + 10_000)
        agg.noteFailure("proxy", "reset", created + 14_000)

        val rows = agg.flush(created + 80_000, includeCurrent = false).single().rowsJson
        assertTrue(rows.contains("\"error_class\":\"refused\""))
        assertFalse(rows.contains("reset"))
    }

    @Test
    fun errorBeforeCloseStillJoins() {
        val agg = ConnectionMinuteAggregator()
        val created = 1_000_000L
        agg.noteFailure("proxy", "io_timeout", created + 1_000)
        agg.observe(
            ConnSample(
                kind = ConnKind.NEW,
                id = "c",
                outbound = "proxy",
                outboundType = "vmess",
                destination = "10.0.0.2:80",
                network = "tcp",
                createdAt = created,
            ),
            observedAt = created,
        )
        agg.observe(
            ConnSample(kind = ConnKind.CLOSED, id = "c", closedAt = created + 1_200),
            observedAt = created + 1_200,
        )
        val rows = agg.flush(created + 80_000, includeCurrent = false).single().rowsJson
        assertTrue(rows.contains("\"dest\":\"http\""))
        assertTrue(rows.contains("\"down\":false"))
        assertTrue(rows.contains("\"error_class\":\"io_timeout\""))
    }
}
