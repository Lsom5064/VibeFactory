package kr.ac.kangwon.hai.vibefactory

internal data class TimelineEventSnapshot(
    val eventId: String,
    val createdAt: String,
    val kind: String,
    val title: String,
    val body: String,
    val detail: String,
    val eventType: String,
    val confirmationAction: String?,
    val confirmationPayload: String?,
    val preparedPrompt: String?,
    val renderMode: String?,
    val apkUrl: String?,
    val apkPath: String?,
    val apkSizeBytes: Long?,
    val appName: String?,
    val packageName: String?,
    val imagePreviews: List<ChatImagePreview>
)

internal data class TimelineEventPage(
    val events: List<TimelineEventSnapshot>,
    val nextCursor: String?
)

internal data class ChatScrollSnapshot(
    val messageId: String,
    val topOffset: Int
)

internal data class ChatBottomScrollSnapshot(
    val bottomOffset: Int
)
