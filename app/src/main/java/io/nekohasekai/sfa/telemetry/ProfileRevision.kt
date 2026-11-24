package io.nekohasekai.sfa.telemetry

import android.content.Context
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import java.io.File
import java.security.MessageDigest

/**
 * Short, stable hash of the active profile so CFG can attribute telemetry to a
 * published subscription revision. The content and URL never leave the device.
 */
internal object ProfileRevision {
    private const val PREFIX_HEX = 12
    private val lock = Any()

    private var cachedPath: String? = null
    private var cachedMtime: Long = -1L
    private var cachedSize: Long = -1L
    private var cachedRev: String? = null

    fun of(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(PREFIX_HEX)
    }

    suspend fun current(context: Context): String? {
        val app = context.applicationContext
        val profile = ProfileManager.get(Settings.selectedProfile) ?: return null
        val path = profile.typed.path
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val mtime = file.lastModified()
        val size = file.length()
        synchronized(lock) {
            if (path == cachedPath && mtime == cachedMtime && size == cachedSize) {
                return cachedRev
            }
        }
        val rev = runCatching { of(file.readText()) }.getOrNull()
        synchronized(lock) {
            cachedPath = path
            cachedMtime = mtime
            cachedSize = size
            cachedRev = rev
        }
        return rev
    }

    fun invalidate() {
        synchronized(lock) {
            cachedPath = null
            cachedMtime = -1L
            cachedSize = -1L
            cachedRev = null
        }
    }
}
