package io.nekohasekai.sfa.conductor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import io.nekohasekai.sfa.agent.AgentImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Imports one gallery image into the private Conductor image store: bounds
 * check, EXIF rotation, downscale, and JPEG compression. The resulting image is
 * what both the main-chat request and the vision tool can use without sending a
 * 10 MB phone photo.
 */
object ConductorImageImporter {
    private const val MAX_SOURCE_BYTES = 25 * 1024 * 1024
    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 88
    private const val MAX_OUTPUT_BYTES = 3 * 1024 * 1024

    suspend fun import(
        context: Context,
        uri: Uri,
        store: ConductorImageStore,
    ): AgentImageRef =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?: "image.jpg"
            val source =
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_SOURCE_BYTES) {
                            throw IOException("Image is larger than ${MAX_SOURCE_BYTES / (1024 * 1024)} MB.")
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                } ?: throw IOException("Could not open the selected image.")

            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("The selected file is not a readable image.")
                }
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION * 2) {
                    sample *= 2
                }
                val decoded =
                    BitmapFactory.decodeByteArray(
                        source,
                        0,
                        source.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: throw IOException("Could not decode the selected image.")

                var bitmap = applyExifOrientation(decoded, source)
                if (bitmap !== decoded) decoded.recycle()
                val scale =
                    minOf(
                        1f,
                        MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height),
                    )
                if (scale < 1f) {
                    val scaled =
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                }

                val flattened = flattenForJpeg(bitmap)
                if (flattened !== bitmap) bitmap.recycle()
                val output = compressBounded(flattened)
                val ref =
                    store.save(
                        bytes = output,
                        mimeType = "image/jpeg",
                        name = name,
                        width = flattened.width,
                        height = flattened.height,
                    )
                flattened.recycle()
                ref
            } catch (e: OutOfMemoryError) {
                throw IOException("The selected image is too large to process.", e)
            }
        }

    private fun applyExifOrientation(
        bitmap: Bitmap,
        source: ByteArray,
    ): Bitmap {
        val orientation =
            runCatching {
                ExifInterface(source.inputStream()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flattenForJpeg(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flattened
    }

    private fun compressBounded(bitmap: Bitmap): ByteArray {
        var quality = JPEG_QUALITY
        while (true) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_OUTPUT_BYTES || quality <= 55) return bytes
            quality -= 10
        }
    }
}
