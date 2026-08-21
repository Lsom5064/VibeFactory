package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import com.google.gson.Gson
import kr.ac.kangwon.hai.vibefactory.SelectedAttachment
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class UiEditorDraftRecord(
    val taskId: String,
    val revisionLabel: String,
    val layoutName: String,
    val configuration: String,
    val baseXmlSha256: String,
    val editedXml: String,
    val descriptions: Map<String, String>,
    val images: List<UiEditorImage>,
    val selectedElementId: String?,
    val status: String,
    val updatedAt: String,
    val serverDraftId: String? = null,
    val serverDraftVersion: Int? = null
)

class UiEditorDraftStore(context: Context, private val gson: Gson) {
    private val root = File(context.filesDir, "ui_editor_drafts")

    fun load(taskId: String, revisionLabel: String, layoutName: String, configuration: String): UiEditorDraftRecord? {
        val file = draftFile(taskId, revisionLabel, layoutName, configuration)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), UiEditorDraftRecord::class.java) }
            .getOrNull()
            ?.takeIf {
                it.taskId == taskId &&
                    it.revisionLabel == revisionLabel &&
                    it.layoutName == layoutName &&
                    it.configuration == configuration
            }
    }

    fun save(record: UiEditorDraftRecord) {
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
        elementStableId: String,
        attachment: SelectedAttachment
    ): UiEditorImage? {
        val bytes = runCatching { Base64.getDecoder().decode(attachment.base64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val imageId = UUID.randomUUID().toString().replace("-", "")
        val resourceName = "vibe_editor_image_${imageId.take(12)}"
        val directory = File(
            root,
            "${safe(taskId)}/${safe(revisionLabel)}/${safe(layoutName)}/images"
        )
        directory.mkdirs()
        val target = File(directory, "$resourceName.jpg")
        target.writeBytes(bytes)
        return UiEditorImage(
            imageId = imageId,
            elementStableId = elementStableId,
            displayName = attachment.displayName,
            mimeType = "image/jpeg",
            localPath = target.absolutePath,
            resourceName = resourceName,
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) },
            sizeBytes = bytes.size.toLong()
        )
    }

    fun persistDownloadedImage(
        taskId: String,
        revisionLabel: String,
        layoutName: String,
        imageId: String,
        elementStableId: String,
        displayName: String,
        resourceName: String,
        sha256: String,
        serverWorkspacePath: String,
        bytes: ByteArray
    ): UiEditorImage? {
        if (bytes.isEmpty()) return null
        val calculatedSha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        if (!calculatedSha.equals(sha256, ignoreCase = true)) return null
        val directory = File(
            root,
            "${safe(taskId)}/${safe(revisionLabel)}/${safe(layoutName)}/images"
        )
        directory.mkdirs()
        val target = File(directory, "${safe(resourceName)}.jpg")
        target.writeBytes(bytes)
        return UiEditorImage(
            imageId = imageId,
            elementStableId = elementStableId,
            displayName = displayName,
            mimeType = "image/jpeg",
            localPath = target.absolutePath,
            resourceName = resourceName,
            sha256 = calculatedSha,
            sizeBytes = bytes.size.toLong(),
            serverWorkspacePath = serverWorkspacePath
        )
    }

    fun recordFor(session: UiEditorSession, status: String = "draft"): UiEditorDraftRecord =
        UiEditorDraftRecord(
            taskId = session.taskId,
            revisionLabel = session.revisionLabel,
            layoutName = session.layout.layout_name,
            configuration = session.layout.configuration,
            baseXmlSha256 = session.baseXmlSha256,
            editedXml = session.document.xml(),
            descriptions = session.descriptions.toMap(),
            images = session.images.toList(),
            selectedElementId = session.selectedElementId,
            status = status,
            updatedAt = Instant.now().toString(),
            serverDraftId = session.serverDraftId,
            serverDraftVersion = session.serverDraftVersion
        )

    private fun draftFile(taskId: String, revisionLabel: String, layoutName: String, configuration: String): File =
        File(
            root,
            "${safe(taskId)}/${safe(revisionLabel)}/${safe(layoutName)}/${safe(configuration)}.json"
        )

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
}
