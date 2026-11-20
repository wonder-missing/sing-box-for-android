package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.InboxEntry
import io.nekohasekai.sfa.agent.InboxRegistry

/**
 * Read-only virtual view of files shared with Conductor. It lives under
 * `conductor/resources/inbox/<id>/<name>` and resolves directly to the original
 * image-store file or content URI; nothing is copied into the workspace.
 */
class InboxVirtualDirectorySource(
    private val registry: InboxRegistry,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualDirectorySegments(path)
        val parsed = parse(segments) ?: throw notFound()
        return when (parsed) {
            is Parsed.Root, is Parsed.IdDir -> directoryEntry(segments)
            is Parsed.File -> fileEntry(parsed.entry)
        }
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualDirectorySegments(path)
        return when (val parsed = parse(segments)) {
            is Parsed.Root -> {
                registry
                    .entries()
                    .filter { it.source.exists() }
                    .sortedBy { it.id }
                    .map { entry ->
                        directoryEntry(
                            listOf(ROOT_CONDUCTOR, ROOT_RESOURCES, ROOT_INBOX, entry.id),
                        )
                    }
            }

            is Parsed.IdDir -> listOf(fileEntry(parsed.entry))
            is Parsed.File -> throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Files are not listable.")
            null -> throw notFound()
        }
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
        val parsed = parse(segments) ?: throw notFound()
        if (parsed !is Parsed.File) {
            throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        }
        val entry = parsed.entry
        val size = entry.size
        if (offset > size) throw rangeNotSatisfiable()
        if (offset == size || length == 0) {
            if (offset < size) throw VfsException(VfsErrorCode.BAD_REQUEST, "Read length must be positive before EOF.")
            return VfsRead(ByteArray(0), true)
        }
        val wanted = minOf(length.toLong(), size - offset).toInt()
        return try {
            entry.source.openAt(offset).use { input ->
                val data = ByteArray(wanted)
                var read = 0
                while (read < wanted) {
                    val n = input.read(data, read, wanted - read)
                    if (n <= 0) break
                    read += n
                }
                val eof = offset + read >= size
                VfsRead(if (read == data.size) data else data.copyOf(read), eof)
            }
        } catch (e: Exception) {
            throw VfsException(VfsErrorCode.NOT_FOUND, "The shared file is no longer available.")
        }
    }

    fun isEmpty(): Boolean = registry.entries().none { it.source.exists() }

    private fun parse(segments: List<String>): Parsed? {
        if (segments.size < 3) return null
        if (segments[0] != ROOT_CONDUCTOR || segments[1] != ROOT_RESOURCES || segments[2] != ROOT_INBOX) {
            return null
        }
        return when (segments.size) {
            3 -> Parsed.Root
            4 -> {
                val entry = registry.entry(segments[3])?.takeIf { it.source.exists() } ?: return null
                Parsed.IdDir(entry)
            }

            5 -> {
                val entry = registry.entry(segments[3])?.takeIf { it.source.exists() } ?: return null
                if (entry.name != segments[4]) return null
                Parsed.File(entry)
            }

            else -> null
        }
    }

    private fun directoryEntry(segments: List<String>): VfsEntry =
        VfsEntry(
            path = segments.joinToString("/"),
            name = segments.lastOrNull().orEmpty(),
            isDirectory = true,
            size = 0,
            mtimeMs = now(),
        )

    private fun fileEntry(entry: InboxEntry): VfsEntry =
        VfsEntry(
            path = "$ROOT_PATH/${entry.id}/${entry.name}",
            name = entry.name,
            isDirectory = false,
            size = entry.size,
            mtimeMs = entry.createdAt.takeIf { it > 0 } ?: now(),
            mimeType = entry.mimeType,
            etag = "\"${entry.size}-${entry.createdAt}\"",
        )

    private sealed interface Parsed {
        data object Root : Parsed

        data class IdDir(val entry: InboxEntry) : Parsed

        data class File(val entry: InboxEntry) : Parsed
    }

    private fun notFound(): VfsException = VfsException(VfsErrorCode.NOT_FOUND, "No such path.")

    private fun rangeNotSatisfiable(): VfsException =
        VfsException(VfsErrorCode.RANGE_NOT_SATISFIABLE, "The read offset is past the end of the file.")

    private companion object {
        const val ROOT_CONDUCTOR = "conductor"
        const val ROOT_RESOURCES = "resources"
        const val ROOT_INBOX = "inbox"
        const val ROOT_PATH = "conductor/resources/inbox"
    }
}
