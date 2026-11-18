package io.nekohasekai.sfa.conductor

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
private const val MAX_IMAGE_DIMENSION = 1600

private sealed interface AssistantImageState {
    data object Idle : AssistantImageState

    data object Loading : AssistantImageState

    data class Loaded(
        val bitmap: ImageBitmap,
    ) : AssistantImageState

    data class Error(
        val message: String,
    ) : AssistantImageState
}

private val assistantImageClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

@Composable
internal fun AssistantMarkdownText(text: String) {
    val segments = remember(text) { assistantMarkdownSegments(text) }
    if (segments.none { it is ConductorAssistantSegment.Image }) {
        SelectionContainer {
            Text(
                text =
                    markdownAnnotated(
                        text,
                        codeBackground = androidx.compose.ui.graphics.Color.Transparent,
                        codeColor = conductorInlineCodeColor(MaterialTheme.colorScheme),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is ConductorAssistantSegment.Text -> {
                    val chunk = segment.text.trim()
                    if (chunk.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                text =
                                    markdownAnnotated(
                                        chunk,
                                        codeBackground = androidx.compose.ui.graphics.Color.Transparent,
                                        codeColor = conductorInlineCodeColor(MaterialTheme.colorScheme),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                is ConductorAssistantSegment.Image -> AssistantImageBlock(segment.alt, segment.url)
            }
        }
    }
}

@Composable
private fun AssistantImageBlock(
    alt: String,
    url: String,
) {
    var state by remember(url) { mutableStateOf<AssistantImageState>(AssistantImageState.Idle) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (state is AssistantImageState.Loading) return
        scope.launch {
            state = AssistantImageState.Loading
            state = withContext(Dispatchers.IO) { loadAssistantImage(url) }
        }
    }

    LaunchedEffect(url) {
        if (url.startsWith("data:image/", ignoreCase = true)) load()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            when (val current = state) {
                is AssistantImageState.Loaded -> {
                    Image(
                        bitmap = current.bitmap,
                        contentDescription = alt.ifBlank { "Image" },
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                }

                is AssistantImageState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Loading image…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                is AssistantImageState.Error -> {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                AssistantImageState.Idle -> Unit
            }

            if (state !is AssistantImageState.Loaded && state !is AssistantImageState.Loading) {
                Text(
                    text = alt.ifBlank { "Image" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!url.startsWith("data:", ignoreCase = true)) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = ::load) {
                        Text(if (state is AssistantImageState.Error) "Retry" else "Load image")
                    }
                }
            }
        }
    }
}

private suspend fun loadAssistantImage(url: String): AssistantImageState {
    if (url.startsWith("data:image/", ignoreCase = true)) {
        return decodeDataImage(url)
    }
    val uri =
        runCatching { URI(url) }.getOrNull()
            ?: return AssistantImageState.Error("Invalid image URL.")
    if (uri.scheme?.equals("https", ignoreCase = true) != true || uri.host.isNullOrBlank()) {
        return AssistantImageState.Error("Only HTTPS images are loaded.")
    }
    if (!isPublicImageHost(uri.host)) {
        return AssistantImageState.Error("Image host is not public.")
    }
    val request =
        Request
            .Builder()
            .url(url)
            .header("Accept", "image/*")
            .build()
    return try {
        assistantImageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AssistantImageState.Error("Image request failed (${response.code}).")
            } else {
                val body = response.body ?: return AssistantImageState.Error("Image response is empty.")
                if (body.contentLength() > MAX_IMAGE_BYTES) {
                    return AssistantImageState.Error("Image is too large.")
                }
                val bytes = body.byteStream().use(::readLimited)
                    ?: return AssistantImageState.Error("Image is too large.")
                decodeImage(bytes)
            }
        }
    } catch (e: Exception) {
        AssistantImageState.Error(e.message?.take(160) ?: "Could not load image.")
    }
}

private fun decodeDataImage(url: String): AssistantImageState {
    val comma = url.indexOf(',')
    if (comma < 0) return AssistantImageState.Error("Invalid embedded image.")
    val encoded = url.substring(comma + 1)
    if (encoded.length > MAX_IMAGE_BYTES * 4 / 3 + 16) {
        return AssistantImageState.Error("Image is too large.")
    }
    val bytes =
        runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return AssistantImageState.Error("Invalid embedded image.")
    if (bytes.size > MAX_IMAGE_BYTES) return AssistantImageState.Error("Image is too large.")
    return decodeImage(bytes)
}

private fun decodeImage(bytes: ByteArray): AssistantImageState {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return AssistantImageState.Error("Unsupported image format.")
    }
    var sample = 1
    while (
        bounds.outWidth / sample > MAX_IMAGE_DIMENSION ||
        bounds.outHeight / sample > MAX_IMAGE_DIMENSION
    ) {
        sample *= 2
    }
    val bitmap =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return AssistantImageState.Error("Unsupported image format.")
    return AssistantImageState.Loaded(bitmap.asImageBitmap())
}

private fun readLimited(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > MAX_IMAGE_BYTES) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun isPublicImageHost(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized.endsWith(".local")) return false
    val addresses =
        runCatching { InetAddress.getAllByName(normalized) }.getOrNull()
            ?: return false
    if (addresses.isEmpty()) return false
    return addresses.all { address ->
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !address.isMulticastAddress &&
            !isUniqueLocalIpv6(address.address)
    }
}

private fun isUniqueLocalIpv6(bytes: ByteArray): Boolean =
    bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
