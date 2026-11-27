package io.nekohasekai.sfa.vfs.relay

/**
 * Encrypts Share secrets, device secrets and Provider keys at rest with
 * Android Keystore AES-256-GCM. The API 24+ source set supplies the real
 * implementation; API 21 builds get an unsupported instance and hide the
 * feature. AAD strings are literal UTF-8 values frozen by the reference design.
 */
interface VfsShareCipher {
    val supported: Boolean

    fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String

    fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray?
}

object NoopVfsShareCipher : VfsShareCipher {
    override val supported: Boolean = false

    override fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String = throw IllegalStateException("VFS Share Key VFS is not supported on this build")

    override fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray? = null
}

object VfsShareAad {
    fun shareSecret(shareId: String): String = "v1|$shareId|secret"

    fun relayServerToken(localId: String): String = "v1|relayServer|$localId|token"

    fun pendingDeviceSecret(
        canonicalBase: String,
        deviceId: String,
    ): String = "v1|$canonicalBase|$deviceId|deviceSecret"

    fun deviceSecret(
        canonicalBase: String,
        relayId: String,
        deviceId: String,
    ): String = "v1|$canonicalBase|$relayId|$deviceId|deviceSecret"

    fun providerKey(
        canonicalBase: String,
        relayId: String,
        deviceId: String,
        providerKeyId: String,
    ): String = "v1|$canonicalBase|$relayId|$deviceId|providerKey|$providerKeyId"
}
