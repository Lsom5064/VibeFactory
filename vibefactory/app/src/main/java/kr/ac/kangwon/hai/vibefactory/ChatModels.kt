package kr.ac.kangwon.hai.vibefactory

enum class InputMode {
    NEW_GENERATE,
    CHAT,
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
    val eventType: String? = null,
    val imagePreviewBase64: String? = null,
    val imagePreviewName: String? = null,
    val confirmAction: String? = null,
    val confirmTaskId: String? = null,
    val confirmPayload: String? = null,
    val artifactTaskId: String? = null,
    val artifactApkUrl: String? = null,
    val artifactApkPath: String? = null,
    val artifactDownloadedPath: String? = null,
    val artifactRevisionLabel: String? = null,
    val artifactBuildAttempt: Int? = null,
    val artifactCanDownload: Boolean = false,
    val artifactCanInstall: Boolean = false,
    val artifactDownloading: Boolean = false,
    val isLoading: Boolean = false
)

data class RuntimeErrorRecord(
    val packageName: String,
    val stackTrace: String,
    val summary: String,
    val errorMessage: String? = null,
    val reportKind: String? = null,
    val awaitingUserConfirmation: Boolean = true,
    val serverReported: Boolean = false
)

object RuntimeErrorStoragePolicy {
    private const val MAX_STACK_TRACE_CHARS = 40_000
    private const val TRUNCATED_SUFFIX = "\n\n[긴 오류 로그는 앱 성능을 위해 일부만 저장했어요.]"

    fun compactStackTrace(stackTrace: String): String {
        val normalized = stackTrace.trim()
        if (normalized.length <= MAX_STACK_TRACE_CHARS) return normalized
        val keepChars = (MAX_STACK_TRACE_CHARS - TRUNCATED_SUFFIX.length)
            .coerceAtLeast(MAX_STACK_TRACE_CHARS / 2)
        return normalized.take(keepChars).trimEnd() + TRUNCATED_SUFFIX
    }
}

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
