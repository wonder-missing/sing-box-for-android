package io.nekohasekai.sfa.log

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

enum class AppLogLevel {
    Info,
    Warning,
    Error,
}

data class AppLogEntry(
    val timestamp: Long,
    val level: AppLogLevel,
    val message: String,
)

/**
 * Small disk-backed app event log. It is independent of the sing-box service
 * so the Logs screen has something to show while the service is stopped.
 *
 * Call [initialize] once from Application.onCreate. Entries are kept in memory
 * and appended to a bounded file; writes are synchronous and infrequent.
 */
object AppLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_LINES = 1000

    private val lock = Any()
    private val buffer = ArrayDeque<AppLogEntry>()
    private val listeners = CopyOnWriteArrayList<(AppLogEntry) -> Unit>()
    private var file: File? = null
    private var lineCount = 0

    fun initialize(file: File) {
        synchronized(lock) {
            this.file = file
            buffer.clear()
            lineCount = 0
            runCatching {
                if (file.exists()) {
                    file.useLines { lines ->
                        lines.forEach { line ->
                            parseLine(line)?.let { buffer.addLast(it) }
                        }
                    }
                }
            }
            trimLocked()
            lineCount = buffer.size
        }
    }

    fun info(message: String) = append(AppLogLevel.Info, message)

    fun warning(message: String) = append(AppLogLevel.Warning, message)

    fun error(message: String) = append(AppLogLevel.Error, message)

    fun entries(): List<AppLogEntry> = synchronized(lock) { buffer.toList() }

    fun addListener(listener: (AppLogEntry) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (AppLogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            lineCount = 0
            file?.let { runCatching { it.writeText("") } }
        }
    }

    private fun append(level: AppLogLevel, message: String) {
        val entry = AppLogEntry(System.currentTimeMillis(), level, message.replace('\n', ' '))
        synchronized(lock) {
            buffer.addLast(entry)
            trimLocked()
            writeLocked(entry)
        }
        listeners.forEach { runCatching { it(entry) } }
    }

    private fun writeLocked(entry: AppLogEntry) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            if (lineCount >= MAX_FILE_LINES) {
                target.writeText(buffer.joinToString("\n", transform = ::encode))
                lineCount = buffer.size
            } else {
                target.appendText(encode(entry) + "\n")
                lineCount++
            }
        }
    }

    private fun trimLocked() {
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeFirst()
        }
    }

    private fun encode(entry: AppLogEntry): String =
        "${entry.timestamp}|${entry.level.name}|${entry.message.replace("|", "\\|")}"

    private fun parseLine(line: String): AppLogEntry? {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val level = runCatching { AppLogLevel.valueOf(parts[1]) }.getOrNull() ?: return null
        return AppLogEntry(timestamp, level, parts[2].replace("\\|", "|"))
    }
}
