package io.nekohasekai.sfa.vfs

/**
 * The authoritative view of what the app grants over the virtual directory.
 * Implementations must enforce their own path confinement; vfsd's checks are
 * not a substitute.
 */
interface VirtualDirectorySource {
    suspend fun stat(path: String): VfsEntry

    suspend fun list(path: String): List<VfsEntry>

    suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead
}
