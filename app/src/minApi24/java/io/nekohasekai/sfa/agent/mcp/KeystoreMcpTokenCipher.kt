package io.nekohasekai.sfa.agent.mcp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same blob format as the Conductor API key provider, with one key per server id. */
class KeystoreMcpTokenCipher : McpTokenCipher {
    override val supported: Boolean = true

    override fun encrypt(
        serverId: String,
        token: String,
    ): String {
        require(token.isNotBlank()) { "MCP token must not be blank" }
        val key = getOrCreateKey(alias(serverId))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(2 + iv.size + ciphertext.size)
        blob[0] = VERSION
        blob[1] = iv.size.toByte()
        iv.copyInto(blob, destinationOffset = 2)
        ciphertext.copyInto(blob, destinationOffset = 2 + iv.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    override fun decrypt(
        serverId: String,
        blob: String,
    ): String? =
        runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            if (bytes.size < 2 || bytes[0].toInt() != VERSION.toInt()) return@runCatching null
            val ivLength = bytes[1].toInt() and 0xff
            if (ivLength <= 0 || bytes.size <= 2 + ivLength) return@runCatching null
            val iv = bytes.copyOfRange(2, 2 + ivLength)
            val ciphertext = bytes.copyOfRange(2 + ivLength, bytes.size)
            val key = existingKey(alias(serverId)) ?: return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()

    private fun existingKey(alias: String): SecretKey? =
        (loadKeyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(alias: String): SecretKey {
        existingKey(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun alias(serverId: String): String = "$ALIAS_PREFIX$serverId"

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALIAS_PREFIX = "mcp-token-"
        private const val VERSION: Byte = 1
    }
}
