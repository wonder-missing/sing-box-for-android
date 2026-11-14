package io.nekohasekai.sfa.vfs

import java.io.File

/**
 * VFS-private files must never live under a scope root. `conductor/` is both
 * exposed by the provider and writable by the model's file tools; trust state
 * there would be readable by containers and editable by the model.
 *
 * Callers must pass `Context.noBackupFilesDir`. Device identity and trust must
 * not survive Auto Backup or device transfer, or a reinstall would look like
 * the same device and skip the mismatch/confirmation flow.
 */
object VfsPrivateFiles {
    const val DIR: String = "vfs"

    fun directory(baseDir: File): File = File(baseDir, DIR)

    fun trustFile(baseDir: File): File = File(directory(baseDir), "trust.json")

    fun resumeFile(baseDir: File): File = File(directory(baseDir), "resume.json")

    fun deviceIdFile(baseDir: File): File = File(directory(baseDir), "device-id")

    fun isInsideVfsScope(
        file: File,
        baseDir: File,
    ): Boolean {
        val relative = file.relativeTo(baseDir).invariantSeparatorsPath
        return relative == "conductor" ||
            relative.startsWith("conductor/") ||
            relative == ".runtime" ||
            relative.startsWith(".runtime/")
    }
}
