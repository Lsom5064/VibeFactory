package kr.ac.kangwon.hai.vibefactory

import java.util.Locale

internal object TaskStatusPolicy {
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

    fun shouldPollConversation(status: String?): Boolean {
        val normalized = normalize(status)
        return normalized in activeBuildStatuses || normalized in awaitingInputStatuses
    }

    fun shouldMonitorBuildInBackground(status: String?): Boolean {
        val normalized = normalize(status)
        return normalized in activeBuildStatuses || normalized == "pending decision"
    }
}
