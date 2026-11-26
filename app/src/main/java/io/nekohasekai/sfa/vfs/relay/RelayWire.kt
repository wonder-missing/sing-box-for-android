package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.VfsEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Inner error codes. They are always carried inside AEAD ciphertext. */
object RelayInnerError {
    const val NOT_FOUND = "not_found"
    const val INVALID_PATH = "invalid_path"
    const val BAD_REQUEST = "bad_request"
    const val NOT_SUPPORTED = "not_supported"
    const val PROVIDER_ERROR = "provider_error"
    const val RANGE_NOT_SATISFIABLE = "range_not_satisfiable"
    const val NONCE_REUSED = "nonce_reused"
    const val STALE_TS = "stale_ts"
    const val IN_FLIGHT = "in_flight"
    const val TOO_LARGE = "too_large"
    const val PERMISSION_LOST = "permission_lost"
    const val CURSOR_INVALID = "cursor_invalid"
    const val TIMEOUT = "timeout"
}

class RelayProtocolException(
    val code: String,
    message: String,
) : Exception(message)

/** Pair-error / link-error codes are plaintext control messages. */
object RelayPairErrorCode {
    const val PAIR_UNKNOWN = "pair_unknown"
    const val CODE_INVALID = "code_invalid"
    const val CODE_EXPIRED = "code_expired"
    const val CODE_CONSUMED = "code_consumed"
    const val SHARE_PAUSED = "share_paused"
    const val STALE_TS = "stale_ts"
    const val RATE_LIMITED = "rate_limited"
}

object RelayLinkErrorCode {
    const val LINK_UNKNOWN = "link_unknown"
    const val SHARE_REVOKED = "share_revoked"
    const val NOT_PAIRED = "not_paired"
    const val AUTH_FAILED = "auth_failed"
}

data class RelayEnvelope(
    val op: String,
    val consumerId: String,
    val linkId: String,
    val pairId: String?,
    val reqId: String,
    val clientNonce: String,
    val ts: Long,
    val iv: String,
    val ct: String,
    val linkIdRaw: ByteArray,
    val pairIdRaw: ByteArray?,
    val reqIdRaw: ByteArray,
    val clientNonceRaw: ByteArray,
    val ivRaw: ByteArray,
    val ctRaw: ByteArray,
)

sealed interface RelayIncoming {
    data class Ack(
        val control: String?,
        val controlId: String?,
        val linkId: String?,
        val generation: Long?,
    ) : RelayIncoming

    data class ControlError(
        val control: String?,
        val controlId: String?,
        val linkId: String?,
        val code: String,
        val message: String?,
        val retryable: Boolean,
        val currentGeneration: Long?,
        val currentPairEpoch: Long?,
        val retryAfterMs: Long?,
    ) : RelayIncoming

    data class PairError(
        val consumerId: String,
        val linkId: String,
        val pairId: String,
        val reqId: String,
        val code: String,
        val message: String?,
        val retryAfterMs: Long?,
        val serverTs: Long?,
        val skewMs: Long?,
    ) : RelayIncoming

    data class LinkError(
        val consumerId: String,
        val linkId: String,
        val reqId: String,
        val code: String,
        val message: String?,
    ) : RelayIncoming

    data class Ping(val id: String?) : RelayIncoming

    data class Pong(val id: String?) : RelayIncoming
}

object RelayWire {
    const val VERSION: Int = 1
    const val MAX_ENVELOPE_BYTES: Int = 8 * 1024 * 1024
    const val MAX_READ_BYTES: Int = 1024 * 1024
    const val MAX_LIST_ENTRIES: Int = 10_000
    const val DEFAULT_LIST_LIMIT: Int = 1_000
    const val MAX_LIST_PAGE_BYTES: Int = 1024 * 1024
    const val MAX_ENTRY_BYTES: Int = 64 * 1024
    const val MAX_PATH_BYTES: Int = 4 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun pairRequestPlaintext(
        pairId: String,
        clientNonce: String,
    ): ByteArray =
        buildJsonObject {
            put("pairId", pairId)
            put("clientNonce", clientNonce)
        }.toString().toByteArray(Charsets.UTF_8)

    fun pairResponsePlaintext(
        shareId: String,
        shareSecret: String,
        scopes: List<String>,
        roots: List<String>,
        label: String,
    ): ByteArray =
        buildJsonObject {
            put("shareId", shareId)
            put("shareSecret", shareSecret)
            put("scopes", buildJsonArray { scopes.forEach { add(it) } })
            put("roots", buildJsonArray { roots.forEach { add(it) } })
            put("label", label)
        }.toString().toByteArray(Charsets.UTF_8)

    fun requestEnvelope(
        op: String,
        consumerId: String,
        linkId: String,
        pairId: String?,
        reqId: String,
        clientNonce: String,
        ts: Long,
        iv: String,
        ct: String,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", op)
            put("consumerId", consumerId)
            put("linkId", linkId)
            if (pairId != null) put("pairId", pairId)
            put("reqId", reqId)
            put("clientNonce", clientNonce)
            put("ts", ts)
            put("iv", iv)
            put("ct", ct)
        }.toString()

    /**
     * Builds a response/error envelope that echoes the routing fields exactly.
     * Relay routes by `(consumerId, linkId, reqId)` and requires the same
     * clientNonce; `pair` responses must keep op and pairId.
     */
    fun responseEnvelope(
        request: RelayEnvelope,
        op: String,
        pairId: String?,
        ts: Long,
        iv: String,
        ct: String,
    ): String =
        requestEnvelope(
            op = op,
            consumerId = request.consumerId,
            linkId = request.linkId,
            pairId = pairId,
            reqId = request.reqId,
            clientNonce = request.clientNonce,
            ts = ts,
            iv = iv,
            ct = ct,
        )

    fun parseEnvelope(text: String): RelayEnvelope? {        val obj = parseObject(text) ?: return null
        val v = obj.strictInt("v") ?: return null
        if (v != VERSION) return null
        val op = obj.string("op") ?: return null
        if (op != "vfs" && op != "pair") return null
        val consumerId = obj.string("consumerId") ?: return null
        val linkId = obj.string("linkId") ?: return null
        val reqId = obj.string("reqId") ?: return null
        val clientNonce = obj.string("clientNonce") ?: return null
        val ts = obj.strictLong("ts") ?: return null
        val iv = obj.string("iv") ?: return null
        val ct = obj.string("ct") ?: return null
        val pairId = obj.string("pairId")

        val consumerRaw = decodeExact(consumerId, 16) ?: return null
        val linkRaw = decodeExact(linkId, 16) ?: return null
        val reqRaw = decodeExact(reqId, 16) ?: return null
        val nonceRaw = decodeExact(clientNonce, RelayCrypto.NONCE_BYTES) ?: return null
        val ivRaw = decodeExact(iv, RelayCrypto.IV_BYTES) ?: return null
        val ctRaw = RelayBase64.decodeUrl(ct) ?: return null
        if (ctRaw.size < RelayCrypto.TAG_BYTES || ctRaw.size > MAX_ENVELOPE_BYTES) return null
        val pairRaw =
            if (op == "pair") {
                decodeExact(pairId ?: return null, 16) ?: return null
            } else {
                if (pairId != null) return null
                null
            }
        if (consumerRaw.isEmpty()) return null
        return RelayEnvelope(
            op = op,
            consumerId = consumerId,
            linkId = linkId,
            pairId = pairId,
            reqId = reqId,
            clientNonce = clientNonce,
            ts = ts,
            iv = iv,
            ct = ct,
            linkIdRaw = linkRaw,
            pairIdRaw = pairRaw,
            reqIdRaw = reqRaw,
            clientNonceRaw = nonceRaw,
            ivRaw = ivRaw,
            ctRaw = ctRaw,
        )
    }

    fun parseIncoming(text: String): Any? {
        val obj = parseObject(text) ?: return null
        return when (obj.string("op")) {
            "ack" ->
                RelayIncoming.Ack(
                    control = obj.string("control"),
                    controlId = obj.string("controlId"),
                    linkId = obj.string("linkId"),
                    generation = obj.strictLong("generation"),
                )

            "error" ->
                obj.strictString("code")?.let { code ->
                    RelayIncoming.ControlError(
                        control = obj.string("control"),
                        controlId = obj.string("controlId"),
                        linkId = obj.string("linkId"),
                        code = code,
                        message = obj.string("message"),
                        retryable = obj.strictBoolean("retryable") ?: false,
                        currentGeneration = obj.strictLong("currentGeneration"),
                        currentPairEpoch = obj.strictLong("currentPairEpoch"),
                        retryAfterMs = obj.strictLong("retryAfterMs"),
                    )
                }

            "pair-error" ->
                obj.strictString("code")?.let { code ->
                    RelayIncoming.PairError(
                        consumerId = obj.string("consumerId").orEmpty(),
                        linkId = obj.string("linkId").orEmpty(),
                        pairId = obj.string("pairId").orEmpty(),
                        reqId = obj.string("reqId").orEmpty(),
                        code = code,
                        message = obj.string("message"),
                        retryAfterMs = obj.strictLong("retryAfterMs"),
                        serverTs = obj.strictLong("serverTs"),
                        skewMs = obj.strictLong("skewMs"),
                    )
                }

            "link-error" ->
                obj.strictString("code")?.let { code ->
                    RelayIncoming.LinkError(
                        consumerId = obj.string("consumerId").orEmpty(),
                        linkId = obj.string("linkId").orEmpty(),
                        reqId = obj.string("reqId").orEmpty(),
                        code = code,
                        message = obj.string("message"),
                    )
                }

            "ping" -> RelayIncoming.Ping(obj.string("id"))
            "pong" -> RelayIncoming.Pong(obj.string("id"))
            "vfs", "pair" -> parseEnvelope(text)
            else -> null
        }
    }

    fun pairPlaintext(
        pairId: String,
        clientNonce: String,
    ): PairPlaintext? {
        val decoded = parsePairPlaintext(pairRequestPlaintext(pairId, clientNonce)) ?: return null
        if (decoded.pairId != pairId || decoded.clientNonce != clientNonce) return null
        return decoded
    }

    fun parsePairPlaintext(bytes: ByteArray): PairPlaintext? {
        val obj = parseObject(String(bytes, Charsets.UTF_8)) ?: return null
        val pairId = obj.string("pairId") ?: return null
        val clientNonce = obj.string("clientNonce") ?: return null
        if (decodeExact(pairId, 16) == null) return null
        if (decodeExact(clientNonce, RelayCrypto.NONCE_BYTES) == null) return null
        return PairPlaintext(pairId, clientNonce)
    }

    private fun pairIdPlaintextBytes(
        pairId: String,
        clientNonce: String,
    ): ByteArray = pairRequestPlaintext(pairId, clientNonce)

    data class PairPlaintext(
        val pairId: String,
        val clientNonce: String,
    )

    fun innerError(
        code: String,
        message: String,
    ): ByteArray =
        buildJsonObject {
            put("v", VERSION)
            put("type", "error")
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString().toByteArray(Charsets.UTF_8)

    fun statResponse(entry: VfsEntry): ByteArray =
        buildJsonObject {
            put("v", VERSION)
            put("type", "entry")
            put("entry", entryObject(entry))
        }.toString().toByteArray(Charsets.UTF_8)

    fun listResponse(
        entries: List<VfsEntry>,
        nextCursor: String?,
    ): ByteArray =
        buildJsonObject {
            put("v", VERSION)
            put("type", "entries")
            put("entries", buildJsonArray { entries.forEach { add(entryObject(it)) } })
            put("nextCursor", nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString().toByteArray(Charsets.UTF_8)

    fun readResponse(
        data: ByteArray,
        etag: String?,
        offset: Long,
        nextOffset: Long,
        eof: Boolean,
    ): ByteArray =
        buildJsonObject {
            put("v", VERSION)
            put("type", "data")
            put("data", RelayBase64.encodeStd(data))
            if (etag != null) put("etag", etag)
            put("offset", offset)
            put("length", data.size)
            put("nextOffset", nextOffset)
            put("eof", eof)
        }.toString().toByteArray(Charsets.UTF_8)

    fun entryObject(entry: VfsEntry): JsonObject =
        buildJsonObject {
            put("path", entry.path)
            put("name", entry.name)
            put("isDirectory", entry.isDirectory)
            put("size", entry.size)
            put("mtimeMs", entry.mtimeMs)
            entry.mimeType?.let { put("mimeType", it) }
            entry.etag?.let { put("etag", it) }
        }

    fun entryJsonBytes(entry: VfsEntry): Int = entryObject(entry).toString().toByteArray(Charsets.UTF_8).size

    fun parseInnerRequest(bytes: ByteArray): RelayInnerRequest {
        val obj = parseObject(String(bytes, Charsets.UTF_8))
            ?: throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "invalid inner json")
        val v = obj.strictInt("v") ?: throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "invalid version")
        if (v != VERSION) throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "invalid version")
        val type = obj.string("type") ?: throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "missing type")
        val path = obj.string("path") ?: throw RelayProtocolException(RelayInnerError.INVALID_PATH, "missing path")
        return when (type) {
            "stat" -> RelayInnerRequest.Stat(path)
            "list" -> {
                val limit = obj.strictInt("limit") ?: DEFAULT_LIST_LIMIT
                if (limit !in 1..MAX_LIST_ENTRIES) {
                    throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "invalid limit")
                }
                RelayInnerRequest.List(path, obj.string("cursor"), limit)
            }

            "read" -> {
                val offset = obj.strictLong("offset") ?: 0
                val length = obj.strictLong("length")
                    ?: throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "missing read length")
                if (offset < 0 || length < 0 || length > MAX_READ_BYTES) {
                    throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "invalid read range")
                }
                RelayInnerRequest.Read(path, offset, length.toInt(), obj.string("etag"))
            }

            else -> throw RelayProtocolException(RelayInnerError.BAD_REQUEST, "unknown type")
        }
    }

    fun pairError(
        consumerId: String,
        linkId: String,
        pairId: String,
        reqId: String,
        code: String,
        message: String,
        retryAfterMs: Long? = null,
        serverTs: Long? = null,
        skewMs: Long? = null,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "pair-error")
            put("consumerId", consumerId)
            put("linkId", linkId)
            put("pairId", pairId)
            put("reqId", reqId)
            put("code", code)
            put("message", message)
            if (retryAfterMs != null) put("retryAfterMs", retryAfterMs)
            if (serverTs != null) put("serverTs", serverTs)
            if (skewMs != null) put("skewMs", skewMs)
        }.toString()

    fun linkError(
        consumerId: String,
        linkId: String,
        reqId: String,
        code: String,
        message: String,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "link-error")
            put("consumerId", consumerId)
            put("linkId", linkId)
            put("reqId", reqId)
            put("code", code)
            put("message", message)
        }.toString()

    fun pairControl(
        controlId: String,
        linkId: String,
        pairId: String?,
        ttlSeconds: Long?,
        state: String,
        pairEpoch: Long,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "pair")
            put("controlId", controlId)
            put("linkId", linkId)
            if (pairId != null) put("pairId", pairId)
            if (ttlSeconds != null) put("ttlSeconds", ttlSeconds)
            put("state", state)
            put("pairEpoch", pairEpoch)
        }.toString()

    fun registerControl(
        controlId: String,
        linkId: String,
        deviceId: String,
        generation: Long,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "register")
            put("controlId", controlId)
            put("linkId", linkId)
            put("deviceId", deviceId)
            put("generation", generation)
        }.toString()

    fun unregisterControl(
        controlId: String,
        linkId: String,
        reason: String,
    ): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "unregister")
            put("controlId", controlId)
            put("linkId", linkId)
            put("reason", reason)
        }.toString()

    fun ping(id: String): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "ping")
            put("id", id)
        }.toString()

    fun pong(id: String?): String =
        buildJsonObject {
            put("v", VERSION)
            put("op", "pong")
            if (id != null) put("id", id)
        }.toString()

    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun decodeExact(
        text: String,
        size: Int,
    ): ByteArray? {
        val bytes = RelayBase64.decodeUrl(text) ?: return null
        return bytes.takeIf { it.size == size }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strictString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strictLong(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    private fun JsonObject.strictInt(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.strictBoolean(name: String): Boolean? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toBooleanStrictOrNull()
    }
}

/**
 * Relay only accepts `pair` controls for a Link after its `register` ack. The
 * runtime uses this gate to enforce register-before-pair across reconnects.
 */
class RelayLinkGate {
    private val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun onRegisterAck(linkId: String) {
        registered += linkId
    }

    fun canSendPair(linkId: String): Boolean = linkId in registered

    fun forget(linkId: String) {
        registered -= linkId
    }

    fun clear() {
        registered.clear()
    }
}

sealed interface RelayInnerRequest {    data class Stat(val path: String) : RelayInnerRequest

    data class List(
        val path: String,
        val cursor: String?,
        val limit: Int,
    ) : RelayInnerRequest

    data class Read(
        val path: String,
        val offset: Long,
        val length: Int,
        val etag: String?,
    ) : RelayInnerRequest
}
