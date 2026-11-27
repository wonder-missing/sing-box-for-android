package io.nekohasekai.sfa.vfs.relay

import java.security.SecureRandom

/**
 * Pairing Code: 16 characters from Crockford base32 (no I, L, O, U), valid for
 * five minutes, single use. The code exists in memory only; it is never
 * persisted or logged. `pairId` is the public routing half and may be stored.
 */
object VfsPairing {
    const val LENGTH: Int = 16
    const val TTL_MS: Long = 5L * 60L * 1000L
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): CharArray =
        CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }

    /** Uppercases, removes spaces/hyphens, and applies Crockford aliases. */
    fun normalize(input: String): CharArray? {
        val out = CharArray(LENGTH)
        var count = 0
        for (raw in input) {
            if (raw == ' ' || raw == '-' || raw == '\t') continue
            val ch =
                when (val upper = raw.uppercaseChar()) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    'U' -> 'V'
                    else -> upper
                }
            if (ALPHABET.indexOf(ch) < 0) return null
            if (count >= LENGTH) return null
            out[count++] = ch
        }
        if (count != LENGTH) return null
        return out
    }

    fun isValid(input: String): Boolean = normalize(input) != null

    fun ascii(code: CharArray): String = String(code)

    fun formatForDisplay(code: CharArray): String {
        val text = ascii(code)
        return buildString {
            text.chunked(4).forEachIndexed { index, chunk ->
                if (index > 0) append(' ')
                append(chunk)
            }
        }
    }

    /**
     * `"$HOME/bin/vfsc" pair --link <linkId> --pair <pairId> --code <code>`
     * is the default copy command. It is allowed to carry the code in argv, the
     * user is told it is a one-time secret; stdin/env alternatives are shown too.
     */
    fun pairingCommand(
        linkId: String,
        pairId: String,
        code: CharArray,
    ): String = "\"\$HOME/bin/vfsc\" pair --link $linkId --pair $pairId --code ${ascii(code)}"

    fun zeroize(code: CharArray?) {
        code?.fill('\u0000')
    }
}

/** 5 invalid pair attempts per Link per minute; a valid code is not burned. */
class RelayPairRateLimiter(
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 60_000L,
    private val limit: Int = 5,
) {
    private val attempts = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun allow(linkId: String): Boolean {
        val current = now()
        val list = attempts.getOrPut(linkId) { mutableListOf() }
        list.removeAll { current - it > windowMs }
        if (list.size >= limit) return false
        list += current
        return true
    }

    @Synchronized
    fun reset(linkId: String) {
        attempts.remove(linkId)
    }

    fun retryAfterMs(): Long = windowMs
}

/** Process-lifetime pairing code, keyed by shareId. Never persisted. */
class VfsPairingCodes {
    private val codes = mutableMapOf<String, VfsPairingCode>()

    @Synchronized
    fun put(code: VfsPairingCode) {
        codes[code.shareId]?.let { VfsPairing.zeroize(it.code) }
        codes[code.shareId] = code
    }

    @Synchronized
    fun get(shareId: String): VfsPairingCode? = codes[shareId]

    @Synchronized
    fun remove(shareId: String): VfsPairingCode? = codes.remove(shareId)?.also { VfsPairing.zeroize(it.code) }

    @Synchronized
    fun clear() {
        codes.values.forEach { VfsPairing.zeroize(it.code) }
        codes.clear()
    }

    @Synchronized
    fun keys(): Set<String> = codes.keys.toSet()
}

data class VfsPairingCode(
    val shareId: String,
    val pairId: String,
    val code: CharArray,
    val createdAt: Long,
    val expiresAt: Long,
    val consumedAt: Long? = null,
) {
    fun remainingMs(now: Long): Long = (expiresAt - now).coerceAtLeast(0)

    fun isExpired(now: Long): Boolean = now >= expiresAt
}
