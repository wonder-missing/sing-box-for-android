package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.agent.mcp.McpServerConfig
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
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PortalTarget(
    val portalServerId: String,
    val name: String,
    val canonicalBase: String,
    val hookToken: String,
)

data class ProviderKeyIssue(
    val providerKey: String,
    val providerKeyId: String,
    val hookId: String,
    val deviceId: String,
    val createdAt: Long,
    val expiresAt: Long?,
) {
    fun providerKeyBytes(): ByteArray =
        RelayBase64.decodeUrl(providerKey.removePrefix("wsk_"))
            ?: throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned a malformed Provider key.", false)
}

class RelayProvisionException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)

/**
 * Bootstraps the Relay Provider key from an existing MCP server: the server's
 * `serverBase`/`url` origin plus its `whsec_` token. The token only goes to the
 * selected server's canonical base. No second MCP store is created.
 */
class VfsProviderKeyProvisioner(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun targetOf(config: McpServerConfig): PortalTarget {
        val rawBase = config.serverBase.trim().ifBlank { config.url.trim() }
        val canonical = canonicalOrigin(rawBase)
            ?: throw RelayProvisionException(
                "INVALID_PORTAL",
                "This Portal URL is not an HTTPS origin. Share files needs an HTTPS Portal (Funnel).",
                false,
            )
        val token = config.token.trim()
        if (!token.startsWith("whsec_")) {
            throw RelayProvisionException(
                "TOKEN_UNSUPPORTED",
                "Share files needs this Portal's MCP hook secret (whsec_...). OAuth-only servers are not supported yet.",
                false,
            )
        }
        return PortalTarget(
            portalServerId = config.id,
            name = config.name,
            canonicalBase = canonical,
            hookToken = token,
        )
    }

    fun issue(
        target: PortalTarget,
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
                .header("Authorization", "Bearer ${target.hookToken}")
                .header("Accept", "application/json")
                .post(body)
                .build()
        val (status, text) = execute(request)
        if (status !in 200..299) throw provisionError(status, text)
        val obj = parseObject(text)
            ?: throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned an invalid Provider key response.", false)
        val providerKey = string(obj, "providerKey")
        val providerKeyId = string(obj, "providerKeyId")
        val hookId = string(obj, "hookId")
        val returnedDevice = string(obj, "deviceId") ?: deviceId
        if (providerKey == null || !providerKey.startsWith("wsk_")) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned a malformed Provider key.", false)
        }
        if (providerKeyId == null || RelayBase64.decodeUrl(providerKeyId)?.size != 16) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned a malformed Provider key id.", false)
        }
        if (hookId.isNullOrBlank() || returnedDevice != deviceId) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned a mismatched device identity.", false)
        }
        if (RelayBase64.decodeUrl(providerKey.removePrefix("wsk_"))?.size != 32) {
            throw RelayProvisionException("PROTOCOL_ERROR", "Portal returned a malformed Provider key.", false)
        }
        return ProviderKeyIssue(
            providerKey = providerKey,
            providerKeyId = providerKeyId,
            hookId = hookId,
            deviceId = returnedDevice,
            createdAt = long(obj, "createdAt") ?: System.currentTimeMillis(),
            expiresAt = long(obj, "expiresAt"),
        )
    }

    fun postLink(
        target: PortalTarget,
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
                RelayProvisionException("LINK_LIMIT", "This Portal is already at its Share limit. Revoke a Share or ask the administrator.", false)

            status == 429 ->
                RelayProvisionException("RATE_LIMITED", "The Portal is rate limiting Share creation. Try again shortly.", true)

            status == 503 ->
                RelayProvisionException("RELAY_UNAVAILABLE", "The Portal Relay is unavailable. Try again.", true)

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
                    "The Portal rejected this MCP token. Update the server token in Conductor settings, then Connect again.",
                    false,
                )

            code == "CAPABILITY_REQUIRED" ->
                RelayProvisionException(
                    "CAPABILITY_REQUIRED",
                    "This Portal has not enabled Share Key VFS. Ask its administrator to grant shareKeyVfs to this hook.",
                    false,
                )

            code == "DEVICE_AUTH" ->
                RelayProvisionException(
                    "DEVICE_AUTH",
                    "This device's secret does not match the Portal record. Ask the administrator to delete the old provider key, then create a new Share.",
                    false,
                )

            code == "DEVICE_REVOKED" ->
                RelayProvisionException(
                    "DEVICE_REVOKED",
                    "This device was revoked on the Portal. Create a new Share on this device.",
                    false,
                )

            code == "ROTATE_REQUIRED" ->
                RelayProvisionException(
                    "ROTATE_REQUIRED",
                    "The Portal requires a new Provider key. Update WonderSing, then Connect again.",
                    false,
                )

            code == "HOOK_SUSPENDED" ->
                RelayProvisionException(
                    "HOOK_SUSPENDED",
                    "This Portal hook is suspended. Ask its administrator to re-enable Share Key VFS, then Connect again.",
                    false,
                )

            status == 403 ->
                RelayProvisionException(
                    "FORBIDDEN",
                    detail ?: "The Portal refused this request. Check the Portal's Share Key VFS settings.",
                    false,
                )

            status == 404 ->
                RelayProvisionException(
                    "UNSUPPORTED_PORTAL",
                    "This Portal does not support Share Key VFS. Update the Portal service, then Connect again.",
                    false,
                )

            status == 429 ->
                RelayProvisionException("RATE_LIMITED", "The Portal is rate limiting key issuance. Try again shortly.", true)

            status in 500..599 ->
                RelayProvisionException(
                    "RELAY_UNAVAILABLE",
                    detail ?: "The Portal Relay is unavailable. Try again.",
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
                    "Could not reach the Portal: ${error.message ?: "network error"}",
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

        fun canonicalOrigin(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val host = uri.host ?: return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            val scheme = uri.scheme.lowercase(Locale.US)
            val authority = if (uri.port == -1 || uri.port == 443) host.lowercase(Locale.US) else "${host.lowercase(Locale.US)}:${uri.port}"
            return "$scheme://$authority"
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
