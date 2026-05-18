package kr.ac.kangwon.hai.vibefactory

import android.content.ContentResolver
import android.graphics.BitmapFactory
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
