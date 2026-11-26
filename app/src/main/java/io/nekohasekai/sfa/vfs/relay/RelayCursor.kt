package io.nekohasekai.sfa.vfs.relay

/**
 * List cursor wire: `0x01 || u32be(len(lastKey)) || lastKey || tag16`.
 * The tag binds linkId, requested path, limit, scope version and lastKey, so a
 * cursor cannot be replayed against another Share/range. The cursor is not
 * secret: it only reveals a path the list response already returned.
 */
object RelayCursor {
    const val VERSION: Byte = 1
    const val TAG_BYTES: Int = 16
    const val SCOPE_VERSION: String = "1"

    fun encode(
        cursorKey: ByteArray,
        linkId: ByteArray,
        path: String,
        limit: Int,
        lastKey: ByteArray,
        scopeVersion: String = SCOPE_VERSION,
    ): String {
        val context = tagContext(linkId, path, limit, scopeVersion, lastKey)
        val tag = RelayCrypto.hmacSha256(cursorKey, context).copyOf(TAG_BYTES)
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(RelayAad.u32(lastKey.size))
        out.write(lastKey)
        out.write(tag)
        return RelayBase64.encodeUrl(out.toByteArray())
    }

    /**
     * Returns lastKey, or null when the cursor is malformed, uses another
     * binding, or has a bad tag. Callers additionally require lastKey to still
     * exist in the current sorted result.
     */
    fun decode(
        cursorKey: ByteArray,
        linkId: ByteArray,
        path: String,
        limit: Int,
        cursor: String,
        scopeVersion: String = SCOPE_VERSION,
    ): ByteArray? {
        val raw = RelayBase64.decodeUrl(cursor) ?: return null
        if (raw.size < 1 + 4 + TAG_BYTES) return null
        if (raw[0] != VERSION) return null
        val lastKeyLength =
            ((raw[1].toInt() and 0xff) shl 24) or
                ((raw[2].toInt() and 0xff) shl 16) or
                ((raw[3].toInt() and 0xff) shl 8) or
                (raw[4].toInt() and 0xff)
        if (lastKeyLength < 0 || lastKeyLength > raw.size - 1 - 4 - TAG_BYTES) return null
        val lastKey = raw.copyOfRange(5, 5 + lastKeyLength)
        val tag = raw.copyOfRange(5 + lastKeyLength, 5 + lastKeyLength + TAG_BYTES)
        val expected =
            RelayCrypto
                .hmacSha256(cursorKey, tagContext(linkId, path, limit, scopeVersion, lastKey))
                .copyOf(TAG_BYTES)
        if (!RelayCrypto.constantTimeEquals(tag, expected)) return null
        return lastKey
    }

    fun tagContext(
        linkId: ByteArray,
        path: String,
        limit: Int,
        scopeVersion: String,
        lastKey: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(RelayAad.u32(linkId.size))
        out.write(linkId)
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        out.write(RelayAad.u32(pathBytes.size))
        out.write(pathBytes)
        // The limit is itself a 4-byte value, so it is length-prefixed like any
        // other field (appendix A.6 cursorCtx).
        out.write(RelayAad.u32(4))
        out.write(RelayAad.u32(limit))
        val version = scopeVersion.toByteArray(Charsets.UTF_8)
        out.write(RelayAad.u32(version.size))
        out.write(version)
        out.write(RelayAad.u32(lastKey.size))
        out.write(lastKey)
        return out.toByteArray()
    }
}

/** Bounded seen-nonce cache. Entries live 10 minutes, matching the clock skew. */
class RelayNonceCache(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10L * 60L * 1000L,
    private val maxEntries: Int = 20_000,
) {
    private val entries = LinkedHashMap<String, Long>()

    @Synchronized
    fun remember(
        linkId: String,
        nonce: String,
    ): Boolean {
        val current = now()
        purge(current)
        val key = "$linkId|$nonce"
        val previous = entries[key]
        if (previous != null && current - previous <= ttlMs) return false
        entries[key] = current
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun purge(current: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (current - entry.value > ttlMs) iterator.remove() else break
        }
    }
}

object RelayClock {
    const val SKEW_MS: Long = 5L * 60L * 1000L

    fun isFresh(
        ts: Long,
        now: Long,
    ): Boolean = ts >= 0 && kotlin.math.abs(now - ts) <= SKEW_MS
}
