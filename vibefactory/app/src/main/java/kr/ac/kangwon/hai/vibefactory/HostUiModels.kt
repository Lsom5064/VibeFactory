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

data class PersistedArtifactState(
    val apkUrl: String? = null,
    val downloadedApkPath: String? = null
)

data class UserIdentity(
    val phoneNumber: String?
)
