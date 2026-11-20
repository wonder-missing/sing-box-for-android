package io.nekohasekai.sfa.vfs

import io.nekohasekai.sfa.agent.ConductorFileStore
import io.nekohasekai.sfa.agent.StoredFile
import java.io.File
import java.io.RandomAccessFile

/** Runtime files are generated on read; they are never written to disk. */
interface VirtualDirectoryRuntimeFiles {
    fun traceMarkdown(): String

    fun conversationMarkdown(): String

    fun systemPromptMarkdown(): String
}

/**
 * The first grant: `conductor/` from the Conductor file workspace plus three
 * generated `.runtime` files. Everything else is invisible. Paths are
 * provider-relative and confined to [workingDir] by [ConductorFileStore].
 */
class ConductorVirtualDirectorySource(
    workingDir: File,
    private val runtimeFiles: VirtualDirectoryRuntimeFiles,
    private val inbox: InboxVirtualDirectorySource? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val fileStore = ConductorFileStore(workingDir)
    private val workspaceDir: File = fileStore.workspaceDir

    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) return directoryEntry(ROOT_PATH, ROOT_PATH)
        return when (segments.first()) {
            CONDUCTOR_DIR -> conductorStat(segments)
            RUNTIME_DIR -> runtimeStat(segments)
            else -> throw notFound()
        }
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualDirectorySegments(path)
        if (segments.isEmpty()) {
            return listOf(
                directoryEntry(CONDUCTOR_DIR, CONDUCTOR_DIR),
                directoryEntry(RUNTIME_DIR, RUNTIME_DIR),
            )
        }
        return when (segments.first()) {
            CONDUCTOR_DIR -> conductorList(segments)
            RUNTIME_DIR -> runtimeList(segments)
            else -> throw notFound()
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
        if (segments.isEmpty()) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        return when (segments.first()) {
            CONDUCTOR_DIR -> readConductor(segments, offset, length)
            RUNTIME_DIR -> readRuntime(segments, offset, length)
            else -> throw notFound()
        }
    }

    private suspend fun conductorStat(segments: List<String>): VfsEntry {
        if (segments.size == 1) return conductorDirectoryEntry()
        if (inbox != null && isInboxPath(segments)) {
            return inbox.stat(segments.joinToString("/"))
        }
        if (inboxVisible() && isResourcesPath(segments)) {
            val file = resolveConductorFile(segments)
            if (file == null || !file.exists()) {
                return directoryEntry(RESOURCES_PATH, RESOURCES_DIR)
            }
        }
        val file = resolveConductorFile(segments) ?: throw notFound()
        if (!file.exists()) throw notFound()
        if (file.isDirectory) {
            return VfsEntry(
                path = segments.joinToString("/"),
                name = segments.last(),
                isDirectory = true,
                size = 0,
                mtimeMs = file.lastModified().takeIf { it > 0 } ?: now(),
            )
        }
        if (!file.isFile) throw notFound()
        return VfsEntry(
            path = segments.joinToString("/"),
            name = segments.last(),
            isDirectory = false,
            size = file.length(),
            mtimeMs = file.lastModified().takeIf { it > 0 } ?: now(),
            mimeType = mimeTypeFor(segments.last()),
            etag = fileEtag(file.length(), file.lastModified()),
        )
    }

    private suspend fun conductorList(segments: List<String>): List<VfsEntry> {
        if (segments.size == 1 && !workspaceDir.isDirectory && !inboxVisible()) return emptyList()
        if (inbox != null && isInboxPath(segments)) {
            return inbox.list(segments.joinToString("/"))
        }
        val storedResult =
            runCatching { fileStore.list(segments.joinToString("/")) }
        val stored = storedResult.getOrNull()
        if (stored == null && !(inboxVisible() && (segments.size == 1 || isResourcesPath(segments)))) {
            throw notFound()
        }
        val entries =
            stored
                .orEmpty()
                .map { it.toEntry() }
                .toMutableList()
        if (inboxVisible()) {
            when {
                isResourcesPath(segments) -> {
                    if (entries.none { it.name == INBOX_DIR }) {
                        entries += directoryEntry("$RESOURCES_PATH/$INBOX_DIR", INBOX_DIR)
                    }
                }

                segments.size == 1 -> {
                    if (entries.none { it.name == RESOURCES_DIR }) {
                        entries += directoryEntry(RESOURCES_PATH, RESOURCES_DIR)
                    }
                }
            }
        }
        return entries.sortedBy { it.name }
    }

    private fun isInboxPath(segments: List<String>): Boolean =
        segments.size >= 3 &&
            segments[0] == CONDUCTOR_DIR &&
            segments[1] == RESOURCES_DIR &&
            segments[2] == INBOX_DIR

    private fun isResourcesPath(segments: List<String>): Boolean =
        segments.size == 2 && segments[0] == CONDUCTOR_DIR && segments[1] == RESOURCES_DIR

    private fun inboxVisible(): Boolean = inbox != null && !inbox.isEmpty()

    private fun conductorDirectoryEntry(): VfsEntry =
        VfsEntry(
            path = CONDUCTOR_DIR,
            name = CONDUCTOR_DIR,
            isDirectory = true,
            size = 0,
            mtimeMs = workspaceDir.lastModified().takeIf { it > 0 } ?: now(),
        )

    private fun resolveConductorFile(segments: List<String>): File? =
        runCatching { fileStore.resolveRead(segments.joinToString("/")) }.getOrNull()

    private suspend fun readConductor(
        segments: List<String>,
        offset: Long,
        length: Int,
    ): VfsRead {
        if (segments.size == 1) throw VfsException(VfsErrorCode.NOT_SUPPORTED, "Directories are not readable.")
        if (inbox != null && isInboxPath(segments)) {
            return inbox.read(segments.joinToString("/"), offset, length)
        }
        val file = resolveConductorFile(segments) ?: throw notFound()
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

    private fun runtimeStat(segments: List<String>): VfsEntry {
        if (segments.size == 1) return directoryEntry(RUNTIME_DIR, RUNTIME_DIR)
        if (segments.size != 2 || segments[1] !in RUNTIME_FILES) throw notFound()
        return runtimeEntry(segments[1])
    }

    private fun runtimeList(segments: List<String>): List<VfsEntry> {
        if (segments.size != 1) throw notFound()
        return RUNTIME_FILES.map { runtimeEntry(it) }
    }

    private fun runtimeEntry(name: String): VfsEntry {
        val bytes = runtimeBytes(name)
        return VfsEntry(
            path = "$RUNTIME_DIR/$name",
            name = name,
            isDirectory = false,
            size = bytes.size.toLong(),
            mtimeMs = now(),
            mimeType = MIME_MARKDOWN,
            etag = contentEtag(bytes),
        )
    }

    private fun readRuntime(
        segments: List<String>,
        offset: Long,
        length: Int,
    ): VfsRead {
        if (segments.size != 2 || segments[1] !in RUNTIME_FILES) throw notFound()
        val bytes = runtimeBytes(segments[1])
        val size = bytes.size.toLong()
        if (offset > size) throw rangeNotSatisfiable()
        if (offset == size || length == 0) {
            if (offset < size) throw VfsException(VfsErrorCode.BAD_REQUEST, "Read length must be positive before EOF.")
            return VfsRead(ByteArray(0), true)
        }
        val end = minOf(bytes.size.toLong(), offset + length.toLong()).toInt()
        return VfsRead(bytes.copyOfRange(offset.toInt(), end), end >= bytes.size)
    }

    private fun runtimeBytes(name: String): ByteArray =
        when (name) {
            "trace.md" -> runtimeFiles.traceMarkdown()
            "conversation.md" -> runtimeFiles.conversationMarkdown()
            "system_prompt.md" -> runtimeFiles.systemPromptMarkdown()
            else -> throw notFound()
        }.toByteArray(Charsets.UTF_8)

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

    private fun StoredFile.toEntry(): VfsEntry {
        val entryName = path.substringAfterLast('/')
        return VfsEntry(
            path = path,
            name = entryName,
            isDirectory = directory,
            size = if (directory) 0 else size,
            mtimeMs = modifiedAt.takeIf { it > 0 } ?: now(),
            mimeType = if (directory) null else mimeTypeFor(entryName),
            etag = if (directory) null else fileEtag(size, modifiedAt),
        )
    }

    companion object {
        internal const val ROOT_PATH = ""
        internal const val CONDUCTOR_DIR = "conductor"
        internal const val RESOURCES_DIR = "resources"
        internal const val RESOURCES_PATH = "conductor/resources"
        internal const val INBOX_DIR = "inbox"
        internal const val RUNTIME_DIR = ".runtime"
        internal const val MAX_PATH_CHARS = 4096
        internal const val MIME_MARKDOWN = "text/markdown; charset=utf-8"
        internal val RUNTIME_FILES = listOf("trace.md", "conversation.md", "system_prompt.md")

        internal fun mimeTypeFor(name: String): String =
            when (name.substringAfterLast('.', "").lowercase()) {
                "md", "markdown" -> MIME_MARKDOWN
                "txt", "log" -> "text/plain; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "yaml", "yml" -> "application/yaml; charset=utf-8"
                "csv" -> "text/csv; charset=utf-8"
                else -> "application/octet-stream"
            }

        internal fun fileEtag(
            size: Long,
            modifiedAt: Long,
        ): String = "\"$size-$modifiedAt\""

        private fun contentEtag(bytes: ByteArray): String =
            "\"${bytes.size}-${bytes.contentHashCode().toUInt().toString(16)}\""

        private fun notFound(): VfsException = VfsException(VfsErrorCode.NOT_FOUND, "No such path.")

        private fun rangeNotSatisfiable(): VfsException =
            VfsException(VfsErrorCode.RANGE_NOT_SATISFIABLE, "The read offset is past the end of the file.")
    }
}

/**
 * Splits a provider path into segments. The protocol requires relative paths
 * without traversal; the app rejects rather than normalizes anything it would
 * not serve.
 */
internal fun virtualDirectorySegments(path: String): List<String> {
    if (path.length > ConductorVirtualDirectorySource.MAX_PATH_CHARS) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path is too long.")
    }
    if (path.startsWith("/")) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Paths must be relative.")
    }
    if (path.any { it == '\u0000' || it == '\\' || it.code < 0x20 }) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path contains a forbidden character.")
    }
    if (path.isEmpty()) return emptyList()
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw VfsException(VfsErrorCode.INVALID_PATH, "Path traversal is not allowed.")
    }
    return segments
}
