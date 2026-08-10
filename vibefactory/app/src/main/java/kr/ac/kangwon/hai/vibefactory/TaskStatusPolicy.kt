package kr.ac.kangwon.hai.vibefactory

import java.util.Locale

internal object TaskStatusPolicy {
    data class Evaluation(
        val normalizedStatus: String,
        val isSuccess: Boolean,
        val isCancelled: Boolean,
        val isClarifying: Boolean,
        val isRetryable: Boolean,
        val isPolling: Boolean,
        val isResponseError: Boolean,
        val progressMode: String?
    )

    private val cancelledStatuses = setOf("cancelled", "canceled")
    private val awaitingInputStatuses = setOf(
        "pending decision",
        "clarification needed",
        "clarification required",
        "clarifying"
    )
    private val clarificationStatuses = awaitingInputStatuses + "rejected"
    private val retryableStatuses = setOf("failed", "error")
    private val responseErrorStatuses = setOf("not found", "device mismatch", "invalid state")
    private val activeBuildStatuses = setOf(
        "readytobuild",
        "ready to build",
        "queued",
        "building",
        "processing",
        "running",
        "in progress",
        "working",
        "reviewing",
        "repairing"
    )

    fun normalize(status: String?): String {
        return status.orEmpty()
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isSuccess(status: String?): Boolean = normalize(status) == "success"

    fun isCancelled(status: String?): Boolean = normalize(status) in cancelledStatuses

    fun needsClarification(status: String?): Boolean = normalize(status) in clarificationStatuses

    fun isRetryableFailure(status: String?): Boolean = normalize(status) in retryableStatuses

    fun isResponseError(status: String?): Boolean = normalize(status) in responseErrorStatuses

    fun evaluate(response: StatusResponse): Evaluation {
        val normalizedStatus = normalize(response.status)
        val isClarifying = response.requires_user_input == true ||
            response.pending_decision_reason?.trim()?.lowercase() == "clarification" ||
            needsClarification(normalizedStatus)
        val isRetryable = response.retry_allowed
            ?: response.allowed_next_actions?.any { it.equals("retry", ignoreCase = true) }
            ?: isRetryableFailure(normalizedStatus)
        val isPolling = shouldPollConversation(normalizedStatus) &&
            (!isClarifying || isWebResearchInProgress(response))
        return Evaluation(
            normalizedStatus = normalizedStatus,
            isSuccess = isSuccess(normalizedStatus),
            isCancelled = isCancelled(normalizedStatus),
            isClarifying = isClarifying,
            isRetryable = isRetryable,
            isPolling = isPolling,
            isResponseError = isResponseError(normalizedStatus),
            progressMode = response.progress_mode?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    fun shouldPollConversation(status: String?): Boolean {
        val normalized = normalize(status)
        return normalized in activeBuildStatuses || normalized in awaitingInputStatuses
    }

    fun shouldMonitorBuildInBackground(status: String?): Boolean {
        val normalized = normalize(status)
        return normalized in activeBuildStatuses || normalized == "pending decision"
    }

    fun isWebResearchInProgress(response: StatusResponse): Boolean {
        val statusKey = normalize(response.status)
        if (!shouldPollConversation(statusKey)) return false
        if (statusKey in setOf("reviewing", "repairing")) return false

        val progressMode = response.progress_mode?.trim()?.lowercase().orEmpty()
        if (progressMode in setOf("refine", "retry", "repair", "runtime_repair")) return false
        if (progressMode in setOf("web_research", "research_then_build", "api_research")) return true

        val currentProgressText = listOf(
            response.status_display_text.orEmpty(),
            response.status_message.orEmpty(),
            response.latest_log.orEmpty()
        ).joinToString("\n").lowercase()
        return webResearchMarkers.any(currentProgressText::contains)
    }

    private val webResearchMarkers = listOf(
        "외부 정보 탐색",
        "웹 검색",
        "웹검색",
        "공개 api",
        "api 우선 탐색",
        "대표 api",
        "대표 웹 페이지",
        "웹 페이지 확보",
        "웹 데이터 구조 분석",
        "외부 정보 품질"
    )
}
