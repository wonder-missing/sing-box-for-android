package io.nekohasekai.sfa.vfs.relay

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P0 crypto for Share Key VFS. Every byte layout here is frozen by
 * docs/vfs-share-key-reference.md appendix A; Kotlin must reproduce the
 * vectors exactly.
 */
object RelayCrypto {
    const val KEY_BYTES: Int = 32
    const val IV_BYTES: Int = 12
    const val TAG_BYTES: Int = 16
    const val NONCE_BYTES: Int = 32
    const val SHARE_ID_BYTES: Int = 16

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = KEY_BYTES,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF length out of range" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(prk, HMAC_SHA256))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, length - offset)
            previous.copyInto(out, destinationOffset = offset, endIndex = copy)
            offset += copy
            counter++
        }
        return out
    }

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun shareKey(
        secret: ByteArray,
        linkId: ByteArray,
    ): ByteArray = hkdfSha256(secret, linkId, "wsvfs/v1".toByteArray(Charsets.UTF_8))

    fun requestKey(
        shareKey: ByteArray,
        clientNonce: ByteArray,
    ): ByteArray = hkdfSha256(shareKey, clientNonce, "request".toByteArray(Charsets.UTF_8))

    fun responseKey(
        shareKey: ByteArray,
        clientNonce: ByteArray,
    ): ByteArray = hkdfSha256(shareKey, clientNonce, "response".toByteArray(Charsets.UTF_8))

    fun pairKey(
        normalizedCode: String,
        linkId: ByteArray,
    ): ByteArray =
        hkdfSha256(
            normalizedCode.toByteArray(Charsets.US_ASCII),
            linkId,
            "wsvfs/pair/req".toByteArray(Charsets.UTF_8),
        )

    fun pairResponseKey(
        normalizedCode: String,
        clientNonce: ByteArray,
    ): ByteArray =
        hkdfSha256(
            normalizedCode.toByteArray(Charsets.US_ASCII),
            clientNonce,
            "wsvfs/pair/resp".toByteArray(Charsets.UTF_8),
        )

    fun cursorKey(shareKey: ByteArray): ByteArray =
        hkdfSha256(shareKey, ByteArray(0), "wsvfs/cursor".toByteArray(Charsets.UTF_8))

    /** Returns `ciphertext || tag`, matching the wire field. */
    fun seal(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        iv: ByteArray? = null,
    ): Sealed {
        val actualIv = iv ?: randomBytes(IV_BYTES)
        require(actualIv.size == IV_BYTES) { "GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, actualIv),
        )
        cipher.updateAAD(aad)
        return Sealed(actualIv, cipher.doFinal(plaintext))
    }

    /** Accepts `ciphertext || tag`; throws [javax.crypto.AEADBadTagException] on tamper. */
    fun open(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(iv.size == IV_BYTES) { "GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun randomToken(size: Int): String = RelayBase64.encodeUrl(randomBytes(size))

    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)

    data class Sealed(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    init {
        // SecureRandom's first call on Android may block; warm it off the UI path.
        random.nextBytes(ByteArray(1))
    }
}

/**
 * AAD encoding: every field is `u32be(len) || bytes`; the `v` and `ts` fields
 * are already encoded as complete fields by the caller.
 */
object RelayAad {
    fun pairRequest(
        linkId: ByteArray,
        pairId: ByteArray,
        reqId: ByteArray,
        clientNonce: ByteArray,
        ts: Long,
        iv: ByteArray,
    ): ByteArray = build("pair", "pair-req") {
        raw(linkId)
        raw(pairId)
        raw(reqId)
        raw(clientNonce)
        timestamp(ts)
        raw(iv)
        text("pair-req")
    }

    fun pairResponse(
        linkId: ByteArray,
        pairId: ByteArray,
        reqId: ByteArray,
        clientNonce: ByteArray,
        requestTs: Long,
        requestIv: ByteArray,
        ts: Long,
        iv: ByteArray,
    ): ByteArray = build("pair", "pair-resp") {
        raw(linkId)
        raw(pairId)
        raw(reqId)
        raw(clientNonce)
        timestamp(requestTs)
        raw(requestIv)
        timestamp(ts)
        raw(iv)
        text("pair-resp")
    }

    fun request(
        linkId: ByteArray,
        reqId: ByteArray,
        clientNonce: ByteArray,
        ts: Long,
        iv: ByteArray,
    ): ByteArray = build("vfs", "request") {
        raw(linkId)
        raw(reqId)
        raw(clientNonce)
        timestamp(ts)
        raw(iv)
        text("request")
    }

    fun response(
        linkId: ByteArray,
        reqId: ByteArray,
        clientNonce: ByteArray,
        requestTs: Long,
        requestIv: ByteArray,
        ts: Long,
        iv: ByteArray,
    ): ByteArray = build("vfs", "response") {
        raw(linkId)
        raw(reqId)
        raw(clientNonce)
        timestamp(requestTs)
        raw(requestIv)
        timestamp(ts)
        raw(iv)
        text("response")
    }

    private fun build(
        op: String,
        @Suppress("UNUSED_PARAMETER") direction: String,
        block: AadBuilder.() -> Unit,
    ): ByteArray {
        val builder = AadBuilder()
        builder.version()
        builder.text(op)
        block(builder)
        return builder.toByteArray()
    }

    private class AadBuilder {
        private val out = java.io.ByteArrayOutputStream()

        fun version() {
            out.write(u32(1))
            out.write(0x01)
        }

        fun text(value: String) = raw(value.toByteArray(Charsets.UTF_8))

        fun raw(bytes: ByteArray) {
            out.write(u32(bytes.size))
            out.write(bytes)
        }

        fun timestamp(value: Long) {
            out.write(u32(8))
            out.write(u64(value))
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    internal fun u32(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    internal fun u64(value: Long): ByteArray =
        ByteArray(8) { index -> (value ushr ((7 - index) * 8)).toByte() }
}
