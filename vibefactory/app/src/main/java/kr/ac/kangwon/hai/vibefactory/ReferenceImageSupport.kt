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
import java.util.Base64
import java.util.concurrent.Executors

fun buildReferenceImageAttachment(
    contentResolver: ContentResolver,
    uri: Uri,
    maxBytes: Int
): ReferenceImageAttachment? {
    val displayName = queryDisplayName(contentResolver, uri) ?: "reference_image"
    contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                return null
            }
            output.write(buffer, 0, read)
        }
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
        return ReferenceImageAttachment(displayName = displayName, base64 = encoded)
    }
    return null
}

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
    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
    val scaled = scaleBitmap(decoded, maxDimension = 2048)
    try {
        var quality = 92
        while (quality >= 50) {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= maxPayloadBytes) return bytes
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
    maxDimension: Int = 720
) {
    val encoded = imageBase64?.trim().orEmpty()
    if (encoded.isBlank()) {
        imageView.tag = null
        imageView.setImageDrawable(null)
        imageView.visibility = fallbackVisibility
        return
    }

    InlineImagePreviewLoader.load(
        imageView = imageView,
        encoded = encoded,
        fallbackVisibility = fallbackVisibility,
        maxDimension = maxDimension.coerceAtLeast(1)
    )
}

private object InlineImagePreviewLoader {
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
        fallbackVisibility: Int,
        maxDimension: Int
    ) {
        val cacheKey = buildCacheKey(encoded, maxDimension)
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
            val bitmap = bitmapCache.get(cacheKey) ?: decodeScaledBitmap(encoded, maxDimension)
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

    private fun decodeScaledBitmap(encoded: String, maxDimension: Int): Bitmap? {
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun buildCacheKey(encoded: String, maxDimension: Int): String {
        return buildString {
            append(encoded.length)
            append(':')
            append(encoded.take(48).hashCode())
            append(':')
            append(encoded.takeLast(48).hashCode())
            append(':')
            append(maxDimension)
        }
    }

    private fun cacheSizeBytes(): Int {
        val available = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (available / 12).coerceIn(8 * 1024 * 1024, 32 * 1024 * 1024)
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
