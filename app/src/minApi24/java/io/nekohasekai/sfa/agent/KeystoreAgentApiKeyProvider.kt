package io.nekohasekai.sfa.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreAgentApiKeyProvider(
    context: Context,
) : AgentApiKeyProvider {
    override val supported: Boolean = true

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun apiKey(): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val stored = prefs.getString(KEY_BLOB, null) ?: return@runCatching null
                decrypt(stored)
            }.getOrNull()
        }

    override suspend fun save(apiKey: String) {
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "API key must not be blank" }
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(2 + iv.size + ciphertext.size)
            blob[0] = VERSION
            blob[1] = iv.size.toByte()
            iv.copyInto(blob, destinationOffset = 2)
            ciphertext.copyInto(blob, destinationOffset = 2 + iv.size)
            prefs
                .edit()
                .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_BLOB).apply()
            runCatching {
                val keyStore = loadKeyStore()
                if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
            }
        }
    }

    private fun decrypt(stored: String): String? {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size < 2 || blob[0].toInt() != VERSION.toInt()) return null
        val ivLength = blob[1].toInt() and 0xff
        if (ivLength <= 0 || blob.size <= 2 + ivLength) return null
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val ciphertext = blob.copyOfRange(2 + ivLength, blob.size)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val PREFS = "conductor_secrets"
        private const val KEY_BLOB = "deepseek_api_key_blob"
        private const val ALIAS = "conductor-api-key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
    }
}
