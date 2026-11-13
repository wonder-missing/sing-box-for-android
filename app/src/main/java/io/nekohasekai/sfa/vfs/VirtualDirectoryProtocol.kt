package io.nekohasekai.sfa.vfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class VfsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val mimeType: String? = null,
    val etag: String? = null,
)

data class VfsRead(
    val data: ByteArray,
    val eof: Boolean,
)

sealed interface VfsIncoming {
    data class ServerHello(
        val version: Int,
        val server: String?,
    ) : VfsIncoming

    data class Stat(
        val id: Long,
        val path: String,
    ) : VfsIncoming

    data class List(
        val id: Long,
        val path: String,
    ) : VfsIncoming

    data class Read(
        val id: Long,
        val path: String,
        val offset: Long,
        val length: Int,
    ) : VfsIncoming
}

object VfsErrorCode {
    const val NOT_FOUND = "not_found"
    const val INVALID_PATH = "invalid_path"
    const val BAD_REQUEST = "bad_request"
    const val NOT_SUPPORTED = "not_supported"
    const val PROVIDER_ERROR = "provider_error"
    const val RANGE_NOT_SATISFIABLE = "range_not_satisfiable"
}

class VfsException(
    val code: String,
    message: String,
) : Exception(message)

/**
 * Wire format for vfsd's provider protocol, version 1. The app is the provider:
 * it sends a hello first and then answers stat/list/read requests. Paths are
 * relative to the granted root; the empty path is the root.
 */
object VfsProtocol {
    const val VERSION: Int = 1
    const val MAX_READ_BYTES: Int = 1024 * 1024
    const val PROVIDER_NAME: String = "wondersing"

    private val json = Json { ignoreUnknownKeys = true }

    fun clientHello(provider: String = PROVIDER_NAME): String =
        buildJsonObject {
            put("type", "hello")
            put("version", VERSION)
            put("provider", provider)
        }.toString()

    fun entry(
        id: Long,
        entry: VfsEntry,
    ): String = response("entry", id) { put("entry", entryObject(entry)) }

    fun entries(
        id: Long,
        entries: List<VfsEntry>,
    ): String =
        response("entries", id) {
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { add(entryObject(it)) }
                },
            )
        }

    @OptIn(ExperimentalEncodingApi::class)
    fun data(
        id: Long,
        data: ByteArray,
        eof: Boolean,
    ): String =
        response("data", id) {
            put("data", Base64.Default.encode(data))
            put("eof", eof)
        }

    fun error(
        id: Long,
        code: String,
        message: String? = null,
    ): String =
        response("error", id) {
            put("code", code)
            if (!message.isNullOrBlank()) put("message", message)
        }

    fun parseIncoming(raw: String): VfsIncoming? {
        val objectValue = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return when (objectValue.stringField("type")) {
            "hello" -> {
                val version = objectValue.intField("version") ?: return null
                VfsIncoming.ServerHello(version, objectValue.stringField("server"))
            }

            "stat" -> {
                val id = objectValue.idField() ?: return null
                val path = objectValue.stringField("path") ?: return null
                VfsIncoming.Stat(id, path)
            }

            "list" -> {
                val id = objectValue.idField() ?: return null
                val path = objectValue.stringField("path") ?: return null
                VfsIncoming.List(id, path)
            }

            "read" -> {
                val id = objectValue.idField() ?: return null
                val path = objectValue.stringField("path") ?: return null
                val offset = objectValue.longField("offset")?.takeIf { it >= 0 } ?: return null
                val length = objectValue.intField("length")?.takeIf { it >= 0 } ?: return null
                VfsIncoming.Read(id, path, offset, length)
            }

            else -> null
        }
    }

    private fun response(
        type: String,
        id: Long,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("type", type)
            put("id", id)
            block()
        }.toString()

    private fun entryObject(entry: VfsEntry): JsonObject =
        buildJsonObject {
            put("path", entry.path)
            put("name", entry.name)
            put("isDirectory", entry.isDirectory)
            put("size", entry.size)
            put("mtimeMs", entry.mtimeMs)
            entry.mimeType?.let { put("mimeType", it) }
            entry.etag?.let { put("etag", it) }
        }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longField(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    private fun JsonObject.intField(name: String): Int? =
        longField(name)
            ?.takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }
            ?.toInt()

    private fun JsonObject.idField(): Long? = longField("id")?.takeIf { it >= 0 }
}
