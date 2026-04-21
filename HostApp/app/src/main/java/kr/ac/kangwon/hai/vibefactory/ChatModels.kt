package kr.ac.kangwon.hai.vibefactory

enum class InputMode {
    NEW_GENERATE,
    CONTINUE_CLARIFICATION,
    REFINE_EXISTING,
    RETRY_FAILED,
    READ_ONLY
}

enum class MessageKind {
    USER,
    ASSISTANT,
    CONFIRMATION,
    BUILD_LOG,
    STATUS,
    LOG
}

data class TaskSummary(
    val taskId: String,
    val title: String,
    val appName: String?,
    val packageName: String?,
    val subtitle: String,
    val status: String,
    val updatedAt: String?,
    val hasApk: Boolean,
    val hasRuntimeError: Boolean = false
)

data class ChatMessage(
    val id: String,
    val kind: MessageKind,
    val title: String?,
    val body: String,
    val detail: String? = null,
    val createdAt: String? = null,
    val imagePreviewBase64: String? = null,
    val imagePreviewName: String? = null,
    val confirmAction: String? = null,
    val confirmTaskId: String? = null,
    val confirmPayload: String? = null
)

data class RuntimeErrorRecord(
    val packageName: String,
    val stackTrace: String,
    val summary: String,
    val awaitingUserConfirmation: Boolean = true
)

data class ChatScreenState(
    val taskList: List<TaskSummary> = emptyList(),
    val selectedTaskId: String? = null,
    val displayedAppName: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pollingTaskId: String? = null,
    val inputMode: InputMode = InputMode.NEW_GENERATE,
    val currentStatus: String = "대기 중",
    val statusDetail: String? = null,
    val canDownload: Boolean = false,
    val canInstall: Boolean = false
)
