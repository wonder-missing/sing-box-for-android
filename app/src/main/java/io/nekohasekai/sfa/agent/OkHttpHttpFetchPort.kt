package io.nekohasekai.sfa.agent

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Standard Android HTTP fetch using OkHttp. It does not go through libbox.
 *
 * A named outbound is a Conductor extension: when present, it temporarily
 * changes the running core's global selection so app traffic (including this
 * client) uses that node, then restores the previous selection. Without an
 * outbound, this is a normal request over the device's current network.
 */
class OkHttpHttpFetchPort(
    private val nodeSource: NodeSnapshotSource? = null,
    private val appTrafficUsesVpn: () -> Boolean? = { null },
    private val selectOutbound: ((group: String, outbound: String) -> Unit)? = null,
    private val restoreSelection: ((group: String, previous: String) -> Unit)? = null,
    private val client: OkHttpClient = defaultClient(),
) : HttpFetchPort {
    override fun fetch(
        outbound: String?,
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpFetchOutcome {
        if (cancellation?.isCancelled() == true) return HttpFetchOutcome(error = "cancelled")

        var selectedGroup: AgentGroup? = null
        var selectionChanged = false
        val via = if (outbound == null) "current_network" else "temporary_outbound_selection"
        try {
            if (outbound != null) {
                val snapshot =
                    nodeSource?.snapshot()
                        ?: return HttpFetchOutcome(error = "outbound selection is not available", via = via)
                if (!snapshot.commandClientConnected) {
                    return HttpFetchOutcome(
                        error = "sing-box core is not reachable; start the service before selecting an outbound",
                        via = via,
                    )
                }
                if (snapshot.remoteControl) {
                    return HttpFetchOutcome(
                        error = "outbound selection is local-only; Remote Control is rejected",
                        via = via,
                    )
                }
                if (appTrafficUsesVpn() != true) {
                    return HttpFetchOutcome(
                        error =
                            "this app's HTTP traffic is not routed through the local core (the service must " +
                                "be running in VPN mode); refusing to fetch through a named outbound",
                        via = via,
                    )
                }
                val group =
                    snapshot.groups.firstOrNull { candidate ->
                        candidate.selectable && candidate.items.any { it.tag == outbound }
                    } ?: return HttpFetchOutcome(
                        error = "outbound '$outbound' is not a selectable node in any outbound group",
                        via = via,
                    )
                if (group.selected.isBlank()) {
                    return HttpFetchOutcome(
                        error =
                            "outbound group '${group.tag}' has no current selection to restore; " +
                                "refusing to change its route",
                        via = via,
                    )
                }
                selectedGroup = group
                if (group.selected != outbound) {
                    val select =
                        selectOutbound
                            ?: return HttpFetchOutcome(error = "outbound selection is not available", via = via)
                    select(group.tag, outbound)
                    selectionChanged = true
                }
            }

            val request = buildRequest(url, method, headers, body)
            val call = client.newCall(request)
            cancellation?.onCancel { call.cancel() }
            val started = System.nanoTime()
            val response = call.execute()
            response.use { result ->
                val bodyResult = readBody(result.body, maxBytes.coerceAtLeast(1))
                val contentType = result.body?.contentType()
                val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val binary = bodyResult.bytes.any { it == 0.toByte() }
                return HttpFetchOutcome(
                    statusCode = result.code,
                    statusText = result.message.takeIf { it.isNotBlank() },
                    latencyMs = elapsedMs(started),
                    content = if (binary) null else String(bodyResult.bytes, charset),
                    contentType = contentType?.toString(),
                    headers = result.headers.toMultimap(),
                    bytes = bodyResult.bytes.size,
                    truncated = bodyResult.truncated,
                    binary = binary,
                    via = via,
                )
            }
        } catch (e: Exception) {
            return if (cancellation?.isCancelled() == true) {
                HttpFetchOutcome(via = via, error = "cancelled")
            } else {
                HttpFetchOutcome(via = via, error = e.message ?: e.toString())
            }
        } finally {
            if (selectionChanged) {
                selectedGroup?.let { group ->
                    runCatching { restoreSelection?.invoke(group.tag, group.selected) }
                }
            }
        }
    }

    private fun buildRequest(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val upper = method.uppercase(Locale.US)
        val bodyAllowed = upper != "GET" && upper != "HEAD"
        val hasContentType = headers.any { it.first.equals("Content-Type", ignoreCase = true) }
        val mediaType = if (hasContentType) null else TEXT_MEDIA_TYPE
        val requestBody =
            when {
                !bodyAllowed -> null
                body != null -> body.toRequestBody(mediaType)
                upper in BODY_REQUIRED_METHODS -> ByteArray(0).toRequestBody(null)
                else -> null
            }
        builder.method(method, requestBody)
        return builder.build()
    }

    private fun readBody(
        body: ResponseBody?,
        maxBytes: Int,
    ): BodyRead {
        if (body == null) return BodyRead(ByteArray(0), truncated = false)
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            var truncated = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (total + read > maxBytes) {
                    val take = maxBytes - total
                    if (take > 0) out.write(buffer, 0, take)
                    total = maxBytes
                    truncated = true
                    break
                }
                out.write(buffer, 0, read)
                total += read
            }
            if (body.contentLength() > maxBytes) truncated = true
            return BodyRead(out.toByteArray(), truncated)
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private data class BodyRead(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    companion object {
        private val TEXT_MEDIA_TYPE = "text/plain;charset=UTF-8".toMediaTypeOrNull()
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }
}
