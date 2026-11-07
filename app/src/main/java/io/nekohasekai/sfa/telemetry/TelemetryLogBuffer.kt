package io.nekohasekai.sfa.telemetry

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk operations used by [TelemetryLogBuffer]. Kept behind a tiny port so JVM
 * tests can simulate a full disk or a failed rename without Android.
 */
internal interface TelemetryFileOps {
    fun ensureDir(dir: File): Boolean
    fun openOutput(file: File): OutputStream
    fun rename(from: File, to: File): Boolean
    fun copy(from: File, to: File)
    fun delete(file: File): Boolean

    object Default : TelemetryFileOps {
        override fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

        override fun openOutput(file: File): OutputStream = file.outputStream()

        override fun rename(from: File, to: File): Boolean = from.renameTo(to)

        override fun copy(from: File, to: File) {
            from.copyTo(to, overwrite = true)
        }

        override fun delete(file: File): Boolean = file.delete()
    }
}

/**
 * Small on-disk queue of log lines. Oldest lines are dropped when the file
 * grows past [TelemetryPayload.MAX_DISK_BYTES].
 *
 * Telemetry is best effort: no disk failure may escape [persist] and reach
 * BoxService/core. Failed writes leave the in-memory queue intact and keep it
 * dirty so a later persist retries. The optional [onPersistFailure] callback is
 * invoked once per failure streak (not on every retry) so a full disk cannot
 * flood the client-event ring.
 */
internal class TelemetryLogBuffer(
    private val file: File,
    private val disk: TelemetryFileOps = TelemetryFileOps.Default,
    private val onPersistFailure: ((Exception) -> Unit)? = null,
) {
    private val lock = Any()

    /**
     * Serializes the whole snapshot+write cycle so concurrent persists cannot
     * interleave writes to the shared `pending.log.tmp`. Keyed by file path (not
     * by instance): a stopping session and a newly attached/upload-now session
     * may briefly hold two buffers for the same file.
     */
    private val writeLock = writeLockFor(file)

    private val lines = ArrayDeque<String>()
    private var rawBytes = 0
    private var dirty = false
    private var generation = 0
    private var droppedSinceUpload = 0
    private var consecutiveFailures = 0
    private var lastFailure: Exception? = null

    private class Snapshot(val lines: List<String>, val generation: Int)

    init {
        // Do not read the file while another buffer for the same path is writing.
        synchronized(writeLock) { load() }
    }

    fun append(newLines: List<String>): Boolean {
        if (newLines.isEmpty()) return false
        synchronized(lock) {
            for (line in newLines) {
                lines.addLast(line)
                rawBytes += line.toByteArray(Charsets.UTF_8).size
            }
            dropOldestLocked()
            generation++
            dirty = true
            return overThresholdLocked()
        }
    }

    /** Lines evicted by the disk cap since the last successful upload. */
    fun droppedCount(): Int = synchronized(lock) { droppedSinceUpload }

    fun size(): Int = synchronized(lock) { lines.size }

    fun resetDropped() = synchronized(lock) { droppedSinceUpload = 0 }

    fun isEmpty(): Boolean = synchronized(lock) { lines.isEmpty() }

    fun overThreshold(): Boolean = synchronized(lock) { overThresholdLocked() }

    fun peek(limit: Int = Int.MAX_VALUE): List<String> = synchronized(lock) {
        if (limit >= lines.size) lines.toList() else lines.take(limit)
    }

    fun lastPersistFailure(): Exception? = synchronized(lock) { lastFailure }

    fun dropPrefix(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            var left = count
            while (left > 0 && lines.isNotEmpty()) {
                val removed = lines.removeFirst()
                rawBytes -= removed.toByteArray(Charsets.UTF_8).size
                left--
            }
            if (rawBytes < 0) rawBytes = 0
            generation++
            dirty = true
        }
    }

    /**
     * Writes the current queue to disk. Never throws: IO errors are swallowed,
     * counted locally and reported through [onPersistFailure] (first failure of
     * a streak only). Returns true when the snapshot reached the main file.
     */
    fun persist(): Boolean = synchronized(writeLock) {
        val snapshot = synchronized(lock) {
            if (dirty) {
                dirty = false
                Snapshot(lines.toList(), generation)
            } else {
                null
            }
        }
        if (snapshot == null) {
            true
        } else {
            val error = try {
                write(snapshot.lines)
                null
            } catch (e: Exception) {
                e
            }
            synchronized(lock) {
                // A newer append/drop happened while writing, or the write failed:
                // keep the queue dirty so the next persist picks it up.
                if (error != null || generation != snapshot.generation) {
                    dirty = true
                }
                if (error == null) {
                    consecutiveFailures = 0
                    lastFailure = null
                }
            }
            if (error != null) {
                recordFailure(error)
                false
            } else {
                true
            }
        }
    }

    private fun recordFailure(error: Exception) {
        val firstOfStreak = synchronized(lock) {
            lastFailure = error
            consecutiveFailures++
            consecutiveFailures == 1
        }
        if (firstOfStreak) {
            runCatching { onPersistFailure?.invoke(error) }
        }
    }

    private fun overThresholdLocked(): Boolean {
        return lines.size >= TelemetryPayload.FLUSH_LINE_COUNT || rawBytes >= TelemetryPayload.FLUSH_RAW_BYTES
    }

    private fun dropOldestLocked() {
        while (
            lines.isNotEmpty() &&
            (rawBytes > TelemetryPayload.MAX_DISK_BYTES || lines.size > TelemetryPayload.MAX_DISK_LINES)
        ) {
            val removed = lines.removeFirst()
            rawBytes -= removed.toByteArray(Charsets.UTF_8).size
            droppedSinceUpload++
        }
        if (rawBytes < 0) rawBytes = 0
    }

    private fun load() {
        val loaded = ArrayList<String>()
        try {
            if (!file.isFile) return
            RandomAccessFile(file, "r").use { input ->
                while (input.filePointer < input.length()) {
                    val lengthLine = input.readLine() ?: break
                    val length = lengthLine.toIntOrNull() ?: break
                    if (length < 0 || length > TelemetryPayload.MAX_LINE_CHARS * 4) break
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(bytes, offset, length - offset)
                        if (read < 0) break
                        offset += read
                    }
                    // A half-written tail (e.g. copy fallback died on a full disk)
                    // must not discard the valid prefix before it.
                    if (offset < length) break
                    val newline = input.read()
                    if (newline != '\n'.code && newline != -1) break
                    loaded.add(String(bytes, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // Keep whatever prefix parsed successfully; a corrupt file must not
            // brick service start.
        }
        synchronized(lock) {
            lines.clear()
            rawBytes = 0
            for (line in loaded) {
                lines.addLast(line)
                rawBytes += line.toByteArray(Charsets.UTF_8).size
            }
            dropOldestLocked()
            dirty = false
        }
    }

    private fun write(snapshot: List<String>) {
        val parent = file.parentFile
        if (parent != null) {
            disk.ensureDir(parent)
        }
        val tmp = File(parent, file.name + ".tmp")
        clearStaleTemp(tmp)
        disk.openOutput(tmp).buffered().use { output ->
            for (line in snapshot) {
                val bytes = line.toByteArray(Charsets.UTF_8)
                output.write(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                output.write('\n'.code)
                output.write(bytes)
                output.write('\n'.code)
            }
        }
        if (!disk.rename(tmp, file)) {
            // Some filesystems refuse rename-over-existing; fall back to copy.
            disk.copy(tmp, file)
            disk.delete(tmp)
        }
    }

    private fun clearStaleTemp(tmp: File) {
        if (!tmp.exists()) return
        if (disk.delete(tmp)) return
        // A leftover file is harmless (openOutput truncates it), but a directory
        // squatting on the temp path would make every write fail forever.
        if (tmp.isDirectory) {
            runCatching { tmp.deleteRecursively() }
        }
    }

    private companion object {
        private val pathLocks = ConcurrentHashMap<String, Any>()

        private fun writeLockFor(file: File): Any {
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            return pathLocks.computeIfAbsent(key) { Any() }
        }
    }
}
