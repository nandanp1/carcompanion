package com.carcompanion.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.carcompanion.app.model.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.CRC32
import javax.net.ssl.HttpsURLConnection

object ArtworkEncoder {
    const val MAX_JPEG_BYTES = 32_768
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024

    suspend fun encode(context: Context, exposedBitmap: Bitmap?, exposedUri: String?, quality: Int): Artwork? =
        withContext(Dispatchers.IO) {
            var ownedBitmap: Bitmap? = null
            var softwareCopy: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                val original = exposedBitmap ?: load(context, exposedUri)?.also { ownedBitmap = it } ?: return@withContext null
                if (original.isRecycled || original.width <= 0 || original.height <= 0) return@withContext null
                // Metadata can contain GPU-backed bitmaps, which a software Canvas cannot draw.
                val source = if (original.config == Bitmap.Config.HARDWARE)
                    original.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it } ?: return@withContext null
                else original
                for (size in listOf(240, 200, 160, 120)) {
                    currentCoroutineContext().ensureActive()
                    // Fill any alpha with black, crop centered, and send opaque baseline JPEG.
                    val resized = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(resized)
                        canvas.drawColor(Color.BLACK)
                        val cropSize = minOf(source.width, source.height)
                        val left = (source.width - cropSize) / 2
                        val top = (source.height - cropSize) / 2
                        canvas.drawBitmap(source, Rect(left, top, left + cropSize, top + cropSize), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
                        var attemptQuality = quality.coerceIn(25, 95)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val stream = ByteArrayOutputStream()
                            if (!resized.compress(Bitmap.CompressFormat.JPEG, attemptQuality, stream)) return@withContext null
                            currentCoroutineContext().ensureActive()
                            val jpeg = stream.toByteArray()
                            if (jpeg.size <= MAX_JPEG_BYTES) return@withContext Artwork(CRC32().apply { update(jpeg) }.value, jpeg)
                            if (attemptQuality <= 25) break
                            attemptQuality = (attemptQuality - 10).coerceAtLeast(25)
                        }
                    } finally { resized.recycle() }
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("CarCompanionMedia", "Artwork preparation failed: ${failure.javaClass.simpleName}")
                null // Artwork is optional; inaccessible content URIs do not block metadata.
            } finally { softwareCopy?.recycle(); ownedBitmap?.recycle() }
        }

    private suspend fun load(context: Context, uriText: String?): Bitmap? {
        if (uriText.isNullOrBlank()) return null
        val uri = Uri.parse(uriText)
        val bytes = when (uri.scheme) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
            "https" -> {
                val connection = URL(uriText).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode != 200) null else connection.inputStream.use { readBounded(it) }
                } finally { connection.disconnect() }
            }
            else -> null
        } ?: return null
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 480) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private suspend fun readBounded(stream: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > MAX_SOURCE_BYTES) return null
            output.write(buffer, 0, count)
        }
    }
}
