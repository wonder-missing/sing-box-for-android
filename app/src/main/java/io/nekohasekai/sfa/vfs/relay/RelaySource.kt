package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource

data class RelayListPage(
    val entries: List<VfsEntry>,
    val nextCursor: String?,
)

data class RelayReadResult(
    val data: ByteArray,
    val etag: String?,
    val offset: Long,
    val nextOffset: Long,
    val eof: Boolean,
)

interface VfsRelaySource {
    suspend fun stat(path: String): VfsEntry

    suspend fun list(
        path: String,
        cursor: String?,
        limit: Int,
    ): RelayListPage

    suspend fun read(
        path: String,
        offset: Long,
        length: Int,
        etag: String?,
    ): RelayReadResult
}

class RelaySourceException(
    val code: String,
    message: String,
) : Exception(message)

/** Absolute path rules for v1: `/` root, no traversal, no duplicate slashes. */
object RelayPaths {
    fun segments(path: String): List<String> {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.size > RelayWire.MAX_PATH_BYTES) {
            throw RelaySourceException(RelayInnerError.INVALID_PATH, "path too long")
        }
        if (!path.startsWith("/")) {
            throw RelaySourceException(RelayInnerError.INVALID_PATH, "path must be absolute")
        }
        if (path.any { it == '\u0000' || it == '\\' || it.code < 0x20 }) {
            throw RelaySourceException(RelayInnerError.INVALID_PATH, "path contains a forbidden character")
        }
        if (path == "/") return emptyList()
        if (path.endsWith("/")) {
            throw RelaySourceException(RelayInnerError.INVALID_PATH, "path must not end with a slash")
        }
        val raw = path.removePrefix("/").split('/')
        if (raw.any { it.isEmpty() || it == "." || it == ".." }) {
            throw RelaySourceException(RelayInnerError.INVALID_PATH, "path is not normalized")
        }
        return raw
    }

    fun join(segments: List<String>): String =
        if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")

    fun relative(segments: List<String>): String = segments.joinToString("/")

    fun isReservedRoot(name: String): Boolean = name in RESERVED_ROOTS

    private val RESERVED_ROOTS: Set<String> =
        setOf("vfs", "appfiles", "appdata", "conductor", ".runtime", "selected")
}

/**
 * Maps the absolute Share view onto the app's existing read-only sources. Old
 * source signatures are untouched: this layer validates scope/path, rebases
 * entries to absolute paths, sorts bytewise and owns the cursor.
 */
class RelayAbsoluteSource(
    private val scopes: Set<RelayScope>,
    private val internalFiles: VirtualDirectorySource,
    private val workspace: VirtualDirectorySource,
    private val appData: VirtualDirectorySource,
    private val selected: VirtualDirectorySource?,
    private val shareKey: ByteArray,
    private val linkIdRaw: ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) : VfsRelaySource {
    private val cursorKey = RelayCrypto.cursorKey(shareKey)

    override suspend fun stat(path: String): VfsEntry {
        val segments = RelayPaths.segments(path)
        if (segments.isEmpty()) return rootEntry()
        ensureAllowed(path)
        val routed = route(segments)
        val entry =
            try {
                routed.delegate.stat(routed.relative)
            } catch (error: VfsException) {
                throw mapVfsError(error)
            }
        return rebase(entry, routed.prefix)
    }

    override suspend fun list(
        path: String,
        cursor: String?,
        limit: Int,
    ): RelayListPage {
        if (limit !in 1..RelayWire.MAX_LIST_ENTRIES) {
            throw RelaySourceException(RelayInnerError.BAD_REQUEST, "invalid limit")
        }
        val segments = RelayPaths.segments(path)
        val entries =
            if (segments.isEmpty()) {
                rootList()
            } else {
                ensureAllowed(path)
                val routed = route(segments)
                if (!routed.listable) {
                    throw RelaySourceException(RelayInnerError.NOT_SUPPORTED, "not listable")
                }
                val raw =
                    try {
                        routed.delegate.list(routed.relative)
                    } catch (error: VfsException) {
                        throw mapVfsError(error)
                    }
                raw
                    .map { rebase(it, routed.prefix) }
                    .filter { allowedAbsolute(it.path) }
                    .sortedWith { a, b -> RelayRoots.compareUtf8(a.path, b.path) }
            }

        var startIndex = 0
        if (cursor != null) {
            val lastKey = RelayCursor.decode(cursorKey, linkIdRaw, path, limit, cursor)
                ?: throw RelaySourceException(RelayInnerError.CURSOR_INVALID, "cursor is invalid")
            val lastKeyText = String(lastKey, Charsets.UTF_8)
            val index = entries.indexOfFirst { it.path == lastKeyText }
            if (index < 0) {
                throw RelaySourceException(RelayInnerError.CURSOR_INVALID, "cursor entry no longer exists")
            }
            startIndex = index + 1
        }

        val page = ArrayList<VfsEntry>()
        var budget = 0
        var index = startIndex
        while (index < entries.size && page.size < limit) {
            val entry = entries[index]
            val entryBytes = RelayWire.entryJsonBytes(entry)
            if (entryBytes > RelayWire.MAX_ENTRY_BYTES) {
                throw RelaySourceException(RelayInnerError.TOO_LARGE, "entry is too large")
            }
            if (page.isNotEmpty() && budget + entryBytes > RelayWire.MAX_LIST_PAGE_BYTES) break
            page += entry
            budget += entryBytes
            index++
        }
        val nextCursor =
            if (index < entries.size) {
                val lastKey = page.lastOrNull()?.path?.toByteArray(Charsets.UTF_8)
                    ?: throw RelaySourceException(RelayInnerError.CURSOR_INVALID, "cursor bound is empty")
                RelayCursor.encode(cursorKey, linkIdRaw, path, limit, lastKey)
            } else {
                null
            }
        return RelayListPage(page, nextCursor)
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
        etag: String?,
    ): RelayReadResult {
        if (offset < 0 || length < 0 || length > RelayWire.MAX_READ_BYTES) {
            throw RelaySourceException(RelayInnerError.BAD_REQUEST, "invalid read range")
        }
        val segments = RelayPaths.segments(path)
        if (segments.isEmpty()) {
            throw RelaySourceException(RelayInnerError.NOT_SUPPORTED, "directories are not readable")
        }
        ensureAllowed(path)
        val routed = route(segments)
        if (!routed.readable) {
            throw RelaySourceException(RelayInnerError.NOT_SUPPORTED, "not readable")
        }
        val current = stat(path)
        if (etag != null && current.etag != null && current.etag != etag) {
            throw RelaySourceException(RelayInnerError.BAD_REQUEST, "file changed; restat and retry")
        }
        val read =
            try {
                routed.delegate.read(routed.relative, offset, length)
            } catch (error: VfsException) {
                throw mapVfsError(error)
            }
        val nextOffset = offset + read.data.size
        return RelayReadResult(
            data = read.data,
            etag = current.etag,
            offset = offset,
            nextOffset = nextOffset,
            eof = read.eof,
        )
    }

    fun roots(): List<String> = RelayRoots.of(scopes)

    fun scopesSorted(): List<String> = RelayRoots.scopesSorted(scopes)

    private suspend fun rootList(): List<VfsEntry> {
        val entries = LinkedHashMap<String, VfsEntry>()
        if (RelayScope.APP_WORKSPACE in scopes) {
            try {
                workspace.list("").forEach { raw ->
                    if (RelayPaths.isReservedRoot(raw.name)) return@forEach
                    val entry = rebase(raw, "/")
                    entries[entry.path] = entry
                }
            } catch (_: Exception) {
                // A missing external directory still exposes the virtual roots.
            }
        }
        for (scope in scopes) {
            if (scope == RelayScope.APP_WORKSPACE) continue
            val path = scope.root
            val entry =
                runCatching {
                    val routed = route(RelayPaths.segments(path))
                    rebase(routed.delegate.stat(routed.relative), routed.prefix)
                        .copy(path = path)
                }.getOrNull() ?: virtualEntry(path)
            entries[path] = entry
        }
        return entries.values.sortedWith { a, b -> RelayRoots.compareUtf8(a.path, b.path) }
    }

    private fun virtualEntry(path: String): VfsEntry =
        VfsEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = true,
            size = 0,
            mtimeMs = now(),
        )

    private fun rootEntry(): VfsEntry =
        VfsEntry(path = "/", name = "/", isDirectory = true, size = 0, mtimeMs = now())

    private fun rebase(
        entry: VfsEntry,
        prefix: String,
    ): VfsEntry {
        val path =
            when {
                prefix == "/" -> if (entry.path.isEmpty()) "/" else "/${entry.path}"
                entry.path.isEmpty() -> prefix
                else -> "$prefix/${entry.path}"
            }
        val name = if (path == "/") "/" else path.substringAfterLast('/')
        return entry.copy(path = path, name = name)
    }

    private fun route(segments: List<String>): Routed {
        val first = segments.first()
        return when (first) {
            "appfiles" -> {
                if (RelayScope.APP_FILES !in scopes) throw notFound()
                Routed(internalFiles, RelayPaths.relative(segments.drop(1)), "/appfiles")
            }

            "appdata" -> {
                if (!hasAppDataScope()) throw notFound()
                Routed(appData, RelayPaths.relative(segments), "/")
            }

            "selected" -> {
                if (RelayScope.USER_SELECTED !in scopes) throw notFound()
                val resolver = selected ?: throw notFound()
                Routed(resolver, RelayPaths.relative(segments), "/")
            }

            else -> {
                if (RelayScope.APP_WORKSPACE !in scopes) throw notFound()
                if (RelayPaths.isReservedRoot(first)) throw notFound()
                Routed(workspace, RelayPaths.relative(segments), "/")
            }
        }
    }

    private fun ensureAllowed(path: String) {
        if (!allowedAbsolute(path)) throw notFound()
    }

    private fun allowedAbsolute(path: String): Boolean {
        val segments =
            runCatching { RelayPaths.segments(path) }.getOrNull() ?: return false
        if (segments.isEmpty()) return true
        return when (segments.first()) {
            "appfiles" -> RelayScope.APP_FILES in scopes
            "appdata" -> allowedAppData(segments)
            "selected" -> RelayScope.USER_SELECTED in scopes
            else ->
                RelayScope.APP_WORKSPACE in scopes &&
                    !RelayPaths.isReservedRoot(segments.first())
        }
    }

    private fun allowedAppData(segments: List<String>): Boolean {
        if (segments.size == 1) return hasAppDataScope()
        val scope =
            when (segments[1]) {
                "activity" -> RelayScope.APP_DATA_ACTIVITY
                "groups" -> RelayScope.APP_DATA_GROUPS
                "logs" -> RelayScope.APP_DATA_LOGS
                "telemetry" -> RelayScope.APP_DATA_TELEMETRY
                "diagnostics" -> RelayScope.APP_DATA_DIAGNOSTICS
                else -> return false
            }
        return scope in scopes
    }

    private fun hasAppDataScope(): Boolean =
        scopes.any { it.wire.startsWith("app.data.") }

    private fun notFound(): RelaySourceException =
        RelaySourceException(RelayInnerError.NOT_FOUND, "No such path.")

    private fun mapVfsError(error: VfsException): RelaySourceException =
        RelaySourceException(
            when (error.code) {
                io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND -> RelayInnerError.NOT_FOUND
                io.nekohasekai.sfa.vfs.VfsErrorCode.INVALID_PATH -> RelayInnerError.INVALID_PATH
                io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST -> RelayInnerError.BAD_REQUEST
                io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_SUPPORTED -> RelayInnerError.NOT_SUPPORTED
                io.nekohasekai.sfa.vfs.VfsErrorCode.RANGE_NOT_SATISFIABLE ->
                    RelayInnerError.RANGE_NOT_SATISFIABLE
                io.nekohasekai.sfa.vfs.VfsErrorCode.PERMISSION_LOST -> RelayInnerError.PERMISSION_LOST

                else -> RelayInnerError.PROVIDER_ERROR
            },
            error.message ?: "request failed",
        )

    private data class Routed(
        val delegate: VirtualDirectorySource,
        val relative: String,
        val prefix: String,
        val listable: Boolean = true,
        val readable: Boolean = true,
    )
}
