package io.nekohasekai.sfa.agent

import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class StoredFile(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

data class FileReadResult(
    val path: String,
    val size: Long,
    val text: String,
    val truncated: Boolean,
)

/**
 * Two roots, one policy:
 * - the working directory is readable;
 * - [workspaceName] under it is the only writable area.
 *
 * Paths are always relative to the working directory. Traversal, absolute
 * paths, symlinks and writes outside the workspace are rejected.
 */
class ConductorFileStore(
    private val workingDir: File,
    private val workspaceName: String = "conductor",
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val maxFiles: Int = 20,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
) {
    val workspaceDir: File = File(workingDir, workspaceName)

    fun ensureWorkspace(): File {
        workspaceDir.mkdirs()
        prune()
        return workspaceDir
    }

    fun list(path: String): List<StoredFile> {
        val dir = resolve(workingDir, path, allowRoot = true)
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        return dir
            .listFiles()
            .orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(MAX_LIST_ENTRIES)
            .map { file -> storeEntry(file) }
    }

    fun stat(path: String): StoredFile = storeEntry(resolve(workingDir, path, allowRoot = true))

    fun readText(
        path: String,
        maxBytes: Int = DEFAULT_READ_BYTES,
    ): FileReadResult {
        val file = resolve(workingDir, path, allowRoot = false)
        if (!file.isFile) throw IllegalArgumentException("Not a file: $path")
        val cap = maxBytes.coerceIn(1, MAX_READ_BYTES)
        val size = file.length()
        val bytes =
            file.inputStream().use { input ->
                val buffer = ByteArray(cap)
                var total = 0
                while (total < cap) {
                    val read = input.read(buffer, total, cap - total)
                    if (read < 0) break
                    total += read
                }
                if (total == cap) buffer else buffer.copyOf(total)
            }
        if (bytes.any { it == 0.toByte() }) {
            throw IllegalArgumentException("Binary files are not supported; use file_stat for metadata.")
        }
        return FileReadResult(
            path = relativePath(file),
            size = size,
            text = String(bytes, Charsets.UTF_8),
            truncated = size > bytes.size.toLong(),
        )
    }

    fun writeText(
        path: String,
        text: String,
        overwrite: Boolean = false,
    ): StoredFile {
        val file = resolveWrite(path)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxFileBytes) {
            throw IllegalArgumentException("File is larger than ${maxFileBytes} bytes.")
        }
        ensureWorkspace()
        if (file.exists() && !overwrite) {
            throw IllegalArgumentException("File already exists: $path. Set overwrite=true to replace it.")
        }
        val parent = file.parentFile ?: throw IllegalArgumentException("Invalid path: $path")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalArgumentException("Could not create $path")
        if (!file.exists() && countFiles() >= maxFiles) {
            prune()
            if (countFiles() >= maxFiles) {
                throw IllegalArgumentException("Workspace file limit ($maxFiles) reached. Delete old files first.")
            }
        }
        val temp = File(parent, ".tmp-${UUID.randomUUID()}")
        try {
            temp.writeBytes(bytes)
            if (file.exists() && !file.delete()) throw IllegalArgumentException("Could not replace $path")
            if (!temp.renameTo(file)) throw IllegalArgumentException("Could not write $path")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return storeEntry(file)
    }

    fun mkdir(path: String): StoredFile {
        val dir = resolveWrite(path)
        if (dir.exists() && !dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalArgumentException("Could not create $path")
        return storeEntry(dir)
    }

    fun delete(path: String): StoredFile {
        val file = resolveWrite(path)
        val entry = storeEntry(file)
        if (file.isDirectory) {
            if (!file.deleteRecursively()) throw IllegalArgumentException("Could not delete $path")
        } else if (!file.delete()) {
            throw IllegalArgumentException("Could not delete $path")
        }
        return entry
    }

    fun resolveRead(path: String): File = resolve(workingDir, path, allowRoot = false)

    private fun resolveWrite(path: String): File {
        val file = resolve(workingDir, path, allowRoot = false)
        val workspace = workspaceDir.canonicalFile
        if (file != workspace && !file.path.startsWith(workspace.path + File.separator)) {
            throw IllegalArgumentException("Writes are only allowed under $workspaceName/.")
        }
        return file
    }

    fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(workingDir.canonicalFile).path.replace(File.separatorChar, '/')

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun resolve(
        root: File,
        path: String,
        allowRoot: Boolean,
    ): File {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.length > MAX_PATH_CHARS) throw IllegalArgumentException("Path is too long.")
        if (normalized.startsWith("/")) throw IllegalArgumentException("Absolute paths are not allowed.")
        if (normalized.split('/').any { it == ".." }) {
            throw IllegalArgumentException("Parent traversal is not allowed.")
        }
        val rootCanonical = root.canonicalFile
        val file = File(rootCanonical, normalized).canonicalFile
        if (file != rootCanonical && !file.path.startsWith(rootCanonical.path + File.separator)) {
            throw IllegalArgumentException("Path is outside the allowed root.")
        }
        if (!allowRoot && file == rootCanonical) throw IllegalArgumentException("A file path is required.")
        return file
    }

    private fun storeEntry(file: File): StoredFile =
        StoredFile(
            path = relativePath(file),
            directory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            modifiedAt = file.lastModified(),
        )

    private fun countFiles(): Int =
        workspaceDir
            .walkTopDown()
            .count { it.isFile && !it.name.startsWith(".tmp-") }

    private fun prune() {
        if (!workspaceDir.isDirectory) return
        val cutoff = System.currentTimeMillis() - ttlMs
        val files =
            workspaceDir
                .walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".tmp-") }
                .sortedBy { it.lastModified() }
                .toList()
        files.filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        val remaining = files.filter { it.exists() }
        remaining.dropLast(maxFiles).forEach { runCatching { it.delete() } }
    }

    companion object {
        internal const val DEFAULT_READ_BYTES = 64 * 1024
        internal const val MAX_READ_BYTES = 256 * 1024
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_PATH_CHARS = 512
    }
}
