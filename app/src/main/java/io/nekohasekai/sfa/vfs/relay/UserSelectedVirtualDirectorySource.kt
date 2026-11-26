package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource
import java.security.MessageDigest

data class SelectedDocument(
    val uri: String,
    val name: String,
    val size: Long,
    val mtimeMs: Long,
    val mimeType: String?,
)

interface SelectedDocumentResolver {
    fun documents(uris: List<String>): List<SelectedDocument>

    fun read(
        uri: String,
        offset: Long,
        length: Int,
    ): VfsRead
}

class SelectedPermissionLostException(message: String) : Exception(message)

/**
 * Read-only SAF view under selected/. Files are never copied; names are
 * `<id>-<sanitized-name>` and v1 supports files only (directories are
 * `not_supported`). Permission loss is reported as `permission_lost`, not
 * `not_found`, so the CLI can tell the user to pick files again.
 */
class UserSelectedVirtualDirectorySource(
    private val resolver: SelectedDocumentResolver,
    uris: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val documents: List<SelectedDocument>
    private val names: Map<String, SelectedDocument>

    init {
        val resolved = runCatching { resolver.documents(uris) }.getOrDefault(emptyList())
        val used = HashMap<String, Int>()
        documents =
            resolved.map { document ->
                val id = idOf(document.uri)
                val sanitized = sanitize(document.name)
                val base = "$id-$sanitized"
                val count = used.getOrDefault(base, 0)
                used[base] = count + 1
                val name = if (count == 0) base else "$base-${count + 1}"
                document.copy(name = name)
            }
        names = documents.associateBy { it.name }
    }

    override suspend fun stat(path: String): VfsEntry {
        val segments = segments(path)
        if (segments.isEmpty()) {
            return VfsEntry("selected", "selected", true, 0, now())
        }
        if (segments.size != 1) throw notFound()
        val document = names[segments[0]] ?: throw notFound()
        return entry(document)
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = segments(path)
        if (segments.isNotEmpty()) throw notSupported()
        return documents.map(::entry).sortedBy { it.name }
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
        if (segments.size != 1) throw notSupported()
        val document = names[segments[0]] ?: throw notFound()
        return try {
            resolver.read(document.uri, offset, length)
        } catch (_: SelectedPermissionLostException) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.PERMISSION_LOST, "File permission was lost.")
        } catch (_: Exception) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "The shared file is no longer available.")
        }
    }

    private fun entry(document: SelectedDocument): VfsEntry =
        VfsEntry(
            path = "selected/${document.name}",
            name = document.name,
            isDirectory = false,
            size = document.size,
            mtimeMs = document.mtimeMs.takeIf { it > 0 } ?: now(),
            mimeType = document.mimeType,
            etag = "\"${document.size}-${document.mtimeMs}\"",
        )

    private fun segments(path: String): List<String> {
        if (path.isEmpty() || path == "selected") return emptyList()
        val relative =
            when {
                path.startsWith("selected/") -> path.removePrefix("selected/")
                else -> throw notFound()
            }
        if (relative.contains("//")) throw notFound()
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw notFound()
        return parts
    }

    private fun idOf(uri: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sanitize(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("")
        return cleaned.trim().ifEmpty { "file" }.take(80)
    }

    private fun notFound(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "No such path.")

    private fun notSupported(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_SUPPORTED, "Directories are not supported.")
}
