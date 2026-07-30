package kr.ac.kangwon.hai.vibefactory

internal object TaskProgressTimelinePolicy {
    const val INSTALLABLE_APK_READY_MESSAGE = "설치 가능한 앱 파일을 준비했어요"

    private val activeBuildStatuses = setOf("queued", "running", "processing", "building", "in progress", "working")
    private val terminalBuildStatuses = setOf("success", "failed", "error", "rejected", "timeout", "timed out", "ratelimited", "cancelled", "canceled")

    fun isActiveBuildStatus(status: String?): Boolean {
        return normalizeStatus(status) in activeBuildStatuses
    }

    fun isTerminalBuildStatus(status: String?): Boolean {
        return normalizeStatus(status) in terminalBuildStatuses
    }

    fun shouldRemoveLoadingMessage(status: String?, message: ChatMessage): Boolean {
        return isTerminalBuildStatus(status) && message.isLoading
    }

    fun shouldAlwaysShowStatusMessage(message: ChatMessage): Boolean {
        return message.id.startsWith("finished-build-status-")
    }

    fun buildProgressDetail(vararg lines: String?): String? {
        val cleaned = lines
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .flatMap { it.lineSequence() }
            .map(::stripTechnicalLabel)
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() && !isTechnicalPhaseValue(it) }
            .distinct()
            .toList()

        val naturalLines = cleaned
            .filterNot { line ->
                cleaned.any { other ->
                    other != line && other.contains(line) && line.length < other.length
                }
            }

        return naturalLines.firstOrNull()
    }

    fun terminalProgressMessage(status: String?, rawMessage: String?): String? {
        val normalized = normalizeStatus(status)
        if (normalized == "success") {
            return INSTALLABLE_APK_READY_MESSAGE
        }
        return buildProgressDetail(rawMessage)
    }

    fun stripLoadingDetailWhenLogsHidden(
        showLogs: Boolean,
        message: ChatMessage,
        isProcessingBody: (String) -> Boolean
    ): ChatMessage {
        if (showLogs || message.kind != MessageKind.STATUS || !isProcessingBody(message.body)) {
            return message
        }
        return message.copy(detail = null)
    }

    fun removeActiveLoadingMessages(timeline: MutableList<ChatMessage>): Boolean {
        return timeline.removeAll { message ->
            message.isLoading && message.kind == MessageKind.STATUS
        }
    }

    fun removeMatchingProgressMessages(
        timeline: MutableList<ChatMessage>,
        message: ChatMessage,
        progressKey: (String) -> String?
    ): Boolean {
        if (!message.isLoading || message.kind != MessageKind.STATUS) return false
        val incomingKey = progressKey(message.body)
        var removed = false
        if (incomingKey != null) {
            removed = timeline.removeAll { existing ->
                existing.kind == MessageKind.STATUS && progressKey(existing.body) == incomingKey
            }
        }
        return removed
    }

    fun moveLoadingMessagesToEnd(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { it.isLoading }) return messages
        return messages.filterNot { it.isLoading } + messages.filter { it.isLoading }
    }

    fun moveArtifactsToEndOfUserTurns(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { !it.artifactTaskId.isNullOrBlank() }) return messages
        val reordered = mutableListOf<ChatMessage>()
        val currentTurn = mutableListOf<ChatMessage>()
        var hasStartedUserTurn = false

        fun flushTurn(reorderArtifacts: Boolean) {
            if (currentTurn.isEmpty()) return
            if (reorderArtifacts) {
                reordered += currentTurn.filter { it.artifactTaskId.isNullOrBlank() }
                reordered += currentTurn.filter { !it.artifactTaskId.isNullOrBlank() }
            } else {
                reordered += currentTurn
            }
            currentTurn.clear()
        }

        messages.forEach { message ->
            if (message.kind == MessageKind.USER) {
                flushTurn(reorderArtifacts = hasStartedUserTurn)
                hasStartedUserTurn = true
            }
            currentTurn += message
        }
        flushTurn(reorderArtifacts = hasStartedUserTurn)
        return reordered
    }

    fun keepLatestDuplicateArtifacts(messages: List<ChatMessage>): List<ChatMessage> {
        val latestIndexByKey = messages
            .mapIndexedNotNull { index, message -> artifactDedupeKey(message)?.let { key -> key to index } }
            .toMap()
        if (latestIndexByKey.isEmpty()) return messages

        return messages.filterIndexed { index, message ->
            val key = artifactDedupeKey(message) ?: return@filterIndexed true
            latestIndexByKey[key] == index
        }
    }

    fun artifactDedupeKey(message: ChatMessage): String? {
        val taskId = message.artifactTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val revision = artifactRevisionKey(message)
        if (revision != null) return "$taskId\u0001revision\u0001$revision"

        val identity = message.artifactApkPath?.trim()?.takeIf { it.isNotBlank() }
            ?: message.artifactApkUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: message.body.trim().takeIf { it.isNotBlank() }
            ?: return null
        return "$taskId\u0001$identity"
    }

    private fun artifactRevisionKey(message: ChatMessage): String? {
        Regex("""(?:^|[/\\])rev_0*(\d+)(?:[/\\]|$)""")
            .find(message.artifactApkPath.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "v$it" }
        normalizedRevisionLabel(message.artifactRevisionLabel)?.let { return it }
        message.artifactBuildAttempt?.takeIf { it > 0 }?.let { return "v$it" }
        return null
    }

    private fun normalizedRevisionLabel(label: String?): String? {
        val raw = label?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.removePrefix("build-").toIntOrNull()?.takeIf { it > 0 }?.let { return "v$it" }
        Regex("""(?:^|[^0-9])v0*(\d+)(?:$|[^0-9])""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "v$it" }
        Regex("""(?:^|[/\\])rev_0*(\d+)(?:[/\\]|$)""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return "v$it" }
        return raw.lowercase()
    }

    private fun stripTechnicalLabel(value: String): String {
        return value
            .replace(Regex("(?m)^\\s*(단계|상태)\\s*:\\s*"), "")
            .trim()
    }

    private fun isTechnicalPhaseValue(value: String): Boolean {
        return value.lowercase() in setOf(
            "started",
            "succeeded",
            "failed",
            "running",
            "queued",
            "pending",
            "completed"
        )
    }

    private fun normalizeStatus(status: String?): String {
        return TaskStatusPolicy.normalize(status)
    }
}
