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
    val confirmed: Boolean = false
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
            annotations = session.annotations
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
            confirmed = confirmed
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
