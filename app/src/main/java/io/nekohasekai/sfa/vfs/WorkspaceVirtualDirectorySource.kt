package io.nekohasekai.sfa.vfs

import java.io.File
import java.io.RandomAccessFile

/**
 * Exposes the app's external working directory at the virtual root when the
 * workdir scope is granted. `conductor/` and `.runtime/` keep the existing
 * Conductor source semantics; every other path is served read-only from
 * [workingDir]. Paths are canonicalized so a symlink cannot escape the root.
 */
class WorkspaceVirtualDirectorySource(
    workingDir: File,
    private val conductorSource: ConductorVirtualDirectorySource,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val baseDir = workingDir.canonicalFile

    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return directoryEntry("", "")
        if (isConductorPath(segments)) return conductorSource.stat(path)
        val file = resolve(segments)
        if (!file.exists()) throw notFound()
        return entry(segments.joinToString("/"), file)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return listRoot()
        if (isConductorPath(segments)) return conductorSource.list(path)
        val dir = resolve(segments)
        if (!dir.isDirectory) throw notFound()
        return dir
            .listFiles()
            ?.sortedBy { it.name }
            ?.map { entry((segments + it.name).joinToString("/"), it) }
            .orEmpty()
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        if (offset < 0 || length < 0 || length > VfsProtocol.MAX_READ_BYTES) {
            throw VfsException(VfsErrorCode.BAD_REQUEST, "Invalid read range.")
        }
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        if (isConductorPath(segments)) return conductorSource.read(path, offset, length)
        val file = resolve(segments)
        if (file.isDirectory) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        if (!file.isFile) throw notFound()
        val size = file.length()
        if (offset > size) throw rangeNotSatisfiable()
        if (offset == size || length == 0) {
            if (offset < size) throw VfsException(VfsErrorCode.BAD_REQUEST, "Read length must be positive before EOF.")
            return VfsRead(ByteArray(0), true)
        }
        val wanted = minOf(length.toLong(), size - offset).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val data = ByteArray(wanted)
            raf.readFully(data)
            return VfsRead(data, offset + wanted >= size)
        }
    }

    private suspend fun listRoot(): List<VfsEntry> {
        val entries = LinkedHashMap<String, VfsEntry>()
        entries[CONDUCTOR_DIR] = conductorSource.stat(CONDUCTOR_DIR)
        baseDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.name == RUNTIME_DIR) return@forEach
            entries[file.name] = entry(file.name, file)
        }
        entries[RUNTIME_DIR] = conductorSource.stat(RUNTIME_DIR)
        return entries.values.toList()
    }

    private fun isConductorPath(segments: List<String>): Boolean =
        segments.first() == CONDUCTOR_DIR || segments.first() == RUNTIME_DIR

    private fun resolve(segments: List<String>): File {
        val candidate = File(baseDir, segments.joinToString("/")).canonicalFile
        if (candidate.path != baseDir.path && !candidate.path.startsWith(baseDir.path + File.separator)) {
            throw notFound()
        }
        return candidate
    }

    private fun entry(
        path: String,
        file: File,
    ): VfsEntry {
        val directory = file.isDirectory
        val mtime = file.lastModified().takeIf { it > 0 } ?: now()
        return if (directory) {
            VfsEntry(
                path = path,
                name = file.name.ifEmpty { path },
                isDirectory = true,
                size = 0,
                mtimeMs = mtime,
            )
        } else {
            VfsEntry(
                path = path,
                name = file.name,
                isDirectory = false,
                size = file.length(),
                mtimeMs = mtime,
                mimeType = ConductorVirtualDirectorySource.mimeTypeFor(file.name),
                etag = ConductorVirtualDirectorySource.fileEtag(file.length(), mtime),
            )
        }
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

    private fun rangeNotSatisfiable(): VfsException =
        VfsException(VfsErrorCode.RANGE_NOT_SATISFIABLE, "The read offset is past the end of the file.")

    private companion object {
        const val CONDUCTOR_DIR = "conductor"
        const val RUNTIME_DIR = ".runtime"
    }
}
