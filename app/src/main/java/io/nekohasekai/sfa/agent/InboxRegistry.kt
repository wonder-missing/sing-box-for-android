package io.nekohasekai.sfa.agent

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A file the agent may read without copying it into the Conductor workspace.
 * Chat attachments resolve to the image store; `request_files` entries resolve
 * to the content URI held by the picker.
 */
interface InboxSource {
    fun open(): InputStream

    fun openAt(offset: Long): InputStream {
        val input = open()
        var remaining = offset
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) break
                remaining -= 1L
            }
        }
        return input
    }

    fun exists(): Boolean = true
}

class FileInboxSource(
    private val file: File,
) : InboxSource {
    override fun open(): InputStream = FileInputStream(file)

    override fun openAt(offset: Long): InputStream {
        val input = FileInputStream(file)
        return try {
            input.channel.position(offset)
            input
        } catch (e: Exception) {
            input.close()
            super.openAt(offset)
        }
    }

    override fun exists(): Boolean = file.isFile
}

data class InboxEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val source: InboxSource,
)

/**
 * Process-level registry of shared files. The VFS provider and the local tools
 * read through the same entries; nothing is copied.
 */
class InboxRegistry {
    private val lock = Any()
    private val entries = LinkedHashMap<String, InboxEntry>()

    fun register(
        id: String,
        originalName: String,
        mimeType: String,
        size: Long,
        source: InboxSource,
        now: Long = System.currentTimeMillis(),
    ): InboxEntry? {
        if (!InboxPaths.validId(id)) return null
        val entry =
            InboxEntry(
                id = id,
                name = InboxPaths.fileName(originalName, mimeType),
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                size = size.coerceAtLeast(0L),
                createdAt = now,
                source = source,
            )
        synchronized(lock) {
            entries.remove(id)
            entries[id] = entry
            while (entries.size > MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
        }
        return entry
    }

    fun registerFile(
        id: String,
        originalName: String,
        mimeType: String,
        file: File,
        now: Long = System.currentTimeMillis(),
    ): InboxEntry? =
        register(
            id = id,
            originalName = originalName,
            mimeType = mimeType,
            size = file.length(),
            source = FileInboxSource(file),
            now = now,
        )

    fun entry(id: String): InboxEntry? = synchronized(lock) { entries[id] }

    fun entries(): List<InboxEntry> = synchronized(lock) { entries.values.toList() }

    fun remove(id: String) {
        synchronized(lock) { entries.remove(id) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun isEmpty(): Boolean = synchronized(lock) { entries.values.none { it.source.exists() } }

    companion object {
        const val MAX_ENTRIES = 64

        /** Shared by the Conductor ViewModel and the process-level VFS provider. */
        val shared = InboxRegistry()
    }
}
