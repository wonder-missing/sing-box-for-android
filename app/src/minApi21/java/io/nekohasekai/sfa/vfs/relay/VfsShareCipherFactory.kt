package io.nekohasekai.sfa.vfs.relay

/** API 21 builds hide Share Key VFS; there is no Keystore AES-256-GCM path. */
object VfsShareCipherFactory {
    fun create(): VfsShareCipher = NoopVfsShareCipher
}
