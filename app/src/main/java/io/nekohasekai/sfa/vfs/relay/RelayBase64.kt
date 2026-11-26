package io.nekohasekai.sfa.vfs.relay

/**
 * Base64 for the Share Key VFS wire. Relay fields use RFC 4648 section 5
 * (base64url) without padding and must be canonical; a decoder rejects
 * non-zero padding bits instead of accepting a second spelling of the same
 * bytes. Inner `data` fields use standard base64 with padding, as the CLI
 * expects.
 */
object RelayBase64 {
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val urlIndex = IntArray(128) { -1 }.also { table ->
        URL_ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }
    private val stdIndex = IntArray(128) { -1 }.also { table ->
        STD_ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    fun encodeUrl(bytes: ByteArray): String = encode(bytes, URL_ALPHABET, pad = false)

    fun encodeStd(bytes: ByteArray): String = encode(bytes, STD_ALPHABET, pad = true)

    fun decodeUrl(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.any { it == '=' }) return null
        return decode(text, urlIndex, alphabetSize = 64, padded = false)
    }

    fun decodeStd(text: String): ByteArray? = decode(text, stdIndex, alphabetSize = 64, padded = true)

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = Character.digit(text[i * 2], 16)
            val low = Character.digit(text[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun encode(
        bytes: ByteArray,
        alphabet: String,
        pad: Boolean,
    ): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xff
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xff else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xff else 0
            val remaining = bytes.size - index
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (remaining > 1) {
                out.append(alphabet[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
            } else if (pad) {
                out.append('=')
            }
            if (remaining > 2) {
                out.append(alphabet[b2 and 0x3f])
            } else if (pad) {
                out.append('=')
            }
            index += 3
        }
        return out.toString()
    }

    private fun decode(
        text: String,
        table: IntArray,
        alphabetSize: Int,
        padded: Boolean,
    ): ByteArray? {
        var length = text.length
        var padding = 0
        if (padded) {
            while (length > 0 && text[length - 1] == '=') {
                length--
                padding++
                if (padding > 2) return null
            }
            if (padding > 0 && text.length % 4 != 0) return null
        }
        if (length % 4 == 1) return null
        val out = ByteArray(length * 3 / 4)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (i in 0 until length) {
            val code = text[i].code
            val value = if (code < 128) table[code] else -1
            if (value < 0 || value >= alphabetSize) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer ushr bits) and 0xff).toByte()
            }
        }
        // Canonical form: leftover bits must be zero.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out
    }

    private const val HEX = "0123456789abcdef"
}
