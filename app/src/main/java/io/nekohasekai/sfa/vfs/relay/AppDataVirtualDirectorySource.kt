package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The `appdata/` tree: activity, groups, logs, telemetry and diagnostics.
 * Raw files are read directly; logs are a best-effort redacted tail; groups and
 * diagnostics are generated JSON (v1 group state is not wired to the core yet
 * and reports `available:false`).
 */
class AppDataVirtualDirectorySource(
    filesDir: File,
    private val diagnosticsJson: suspend () -> String,
    private val groupsJson: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) : VirtualDirectorySource {
    private val base = filesDir.canonicalFile

    override suspend fun stat(path: String): VfsEntry {
        val segments = virtualSegments(path)
        if (segments.isEmpty()) return directory("appdata", "appdata")
        return when (segments.first()) {
            "activity" -> activityStat(segments)
            "groups" -> generatedStat(segments, GROUPS_NAME, "appdata/groups/$GROUPS_NAME", groupsJson())
            "logs" -> generatedStat(segments, LOGS_NAME, "appdata/logs/$LOGS_NAME", redactedLogs())
            "telemetry" -> telemetryStat(segments)
            "diagnostics" ->
                generatedStat(segments, DIAGNOSTICS_NAME, "appdata/diagnostics/$DIAGNOSTICS_NAME", diagnosticsJson())
            else -> throw notFound()
        }
    }

    override suspend fun list(path: String): List<VfsEntry> {
        val segments = virtualSegments(path)
        if (segments.isEmpty()) {
            return listOf(
                directory("appdata/activity", "activity"),
                directory("appdata/groups", "groups"),
                directory("appdata/logs", "logs"),
                directory("appdata/telemetry", "telemetry"),
                directory("appdata/diagnostics", "diagnostics"),
            )
        }
        return when (segments.first()) {
            "activity" -> {
                if (segments.size != 1) throw notSupported()
                activityFile()?.let { file -> listOf(fileEntry("appdata/activity/$ACTIVITY_NAME", file)) }.orEmpty()
            }

            "groups" -> {
                if (segments.size != 1) throw notSupported()
                listOf(generatedEntry("appdata/groups/$GROUPS_NAME", groupsJson()))
            }

            "logs" -> {
                if (segments.size != 1) throw notSupported()
                listOf(generatedEntry("appdata/logs/$LOGS_NAME", redactedLogs()))
            }

            "telemetry" -> telemetryList(segments)

            "diagnostics" -> {
                if (segments.size != 1) throw notSupported()
                listOf(generatedEntry("appdata/diagnostics/$DIAGNOSTICS_NAME", diagnosticsJson()))
            }

            else -> throw notFound()
        }
    }

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        val segments = virtualSegments(path)
        if (offset < 0 || length < 0 || length > RelayWire.MAX_READ_BYTES) throw badRequest()
        if (segments.size < 2) throw notSupported()
        return when (segments.first()) {
            "activity" -> {
                if (segments.size != 2 || segments[1] != ACTIVITY_NAME) throw notFound()
                val file = activityFile() ?: throw notFound()
                readFile(file, offset, length)
            }

            "groups" -> {
                if (segments.size != 2 || segments[1] != GROUPS_NAME) throw notFound()
                readBytes(groupsJson().toByteArray(Charsets.UTF_8), offset, length)
            }

            "logs" -> {
                if (segments.size != 2 || segments[1] != LOGS_NAME) throw notFound()
                readBytes(redactedLogs().toByteArray(Charsets.UTF_8), offset, length)
            }

            "telemetry" -> {
                if (segments.size != 2) throw notFound()
                val file = telemetryFile(segments[1]) ?: throw notFound()
                readFile(file, offset, length)
            }

            "diagnostics" -> {
                if (segments.size != 2 || segments[1] != DIAGNOSTICS_NAME) throw notFound()
                readBytes(diagnosticsJson().toByteArray(Charsets.UTF_8), offset, length)
            }

            else -> throw notFound()
        }
    }

    private fun activityStat(segments: List<String>): VfsEntry {
        if (segments.size == 1) return directory("appdata/activity", "activity")
        if (segments.size != 2 || segments[1] != ACTIVITY_NAME) throw notFound()
        val file = activityFile() ?: throw notFound()
        return fileEntry("appdata/activity/$ACTIVITY_NAME", file)
    }

    private fun telemetryStat(segments: List<String>): VfsEntry {
        if (segments.size == 1) return directory("appdata/telemetry", "telemetry")
        if (segments.size != 2) throw notFound()
        val file = telemetryFile(segments[1]) ?: throw notFound()
        return fileEntry("appdata/telemetry/${segments[1]}", file)
    }

    private fun telemetryList(segments: List<String>): List<VfsEntry> {
        if (segments.size != 1) throw notSupported()
        val dir = File(base, "telemetry")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
            .map { fileEntry("appdata/telemetry/${it.name}", it) }
    }

    private fun generatedStat(
        segments: List<String>,
        fileName: String,
        path: String,
        content: String,
    ): VfsEntry {
        val parent = path.substringBeforeLast('/')
        if (segments.size == 1) return directory(parent, segments.first())
        if (segments.size != 2 || segments[1] != fileName) throw notFound()
        return generatedEntry(path, content)
    }

    private fun activityFile(): File? {
        val file = File(base, "activity/$ACTIVITY_NAME")
        return file.takeIf { it.isFile }
    }

    private fun telemetryFile(name: String): File? {
        if (name.contains('/') || name == "." || name == "..") return null
        val file = File(base, "telemetry/$name")
        return file.takeIf { it.isFile }
    }

    private fun redactedLogs(): String {
        val file = File(base, "logs/app.log")
        if (!file.isFile) return ""
        val text =
            runCatching {
                val size = file.length()
                val start = (size - MAX_LOG_BYTES).coerceAtLeast(0)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val data = ByteArray((size - start).toInt().coerceAtMost(MAX_LOG_BYTES))
                    raf.readFully(data)
                    String(data, Charsets.UTF_8)
                }
            }.getOrDefault("")
        return VfsLogRedactor.redact(text)
    }

    private fun fileEntry(
        path: String,
        file: File,
    ): VfsEntry {
        val mtime = file.lastModified().takeIf { it > 0 } ?: now()
        return VfsEntry(
            path = path,
            name = file.name,
            isDirectory = false,
            size = file.length(),
            mtimeMs = mtime,
            mimeType = io.nekohasekai.sfa.vfs.ConductorVirtualDirectorySource.mimeTypeFor(file.name),
            etag = "\"${file.length()}-$mtime\"",
        )
    }

    private fun generatedEntry(
        path: String,
        content: String,
    ): VfsEntry {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return VfsEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = false,
            size = bytes.size.toLong(),
            mtimeMs = now(),
            mimeType = "application/json; charset=utf-8",
            etag = "\"${bytes.size}-${contentHash(bytes)}\"",
        )
    }

    private fun directory(
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

    private fun readFile(
        file: File,
        offset: Long,
        length: Int,
    ): VfsRead {
        val size = file.length()
        if (offset > size) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.RANGE_NOT_SATISFIABLE, "offset past end")
        }
        if (offset == size || length == 0) {
            if (offset < size) throw badRequest()
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

    private fun readBytes(
        bytes: ByteArray,
        offset: Long,
        length: Int,
    ): VfsRead {
        val size = bytes.size.toLong()
        if (offset > size) {
            throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.RANGE_NOT_SATISFIABLE, "offset past end")
        }
        if (offset == size || length == 0) {
            if (offset < size) throw badRequest()
            return VfsRead(ByteArray(0), true)
        }
        val end = minOf(size, offset + length).toInt()
        return VfsRead(bytes.copyOfRange(offset.toInt(), end), end >= size)
    }

    private fun virtualSegments(path: String): List<String> {
        if (path.isEmpty() || path == "appdata") return emptyList()
        if (!path.startsWith("appdata/")) throw badRequest()
        val relative = path.removePrefix("appdata/")
        if (relative.contains("//")) throw badRequest()
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) throw badRequest()
        return segments
    }

    private fun contentHash(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .take(4)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun notFound(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "No such path.")

    private fun notSupported(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_SUPPORTED, "Not supported.")

    private fun badRequest(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST, "Invalid request.")

    companion object {
        private const val ACTIVITY_NAME = "events.jsonl"
        private const val GROUPS_NAME = "current.json"
        private const val LOGS_NAME = "app.log"
        private const val DIAGNOSTICS_NAME = "index.json"
        private const val MAX_LOG_BYTES = 256 * 1024
    }
}

/**
 * Best-effort redaction for the logs scope. Amber does not mean sanitized.
 * Keeps timestamps/levels/event names and removes credentials, URLs, UUIDs and
 * IPv4 addresses; tests assert known secrets do not survive.
 */
object VfsLogRedactor {
    private val authorization = Regex("(?i)(authorization\\s*:\\s*)([^\\r\\n]+)")
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")
    private val keyValue =
        Regex(
            "(?i)\\b(token|access_token|api_key|apikey|secret|password|passwd|pwd|key)\\b\\s*[=:]\\s*[^\\s,;\"']+",
        )
    private val url = Regex("(?i)\\bhttps?://[^\\s\"'<>]+")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    fun redact(text: String): String {
        var out = text
        out = authorization.replace(out) { match -> match.groupValues[1] + "[redacted]" }
        out = bearer.replace(out, "$1[redacted]")
        out = keyValue.replace(out) { match -> "${match.groupValues[1]}=[redacted]" }
        out = url.replace(out, "[url]")
        out = uuid.replace(out, "[uuid]")
        out = ipv4.replace(out, "[ip]")
        return out
    }
}
