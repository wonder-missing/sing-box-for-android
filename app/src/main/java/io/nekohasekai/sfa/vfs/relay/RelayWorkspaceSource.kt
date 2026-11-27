package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource
import java.io.File
import java.io.RandomAccessFile

/** Local MIME mapping so the Relay path does not depend on Conductor sources. */
object RelayMimeTypes {
    fun forName(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown; charset=utf-8"
            "txt", "log" -> "text/plain; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "yaml", "yml" -> "application/yaml; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            else -> "application/octet-stream"
        }
}

/**
 * Raw read-only view of the app's external files directory. Physical
 * `conductor/` and `.runtime/` stay excluded as a security boundary, together
 * with the reserved virtual roots. Canonical paths keep symlinks inside.
 */
class RelayWorkspaceSource(
    baseDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val base = baseDir.canonicalFile

    override suspend fun stat(path: String): VfsEntry {
        val segments = segments(path)
        if (segments.isEmpty()) return directoryEntry("", "")
        rejectExcluded(segments)
        val file = resolve(segments)
        if (!file.exists()) throw notFound()
        return entry(segments.joinToString("/"), file)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = segments(path)
        if (segments.isEmpty()) {
            return base
                .listFiles()
                ?.filterNot { it.name in EXCLUDED_ROOTS }
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
        if (offset < 0 || length < 0 || length > RelayWire.MAX_READ_BYTES) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST, "Invalid read range.")
        }
        val segments = segments(path)
        if (segments.isEmpty()) throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        rejectExcluded(segments)
        val file = resolve(segments)
        if (file.isDirectory) throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        if (!file.isFile) throw notFound()
        val size = file.length()
        if (offset > size) throw rangeNotSatisfiable()
        if (offset == size || length == 0) {
            if (offset < size) throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST, "Read length must be positive before EOF.")
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
        if (segments.first() in EXCLUDED_ROOTS) throw notFound()
    }

    private fun resolve(segments: List<String>): File {
        val candidate = File(base, segments.joinToString("/")).canonicalFile
        if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) {
            throw notFound()
        }
        val relative = candidate.relativeTo(base).invariantSeparatorsPath
        if (relative.isNotEmpty() && relative.substringBefore('/') in EXCLUDED_ROOTS) throw notFound()
        return candidate
    }

    private fun entry(
        path: String,
        file: File,
    ): VfsEntry {
        val mtime = file.lastModified().takeIf { it > 0 } ?: now()
        return if (file.isDirectory) {
            VfsEntry(path, file.name.ifEmpty { path }, true, 0, mtime)
        } else {
            VfsEntry(
                path = path,
                name = file.name,
                isDirectory = false,
                size = file.length(),
                mtimeMs = mtime,
                mimeType = RelayMimeTypes.forName(file.name),
                etag = "\"${file.length()}-$mtime\"",
            )
        }
    }

    private fun directoryEntry(
        path: String,
        name: String,
    ): VfsEntry = VfsEntry(path, name, true, 0, now())

    private fun segments(path: String): List<String> {
        if (path.length > 4096) throw badRequest()
        if (path.startsWith("/")) throw badRequest()
        if (path.isEmpty()) return emptyList()
        if (path.any { it == '\u0000' || it == '\\' || it.code < 0x20 }) throw badRequest()
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw badRequest()
        return parts
    }

    private fun notFound(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "No such path.")

    private fun badRequest(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST, "Invalid path.")

    private fun rangeNotSatisfiable(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.RANGE_NOT_SATISFIABLE, "The read offset is past the end of the file.")

    companion object {
        internal val EXCLUDED_ROOTS =
            setOf("vfs", "conductor", ".runtime", "appfiles", "appdata", "selected")
    }
}

/** `app.files` view: filesDir minus physical `vfs/` and `conductor/`. */
class RelayAppFilesSource(
    private val delegate: VirtualDirectorySource,
) : VirtualDirectorySource {
    override suspend fun stat(path: String): VfsEntry {
        rejectBlocked(path)
        return delegate.stat(path)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        rejectBlocked(path)
        return delegate.list(path).filterNot { blocked(it.path) }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        rejectBlocked(path)
        return delegate.read(path, offset, length)
    }

    private fun rejectBlocked(path: String) {
        if (blocked(path)) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "No such path.")
        }
    }

    private fun blocked(path: String): Boolean =
        path.substringBefore('/') in BLOCKED_ROOTS

    private companion object {
        val BLOCKED_ROOTS = setOf("vfs", "conductor")
    }
}
