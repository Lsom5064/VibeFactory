package kr.ac.kangwon.hai.vibefactory

data class ReferenceImageAttachment(
    val displayName: String,
    val base64: String
) {
    fun toChatPreview(): ChatImagePreview {
        return ChatImagePreview(displayName = displayName, base64 = base64)
    }
}

data class ChatImagePreview(
    val displayName: String,
    val base64: String
)

enum class SelectedAttachmentKind(
    val payloadType: String,
    val chipPrefix: String
) {
    IMAGE("image", "Image"),
    PDF("pdf", "PDF"),
    TEXT("text", "Text")
}

data class SelectedAttachment(
    val kind: SelectedAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val base64: String
) {
    fun toPayload(): AttachmentPayload {
        return AttachmentPayload(
            type = kind.payloadType,
            mime_type = mimeType,
            name = displayName,
            base64 = base64
        )
    }

    fun toChatImagePreview(): ChatImagePreview? {
        if (kind != SelectedAttachmentKind.IMAGE) return null
        return ChatImagePreview(displayName = displayName, base64 = base64)
    }

    fun chipLabel(): String = buildAttachmentChipLabel(kind, displayName)
}

fun buildAttachmentChipLabel(kind: SelectedAttachmentKind, displayName: String): String {
    val safeName = displayName.trim().ifBlank { "attachment" }
    return "[${kind.chipPrefix} #1] $safeName x"
}

fun List<SelectedAttachment>.toPayloads(): List<AttachmentPayload> {
    return map { it.toPayload() }
}

fun List<SelectedAttachment>.toChatImagePreview(): ChatImagePreview? {
    val imageAttachments = filter { it.kind == SelectedAttachmentKind.IMAGE }
    val first = imageAttachments.firstOrNull() ?: return null
    val displayName = if (imageAttachments.size == 1) {
        first.displayName
    } else {
        "${first.displayName} 외 ${imageAttachments.size - 1}장"
    }
    return ChatImagePreview(displayName = displayName, base64 = first.base64)
}

fun List<SelectedAttachment>.toChatImagePreviews(): List<ChatImagePreview> {
    return filter { it.kind == SelectedAttachmentKind.IMAGE }
        .map { ChatImagePreview(displayName = it.displayName, base64 = it.base64) }
}

fun buildAttachmentChipLabel(attachments: List<SelectedAttachment>): String {
    if (attachments.isEmpty()) return ""
    if (attachments.size == 1) return attachments.first().chipLabel()
    val imageCount = attachments.count { it.kind == SelectedAttachmentKind.IMAGE }
    val fileCount = attachments.size - imageCount
    val parts = buildList {
        if (imageCount > 0) add("이미지 ${imageCount}장")
        if (fileCount > 0) add("파일 ${fileCount}개")
    }
    return "${parts.joinToString(", ")} x"
}

data class PersistedArtifactState(
    val apkUrl: String? = null,
    val downloadedApkPath: String? = null
)

data class UserIdentity(
    val phoneNumber: String?
)
