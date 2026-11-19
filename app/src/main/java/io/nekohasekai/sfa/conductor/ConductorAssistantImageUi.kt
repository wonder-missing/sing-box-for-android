package io.nekohasekai.sfa.conductor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
private const val MAX_IMAGE_DIMENSION = 1600
private const val MAX_SVG_CHARS = 256 * 1024
private val UNSAFE_SVG =
    Regex(
        """(?is)<\s*(script|foreignObject|image|iframe|object|embed)\b|\son[a-z]+\s*=|(?:href|xlink:href)\s*=\s*["'](?!#)|javascript:|url\s*\(\s*["']?(?:https?:|data:)|<!doctype|<!entity|@import""",
    )

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
internal fun AssistantMarkdownText(text: String, resourceFile: (String) -> File?) {
    val segments = remember(text) { assistantMarkdownSegments(text) }
    if (
        segments.none {
            it is ConductorAssistantSegment.Image ||
                it is ConductorAssistantSegment.Svg ||
                it is ConductorAssistantSegment.Code ||
                it is ConductorAssistantSegment.Table
        }
    ) {
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

                is ConductorAssistantSegment.Image -> AssistantImageBlock(segment.alt, segment.url, resourceFile)

                is ConductorAssistantSegment.Svg -> AssistantSvgBlock(segment.source)

                is ConductorAssistantSegment.Code -> AssistantCodeBlock(segment)

                is ConductorAssistantSegment.Table -> AssistantTableBlock(segment)
            }
        }
    }
}

@Composable
private fun AssistantImageBlock(
    alt: String,
    url: String,
    resourceFile: (String) -> File?,
) {
    var state by remember(url) { mutableStateOf<AssistantImageState>(AssistantImageState.Idle) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (state is AssistantImageState.Loading) return
        scope.launch {
            state = AssistantImageState.Loading
            state = withContext(Dispatchers.IO) { loadAssistantImage(url, resourceFile) }
        }
    }

    val localResource = !url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true) &&
        !url.startsWith("data:", ignoreCase = true)
    LaunchedEffect(url, localResource) {
        if (url.startsWith("data:image/", ignoreCase = true) || localResource) load()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = if (state is AssistantImageState.Loaded) Modifier else Modifier.padding(12.dp)) {
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
                                .clip(RoundedCornerShape(12.dp)),
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

@Composable
private fun AssistantSvgBlock(source: String) {
    var state by remember(source) { mutableStateOf<AssistantImageState>(AssistantImageState.Loading) }
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        state = withContext(Dispatchers.IO) { decodeSvg(source) }
    }

    val loaded = state as? AssistantImageState.Loaded
    if (loaded != null) {
        Image(
            bitmap = loaded.bitmap,
            contentDescription = "SVG",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullscreen = true },
        )
        if (fullscreen) {
            FullscreenImageDialog(
                bitmap = loaded.bitmap,
                contentDescription = "SVG",
                onDismiss = { fullscreen = false },
            )
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (val current = state) {
                is AssistantImageState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Rendering SVG…",
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

                AssistantImageState.Idle, is AssistantImageState.Loaded -> Unit
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    bitmap: ImageBitmap,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale <= 1f) Offset.Zero else offset + panChange
        }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transform),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

private suspend fun loadAssistantImage(
    url: String,
    resourceFile: (String) -> File?,
): AssistantImageState {
    if (url.startsWith("data:image/", ignoreCase = true)) {
        return decodeDataImage(url)
    }
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        val file = resourceFile(url) ?: return AssistantImageState.Error("Resource not found.")
        if (file.length() > MAX_IMAGE_BYTES) return AssistantImageState.Error("Image is too large.")
        return decodeImage(file.readBytes())
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
    val header = url.substring(0, comma).lowercase()
    val encoded = url.substring(comma + 1)
    if (encoded.length > MAX_IMAGE_BYTES * 4 / 3 + 16) {
        return AssistantImageState.Error("Image is too large.")
    }
    if (header.contains("image/svg+xml")) {
        val source =
            if (header.contains(";base64")) {
                runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                    ?.toString(Charsets.UTF_8)
            } else {
                runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            } ?: return AssistantImageState.Error("Invalid embedded SVG.")
        return decodeSvg(source)
    }
    val bytes =
        runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return AssistantImageState.Error("Invalid embedded image.")
    if (bytes.size > MAX_IMAGE_BYTES) return AssistantImageState.Error("Image is too large.")
    return decodeImage(bytes)
}

private fun decodeImage(bytes: ByteArray): AssistantImageState {
    val text = bytes.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
    if (text.startsWith("<svg", ignoreCase = true) || text.startsWith("<?xml", ignoreCase = true)) {
        if (text.contains("<svg", ignoreCase = true)) return decodeSvg(text)
    }
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

private fun decodeSvg(source: String): AssistantImageState {
    if (source.length > MAX_SVG_CHARS) {
        return AssistantImageState.Error("SVG is too large.")
    }
    if (UNSAFE_SVG.containsMatchIn(source)) {
        return AssistantImageState.Error("SVG contains unsupported content.")
    }
    val svg =
        runCatching { SVG.getFromString(source) }.getOrNull()
            ?: return AssistantImageState.Error("Invalid or unsupported SVG.")
    var width = svg.documentWidth
    var height = svg.documentHeight
    if (!width.isFinite() || width <= 0f) width = 800f
    if (!height.isFinite() || height <= 0f) height = 600f
    val scale = min(1f, MAX_IMAGE_DIMENSION / max(width, height))
    val pixelWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val pixelHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return runCatching {
        val picture = svg.renderToPicture(pixelWidth, pixelHeight)
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPicture(picture)
        AssistantImageState.Loaded(bitmap.asImageBitmap())
    }.getOrElse {
        AssistantImageState.Error("Could not render SVG.")
    }
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
