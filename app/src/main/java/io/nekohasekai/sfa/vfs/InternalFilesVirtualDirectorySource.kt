package io.nekohasekai.sfa.vfs

import java.io.File
import java.io.RandomAccessFile

/**
 * Read-only view of the app's internal files directory. The VFS control plane
 * (`vfs/`) is deliberately excluded: a scope must not be able to observe or
 * feed back its own trust, resume or device state.
 */
class InternalFilesVirtualDirectorySource(
    baseDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val base = baseDir.canonicalFile

    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return directoryEntry("", "")
        rejectExcluded(segments)
        val file = resolve(segments)
        if (!file.exists()) throw notFound()
        return entry(segments.joinToString("/"), file)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) {
            return base
                .listFiles()
                ?.filter { it.name != EXCLUDED_DIR }
                ?.sortedBy { it.name }
                ?.map { entry(it.name, it) }
                .orEmpty()
        }
        rejectExcluded(segments)
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
        if (offset < 0 || length < 0 || length > MAX_READ_BYTES) {
            throw VfsException(VfsErrorCode.BAD_REQUEST, "Invalid read range.")
        }
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        rejectExcluded(segments)
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

    private fun rejectExcluded(segments: List<String>) {
        if (segments.first() == EXCLUDED_DIR) throw notFound()
    }

    private fun resolve(segments: List<String>): File {
        val candidate = File(base, segments.joinToString("/")).canonicalFile
        if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) {
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
                mimeType = VfsFileMetadata.mimeTypeFor(file.name),
                etag = VfsFileMetadata.fileEtag(file.length(), mtime),
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
        const val EXCLUDED_DIR = "vfs"
        const val MAX_READ_BYTES = 1024 * 1024
    }
}
