package kr.ac.kangwon.hai.vibefactory

internal object ChatTimelineVisibility {
    private val serverProgressTitles = setOf("빌드", "파일", "명령", "준비", "점검", "테스트", "작업")
    private val engineAssistantTitles = setOf("작업 엔진", "작업 엔진 메시지")

    fun shouldShowMainChatMessage(
        message: ChatMessage,
        normalizedBody: String,
        visibleStatusBodies: Set<String>,
        showProgressTimeline: Boolean = false
    ): Boolean {
        if (message.isLoading) return true
        if (!message.artifactTaskId.isNullOrBlank()) return true

        return when (message.kind) {
            MessageKind.USER,
            MessageKind.CONFIRMATION -> true
            MessageKind.ASSISTANT -> !isEngineProgressAssistantMessage(message)
            MessageKind.STATUS -> shouldShowStatusMessage(
                message = message,
                normalizedBody = normalizedBody,
                visibleStatusBodies = visibleStatusBodies,
                showProgressTimeline = showProgressTimeline
            )
            MessageKind.LOG,
            MessageKind.BUILD_LOG -> false
        }
    }

    fun shouldShowStatusMessage(
        message: ChatMessage,
        normalizedBody: String,
        visibleStatusBodies: Set<String>,
        showProgressTimeline: Boolean = true
    ): Boolean {
        if (message.isLoading) return true
        if (!message.artifactTaskId.isNullOrBlank()) return true
        if (normalizedBody.isBlank()) return false
        if (isServerProgressMessage(message) || isServerProgressBody(normalizedBody)) {
            return showProgressTimeline
        }

        return visibleStatusBodies.any { normalizeBody(it) == normalizedBody }
    }

    private fun isServerProgressMessage(message: ChatMessage): Boolean {
        return (message.id.startsWith("timeline-") || message.id.startsWith("current-build-log-")) &&
            message.title in serverProgressTitles
    }

    private fun isEngineProgressAssistantMessage(message: ChatMessage): Boolean {
        val eventType = message.eventType?.trim()?.lowercase()
        if (eventType == "agent_message") return true
        if (eventType == "assistant_message") return false
        if (message.id.startsWith("planner-")) return true
        if (message.id.startsWith("timeline-") && message.title in engineAssistantTitles) return true
        if (message.title?.trim() in engineAssistantTitles && isOperationalAssistantBody(message.body)) return true

        return false
    }

    private fun isOperationalAssistantBody(body: String): Boolean {
        val normalized = normalizeBody(body)
        return normalized.contains("`") ||
            normalized.contains("Flutter") ||
            normalized.contains("Gradle") ||
            normalized.contains("APK") ||
            normalized.contains("pubspec") ||
            normalized.contains("SharedPreferences") ||
            normalized.contains("JSON") ||
            normalized.contains("symlink") ||
            normalized.contains("테스트") ||
            normalized.contains("빌드") ||
            normalized.contains("로그") ||
            normalized.contains("실행") ||
            normalized.contains("확인") ||
            normalized.contains("준비") ||
            normalized.contains("진행") ||
            normalized.contains("정적 분석") ||
            normalized.contains("의존성") ||
            normalized.contains("파일") ||
            normalized.contains("코드 수정") ||
            normalized.contains("작업 범위") ||
            normalized.contains("점검")
    }

    private fun isServerProgressBody(body: String): Boolean {
        return body.contains("준비하고 있어요") ||
            body.contains("시작했어요") ||
            body.contains("분석하고 있어요") ||
            body.contains("빌드하고 있어요") ||
            body.contains("설치 가능한") ||
            body.startsWith("파일 수정") ||
            body.startsWith("명령 ") ||
            body.contains("패키지") ||
            body.contains("점검") ||
            body.contains("설치 파일") ||
            body.contains("앱 파일")
    }

    fun normalizeBody(value: String): String {
        return value.trim().trimEnd('.')
    }
}
