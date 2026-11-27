package io.nekohasekai.sfa.vfs.relay

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.log.AppLog
import io.nekohasekai.sfa.telemetry.ProfileRevision
import io.nekohasekai.sfa.vfs.InternalFilesVirtualDirectorySource
import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream

/** Wires the absolute Share view to the app's real directories and runtime files. */
object AndroidRelaySources {
    fun create(
        context: Context,
        share: VfsShareRecord,
        secret: ByteArray,
        shareCount: Int = 1,
        deviceId: String = "",
    ): VfsRelaySource {
        val app = context.applicationContext
        val externalDir = app.getExternalFilesDir(null)
        val workspaceSource =
            externalDir
                ?.let { RelayWorkspaceSource(it) }
                ?: EmptyVirtualDirectorySource
        val internalSource = RelayAppFilesSource(InternalFilesVirtualDirectorySource(app.filesDir))
        val telemetryDir = File(app.filesDir, "telemetry")
        val activityFile = File(app.filesDir, "activity/events.jsonl")
        val logFile = File(app.filesDir, "logs/app.log")
        val diagnostics: suspend () -> String =
            {
                buildJsonObject {
                    put("generated_at", System.currentTimeMillis())
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("device_prefix", deviceId.take(8))
                    put("profile_rev", runCatching { ProfileRevision.current(app) }.getOrNull() ?: "")
                    put("event_count", AppLog.entries().size)
                    put("activity_bytes", activityFile.length())
                    put("telemetry_bytes", telemetryDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
                    put("log_bytes", logFile.length())
                    put("share_count", shareCount)
                }.toString()
            }
        val appData =
            AppDataVirtualDirectorySource(
                filesDir = app.filesDir,
                diagnosticsJson = diagnostics,
                groupsJson = {
                    buildJsonObject {
                        put("generated_at", System.currentTimeMillis())
                        put("available", false)
                        put(
                            "groups",
                            buildJsonArray {
                            },
                        )
                    }.toString()
                },
            )
        val selected =
            if (share.selectedUris.isEmpty()) {
                null
            } else {
                UserSelectedVirtualDirectorySource(
                    AndroidSelectedDocumentResolver(app),
                    share.selectedUris,
                )
            }
        val linkIdRaw = RelayBase64.decodeUrl(share.linkId)
            ?: throw RelayStoreException("Share link id is not valid base64url.")
        return RelayAbsoluteSource(
            scopes = share.concreteScopes,
            internalFiles = internalSource,
            workspace = workspaceSource,
            appData = appData,
            selected = selected,
            shareKey = RelayCrypto.shareKey(secret, linkIdRaw),
            linkIdRaw = linkIdRaw,
        )
    }
}

private object EmptyVirtualDirectorySource : VirtualDirectorySource {
    override suspend fun stat(path: String): VfsEntry = throw notFound()

    override suspend fun list(path: String): List<VfsEntry> = throw notFound()

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead = throw notFound()

    private fun notFound(): VfsException =
        VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.NOT_FOUND, "No such path.")
}

/** SAF reads through the persisted content URI; v1 supports regular files only. */
class AndroidSelectedDocumentResolver(
    private val context: Context,
) : SelectedDocumentResolver {
    override fun documents(uris: List<String>): List<SelectedDocument> =
        uris.mapNotNull { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@mapNotNull null
            val name = displayName(uri) ?: uri.lastPathSegment ?: return@mapNotNull null
            val size =
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it >= 0 } else null
                        }
                }.getOrNull() ?: probeSize(uri) ?: 0L
            SelectedDocument(
                uri = raw,
                name = name,
                size = size,
                mtimeMs = 0,
                mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
            )
        }

    override fun read(
        uri: String,
        offset: Long,
        length: Int,
    ): VfsRead {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: throw SelectedPermissionLostException("Invalid content URI.")
        val descriptor =
            try {
                context.contentResolver.openFileDescriptor(parsed, "r")
            } catch (_: SecurityException) {
                throw SelectedPermissionLostException("File permission was lost.")
            } ?: throw SelectedPermissionLostException("File permission was lost.")
        descriptor.use {
            val size = it.statSize.takeIf { value -> value >= 0 } ?: 0L
            if (offset > size) {
                throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.RANGE_NOT_SATISFIABLE, "offset past end")
            }
            if (offset == size || length == 0) {
                if (offset < size) {
                    throw VfsException(io.nekohasekai.sfa.vfs.VfsErrorCode.BAD_REQUEST, "invalid read range")
                }
                return VfsRead(ByteArray(0), true)
            }
            val wanted = minOf(length.toLong(), size - offset).toInt()
            val data = ByteArray(wanted)
            val read = pread(it, data, offset)
            return VfsRead(data.copyOf(read), offset + read >= size)
        }
    }

    private fun pread(
        descriptor: ParcelFileDescriptor,
        data: ByteArray,
        offset: Long,
    ): Int {
        var total = 0
        val direct = runCatching {
            while (total < data.size) {
                val n = Os.pread(descriptor.fileDescriptor, data, total, data.size - total, offset + total)
                if (n <= 0) break
                total += n
            }
            total
        }.getOrNull()
        if (direct != null) return direct
        // Some providers do not support seekable descriptors; fall back to skip.
        FileInputStream(descriptor.fileDescriptor).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            var read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n <= 0) break
                read += n
            }
            return read
        }
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                }
        }.getOrNull()

    private fun probeSize(uri: Uri): Long? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
}
