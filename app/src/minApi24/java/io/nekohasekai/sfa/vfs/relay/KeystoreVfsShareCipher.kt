package io.nekohasekai.sfa.vfs.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for Share secrets, device secrets and Provider keys.
 * Blob: `0x01 || iv(12) || ciphertext || tag(16)`, base64url without padding.
 * AAD is the UTF-8 literal supplied by the caller (see [VfsShareAad]).
 */
class KeystoreVfsShareCipher : VfsShareCipher {
    override val supported: Boolean = true

    override fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val blob = ByteArray(1 + iv.size + ciphertext.size)
        blob[0] = VERSION
        iv.copyInto(blob, destinationOffset = 1)
        ciphertext.copyInto(blob, destinationOffset = 1 + iv.size)
        return RelayBase64.encodeUrl(blob)
    }

    override fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray? =
        runCatching {
            val bytes = RelayBase64.decodeUrl(blob) ?: return@runCatching null
            if (bytes.size < 1 + RelayCrypto.IV_BYTES + RelayCrypto.TAG_BYTES) return@runCatching null
            if (bytes[0] != VERSION) return@runCatching null
            val iv = bytes.copyOfRange(1, 1 + RelayCrypto.IV_BYTES)
            val ciphertext = bytes.copyOfRange(1 + RelayCrypto.IV_BYTES, bytes.size)
            val key = existingKey() ?: return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        }.getOrNull()

    private fun existingKey(): SecretKey? =
        (loadKeyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALIAS = "wondersing-vfs-share-v1"
        private const val VERSION: Byte = 1
    }
}
