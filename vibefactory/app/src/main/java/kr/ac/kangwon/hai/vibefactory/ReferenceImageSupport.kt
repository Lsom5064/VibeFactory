package kr.ac.kangwon.hai.vibefactory

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors

private const val MAX_STORED_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_STORED_IMAGE_DIMENSION = 1600

fun buildSelectedAttachment(
    contentResolver: ContentResolver,
    uri: Uri,
    requestedKind: SelectedAttachmentKind,
    maxOriginalImageBytes: Int,
    maxImagePayloadBytes: Int,
    maxPdfBytes: Int,
    maxTextBytes: Int
): SelectedAttachment? {
    val displayName = queryDisplayName(contentResolver, uri) ?: "attachment"
    val mimeType = contentResolver.getType(uri).orEmpty()
    val rawBytes = readUriBytes(
        contentResolver = contentResolver,
        uri = uri,
        maxBytes = when (requestedKind) {
            SelectedAttachmentKind.IMAGE -> maxOriginalImageBytes
            SelectedAttachmentKind.PDF -> maxPdfBytes
            SelectedAttachmentKind.TEXT -> maxTextBytes
        }
    ) ?: return null

    val payloadBytes = when (requestedKind) {
        SelectedAttachmentKind.IMAGE -> compressImagePayload(rawBytes, maxImagePayloadBytes) ?: return null
        SelectedAttachmentKind.PDF,
        SelectedAttachmentKind.TEXT -> rawBytes
    }

    return SelectedAttachment(
        kind = requestedKind,
        displayName = displayName,
        mimeType = mimeType.ifBlank { fallbackMimeType(requestedKind) },
        base64 = Base64.getEncoder().encodeToString(payloadBytes)
    )
}

private fun readUriBytes(contentResolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray? {
    contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
    return null
}

fun compressImagePayload(rawBytes: ByteArray, maxPayloadBytes: Int): ByteArray? {
    val decoded = decodeScaledBitmapBytes(rawBytes, MAX_STORED_IMAGE_DIMENSION) ?: return null
    val scaled = scaleBitmap(decoded, maxDimension = MAX_STORED_IMAGE_DIMENSION)
    val effectiveMaxPayloadBytes = minOf(maxPayloadBytes, MAX_STORED_IMAGE_BYTES)
    try {
        var quality = 88
        while (quality >= 44) {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= effectiveMaxPayloadBytes) return bytes
            quality -= 8
        }
        return null
    } finally {
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }
}

private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / largest.toFloat()
    val matrix = Matrix().apply { postScale(scale, scale) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

internal fun calculateBitmapSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    val largestDimension = maxOf(width, height)
    var sampleSize = 1
    while (largestDimension / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun decodeScaledBitmapBytes(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun fallbackMimeType(kind: SelectedAttachmentKind): String {
    return when (kind) {
        SelectedAttachmentKind.IMAGE -> "image/jpeg"
        SelectedAttachmentKind.PDF -> "application/pdf"
        SelectedAttachmentKind.TEXT -> "text/plain"
    }
}

fun bindInlineImagePreview(
    imageView: ImageView,
    imageBase64: String?,
    fallbackVisibility: Int,
    maxDimension: Int = 720,
    imageUrl: String? = null
) {
    val encoded = imageBase64?.trim().orEmpty()
    val remoteUrl = imageUrl?.trim().orEmpty()
    if (encoded.isBlank() && remoteUrl.isBlank()) {
        imageView.tag = null
        imageView.setImageDrawable(null)
        imageView.visibility = fallbackVisibility
        return
    }

    InlineImagePreviewLoader.load(
        imageView = imageView,
        encoded = encoded,
        imageUrl = remoteUrl,
        fallbackVisibility = fallbackVisibility,
        maxDimension = maxDimension.coerceAtLeast(1)
    )
}

fun compactImagePreviewForStorage(encoded: String, maxEncodedChars: Int): String {
    val normalized = encoded.trim()
    if (normalized.isBlank()) return ""
    val sourceBytes = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return ""
    if (!isCompleteImageBytes(sourceBytes)) return ""
    if (normalized.length <= maxEncodedChars) return normalized

    val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return ""
    val scaled = scaleBitmap(decoded, maxDimension = 720)
    try {
        var quality = 86
        while (quality >= 46) {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val compacted = Base64.getEncoder().encodeToString(output.toByteArray())
            if (compacted.length <= maxEncodedChars) return compacted
            quality -= 8
        }
        return ""
    } finally {
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }
}

private object InlineImagePreviewLoader {
    private const val MAX_REMOTE_IMAGE_BYTES = 16 * 1024 * 1024
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "vibefactory-image-decoder").apply { isDaemon = true }
    }
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(
        imageView: ImageView,
        encoded: String,
        imageUrl: String,
        fallbackVisibility: Int,
        maxDimension: Int
    ) {
        val cacheKey = buildCacheKey(encoded, imageUrl, maxDimension)
        imageView.tag = cacheKey
        bitmapCache.get(cacheKey)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            return
        }

        imageView.setImageDrawable(null)
        imageView.visibility = fallbackVisibility
        val target = WeakReference(imageView)

        decoder.execute {
            val bitmap = bitmapCache.get(cacheKey) ?: decodeScaledBitmap(
                encoded = encoded,
                imageUrl = imageUrl,
                maxDimension = maxDimension
            )
            if (bitmap != null) {
                bitmapCache.put(cacheKey, bitmap)
            }
            mainHandler.post {
                val targetView = target.get() ?: return@post
                if (targetView.tag != cacheKey) return@post
                if (bitmap != null) {
                    targetView.setImageBitmap(bitmap)
                    targetView.visibility = View.VISIBLE
                } else {
                    targetView.setImageDrawable(null)
                    targetView.visibility = fallbackVisibility
                }
            }
        }
    }

    private fun decodeScaledBitmap(
        encoded: String,
        imageUrl: String,
        maxDimension: Int
    ): Bitmap? {
        val localBytes = encoded
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val sourceBytes = when {
            localBytes != null && isCompleteImageBytes(localBytes) -> localBytes
            imageUrl.isNotBlank() -> downloadImageBytes(imageUrl)
            else -> localBytes
        } ?: return null
        return decodeScaledBitmapBytes(sourceBytes, maxDimension)
    }

    private fun downloadImageBytes(imageUrl: String): ByteArray? {
        val connection = runCatching {
            (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
            }
        }.getOrNull() ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_REMOTE_IMAGE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray().takeIf(::isCompleteImageBytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildCacheKey(encoded: String, imageUrl: String, maxDimension: Int): String {
        return buildString {
            append(encoded.length)
            append(':')
            append(encoded.take(48).hashCode())
            append(':')
            append(encoded.takeLast(48).hashCode())
            append(':')
            append(imageUrl.hashCode())
            append(':')
            append(maxDimension)
        }
    }

    private fun cacheSizeBytes(): Int {
        val available = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (available / 12).coerceIn(8 * 1024 * 1024, 32 * 1024 * 1024)
    }
}

private fun isCompleteImageBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val unsigned = { index: Int -> bytes[index].toInt() and 0xFF }
    return when {
        unsigned(0) == 0xFF && unsigned(1) == 0xD8 ->
            unsigned(bytes.lastIndex - 1) == 0xFF && unsigned(bytes.lastIndex) == 0xD9
        unsigned(0) == 0x89 &&
            bytes.copyOfRange(1, 4).contentEquals(byteArrayOf(0x50, 0x4E, 0x47)) ->
            bytes.takeLast(12).toByteArray().let { tail ->
                tail.size >= 8 &&
                    tail[4] == 0x49.toByte() &&
                    tail[5] == 0x45.toByte() &&
                    tail[6] == 0x4E.toByte() &&
                    tail[7] == 0x44.toByte()
            }
        bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray()) ->
            unsigned(bytes.lastIndex) == 0x3B
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> {
            val declaredSize = unsigned(4) or
                (unsigned(5) shl 8) or
                (unsigned(6) shl 16) or
                (unsigned(7) shl 24)
            declaredSize + 8 <= bytes.size
        }
        else -> true
    }
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return uri.lastPathSegment
}
