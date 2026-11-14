package io.nekohasekai.sfa.vfs

import java.io.File
import java.util.UUID

object VfsDeviceId {
    fun generate(): String = UUID.randomUUID().toString()

    fun isValid(value: String): Boolean =
        value.length in 8..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

/** A per-install identity. Clearing app data creates a new one by design. */
class VfsDeviceIdStore(
    private val file: File,
) {
    @Synchronized
    fun getOrCreate(): String {
        val existing = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (existing != null && VfsDeviceId.isValid(existing)) return existing

        val created = VfsDeviceId.generate()
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp =
            if (parent != null) {
                File(parent, file.name + ".tmp")
            } else {
                File(file.name + ".tmp")
            }
        tmp.writeText(created, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(created, Charsets.UTF_8)
            tmp.delete()
        }
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return created
    }
}
