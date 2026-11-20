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
            "appfiles" -> VfsScope.APPFILES in scopes
            "conductor" -> {
                // The inbox maps app-private image-store files and picker
                // content URIs. A workdir-only grant must not read it.
                val inbox = segments.size >= 3 && segments[1] == "resources" && segments[2] == "inbox"
                if (inbox) {
                    VfsScope.CONDUCTOR in scopes
                } else {
                    VfsScope.CONDUCTOR in scopes || VfsScope.WORKDIR in scopes
                }
            }
            ".runtime" -> {
                if (segments.size == 1) {
                    RUNTIME_SCOPES.any { it in scopes }
                } else {
                    when (segments[1]) {
                        "trace.md" -> VfsScope.TRACE in scopes
                        "system_prompt.md" -> VfsScope.SYSTEM_PROMPT in scopes
                        "conversation.md" -> VfsScope.CONVERSATION in scopes
                        else -> false
                    }
                }
            }

            else -> VfsScope.WORKDIR in scopes
        }
    }

    private companion object {
        val RUNTIME_SCOPES = setOf(VfsScope.TRACE, VfsScope.SYSTEM_PROMPT, VfsScope.CONVERSATION)
    }

    private fun notFound(): VfsException =
        VfsException(VfsErrorCode.NOT_FOUND, "No such path.")
}
