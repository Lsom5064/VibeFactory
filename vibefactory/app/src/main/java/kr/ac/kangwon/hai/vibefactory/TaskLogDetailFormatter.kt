package kr.ac.kangwon.hai.vibefactory

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object TaskLogDetailFormatter {
    private const val UNKNOWN_TIME = ""
    private const val INTERNAL_LINK_MARKER = "\u0000"
    private val gson = Gson()
    private val displayTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
    private val hiddenBodies = setOf(
        "APK build completed",
        "APK 빌드가 완료되었어요.",
        "APK 생성이 완료되었어요.",
        "앱 생성이 완료되었어요.",
        "앱 생성이 완료되었어요",
        "앱 생성 작업을 진행하고 있습니다.",
        "앱 생성 작업을 진행하고 있습니다",
        "앱을 생성하고 있어요.",
        "앱을 생성하고 있어요",
        "후속 요청 전송",
        "task created",
        "running"
    )
    private val noisyBodies = setOf(
        "작업 진행",
        "작업 완료",
        "Running",
        "Started",
        "Queued",
        "Pending",
        "Succeeded",
        "Completed"
    )
    private val developerOnlyEventTypes = setOf(
        "command",
        "command_execution",
        "command_output",
        "file_change",
        "tool_call",
        "tool_output"
    )
    private val developerOnlyLabels = setOf("명령", "파일")
    private val markdownLinkRegex = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val internalLinkMarkerRegex = Regex(
        Regex.escape(INTERNAL_LINK_MARKER) + """(?:에서|으로|로|을|를|에|의)?"""
    )
    private val internalReferenceListRegex = Regex(
        """관련\s*(?:위치|경로|파일)(?:은|는|이|가)?\s*""" +
            Regex.escape(INTERNAL_LINK_MARKER) +
            """(?:\s*(?:[,，;·]|및|와|과)\s*""" +
            Regex.escape(INTERNAL_LINK_MARKER) +
            """)*\s*(?:입니다|이에요|예요)?[.!?]?"""
    )
    private val internalFileReferenceRegex = Regex(
        """(?i)(?:^|[/\\])(?:project|logs|workspaces?|\.codex_result)(?:[/\\]|$)|""" +
            """(?:^|[/\\])(?:Users|home|srv|opt|var|tmp|private|volume\d*)(?:[/\\]|$)|""" +
            """\.(?:dart|kt|java|xml|gradle|json|ya?ml|py)(?::\d+)?$"""
    )
    private val developerDetailMarkers = listOf(
        "flutter pub get",
        "flutter analyze",
        "no issues found",
        ".codex_result",
        "task_result.json",
        "logs/build.log",
        "결과 계약 파일"
    )

    fun buildPayload(
        taskId: String,
        summary: TaskSummary?,
        currentStatus: String,
        displayedAppName: String?,
        messages: List<ChatMessage>,
        rawLogContents: List<String>,
        formatTimestamp: (String?) -> String?
    ): TaskLogDetailPayload {
        val appName = summary?.title?.trim()?.takeIf { it.isNotBlank() }
            ?: displayedAppName?.trim()?.takeIf { it.isNotBlank() }
            ?: "앱"
        val rawStatus = summary?.status?.trim()?.takeIf { it.isNotBlank() }
            ?: currentStatus.trim().takeIf { it.isNotBlank() }
            ?: "상태 없음"
        val status = if (summary?.hasRuntimeError == true) {
            "실행 오류 확인 필요"
        } else {
            rawStatus
        }
        val normalizedTaskId = taskId.trim()
        val visibleMessages = messages
            .filterNot { it.kind == MessageKind.BUILD_LOG }
            .mapNotNull { messageToItem(it, formatTimestamp) }
        val latestFirstMessages = visibleMessages.asReversed()
        val progressItems = latestFirstMessages
            .filter { it.label in setOf("상태", "빌드", "준비", "점검", "테스트", "작업") }
            .filterNot { isNoisyBody(it.body) }
            .distinctItems()
        val agentItems = latestFirstMessages
            .filter { it.label == "작업 메모" }
            .distinctItems()
            .ifEmpty { agentItemsFromRawLogs(rawLogContents) }

        val lastUpdated = visibleMessages
            .mapNotNull { it.time.takeIf { time -> time.isNotBlank() } }
            .lastOrNull()
            ?: "시간 정보 없음"

        return TaskLogDetailPayload(
            title = "$appName · 작업 로그",
            appName = appName,
            taskId = normalizedTaskId,
            status = status,
            statusTone = statusTone(status),
            lastUpdated = lastUpdated,
            progressItems = progressItems,
            agentItems = agentItems,
            apkAction = latestApkAction(messages, summary, taskId, appName)
        )
    }

    fun agentItemsFromStatus(response: StatusResponse): List<TaskLogDetailItem> {
        val structuredContents = response.raw_log_sections
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                element
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { firstString(it, "content") }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
        val rawLogContents = structuredContents.ifEmpty {
            listOfNotNull(
                response.full_log?.trim()?.takeIf { it.isNotBlank() }
                    ?: response.log?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        return agentItemsFromRawLogs(rawLogContents)
    }

    internal fun agentItemsFromRawLogs(rawLogContents: List<String>): List<TaskLogDetailItem> {
        return extractAgentMessages(rawLogContents)
            .asReversed()
            .map { TaskLogDetailItem(UNKNOWN_TIME, "작업 메모", it) }
    }

    private fun messageToItem(
        message: ChatMessage,
        formatTimestamp: (String?) -> String?
    ): TaskLogDetailItem? {
        if (isArtifactMessage(message)) return null
        val eventType = message.eventType?.trim()?.lowercase().orEmpty()
        if (eventType in developerOnlyEventTypes) return null
        val body = sanitizeLogText(message.body)
        if (body.isBlank() || isHiddenBody(body)) return null
        val label = labelFor(message)
        if (label in developerOnlyLabels) return null
        val time = formatDisplayTime(message.createdAt, formatTimestamp)
        val detail = sanitizeLogText(message.detail).takeIf {
            it.isNotBlank() && normalizeText(it) != normalizeText(body)
        }
        return TaskLogDetailItem(time, label, body, detail)
    }

    private fun latestApkAction(
        messages: List<ChatMessage>,
        summary: TaskSummary?,
        taskId: String,
        appName: String
    ): TaskLogApkAction? {
        val artifact = messages.asReversed().firstOrNull(::isArtifactMessage)
        val resolvedTaskId = artifact?.artifactTaskId?.trim()?.takeIf { it.isNotBlank() }
            ?: taskId.trim().takeIf { it.isNotBlank() }
        val apkUrl = artifact?.artifactApkUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: resolvedTaskId
                ?.takeIf { summary?.hasApk == true || artifact != null }
                ?.let { "${HostAppConfig.BASE_URL}/download/$it" }
        val downloadedPath = artifact?.artifactDownloadedPath?.trim()?.takeIf { it.isNotBlank() }
        if (apkUrl.isNullOrBlank() && downloadedPath.isNullOrBlank()) return null

        val title = artifact?.body?.trim()?.takeIf { it.isNotBlank() } ?: "$appName APK"
        val meta = artifact?.detail?.trim()?.takeIf { it.isNotBlank() } ?: "APK 파일"
        return TaskLogApkAction(
            taskId = resolvedTaskId.orEmpty(),
            title = title,
            meta = meta,
            apkUrl = apkUrl,
            artifactPath = artifact?.artifactApkPath?.trim()?.takeIf { it.isNotBlank() },
            downloadedPath = downloadedPath
        )
    }

    private fun isArtifactMessage(message: ChatMessage): Boolean {
        return !message.artifactTaskId.isNullOrBlank() ||
            !message.artifactApkUrl.isNullOrBlank() ||
            !message.artifactApkPath.isNullOrBlank() ||
            !message.artifactDownloadedPath.isNullOrBlank()
    }

    private fun labelFor(message: ChatMessage): String {
        val eventType = message.eventType?.trim()?.lowercase().orEmpty()
        if (eventType == "agent_message") return "작업 메모"
        if (eventType == "file_change") return "파일"
        return when {
            !message.title.isNullOrBlank() -> message.title.trim()
            message.kind == MessageKind.USER -> "나"
            message.kind == MessageKind.ASSISTANT -> "AI"
            message.kind == MessageKind.CONFIRMATION -> "확인"
            message.kind == MessageKind.STATUS -> "상태"
            else -> "로그"
        }
    }

    private fun formatDisplayTime(value: String?, formatTimestamp: (String?) -> String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return UNKNOWN_TIME
        val formatted = formatTimestamp(raw)?.trim().orEmpty()
        if (formatted.isNotBlank() && !looksLikeRawTimestamp(formatted)) return formatted
        return parseTimestamp(raw)?.let { displayTimeFormatter.format(it) } ?: UNKNOWN_TIME
    }

    private fun parseTimestamp(value: String): java.time.ZonedDateTime? {
        return runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault())
            }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault())
            }.getOrNull()
    }

    private fun looksLikeRawTimestamp(value: String): Boolean {
        return (value.contains("T") && (value.contains("+") || value.endsWith("Z"))) ||
            Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""").containsMatchIn(value)
    }

    private fun statusTone(status: String): String {
        val normalized = status.lowercase()
        return when {
            normalized.contains("실패") || normalized.contains("failed") || normalized.contains("error") -> "error"
            normalized.contains("완료") || normalized.contains("success") -> "success"
            else -> "running"
        }
    }

    private fun extractAgentMessages(rawLogContents: List<String>): List<String> {
        val messages = linkedSetOf<String>()
        rawLogContents.forEach { content ->
            content.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") && it.endsWith("}") }
                .forEach { line ->
                    val parsed = runCatching { gson.fromJson(line, JsonObject::class.java) }.getOrNull()
                        ?: return@forEach
                    if (firstString(parsed, "type")?.trim() != "item.completed") return@forEach
                    val item = parsed.get("item")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@forEach
                    if (firstString(item, "type")?.trim() != "agent_message") return@forEach
                    firstTextCandidate(item)
                        ?.let(::sanitizeLogText)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { messages += it }
                }
        }
        return messages.toList()
    }

    private fun firstTextCandidate(item: JsonObject): String? {
        val payload = item.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
        return listOfNotNull(
            firstString(item, "text"),
            firstString(item, "message"),
            firstString(item, "content"),
            payload?.let { firstString(it, "text") },
            payload?.let { firstString(it, "message") },
            payload?.let { firstString(it, "content") }
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonNull) continue
            if (value.isJsonPrimitive) return value.asString
        }
        return null
    }

    private fun List<TaskLogDetailItem>.distinctItems(): List<TaskLogDetailItem> {
        val seen = linkedSetOf<String>()
        return filter { seen.add("${it.label}\u0001${normalizeText(it.body)}\u0001${normalizeText(it.detail)}") }
    }

    private fun isHiddenBody(value: String): Boolean {
        val normalized = normalizeText(value)
        return hiddenBodies.any { it.equals(normalized, ignoreCase = true) }
    }

    private fun isNoisyBody(value: String): Boolean {
        val normalized = normalizeText(value)
        return noisyBodies.any { it.equals(normalized, ignoreCase = true) }
    }

    private fun normalizeText(value: String?): String {
        return value.orEmpty().replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeLogText(value: String?): String {
        return value.orEmpty()
            .lineSequence()
            .mapNotNull { rawLine ->
                val line = stripInternalMarkdownLinks(rawLine)
                    .replace(Regex("^\\s*(단계|상태)\\s*:\\s*"), "")
                    .trim()
                if (isDeveloperOnlyLogLine(line)) {
                    null
                } else {
                    line
                        .replace(
                            Regex("""(?i)(?:/Users|/home|/srv|/opt|/var|/tmp|/private|/volume\d*)/[^\s"'`]+"""),
                            "앱 내부 파일"
                        )
                        .replace(
                            Regex("""(?i)[A-Z]:\\[^\s"'`]+"""),
                            "앱 내부 파일"
                        )
                        .replace(
                            Regex("""(?i)\b(?:workspace|workspaces|user_phone_[^/\s]+)(?:[/\\][^\s"'`]+)*"""),
                            "사용자 작업 공간"
                        )
                        .replace(
                            Regex("""(?i)`?[\w./\\-]+\.(?:dart|kt|java|xml|gradle|json|ya?ml|py)(?::\d+)?`?"""),
                            "앱 내부 파일"
                        )
                        .replace(
                            Regex("""(?<!\d)01[016789](?:[- ]?\d){7,8}(?!\d)"""),
                            "사용자 정보"
                        )
                        .replace(Regex("""(?i)\bpackage_name\b"""), "앱 식별 정보")
                        .replace(Regex("""(?i)\btask_id\b"""), "작업 정보")
                        .replace(
                            Regex("""(?<![\w가-힣])_?[A-Za-z]+(?:[A-Z][A-Za-z0-9]*|_[A-Za-z0-9]+)[A-Za-z0-9_]*(?![\w가-힣])"""),
                            "앱 내부 설정"
                        )
                        .replace(Regex("""\s+([,.!?])"""), "$1")
                        .replace(Regex("""[ \t]{2,}"""), " ")
                        .trim()
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun stripInternalMarkdownLinks(value: String): String {
        val marked = markdownLinkRegex.replace(value) { match ->
            val label = match.groupValues[1].trim()
            val target = match.groupValues[2].trim()
            when {
                !looksLikeInternalFileReference(target) -> label
                looksLikeInternalFileReference(label) -> INTERNAL_LINK_MARKER
                else -> label
            }
        }
        return marked
            .replace(internalReferenceListRegex, "")
            .replace(internalLinkMarkerRegex, "")
            .replace(Regex("""\s*[,，;·]+\s*(?:입니다|이에요|예요)?[.!?]?\s*$"""), "")
            .trimEnd()
    }

    private fun looksLikeInternalFileReference(value: String): Boolean {
        return internalFileReferenceRegex.containsMatchIn(value.trim().trim('`'))
    }

    private fun isDeveloperOnlyLogLine(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = value.trim().lowercase()
        if (normalized.startsWith("{") && normalized.endsWith("}")) return true
        if (developerDetailMarkers.any(normalized::contains)) return true
        return listOf(
            "$ ",
            "command:",
            "명령:",
            "cd ",
            "flutter ",
            "dart ",
            "gradle ",
            "./gradlew",
            "python ",
            "python3 ",
            "codex ",
            "bash ",
            "sh "
        ).any(normalized::startsWith)
    }
}
