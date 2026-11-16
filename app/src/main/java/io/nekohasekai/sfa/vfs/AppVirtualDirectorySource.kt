package io.nekohasekai.sfa.vfs

/**
 * Merges the external working directory, the internal files directory
 * (`appfiles/`) and the virtual `.runtime/` files into one provider view.
 */
class AppVirtualDirectorySource(
    private val external: WorkspaceVirtualDirectorySource?,
    private val conductor: ConductorVirtualDirectorySource,
    private val internalFiles: InternalFilesVirtualDirectorySource,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return directoryEntry("", "")
        return when (segments.first()) {
            APPFILES_DIR -> rebase(internalFiles.stat(relative(segments)))
            CONDUCTOR_DIR, RUNTIME_DIR -> conductor.stat(path)
            else -> external?.stat(path) ?: throw notFound()
        }
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return listRoot()
        return when (segments.first()) {
            APPFILES_DIR -> internalFiles.list(relative(segments)).map(::rebase)
            CONDUCTOR_DIR, RUNTIME_DIR -> conductor.list(path)
            else -> external?.list(path) ?: throw notFound()
        }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        return when (segments.first()) {
            APPFILES_DIR -> internalFiles.read(relative(segments), offset, length)
            CONDUCTOR_DIR, RUNTIME_DIR -> conductor.read(path, offset, length)
            else -> external?.read(path, offset, length) ?: throw notFound()
        }
    }

    private suspend fun listRoot(): List<VfsEntry> {
        val entries = LinkedHashMap<String, VfsEntry>()
        if (external != null) {
            external.list("").forEach { entry ->
                if (entry.name == APPFILES_DIR || entry.name == RUNTIME_DIR) return@forEach
                entries[entry.name] = entry
            }
        }
        if (CONDUCTOR_DIR !in entries) {
            entries[CONDUCTOR_DIR] = conductor.stat(CONDUCTOR_DIR)
        }
        entries[APPFILES_DIR] = directoryEntry(APPFILES_DIR, APPFILES_DIR)
        entries[RUNTIME_DIR] = conductor.stat(RUNTIME_DIR)
        return entries.values.toList()
    }

    private fun relative(segments: List<String>): String =
        segments.drop(1).joinToString("/")

    private fun rebase(entry: VfsEntry): VfsEntry =
        if (entry.path.isEmpty()) {
            entry.copy(path = APPFILES_DIR, name = APPFILES_DIR)
        } else {
            entry.copy(path = "$APPFILES_DIR/${entry.path}")
        }

    private fun directoryEntry(
        path: String,
        name: String,
    ): VfsEntry =
        VfsEntry(
            path = path,
            name = name,
            isDirectory = true,
            size = 0,
            mtimeMs = now(),
        )

    private fun notFound(): VfsException = VfsException(VfsErrorCode.NOT_FOUND, "No such path.")

    private companion object {
        const val CONDUCTOR_DIR = "conductor"
        const val RUNTIME_DIR = ".runtime"
        const val APPFILES_DIR = "appfiles"
    }
}
