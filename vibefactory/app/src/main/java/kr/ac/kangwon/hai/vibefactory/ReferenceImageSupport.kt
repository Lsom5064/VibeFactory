package kr.ac.kangwon.hai.vibefactory

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.util.Base64

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
    fallbackVisibility: Int
) {
    val encoded = imageBase64?.trim().orEmpty()
    if (encoded.isBlank()) {
        imageView.setImageDrawable(null)
        imageView.visibility = fallbackVisibility
        return
    }

    runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.onSuccess { bitmap ->
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
        } else {
            imageView.setImageDrawable(null)
            imageView.visibility = fallbackVisibility
        }
    }.onFailure {
        imageView.setImageDrawable(null)
        imageView.visibility = fallbackVisibility
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
