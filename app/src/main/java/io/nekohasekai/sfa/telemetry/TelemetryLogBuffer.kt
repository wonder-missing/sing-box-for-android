package io.nekohasekai.sfa.telemetry

import java.io.File
import java.io.RandomAccessFile

/**
 * Small on-disk queue of log lines. Oldest lines are dropped when the file
 * grows past [TelemetryPayload.MAX_DISK_BYTES].
 */
internal class TelemetryLogBuffer(private val file: File) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var rawBytes = 0
    private var dirty = false
    private var generation = 0

    init {
        load()
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

    fun isEmpty(): Boolean = synchronized(lock) { lines.isEmpty() }

    fun overThreshold(): Boolean = synchronized(lock) { overThresholdLocked() }

    fun peek(limit: Int = Int.MAX_VALUE): List<String> = synchronized(lock) {
        if (limit >= lines.size) lines.toList() else lines.take(limit)
    }

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

    fun persist() {
        val snapshot: List<String>
        val generationAtStart: Int
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            snapshot = lines.toList()
            generationAtStart = generation
        }
        write(snapshot)
        synchronized(lock) {
            if (generation != generationAtStart) {
                dirty = true
            }
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
        }
        if (rawBytes < 0) rawBytes = 0
    }

    private fun load() {
        if (!file.isFile) return
        val loaded = ArrayList<String>()
        try {
            RandomAccessFile(file, "r").use { input ->
                while (input.filePointer < input.length()) {
                    val lengthLine = input.readLine() ?: break
                    val length = lengthLine.toIntOrNull() ?: break
                    if (length < 0 || length > TelemetryPayload.MAX_LINE_CHARS * 4) break
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(bytes, offset, length - offset)
                        if (read < 0) return
                        offset += read
                    }
                    val newline = input.read()
                    if (newline != '\n'.code && newline != -1) break
                    loaded.add(String(bytes, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            loaded.clear()
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
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, file.name + ".tmp")
        tmp.outputStream().buffered().use { output ->
            for (line in snapshot) {
                val bytes = line.toByteArray(Charsets.UTF_8)
                output.write(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                output.write('\n'.code)
                output.write(bytes)
                output.write('\n'.code)
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
