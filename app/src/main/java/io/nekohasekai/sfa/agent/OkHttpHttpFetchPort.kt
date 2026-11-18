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
 * Standard Android HTTP fetch using OkHttp. It does not touch libbox and does
 * not change any core selection; the request uses the device's current network.
 */
class OkHttpHttpFetchPort(
    private val client: OkHttpClient = defaultClient(),
) : HttpFetchPort {
    override fun fetch(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpFetchOutcome {
        if (cancellation?.isCancelled() == true) return HttpFetchOutcome(error = "cancelled")

        return try {
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
                HttpFetchOutcome(
                    statusCode = result.code,
                    statusText = result.message.takeIf { it.isNotBlank() },
                    latencyMs = elapsedMs(started),
                    content = if (binary) null else String(bodyResult.bytes, charset),
                    contentType = contentType?.toString(),
                    headers = result.headers.toMultimap(),
                    bytes = bodyResult.bytes.size,
                    truncated = bodyResult.truncated,
                    binary = binary,
                )
            }
        } catch (e: Exception) {
            if (cancellation?.isCancelled() == true) {
                HttpFetchOutcome(error = "cancelled")
            } else {
                HttpFetchOutcome(error = e.message ?: e.toString())
            }
        }
    }

    override fun download(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        maxBytes: Int,
        cancellation: ToolCancellation?,
    ): HttpDownloadOutcome {
        if (cancellation?.isCancelled() == true) return HttpDownloadOutcome(error = "cancelled")
        return try {
            val request = buildRequest(url, method, headers, body)
            val call = client.newCall(request)
            cancellation?.onCancel { call.cancel() }
            val started = System.nanoTime()
            call.execute().use { result ->
                val bodyResult = readBody(result.body, maxBytes.coerceAtLeast(1))
                HttpDownloadOutcome(
                    statusCode = result.code,
                    statusText = result.message.takeIf { it.isNotBlank() },
                    latencyMs = elapsedMs(started),
                    contentType = result.body?.contentType()?.toString(),
                    bytes = bodyResult.bytes,
                    truncated = bodyResult.truncated,
                )
            }
        } catch (e: Exception) {
            if (cancellation?.isCancelled() == true) {
                HttpDownloadOutcome(error = "cancelled")
            } else {
                HttpDownloadOutcome(error = e.message ?: e.toString())
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
