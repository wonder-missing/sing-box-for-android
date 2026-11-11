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
    private val providerIdOverride: String? = null,
) : AgentApiKeyProvider {
    override val supported: Boolean = true

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun providerId(): String =
        ModelProviders.byId(providerIdOverride ?: ModelProviderSettings.active().id).id

    override suspend fun apiKey(): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = providerId()
                val stored = prefs.getString(blobKey(id), null)
                if (stored != null) {
                    return@runCatching decrypt(stored, alias(id))
                }
                legacyBlob(id)?.let { decrypt(it, LEGACY_ALIAS) }
            }.getOrNull()
        }

    override suspend fun save(apiKey: String) {
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "API key must not be blank" }
            val id = providerId()
            val key = getOrCreateKey(alias(id))
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
                .putString(blobKey(id), Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
            if (id == ModelProviders.DEEPSEEK) {
                prefs.edit().remove(LEGACY_KEY_BLOB).apply()
                deleteKey(LEGACY_ALIAS)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val id = providerId()
            prefs.edit().remove(blobKey(id)).apply()
            deleteKey(alias(id))
            if (id == ModelProviders.DEEPSEEK) {
                prefs.edit().remove(LEGACY_KEY_BLOB).apply()
                deleteKey(LEGACY_ALIAS)
            }
        }
    }

    private fun legacyBlob(id: String): String? =
        if (id == ModelProviders.DEEPSEEK) prefs.getString(LEGACY_KEY_BLOB, null) else null

    private fun decrypt(
        stored: String,
        alias: String,
    ): String? {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size < 2 || blob[0].toInt() != VERSION.toInt()) return null
        val ivLength = blob[1].toInt() and 0xff
        if (ivLength <= 0 || blob.size <= 2 + ivLength) return null
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val ciphertext = blob.copyOfRange(2 + ivLength, blob.size)
        val key = existingKey(alias) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun existingKey(alias: String): SecretKey? =
        (loadKeyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(alias: String): SecretKey {
        existingKey(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey(alias: String) {
        runCatching {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun blobKey(id: String): String = "$KEY_BLOB_PREFIX$id"

    private fun alias(id: String): String = "$ALIAS_PREFIX$id"

    companion object {
        private const val PREFS = "conductor_secrets"
        private const val KEY_BLOB_PREFIX = "api_key_blob_"
        private const val ALIAS_PREFIX = "conductor-api-key-"
        private const val LEGACY_KEY_BLOB = "deepseek_api_key_blob"
        private const val LEGACY_ALIAS = "conductor-api-key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
    }
}
