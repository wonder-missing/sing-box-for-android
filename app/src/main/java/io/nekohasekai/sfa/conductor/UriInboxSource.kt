package io.nekohasekai.sfa.conductor

import android.content.ContentResolver
import android.net.Uri
import io.nekohasekai.sfa.agent.InboxSource
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Reads a picker-selected file through its temporary content-URI grant. */
class UriInboxSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : InboxSource {
    override fun open(): InputStream =
        resolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected file is no longer available.")

    override fun openAt(offset: Long): InputStream {
        val descriptor =
            runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
                ?: return super.openAt(offset)
        val stream =
            try {
                FileInputStream(descriptor.fileDescriptor).also { it.channel.position(offset) }
            } catch (e: Exception) {
                descriptor.close()
                return super.openAt(offset)
            }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    descriptor.close()
                }
            }
        }
    }
}
