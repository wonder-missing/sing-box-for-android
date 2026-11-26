package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWireTest {
    private val dataRequestWire =
        """{"v":1,"op":"vfs","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000000000,"iv":"eHl6e3x9fn-AgYKD","ct":"R5RMWpfjIbuTWd2Uc0Lh2fwKUKv9jLj_mEVCARgX44sr-ov1GxJdz-p7nG42FzbMCsGnDUNFUOtxt6_Yce6NdYJKXOzGvI6sv9N5jh0"}"""

    private val pairRequestWire =
        """{"v":1,"op":"pair","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000000000,"iv":"YGFiY2RlZmdoaWpr","ct":"iNXChBLtaSFgjvzd8f9ZIWk9HRt366hSlQCDH-WKnPO_NyDyLF-86KfrCYj0BAVuJubiWv6qdd4JsrutzlqLWe8ED_vbapWlay5HstwUvSPgiyWWEoLBYBWkz-82X2Uk-yb4kBqT2C1ifUph5ZlH"}"""

    @Test
    fun parsesDataAndPairEnvelopes() {
        val data = RelayWire.parseEnvelope(dataRequestWire)
        assertNotNull(data)
        assertEquals("vfs", data!!.op)
        assertEquals(16, data.linkIdRaw.size)
        assertEquals(16, data.reqIdRaw.size)
        assertEquals(32, data.clientNonceRaw.size)
        assertEquals(12, data.ivRaw.size)
        assertEquals(1_760_000_000_000L, data.ts)
        assertNull(data.pairIdRaw)
        assertTrue(data.ctRaw.size > 16)

        val pair = RelayWire.parseEnvelope(pairRequestWire)
        assertNotNull(pair)
        assertEquals("pair", pair!!.op)
        assertEquals("UFFSU1RVVldYWVpbXF1eXw", pair.pairId)
        assertEquals(16, pair.pairIdRaw!!.size)
    }

    @Test
    fun rejectsMalformedEnvelopes() {
        assertNull(RelayWire.parseEnvelope(dataRequestWire.replace("\"v\":1", "\"v\":2")))
        assertNull(RelayWire.parseEnvelope(dataRequestWire.replace("\"linkId\":\"EBESExQVFhcYGRobHB0eHw\"", "\"linkId\":\"abc\"")))
        assertNull(RelayWire.parseEnvelope(dataRequestWire.replace("\"ct\":\"R5RM", "\"ct\":\"!5RM")))
        // pair op requires pairId
        assertNull(
            RelayWire.parseEnvelope(
                pairRequestWire.replace(",\"pairId\":\"UFFSU1RVVldYWVpbXF1eXw\"", ""),
            ),
        )
    }

    @Test
    fun controlFramesCarryRequiredFieldsAndOmitWrongOnes() {
        val active =
            Json.parseToJsonElement(
                RelayWire.pairControl("ICEiIyQlJicoKSorLC0uLw", "EBESExQVFhcYGRobHB0eHw", "UFFSU1RVVldYWVpbXF1eXw", 300, "active", 3),
            ).jsonObject
        assertEquals("pair", active["op"]!!.jsonPrimitive.content)
        assertEquals(300L, active["ttlSeconds"]!!.jsonPrimitive.long)
        assertEquals(3L, active["pairEpoch"]!!.jsonPrimitive.long)

        val paused =
            Json.parseToJsonElement(
                RelayWire.pairControl("c", "l", null, null, "paused", 4),
            ).jsonObject
        assertFalse(paused.containsKey("pairId"))
        assertFalse(paused.containsKey("ttlSeconds"))

        val consumed =
            Json.parseToJsonElement(
                RelayWire.pairControl("c", "l", "p", null, "consumed", 5),
            ).jsonObject
        assertEquals("p", consumed["pairId"]!!.jsonPrimitive.content)
        assertFalse(consumed.containsKey("ttlSeconds"))

        val register =
            Json.parseToJsonElement(RelayWire.registerControl("c", "l", "d", 7)).jsonObject
        assertEquals(7L, register["generation"]!!.jsonPrimitive.long)
        val unregister =
            Json.parseToJsonElement(RelayWire.unregisterControl("c", "l", "revoked")).jsonObject
        assertEquals("revoked", unregister["reason"]!!.jsonPrimitive.content)
        assertTrue(RelayWire.ping("id").contains("\"op\":\"ping\""))
        assertTrue(RelayWire.pong("id").contains("\"op\":\"pong\""))
    }

    @Test
    fun parsesAckErrorPairErrorAndLinkError() {
        val ack =
            RelayWire.parseIncoming(
                """{"v":1,"op":"ack","control":"register","controlId":"c","linkId":"l","ok":true,"generation":7}""",
            )
        assertTrue(ack is RelayIncoming.Ack)
        assertEquals(7L, (ack as RelayIncoming.Ack).generation)

        val error =
            RelayWire.parseIncoming(
                """{"v":1,"op":"error","control":"register","controlId":"c","linkId":"l","code":"STALE_GENERATION","message":"generation behind","retryable":true,"currentGeneration":8,"retryAfterMs":1000}""",
            )
        assertTrue(error is RelayIncoming.ControlError)
        val controlError = error as RelayIncoming.ControlError
        assertEquals("STALE_GENERATION", controlError.code)
        assertEquals(8L, controlError.currentGeneration)
        assertEquals(1000L, controlError.retryAfterMs)

        val pairError =
            RelayWire.parseIncoming(
                """{"v":1,"op":"pair-error","consumerId":"c","linkId":"l","pairId":"p","reqId":"r","code":"code_expired","message":"pairing code expired"}""",
            )
        assertTrue(pairError is RelayIncoming.PairError)
        assertEquals(RelayPairErrorCode.CODE_EXPIRED, (pairError as RelayIncoming.PairError).code)

        val linkError =
            RelayWire.parseIncoming(
                """{"v":1,"op":"link-error","consumerId":"c","linkId":"l","reqId":"r","code":"share_revoked","message":"share revoked"}""",
            )
        assertTrue(linkError is RelayIncoming.LinkError)
        assertEquals(RelayLinkErrorCode.SHARE_REVOKED, (linkError as RelayIncoming.LinkError).code)

        assertTrue(RelayWire.parseIncoming("""{"v":1,"op":"ping","id":"x"}""") is RelayIncoming.Ping)
        assertTrue(RelayWire.parseIncoming("""{"v":1,"op":"pong","id":"x"}""") is RelayIncoming.Pong)
    }

    @Test
    fun pairPlaintextValidationRequiresMatchingOuterFields() {
        val bytes = RelayWire.pairRequestPlaintext("UFFSU1RVVldYWVpbXF1eXw", "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8")
        val parsed = RelayWire.parsePairPlaintext(bytes)
        assertNotNull(parsed)
        assertEquals("UFFSU1RVVldYWVpbXF1eXw", parsed!!.pairId)
        assertNull(RelayWire.parsePairPlaintext("{\"pairId\":\"x\",\"clientNonce\":\"y\"}".toByteArray()))
    }

    @Test
    fun innerRequestsAndResponsesMatchAppendixC() {
        val stat = RelayWire.parseInnerRequest("""{"v":1,"type":"stat","path":"/appdata/activity/events.jsonl"}""".toByteArray())
        assertTrue(stat is RelayInnerRequest.Stat)

        val list =
            RelayWire.parseInnerRequest(
                """{"v":1,"type":"list","path":"/appdata/activity","limit":1000}""".toByteArray(),
            )
        assertTrue(list is RelayInnerRequest.List)
        assertEquals(1000, (list as RelayInnerRequest.List).limit)
        assertEquals(1000, RelayWire.parseInnerRequest("""{"v":1,"type":"list","path":"/x"}""".toByteArray()).let { (it as RelayInnerRequest.List).limit })

        val read =
            RelayWire.parseInnerRequest(
                """{"v":1,"type":"read","path":"/appfiles/bin.dat","offset":0,"length":4096}""".toByteArray(),
            )
        assertTrue(read is RelayInnerRequest.Read)
        assertEquals(4096, (read as RelayInnerRequest.Read).length)

        val entry =
            VfsEntry(
                path = "/appdata/activity/a.jsonl",
                name = "a.jsonl",
                isDirectory = false,
                size = 12,
                mtimeMs = 1_760_000_000_000,
            )
        val entries =
            Json.parseToJsonElement(String(RelayWire.listResponse(listOf(entry), "AQAAABk"), Charsets.UTF_8)).jsonObject
        assertEquals("entries", entries["type"]!!.jsonPrimitive.content)
        assertEquals("AQAAABk", entries["nextCursor"]!!.jsonPrimitive.content)
        val empty =
            Json.parseToJsonElement(String(RelayWire.listResponse(emptyList(), null), Charsets.UTF_8)).jsonObject
        assertTrue(empty["nextCursor"].toString() == "null")

        val data =
            Json.parseToJsonElement(
                String(RelayWire.readResponse("hi".toByteArray(), "e1", 0, 2, true), Charsets.UTF_8),
            ).jsonObject
        assertEquals("aGk=", data["data"]!!.jsonPrimitive.content)
        assertTrue(data["eof"]!!.jsonPrimitive.boolean)
        assertEquals(2L, data["nextOffset"]!!.jsonPrimitive.long)

        val error = Json.parseToJsonElement(String(RelayWire.innerError("invalid_path", "path not normalized"), Charsets.UTF_8)).jsonObject
        assertEquals("invalid_path", error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val statResponse =
            Json.parseToJsonElement(String(RelayWire.statResponse(entry), Charsets.UTF_8)).jsonObject
        val entryObject = statResponse["entry"]!!.jsonObject
        assertEquals("/appdata/activity/a.jsonl", entryObject["path"]!!.jsonPrimitive.content)
        assertEquals(false, entryObject["isDirectory"]!!.jsonPrimitive.boolean)
        assertEquals(1_760_000_000_000L, entryObject["mtimeMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun innerRequestRejectsBadRangesAndVersions() {
        assertThrowsInner(RelayInnerError.BAD_REQUEST) {
            RelayWire.parseInnerRequest("""{"v":2,"type":"stat","path":"/x"}""".toByteArray())
        }
        assertThrowsInner(RelayInnerError.BAD_REQUEST) {
            RelayWire.parseInnerRequest("""{"v":1,"type":"read","path":"/x","offset":0}""".toByteArray())
        }
        assertThrowsInner(RelayInnerError.BAD_REQUEST) {
            RelayWire.parseInnerRequest(
                """{"v":1,"type":"read","path":"/x","offset":0,"length":1048577}""".toByteArray(),
            )
        }
        assertThrowsInner(RelayInnerError.BAD_REQUEST) {
            RelayWire.parseInnerRequest(
                """{"v":1,"type":"list","path":"/x","limit":10001}""".toByteArray(),
            )
        }
    }

    @Test
    fun responsesEchoConsumerRouteFieldsAndKeepPairOp() {
        val pairRequest = RelayWire.parseEnvelope(pairRequestWire)!!
        val pairResponse =
            RelayWire.responseEnvelope(
                request = pairRequest,
                op = "pair",
                pairId = pairRequest.pairId,
                ts = 1_760_000_001_000L,
                iv = "bG1ub3BxcnN0dXZ3",
                ct = "AAAAAAAAAAAAAAAAAAAAAA",
            )
        val parsedPair = RelayWire.parseEnvelope(pairResponse)!!
        assertEquals("pair", parsedPair.op)
        assertEquals(pairRequest.consumerId, parsedPair.consumerId)
        assertEquals(pairRequest.linkId, parsedPair.linkId)
        assertEquals(pairRequest.pairId, parsedPair.pairId)
        assertEquals(pairRequest.reqId, parsedPair.reqId)
        assertEquals(pairRequest.clientNonce, parsedPair.clientNonce)
        assertEquals(1_760_000_001_000L, parsedPair.ts)
        assertEquals("bG1ub3BxcnN0dXZ3", parsedPair.iv)

        val dataRequest = RelayWire.parseEnvelope(dataRequestWire)!!
        val dataResponse =
            RelayWire.responseEnvelope(
                request = dataRequest,
                op = "vfs",
                pairId = null,
                ts = 1_760_000_001_000L,
                iv = "hIWGh4iJiouMjY6P",
                ct = "AAAAAAAAAAAAAAAAAAAAAA",
            )
        val parsedData = RelayWire.parseEnvelope(dataResponse)!!
        assertEquals("vfs", parsedData.op)
        assertEquals(dataRequest.consumerId, parsedData.consumerId)
        assertEquals(dataRequest.linkId, parsedData.linkId)
        assertEquals(dataRequest.reqId, parsedData.reqId)
        assertEquals(dataRequest.clientNonce, parsedData.clientNonce)
        assertNull(parsedData.pairId)
    }

    @Test
    fun pairControlsAreGatedOnRegisterAck() {
        val gate = RelayLinkGate()
        assertFalse(gate.canSendPair("link"))
        gate.onRegisterAck("link")
        assertTrue(gate.canSendPair("link"))
        gate.forget("link")
        assertFalse(gate.canSendPair("link"))
        gate.onRegisterAck("link")
        gate.clear()
        assertFalse(gate.canSendPair("link"))
    }

    private fun assertThrowsInner(
        code: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: RelayProtocolException) {
            assertEquals(code, error.code)
            return
        }
        throw AssertionError("expected $code")
    }
}
