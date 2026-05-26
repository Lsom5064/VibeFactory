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

data class PersistedArtifactState(
    val apkUrl: String? = null,
    val downloadedApkPath: String? = null
)

data class UserIdentity(
    val phoneNumber: String?
)
