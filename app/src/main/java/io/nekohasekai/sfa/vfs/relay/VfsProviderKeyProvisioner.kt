package io.nekohasekai.sfa.vfs.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class RelayServerTarget(
    val serverId: String,
    val name: String,
    val canonicalBase: String,
    val relayToken: String,
)

data class ProviderKeyIssue(
    val providerKey: String,
    val providerKeyId: String,
    val relayId: String,
    val deviceId: String,
    val createdAt: Long,
    val expiresAt: Long?,
) {
    fun providerKeyBytes(): ByteArray =
        RelayBase64.decodeUrl(providerKey.removePrefix("wsk_"))
            ?: throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned a malformed Provider key.", false)
}

class RelayProvisionException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)

/**
 * Bootstraps the Relay Provider key from a locally configured Relay Server.
 * The relay token is only sent to that server's canonical HTTPS origin.
 */
class VfsProviderKeyProvisioner(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun targetOf(
        server: VfsRelayServer,
        relayToken: String,
    ): RelayServerTarget {
        val canonical = RelayServerValidation.canonicalHttpsOrigin(server.baseUrl)
            ?: throw RelayProvisionException(
                "INVALID_RELAY_URL",
                "The Relay URL must be an HTTPS origin.",
                false,
            )
        val token = RelayServerValidation.normalizeToken(relayToken)
            ?: throw RelayProvisionException(
                "INVALID_TOKEN",
                "The Relay token is missing or invalid. Edit the Relay Server.",
                false,
            )
        return RelayServerTarget(
            serverId = server.id,
            name = server.name,
            canonicalBase = canonical,
            relayToken = token,
        )
    }

    fun issue(
        target: RelayServerTarget,
        deviceId: String,
        deviceSecret: ByteArray,
        deviceName: String,
    ): ProviderKeyIssue {
        val body =
            buildJsonObject {
                put("deviceId", deviceId)
                put("name", deviceName.take(64))
                put("deviceSecret", RelayBase64.encodeUrl(deviceSecret))
            }.toString().toRequestBody(JSON)
        val request =
            Request.Builder()
                .url("${target.canonicalBase}/v1/provider-keys")
                .header("Authorization", "Bearer ${target.relayToken}")
                .header("Accept", "application/json")
                .post(body)
                .build()
        val (status, text) = execute(request)
        if (status !in 200..299) throw provisionError(status, text)
        return parseIssueResponse(text, deviceId)
    }

    fun parseIssueResponse(
        text: String,
        deviceId: String,
    ): ProviderKeyIssue {
        val obj = parseObject(text)
            ?: throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned an invalid Provider key response.", false)
        val providerKey = string(obj, "providerKey")
        val providerKeyId = string(obj, "providerKeyId")
        val relayId = string(obj, "relayId")
        val returnedDevice = string(obj, "deviceId") ?: deviceId
        if (providerKey == null || !providerKey.startsWith("wsk_")) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned a malformed Provider key.", false)
        }
        if (providerKeyId == null || RelayBase64.decodeUrl(providerKeyId)?.size != 16) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned a malformed Provider key id.", false)
        }
        if (relayId.isNullOrBlank() || returnedDevice != deviceId) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned a mismatched relay identity.", false)
        }
        if (RelayBase64.decodeUrl(providerKey.removePrefix("wsk_"))?.size != 32) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Relay Server returned a malformed Provider key.", false)
        }
        return ProviderKeyIssue(
            providerKey = providerKey,
            providerKeyId = providerKeyId,
            relayId = relayId,
            deviceId = returnedDevice,
            createdAt = long(obj, "createdAt") ?: System.currentTimeMillis(),
            expiresAt = long(obj, "expiresAt"),
        )
    }

    fun postLink(
        target: RelayServerTarget,
        providerKey: String,
        linkId: String,
    ) {
        val body =
            buildJsonObject {
                put("linkId", linkId)
            }.toString().toRequestBody(JSON)
        val request =
            Request.Builder()
                .url("${target.canonicalBase}/v1/links")
                .header("Authorization", "Bearer $providerKey")
                .header("Accept", "application/json")
                .post(body)
                .build()
        val (status, text) = execute(request)
        if (status in 200..299) return
        val code = parseObject(text)?.let { string(it, "code") }
        throw when {
            status == 409 || code == "LINK_OWNED" ->
                RelayProvisionException("LINK_OWNED", "This Share belongs to another device. Create a new Share.", false)

            status == 410 || code == "REVOKED" ->
                RelayProvisionException("LINK_REVOKED", "This Share was revoked. Create a new Share.", false)

            code == "LINK_LIMIT" ->
                RelayProvisionException("LINK_LIMIT", "This Relay Server is already at its Share limit. Revoke a Share first.", false)

            status == 429 ->
                RelayProvisionException("RATE_LIMITED", "The Relay Server is rate limiting Share creation. Try again shortly.", true)

            status == 503 ->
                RelayProvisionException("RELAY_UNAVAILABLE", "The Relay Server is unavailable. Try again.", true)

            else -> provisionError(status, text)
        }
    }

    private fun provisionError(
        status: Int,
        text: String,
    ): RelayProvisionException {
        val obj = parseObject(text)
        val code = obj?.let { string(it, "code") }
        val message = obj?.let { string(it, "message") }
        val detail = message?.take(180)
        return when {
            status == 401 ->
                RelayProvisionException(
                    "AUTH",
                    "The Relay Server rejected this token. Edit the Relay Server and check the token, then Connect again.",
                    false,
                )

            code == "CAPABILITY_REQUIRED" ->
                RelayProvisionException(
                    "CAPABILITY_REQUIRED",
                    "This Relay Server has not enabled Share Key VFS.",
                    false,
                )

            code == "DEVICE_AUTH" ->
                RelayProvisionException(
                    "DEVICE_AUTH",
                    "This device's secret does not match the Relay Server record. Use Reset Share files data to create a new device identity and Share.",
                    false,
                )

            code == "DEVICE_REVOKED" ->
                RelayProvisionException(
                    "DEVICE_REVOKED",
                    "This device was revoked on the Relay Server. Use Reset Share files data to create a new device identity and Share.",
                    false,
                )

            code == "ROTATE_REQUIRED" ->
                RelayProvisionException(
                    "ROTATE_REQUIRED",
                    "The Relay Server requires a new Provider key. Update WonderSing, then Connect again.",
                    false,
                )

            status == 403 ->
                RelayProvisionException(
                    "FORBIDDEN",
                    detail ?: "The Relay Server refused this request. Check its settings.",
                    false,
                )

            status == 404 ->
                RelayProvisionException(
                    "UNSUPPORTED_RELAY_SERVER",
                    "This Relay Server does not support Share Key VFS.",
                    false,
                )

            status == 429 ->
                RelayProvisionException("RATE_LIMITED", "The Relay Server is rate limiting key issuance. Try again shortly.", true)

            status in 500..599 ->
                RelayProvisionException(
                    "RELAY_UNAVAILABLE",
                    detail ?: "The Relay Server is unavailable. Try again.",
                    true,
                )

            else ->
                RelayProvisionException(
                    "PROVIDER_KEY_FAILED",
                    detail ?: "Could not get a Provider key (HTTP $status).",
                    false,
                )
        }
    }

    private fun execute(request: Request): Pair<Int, String> {
        val response =
            try {
                client.newCall(request).execute()
            } catch (error: Exception) {
                throw RelayProvisionException(
                    "RELAY_UNREACHABLE",
                    "Could not reach the Relay Server: ${error.message ?: "network error"}",
                    true,
                )
            }
        response.use {
            val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
            return it.code to body
        }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun string(
        obj: JsonObject,
        name: String,
    ): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun long(
        obj: JsonObject,
        name: String,
    ): Long? {
        val primitive = obj[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun canonicalOrigin(raw: String): String? = RelayServerValidation.canonicalHttpsOrigin(raw)

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
