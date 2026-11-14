package io.nekohasekai.sfa.vfs

import java.io.File

/**
 * VFS-private files must never live under a scope root. `conductor/` is both
 * exposed by the provider and writable by the model's file tools; trust state
 * there would be readable by containers and editable by the model.
 */
object VfsPrivateFiles {
    const val DIR: String = "vfs"

    fun directory(filesDir: File): File = File(filesDir, DIR)

    fun trustFile(filesDir: File): File = File(directory(filesDir), "trust.json")

    fun resumeFile(filesDir: File): File = File(directory(filesDir), "resume.json")

    fun deviceIdFile(filesDir: File): File = File(directory(filesDir), "device-id")

    fun isInsideVfsScope(
        file: File,
        filesDir: File,
    ): Boolean {
        val relative = file.relativeTo(filesDir).invariantSeparatorsPath
        return relative == "conductor" ||
            relative.startsWith("conductor/") ||
            relative == ".runtime" ||
            relative.startsWith(".runtime/")
    }
}
