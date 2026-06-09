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
        val status = summary?.status?.trim()?.takeIf { it.isNotBlank() }
            ?: currentStatus.trim().takeIf { it.isNotBlank() }
            ?: "상태 없음"
        val taskIdShort = taskId.trim().take(8)
        val visibleMessages = messages
            .filterNot { it.kind == MessageKind.BUILD_LOG }
            .mapNotNull { messageToItem(it, formatTimestamp) }
        val latestFirstMessages = visibleMessages.asReversed()
        val progressItems = latestFirstMessages
            .filter { it.label in setOf("상태", "빌드", "준비", "점검", "테스트", "작업", "명령") }
            .filterNot { isNoisyBody(it.body) }
            .distinctItems()
        val agentItems = latestFirstMessages
            .filter { it.label == "작업 메모" }
            .distinctItems()
            .ifEmpty {
                extractAgentMessages(rawLogContents)
                    .asReversed()
                    .map { TaskLogDetailItem(UNKNOWN_TIME, "작업 메모", it) }
            }

        val lastUpdated = visibleMessages
            .mapNotNull { it.time.takeIf { time -> time.isNotBlank() } }
            .lastOrNull()
            ?: "시간 정보 없음"

        return TaskLogDetailPayload(
            title = "$appName · 작업 로그",
            appName = appName,
            taskId = taskIdShort,
            status = status,
            statusTone = statusTone(status),
            lastUpdated = lastUpdated,
            progressItems = progressItems,
            agentItems = agentItems,
            apkAction = latestApkAction(messages, summary, taskId, appName)
        )
    }

    private fun messageToItem(
        message: ChatMessage,
        formatTimestamp: (String?) -> String?
    ): TaskLogDetailItem? {
        if (isArtifactMessage(message)) return null
        val body = sanitizeLogText(message.body)
        if (body.isBlank() || isHiddenBody(body)) return null
        val label = labelFor(message)
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
                    firstTextCandidate(item)?.let { messages += it }
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
            .map { line ->
                line
                    .replace(Regex("^\\s*(단계|상태)\\s*:\\s*"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }
}
