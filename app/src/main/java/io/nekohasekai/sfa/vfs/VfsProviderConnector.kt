package io.nekohasekai.sfa.vfs

data class VfsProviderConnectRequest(
    val url: String,
    val token: String,
    val linkId: String,
    val deviceId: String,
    val resumeToken: String?,
    val scope: Set<VfsScope>,
)

data class VfsServerHello(
    val server: String,
    val resumeToken: String?,
    val expiresAt: Long?,
)

interface VfsProviderSession {
    val hello: VfsServerHello

    fun close(
        code: Int,
        reason: String,
    )
}

interface VfsProviderConnector {
    suspend fun connect(request: VfsProviderConnectRequest): VfsProviderSession
}

/** Replaced by the OkHttp connector in P6c; never claims a connection today. */
object UnavailableVfsProviderConnector : VfsProviderConnector {
    override suspend fun connect(request: VfsProviderConnectRequest): VfsProviderSession =
        throw VfsConnectException(
            "VFS_CONNECT_FAILED",
            "The VFS transport is not available in this build.",
        )
}
