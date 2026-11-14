package io.nekohasekai.sfa.vfs

data class VfsConsentRequest(
    val serverName: String,
    val server: String,
    val scope: Set<VfsScope>,
)

enum class VfsConsentDecision {
    DENY,
    ONCE,
    TRUST_15_DAYS,
}

interface VfsConsentPort {
    suspend fun request(request: VfsConsentRequest): VfsConsentDecision
}
