package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCryptoTest {
    private val linkId = hex("101112131415161718191a1b1c1d1e1f")
    private val pairId = hex("505152535455565758595a5b5c5d5e5f")
    private val reqId = hex("202122232425262728292a2b2c2d2e2f")
    private val clientNonce = hex("303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f")
    private val ts = 1_760_000_000_000L
    private val pairReqIv = hex("606162636465666768696a6b")
    private val pairRespIv = hex("6c6d6e6f7071727374757677")
    private val dataReqIv = hex("78797a7b7c7d7e7f80818283")
    private val dataRespIv = hex("8485868788898a8b8c8d8e8f")
    private val responseTs = 1_760_000_001_000L
    private val shareId = hex("707172737475767778797a7b7c7d7e7f")
    private val shareSecret = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val code = "ABCDEFGHJKMNPQRS"

    @Test
    fun derivedKeysMatchAppendixA2() {
        val k = RelayCrypto.shareKey(shareSecret, linkId)
        assertEquals(
            "63807d96748d53879b23127778d6a875d4b02679910881c6d9af6207b081e20a",
            RelayBase64.hex(k),
        )
        assertEquals(
            "ed2e51adaffa5eec1bd5958b996330117dd45388ac9d842a19b409f323761724",
            RelayBase64.hex(RelayCrypto.requestKey(k, clientNonce)),
        )
        assertEquals(
            "52d0def9dd176490fb83597ef75eb06855a87c1d88d51a46f17056a79cae7d6b",
            RelayBase64.hex(RelayCrypto.responseKey(k, clientNonce)),
        )
        assertEquals(
            "c6aa8690b32bec4a8e8dfe6af4cb237880bffe4b2d22ab8fc79a6c8fb68dcd20",
            RelayBase64.hex(RelayCrypto.pairKey(code, linkId)),
        )
        assertEquals(
            "4e7aae0fa4db7daabbd75b2edc1382a3bc43a06db96dd6fcd3d2f8a532fc1ac0",
            RelayBase64.hex(RelayCrypto.pairResponseKey(code, clientNonce)),
        )
        assertEquals(
            "532ce7eae5974910d8c535f5ab4733b1786df1197374b7fae792a536819e9e67",
            RelayBase64.hex(RelayCrypto.cursorKey(k)),
        )
    }

    @Test
    fun aadMatchesAppendixA3() {
        assertEquals(
            "0000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c606162636465666768696a6b00000008706169722d726571",
            RelayBase64.hex(RelayAad.pairRequest(linkId, pairId, reqId, clientNonce, ts, pairReqIv)),
        )
        assertEquals(
            "0000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c606162636465666768696a6b0000000800000199c82cc3e80000000c6c6d6e6f707172737475767700000009706169722d72657370",
            RelayBase64.hex(
                RelayAad.pairResponse(
                    linkId, pairId, reqId, clientNonce, ts, pairReqIv, responseTs, pairRespIv,
                ),
            ),
        )
        assertEquals(
            "00000001010000000376667300000010101112131415161718191a1b1c1d1e1f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c78797a7b7c7d7e7f808182830000000772657175657374",
            RelayBase64.hex(RelayAad.request(linkId, reqId, clientNonce, ts, dataReqIv)),
        )
        assertEquals(
            "00000001010000000376667300000010101112131415161718191a1b1c1d1e1f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c78797a7b7c7d7e7f808182830000000800000199c82cc3e80000000c8485868788898a8b8c8d8e8f00000008726573706f6e7365",
            RelayBase64.hex(
                RelayAad.response(
                    linkId, reqId, clientNonce, ts, dataReqIv, responseTs, dataRespIv,
                ),
            ),
        )
    }

    @Test
    fun plaintextMatchesAppendixA4() {
        val pairReq = RelayWire.pairRequestPlaintext(b64(pairId), b64(clientNonce))
        assertEquals(
            """{"pairId":"UFFSU1RVVldYWVpbXF1eXw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"}""",
            String(pairReq, Charsets.UTF_8),
        )
        val pairResp =
            RelayWire.pairResponsePlaintext(
                shareId = b64(shareId),
                shareSecret = b64(shareSecret),
                scopes = listOf("app.data.activity"),
                roots = listOf("/appdata/activity"),
                label = "Claude",
            )
        assertEquals(
            """{"shareId":"cHFyc3R1dnd4eXp7fH1-fw","shareSecret":"gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8","scopes":["app.data.activity"],"roots":["/appdata/activity"],"label":"Claude"}""",
            String(pairResp, Charsets.UTF_8),
        )
    }

    @Test
    fun ciphertextsMatchAppendixA5() {
        val k = RelayCrypto.shareKey(shareSecret, linkId)
        val pairReqPt = RelayWire.pairRequestPlaintext(b64(pairId), b64(clientNonce))
        val pairReqSealed =
            RelayCrypto.seal(
                RelayCrypto.pairKey(code, linkId),
                pairReqPt,
                RelayAad.pairRequest(linkId, pairId, reqId, clientNonce, ts, pairReqIv),
                pairReqIv,
            )
        assertEquals(
            "88d5c28412ed6921608efcddf1ff5921693d1d1b77eba8529500831fe58a9cf3bf3720f22c5fbce8a7eb0988f404056e26e6e25afeaa75de09b2bbadce5a8b59ef040ffbdb6a95a56b2e47b2dc14bd23e08b25961282c16015a4cfef365f6524fb26f8901a93d82d627d4a61e59947",
            RelayBase64.hex(pairReqSealed.ciphertext),
        )
        assertEquals(
            "24fb26f8901a93d82d627d4a61e59947",
            RelayBase64.hex(pairReqSealed.ciphertext.copyOfRange(pairReqSealed.ciphertext.size - 16, pairReqSealed.ciphertext.size)),
        )
        val pairRespPt =
            RelayWire.pairResponsePlaintext(
                b64(shareId), b64(shareSecret), listOf("app.data.activity"), listOf("/appdata/activity"), "Claude",
            )
        val pairRespSealed =
            RelayCrypto.seal(
                RelayCrypto.pairResponseKey(code, clientNonce),
                pairRespPt,
                RelayAad.pairResponse(linkId, pairId, reqId, clientNonce, ts, pairReqIv, responseTs, pairRespIv),
                pairRespIv,
            )
        assertEquals(
            "7ed26b4187fb538d7ebb568ebc775074948bf453aadddf3134d07b39bd762e54e485c471b7934dcb4c4d8a51b3b10f4e4396cbc6f5dfd325c7aec58ed8cdddaeb24c8c1c62ce0375c7ae902b6d5baeff07fa8207861ab80f7a822a9c6263703ca3265e7c126089bb13ce6ffbff05f87af5bc82c210b3b5f80fb5b1d78f8e9279eb897fa8ddcd3e904506051b48193d7c3da0b21396a22e1d9e6108d150167f3cdea370aeaeeae424046e66a6576ad0bfb12baa02dc7561d89c1617c13925",
            RelayBase64.hex(pairRespSealed.ciphertext),
        )
        val dataReqPt =
            """{"v":1,"type":"stat","path":"/appdata/activity/events.jsonl"}""".toByteArray(Charsets.UTF_8)
        val dataReqSealed =
            RelayCrypto.seal(
                RelayCrypto.requestKey(k, clientNonce),
                dataReqPt,
                RelayAad.request(linkId, reqId, clientNonce, ts, dataReqIv),
                dataReqIv,
            )
        assertEquals(
            "47944c5a97e321bb9359dd947342e1d9fc0a50abfd8cb8ff984542011817e38b2bfa8bf51b125dcfea7b9c6e361736cc0ac1a70d434550eb71b7afd871ee8d75824a5cecc6bc8eacbfd3798e1d",
            RelayBase64.hex(dataReqSealed.ciphertext),
        )
        val dataRespPt =
            """{"v":1,"type":"entry","entry":{"path":"/appdata/activity/events.jsonl","name":"events.jsonl","isDirectory":false,"size":12345,"mtimeMs":1760000000000}}"""
                .toByteArray(Charsets.UTF_8)
        val dataRespSealed =
            RelayCrypto.seal(
                RelayCrypto.responseKey(k, clientNonce),
                dataRespPt,
                RelayAad.response(linkId, reqId, clientNonce, ts, dataReqIv, responseTs, dataRespIv),
                dataRespIv,
            )
        assertEquals(
            "2771f07f6f24f789cacfe8866d38b9f1296e51a4967ab76271cf010732bd5d3a0a62be26fc39e4ffb5115b3e07a3446cf4eeb057625401b6eeba3fe5d777bd8bef4e38a6ad7ad7c78c1b320f71ceacc958ccd6ee35a10786a2734f0b05413978cfd0169bed46804be0cb3053d44172c94a099f730406f5501f9dd05ca6d04afe0c76aa994320dd8732f4badf3445bc971120f53dd6ca39a2204e2c8d58156fa1ed1ae9298ab4cb",
            RelayBase64.hex(dataRespSealed.ciphertext),
        )

        // Decrypting the frozen ciphertext proves both directions.
        val opened =
            RelayCrypto.open(
                RelayCrypto.pairKey(code, linkId),
                pairReqIv,
                pairReqSealed.ciphertext,
                RelayAad.pairRequest(linkId, pairId, reqId, clientNonce, ts, pairReqIv),
            )
        assertArrayEquals(pairReqPt, opened)
    }

    @Test
    fun doublePrefixedVersionHasADifferentTag() {
        // Negative vector from A.6: do not wrap v/ts twice.
        val k = RelayCrypto.shareKey(shareSecret, linkId)
        val doublePrefix =
            hex(
                "000000010000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f000000080000000800000199c82cc0000000000c606162636465666768696a6b00000008706169722d726571",
            )
        val sealed =
            RelayCrypto.seal(
                RelayCrypto.pairKey(code, linkId),
                RelayWire.pairRequestPlaintext(b64(pairId), b64(clientNonce)),
                doublePrefix,
                pairReqIv,
            )
        assertEquals(
            "482c7c6e1d51fdda69a1308fb9c4c6b5",
            RelayBase64.hex(sealed.ciphertext.copyOfRange(sealed.ciphertext.size - 16, sealed.ciphertext.size)),
        )
        assertNotEquals(
            "24fb26f8901a93d82d627d4a61e59947",
            RelayBase64.hex(sealed.ciphertext.copyOfRange(sealed.ciphertext.size - 16, sealed.ciphertext.size)),
        )
        // The correct key derivations are unrelated to the double-prefix AAD.
        assertEquals(32, RelayCrypto.requestKey(k, clientNonce).size)
    }

    @Test
    fun base64DecoderRejectsNonCanonicalForms() {
        assertNull(RelayBase64.decodeUrl("AB"))
        assertNull(RelayBase64.decodeUrl("A"))
        assertNull(RelayBase64.decodeUrl("AA=="))
        assertArrayEquals(byteArrayOf(0), RelayBase64.decodeUrl("AA"))
        assertEquals("AA", RelayBase64.encodeUrl(byteArrayOf(0)))
        assertArrayEquals(hex("101112131415161718191a1b1c1d1e1f"), RelayBase64.decodeUrl("EBESExQVFhcYGRobHB0eHw"))
    }

    @Test
    fun cursorMatchesAppendixA6AndRejectsTampering() {
        val k = RelayCrypto.shareKey(shareSecret, linkId)
        val cursorKey = RelayCrypto.cursorKey(k)
        val lastKey = "/appdata/activity/a.jsonl".toByteArray(Charsets.UTF_8)
        val encoded =
            RelayCursor.encode(cursorKey, linkId, "/appdata/activity", 1000, lastKey)
        assertEquals("AQAAABkvYXBwZGF0YS9hY3Rpdml0eS9hLmpzb25s6wGqPV8FIDPoBx5WJpgY8Q", encoded)
        assertArrayEquals(
            lastKey,
            RelayCursor.decode(cursorKey, linkId, "/appdata/activity", 1000, encoded),
        )
        assertNull(RelayCursor.decode(cursorKey, linkId, "/appdata/activity", 2000, encoded))
        assertNull(RelayCursor.decode(cursorKey, linkId, "/appdata/other", 1000, encoded))
        assertNull(RelayCursor.decode(cursorKey, hex("ff".repeat(16)), "/appdata/activity", 1000, encoded))
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
        assertNull(RelayCursor.decode(cursorKey, linkId, "/appdata/activity", 1000, tampered))
    }

    @Test
    fun atRestAadStringsMatchAppendixA6() {
        assertEquals(
            "76317c6348467963335231646e6434655870376648312d66777c736563726574",
            RelayBase64.hex(VfsShareAad.shareSecret("cHFyc3R1dnd4eXp7fH1-fw").toByteArray(Charsets.UTF_8)),
        )
        val base = "https://clawdy-mini.tailf665f5.ts.net"
        val hook = "hk_0123456789abcdef0123456789abcdef"
        val device = "7d444840-9dc0-11e0-9b7a-0800200c9a66"
        assertEquals(
            "76317c68747470733a2f2f636c617764792d6d696e692e7461696c6636363566352e74732e6e65747c686b5f30313233343536373839616263646566303132333435363738396162636465667c37643434343834302d396463302d313165302d396237612d3038303032303063396136367c646576696365536563726574",
            RelayBase64.hex(
                VfsShareAad.deviceSecret(base, hook, device).toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals(
            "76317c68747470733a2f2f636c617764792d6d696e692e7461696c6636363566352e74732e6e65747c686b5f30313233343536373839616263646566303132333435363738396162636465667c37643434343834302d396463302d313165302d396237612d3038303032303063396136367c70726f76696465724b65797c4d4445794d7a51314e6a63344f546f375044302d5030",
            RelayBase64.hex(
                VfsShareAad
                    .providerKey(base, hook, device, "MDEyMzQ1Njc4OTo7PD0-P0")
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        assertTrue(
            RelayBase64.hex(VfsShareAad.pendingDeviceSecret(base, device).toByteArray(Charsets.UTF_8))
                .endsWith("7c646576696365536563726574"),
        )
    }

    private fun hex(value: String): ByteArray =
        requireNotNull(RelayBase64.unhex(value)) { "bad hex" }

    private fun b64(bytes: ByteArray): String = RelayBase64.encodeUrl(bytes)
}
