package io.nekohasekai.sfa.vfs

/**
 * Narrows the full Conductor source to the exact scope set the user approved.
 * Paths outside the grant are reported as not found, never as forbidden, so a
 * caller cannot probe what else exists.
 */
class VfsScopedSource(
    private val delegate: VirtualDirectorySource,
    scopes: Set<VfsScope>,
) : VirtualDirectorySource {
    private val scopes = scopes.toSet()

    override suspend fun stat(path: String): VfsEntry {
        if (!allowed(path)) throw notFound()
        return delegate.stat(path)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        if (!allowed(path)) throw notFound()
        return delegate.list(path).filter { allowed(it.path) }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        if (!allowed(path)) throw notFound()
        return delegate.read(path, offset, length)
    }

    private fun allowed(path: String): Boolean =
        runCatching { allowedSegments(virtualDirectorySegments(path)) }.getOrDefault(false)

    private fun allowedSegments(segments: List<String>): Boolean {
        if (segments.isEmpty()) return true
        return when (segments.first()) {
            "conductor" -> VfsScope.CONDUCTOR in scopes
            ".runtime" -> {
                if (segments.size == 1) {
                    scopes.any { it != VfsScope.CONDUCTOR }
                } else {
                    when (segments[1]) {
                        "trace.md" -> VfsScope.TRACE in scopes
                        "system_prompt.md" -> VfsScope.SYSTEM_PROMPT in scopes
                        "conversation.md" -> VfsScope.CONVERSATION in scopes
                        else -> false
                    }
                }
            }

            else -> false
        }
    }

    private fun notFound(): VfsException =
        VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
}
