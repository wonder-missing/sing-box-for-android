package io.nekohasekai.sfa.telemetry

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * On-device telemetry settings. Endpoint and token have no built-in defaults.
 * The ingest token is kept in this private preferences file, which is not part
 * of the app backup rules, and is never written into the shared settings database.
 */
internal object TelemetryPreferences {
    private const val FILE = "telemetry"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ENDPOINT = "endpoint_url"
    private const val KEY_TOKEN = "ingest_token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_AUTH_PAUSE_UNTIL = "auth_pause_until"
    private const val KEY_AUTH_PAUSE_FINGERPRINT = "auth_pause_fingerprint"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun endpointUrl(context: Context): String = prefs(context).getString(KEY_ENDPOINT, "").orEmpty()

    fun ingestToken(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        TelemetryRuntime.onSettingsChanged(context)
    }

    fun save(context: Context, enabled: Boolean, endpoint: String, token: String) {
        val normalizedToken = token.trim().removePrefix("Bearer ").trim()
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_ENDPOINT, normalizeEndpoint(endpoint))
            .putString(KEY_TOKEN, normalizedToken)
            .commit()
        TelemetryRuntime.onSettingsChanged(context)
    }

    fun normalizeEndpoint(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        if (!text.contains("://")) {
            text = "https://$text"
        }
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return text
        if (!uri.scheme.equals("https", ignoreCase = true)) return text
        val host = uri.host ?: return text
        var path = uri.rawPath.orEmpty()
        if (path.length > 1 && path.endsWith("/")) {
            path = path.dropLast(1)
        }
        if (path.isEmpty() || path == "/") {
            path = "/ingest"
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val query = if (uri.rawQuery.isNullOrEmpty()) "" else "?${uri.rawQuery}"
        return "https://$host$port$path$query"
    }

    fun deviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val created = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DEVICE, created).commit()
        return created
    }

    fun canUpload(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val token = ingestToken(context)
        if (token.isBlank() || token.any { it == '\r' || it == '\n' }) return false
        return isHttpsEndpoint(normalizeEndpoint(endpointUrl(context)))
    }

    fun isHttpsEndpoint(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.length > "https://".length &&
            trimmed.startsWith("https://", ignoreCase = true)
    }

    fun isAuthPaused(context: Context, token: String): Boolean {
        val until = prefs(context).getLong(KEY_AUTH_PAUSE_UNTIL, 0L)
        if (until <= System.currentTimeMillis()) return false
        val fingerprint = prefs(context).getString(KEY_AUTH_PAUSE_FINGERPRINT, null)
        return fingerprint == fingerprint(token)
    }

    fun noteUnauthorized(context: Context, token: String, pauseMs: Long) {
        prefs(context).edit()
            .putLong(KEY_AUTH_PAUSE_UNTIL, System.currentTimeMillis() + pauseMs)
            .putString(KEY_AUTH_PAUSE_FINGERPRINT, fingerprint(token))
            .apply()
    }

    private fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
