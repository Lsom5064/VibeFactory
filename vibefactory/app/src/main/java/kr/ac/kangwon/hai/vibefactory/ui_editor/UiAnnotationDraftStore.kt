package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import com.google.gson.Gson
import kr.ac.kangwon.hai.vibefactory.SelectedAttachment
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class UiAnnotationDraftRecord(
    val taskId: String,
    val revisionLabel: String,
    val layoutName: String,
    val configuration: String,
    val baseXmlSha256: String,
    val originalXml: String,
    val annotationXml: String,
    val annotations: List<UiAnnotation>,
    val images: List<UiEditorImage> = emptyList(),
    val updatedAt: String,
    val serverDraftId: String? = null,
    val serverDraftVersion: Int? = null,
    val confirmed: Boolean = false,
    val pendingAddition: UiAnnotation? = null,
    val pendingAdditionEditing: Boolean = false
)

class UiAnnotationDraftStore(context: Context, private val gson: Gson) {
    private val root = File(context.filesDir, "ui_annotation_drafts")

    fun load(taskId: String, revisionLabel: String, layoutName: String, configuration: String): UiAnnotationDraftRecord? {
        val file = draftFile(taskId, revisionLabel, layoutName, configuration)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), UiAnnotationDraftRecord::class.java) }
            .getOrNull()
            ?.let { record ->
                record.copy(
                    annotations = record.annotations.orEmpty().map { annotation ->
                        annotation.copy(imageIds = annotation.imageIds.orEmpty())
                    },
                    images = record.images.orEmpty()
                )
            }
            ?.takeIf {
                it.taskId == taskId && it.revisionLabel == revisionLabel &&
                    it.layoutName == layoutName && it.configuration == configuration
            }
    }

    fun save(record: UiAnnotationDraftRecord) {
        val target = draftFile(record.taskId, record.revisionLabel, record.layoutName, record.configuration)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeText(gson.toJson(record), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    fun persistImage(
        taskId: String,
        revisionLabel: String,
        layoutName: String,
        annotationId: String,
        attachment: SelectedAttachment
    ): UiEditorImage? {
        val bytes = runCatching { Base64.getDecoder().decode(attachment.base64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val imageId = UUID.randomUUID().toString().replace("-", "")
        val resourceName = "vibe_annotation_image_${imageId.take(12)}"
        val directory = imageDirectory(taskId, revisionLabel, layoutName)
        directory.mkdirs()
        val target = File(directory, "$resourceName.jpg")
        target.writeBytes(bytes)
        return UiEditorImage(
            imageId = imageId,
            elementStableId = annotationId,
            displayName = attachment.displayName,
            mimeType = "image/jpeg",
            localPath = target.absolutePath,
            resourceName = resourceName,
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong()
        )
    }

    fun persistDownloadedImage(
        taskId: String,
        revisionLabel: String,
        layoutName: String,
        imageId: String,
        annotationId: String,
        displayName: String,
        resourceName: String,
        expectedSha256: String,
        serverWorkspacePath: String,
        bytes: ByteArray
    ): UiEditorImage? {
        if (bytes.isEmpty() || !sha256(bytes).equals(expectedSha256, ignoreCase = true)) return null
        val directory = imageDirectory(taskId, revisionLabel, layoutName)
        directory.mkdirs()
        val target = File(directory, "${safe(resourceName)}.jpg")
        target.writeBytes(bytes)
        return UiEditorImage(
            imageId = imageId,
            elementStableId = annotationId,
            displayName = displayName,
            mimeType = "image/jpeg",
            localPath = target.absolutePath,
            resourceName = resourceName,
            sha256 = expectedSha256,
            sizeBytes = bytes.size.toLong(),
            serverWorkspacePath = serverWorkspacePath
        )
    }

    /** Call on Dispatchers.IO: raster rendering, compression and file writes stay off the UI thread. */
    fun persistSketch(session: UiAnnotationSession, annotation: UiAnnotation, aspectRatio: Float): UiEditorImage {
        val spec = requireNotNull(annotation.addition)
        val ratio = aspectRatio.coerceIn(0.05f, 20f)
        val width = if (ratio >= 1f) 1024 else (1024 * ratio).toInt().coerceAtLeast(1)
        val height = if (ratio >= 1f) (1024 / ratio).toInt().coerceAtLeast(1) else 1024
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(spec.backgroundColor)
            UiSketchRenderer.draw(canvas, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), spec.strokes)
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val resource = "vibe_add_sketch_${id.take(12)}"
        val dir = imageDirectory(session.taskId, session.revisionLabel, session.layout.layout_name).apply { mkdirs() }
        val file = File(dir, "$resource.jpg").apply { writeBytes(bytes) }
        return UiEditorImage(id, annotation.annotationId, "추가 UI 스케치", "image/jpeg", file.absolutePath,
            resource, sha256(bytes), bytes.size.toLong())
    }

    fun deleteLocalImage(image: UiEditorImage) {
        runCatching { File(image.localPath).takeIf(File::isFile)?.delete() }
    }

    fun recordFor(session: UiAnnotationSession, confirmed: Boolean = false): UiAnnotationDraftRecord {
        val annotationXml = UiAnnotationXmlCodec.encode(
            taskId = session.taskId,
            revisionLabel = session.revisionLabel,
            layoutName = session.layout.layout_name,
            configuration = session.layout.configuration,
            baseXmlSha256 = session.baseXmlSha256,
            annotations = session.annotations,
            referenceCanvasWidthDp = session.referenceCanvasWidthDp,
            referenceCanvasHeightDp = session.referenceCanvasHeightDp,
            previewCanvasHeightDp = session.previewCanvasHeightDp
        )
        return UiAnnotationDraftRecord(
            taskId = session.taskId,
            revisionLabel = session.revisionLabel,
            layoutName = session.layout.layout_name,
            configuration = session.layout.configuration,
            baseXmlSha256 = session.baseXmlSha256,
            originalXml = session.originalXml,
            annotationXml = annotationXml,
            annotations = session.annotations.toList(),
            images = session.images.toList(),
            updatedAt = Instant.now().toString(),
            serverDraftId = session.serverDraftId,
            serverDraftVersion = session.serverDraftVersion,
            confirmed = confirmed,
            pendingAddition = session.pendingAddition,
            pendingAdditionEditing = session.pendingAdditionEditing
        )
    }

    private fun draftFile(taskId: String, revisionLabel: String, layoutName: String, configuration: String): File =
        File(root, "${safe(taskId)}/${safe(revisionLabel)}/${safe(layoutName)}/${safe(configuration)}.json")

    private fun imageDirectory(taskId: String, revisionLabel: String, layoutName: String): File =
        File(root, "${safe(taskId)}/${safe(revisionLabel)}/${safe(layoutName)}/images")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
}
