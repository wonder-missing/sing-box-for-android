package io.nekohasekai.sfa.vfs.relay

object VfsShareCipherFactory {
    fun create(): VfsShareCipher = KeystoreVfsShareCipher()
}
