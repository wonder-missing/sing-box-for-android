package io.nekohasekai.sfa.conductor

import io.nekohasekai.sfa.agent.AgentImageData
import io.nekohasekai.sfa.agent.AgentImageRef
import java.io.File
import java.util.UUID

/**
 * Private storage for images attached to Conductor messages. The session JSON
 * keeps only [AgentImageRef] metadata; image bytes never inflate the session
 * blob or the model transcript persistence.
 */
class ConductorImageStore(
    private val dir: File,
) {
    init {
        dir.mkdirs()
    }

    fun save(
        bytes: ByteArray,
        mimeType: String,
        name: String,
        width: Int,
        height: Int,
    ): AgentImageRef {
        val id = UUID.randomUUID().toString().replace("-", "")
        val file = dir.resolve("$id.${extensionFor(mimeType)}")
        val temp = dir.resolve("$id.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
        prune()
        return AgentImageRef(
            id = id,
            name = name.ifBlank { "image.${extensionFor(mimeType)}" },
            mimeType = mimeType,
            width = width,
            height = height,
            size = bytes.size.toLong(),
        )
    }

    fun load(id: String): AgentImageData? {
        val file = file(id) ?: return null
        return runCatching { AgentImageData(file.readBytes(), mimeFor(file)) }.getOrNull()
    }

    fun file(id: String): File? {
        if (id.isBlank() || id.any { it == '/' || it == '\\' || it == '.' }) return null
        return dir.listFiles()?.firstOrNull { it.nameWithoutExtension == id && it.isFile }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Keeps the private image directory bounded; older attachments may be missing after a long session. */
    private fun prune(
        maxFiles: Int = 80,
        maxBytes: Long = 80L * 1024 * 1024,
    ) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= maxFiles && total <= maxBytes) break
            total -= file.length()
            count -= 1
            runCatching { file.delete() }
        }
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType.substringBefore(';').lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

    private fun mimeFor(file: File): String =
        when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
}
