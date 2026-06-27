package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class PersistedTaskChats(
    val timelines: Map<String, List<ChatMessage>>,
    val statusMessages: Map<String, ChatMessage>
)

class HostPreferencesStore(
    private val context: Context,
    private val gson: Gson,
    private val logTag: String
) {
    companion object {
        private const val MAX_RESTORABLE_TASK_CHAT_JSON_CHARS = 2_000_000
        private const val MAX_PERSISTED_TASKS = 30
        private const val MAX_PERSISTED_MESSAGES_PER_TASK = 160
        private const val MAX_PERSISTED_BODY_CHARS = 4_000
        private const val MAX_PERSISTED_DETAIL_CHARS = 4_000
        private const val MAX_PERSISTED_IMAGE_PREVIEW_CHARS = 120_000
        private const val TRUNCATED_SUFFIX = "\n\n[긴 내용은 앱 성능을 위해 일부만 저장했어요.]"
    }

    private val prefs
        get() = context.getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)

    private val taskChatsDir: File
        get() = File(context.filesDir, "task_chats")

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(HostAppConfig.PREF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            Log.d(logTag, "Reusing stored device_id=$existing")
            return existing
        }

        val newDeviceId = UUID.randomUUID().toString()
        val saved = prefs.edit().putString(HostAppConfig.PREF_DEVICE_ID, newDeviceId).commit()
        if (!saved) {
            throw IllegalStateException("Failed to persist device_id")
        }
        val stored = prefs.getString(HostAppConfig.PREF_DEVICE_ID, null)
        if (stored.isNullOrBlank()) {
            throw IllegalStateException("Stored device_id is blank after commit")
        }
        Log.d(logTag, "Generated new device_id=$stored")
        return stored
    }

    fun loadPhoneNumber(): String? {
        return prefs.getString(HostAppConfig.PREF_PHONE_NUMBER, null)?.trim()?.ifBlank { null }
    }

    fun savePhoneNumber(phoneNumber: String?) {
        prefs.edit()
            .putString(HostAppConfig.PREF_PHONE_NUMBER, phoneNumber?.trim()?.ifBlank { null })
            .apply()
    }

    fun saveLastSelectedTaskId(taskId: String?) {
        prefs.edit()
            .putString(HostAppConfig.PREF_LAST_TASK_ID, taskId)
            .apply()
    }

    fun loadLastSelectedTaskId(): String? {
        return prefs.getString(HostAppConfig.PREF_LAST_TASK_ID, null)
    }

    fun loadHiddenTaskIds(): Set<String> {
        val json = prefs.getString(HostAppConfig.PREF_HIDDEN_TASK_IDS, null) ?: return emptySet()
        return runCatching {
            val type = object : TypeToken<Set<String>>() {}.type
            val saved: Set<String> = gson.fromJson(json, type) ?: emptySet()
            saved.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }.getOrElse {
            Log.w(logTag, "Failed to load hidden task ids", it)
            emptySet()
        }
    }

    fun saveHiddenTaskIds(hiddenTaskIds: Set<String>) {
        prefs.edit()
            .putString(HostAppConfig.PREF_HIDDEN_TASK_IDS, gson.toJson(hiddenTaskIds))
            .apply()
    }

    fun loadTaskChats(): PersistedTaskChats {
        val timelineJson = prefs.getString(HostAppConfig.PREF_TASK_TIMELINES, null)
        val statusJson = prefs.getString(HostAppConfig.PREF_TASK_STATUS_BUBBLES, null)

        val timelines = if (timelineJson.isNullOrBlank()) {
            emptyMap()
        } else if (timelineJson.length > MAX_RESTORABLE_TASK_CHAT_JSON_CHARS) {
            Log.w(
                logTag,
                "Skipping oversized local task timelines cache length=${timelineJson.length}"
            )
            emptyMap()
        } else {
            runCatching {
                val type = object : TypeToken<Map<String, List<ChatMessage>>>() {}.type
                compactTaskChatsForStorage(
                    gson.fromJson<Map<String, List<ChatMessage>>>(timelineJson, type).orEmpty()
                )
            }.getOrElse {
                Log.w(logTag, "Failed to restore task timelines", it)
                emptyMap()
            }
        }

        val statusMessages = if (statusJson.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                val type = object : TypeToken<Map<String, ChatMessage>>() {}.type
                gson.fromJson<Map<String, ChatMessage>>(statusJson, type).orEmpty()
            }.getOrElse {
                Log.w(logTag, "Failed to restore task status bubbles", it)
                emptyMap()
            }
        }

        return PersistedTaskChats(
            timelines = timelines,
            statusMessages = statusMessages
        )
    }

    fun migrateLegacyTaskChatsIfNeeded(hiddenTaskIds: Set<String>): Int {
        val hasLegacyTimelines = prefs.contains(HostAppConfig.PREF_TASK_TIMELINES)
        val hasLegacyStatusMessages = prefs.contains(HostAppConfig.PREF_TASK_STATUS_BUBBLES)
        if (!hasLegacyTimelines && !hasLegacyStatusMessages) return 0

        var migratedCount = 0
        if (hasLegacyTimelines) {
            val restoredChats = loadTaskChats()
            restoredChats.timelines.forEach { (taskId, messages) ->
                val normalizedTaskId = normalizeTaskId(taskId)
                if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds || messages.isEmpty()) {
                    return@forEach
                }
                if (saveTaskChat(normalizedTaskId, messages)) {
                    migratedCount += 1
                }
            }
        }

        prefs.edit()
            .remove(HostAppConfig.PREF_TASK_TIMELINES)
            .remove(HostAppConfig.PREF_TASK_STATUS_BUBBLES)
            .apply()

        Log.d(logTag, "Migrated legacy task chats count=$migratedCount")
        return migratedCount
    }

    fun hasTaskChat(taskId: String): Boolean {
        val normalizedTaskId = normalizeTaskId(taskId)
        if (normalizedTaskId.isBlank()) return false
        return taskChatFile(normalizedTaskId).exists()
    }

    fun loadTaskChat(taskId: String): List<ChatMessage> {
        val normalizedTaskId = normalizeTaskId(taskId)
        if (normalizedTaskId.isBlank()) return emptyList()
        val file = taskChatFile(normalizedTaskId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            val messages: List<ChatMessage> = gson.fromJson(file.readText(Charsets.UTF_8), type) ?: emptyList()
            compactTaskMessagesForStorage(messages)
        }.getOrElse {
            Log.w(logTag, "Failed to load task chat task_id=$normalizedTaskId", it)
            emptyList()
        }
    }

    fun saveTaskChat(taskId: String, messages: List<ChatMessage>): Boolean {
        val normalizedTaskId = normalizeTaskId(taskId)
        if (normalizedTaskId.isBlank()) return true
        return runCatching {
            if (!taskChatsDir.exists() && !taskChatsDir.mkdirs()) {
                Log.e(logTag, "Failed to create task chat directory path=${taskChatsDir.absolutePath}")
                return@runCatching false
            }
            val file = taskChatFile(normalizedTaskId)
            val compacted = compactTaskMessagesForStorage(messages)
            if (compacted.isEmpty()) {
                if (file.exists() && !file.delete()) {
                    Log.w(logTag, "Failed to delete empty task chat task_id=$normalizedTaskId")
                    return@runCatching false
                }
                return@runCatching true
            }
            file.writeText(gson.toJson(compacted), Charsets.UTF_8)
            true
        }.getOrElse {
            Log.e(logTag, "Failed to persist task chat task_id=$normalizedTaskId", it)
            false
        }
    }

    fun deleteTaskChat(taskId: String) {
        val normalizedTaskId = normalizeTaskId(taskId)
        if (normalizedTaskId.isBlank()) return
        runCatching {
            val file = taskChatFile(normalizedTaskId)
            if (file.exists() && !file.delete()) {
                Log.w(logTag, "Failed to delete task chat task_id=$normalizedTaskId")
            }
        }.onFailure {
            Log.w(logTag, "Failed to delete task chat task_id=$normalizedTaskId", it)
        }
    }

    fun saveTaskChats(taskConversationMessages: Map<String, List<ChatMessage>>): Boolean {
        return runCatching {
            val savedAll = taskConversationMessages.all { (taskId, messages) ->
                saveTaskChat(taskId, messages)
            }
            prefs.edit()
                .remove(HostAppConfig.PREF_TASK_TIMELINES)
                .remove(HostAppConfig.PREF_TASK_STATUS_BUBBLES)
                .commit()
            savedAll
        }.getOrElse {
            Log.e(logTag, "Failed to persist task chats", it)
            false
        }
    }

    fun loadPendingRuntimeErrors(): Map<String, RuntimeErrorRecord> {
        val json = prefs.getString(HostAppConfig.PREF_PENDING_RUNTIME_ERRORS, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, RuntimeErrorRecord>>() {}.type
            gson.fromJson<Map<String, RuntimeErrorRecord>>(json, type)
                .orEmpty()
                .mapValues { (_, record) ->
                    record.copy(
                        stackTrace = RuntimeErrorStoragePolicy.compactStackTrace(record.stackTrace)
                    )
                }
        }.getOrElse {
            Log.e(logTag, "Failed to restore pending runtime errors", it)
            emptyMap()
        }
    }

    fun savePendingRuntimeErrors(pendingRuntimeErrors: Map<String, RuntimeErrorRecord>) {
        runCatching {
            val compacted = pendingRuntimeErrors.mapValues { (_, record) ->
                record.copy(
                    stackTrace = RuntimeErrorStoragePolicy.compactStackTrace(record.stackTrace)
                )
            }
            prefs.edit()
                .putString(HostAppConfig.PREF_PENDING_RUNTIME_ERRORS, gson.toJson(compacted))
                .apply()
        }.onFailure {
            Log.e(logTag, "Failed to persist pending runtime errors", it)
        }
    }

    fun loadNotifiedBuildSuccessTaskIds(): Set<String> {
        return prefs.getStringSet(HostAppConfig.PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun saveNotifiedBuildSuccessTaskIds(taskIds: Set<String>) {
        prefs.edit()
            .putStringSet(HostAppConfig.PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS, taskIds)
            .apply()
    }

    fun loadTaskArtifactStates(): Map<String, PersistedArtifactState> {
        val json = prefs.getString(HostAppConfig.PREF_TASK_ARTIFACT_STATES, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, PersistedArtifactState>>() {}.type
            gson.fromJson<Map<String, PersistedArtifactState>>(json, type).orEmpty()
        }.getOrElse {
            Log.e(logTag, "Failed to restore task artifact states", it)
            emptyMap()
        }
    }

    fun saveTaskArtifactStates(taskArtifactStates: Map<String, PersistedArtifactState>) {
        runCatching {
            prefs.edit()
                .putString(HostAppConfig.PREF_TASK_ARTIFACT_STATES, gson.toJson(taskArtifactStates))
                .apply()
        }.onFailure {
            Log.e(logTag, "Failed to persist task artifact states", it)
        }
    }

    fun loadTokenLimit(): String? {
        return prefs.getString(HostAppConfig.PREF_TOKEN_LIMIT, null)?.trim()?.ifBlank { null }
    }

    fun saveTokenLimit(tokenLimit: String?) {
        prefs.edit()
            .putString(HostAppConfig.PREF_TOKEN_LIMIT, tokenLimit?.trim()?.ifBlank { null })
            .apply()
    }

    fun loadDarkModeEnabled(): Boolean {
        return prefs.getBoolean(HostAppConfig.PREF_DARK_MODE_ENABLED, false)
    }

    fun saveDarkModeEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(HostAppConfig.PREF_DARK_MODE_ENABLED, enabled)
            .apply()
    }

    private fun compactTaskChatsForStorage(
        taskConversationMessages: Map<String, List<ChatMessage>>
    ): Map<String, List<ChatMessage>> {
        return taskConversationMessages.entries
            .filter { it.key.isNotBlank() && it.value.isNotEmpty() }
            .takeLast(MAX_PERSISTED_TASKS)
            .associate { (taskId, messages) ->
                taskId to compactTaskMessagesForStorage(messages)
            }
    }

    private fun compactTaskMessagesForStorage(messages: List<ChatMessage>): List<ChatMessage> {
        return messages
            .takeLast(MAX_PERSISTED_MESSAGES_PER_TASK)
            .map(::compactChatMessageForStorage)
    }

    private fun compactChatMessageForStorage(message: ChatMessage): ChatMessage {
        return message.copy(
            body = truncateForPrefs(message.body, MAX_PERSISTED_BODY_CHARS),
            detail = message.detail?.let { truncateForPrefs(it, MAX_PERSISTED_DETAIL_CHARS) },
            imagePreviewBase64 = message.imagePreviewBase64?.take(MAX_PERSISTED_IMAGE_PREVIEW_CHARS)
        )
    }

    private fun truncateForPrefs(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val suffix = TRUNCATED_SUFFIX
        val keepChars = (maxChars - suffix.length).coerceAtLeast(maxChars / 2)
        return value.take(keepChars).trimEnd() + suffix
    }

    private fun normalizeTaskId(taskId: String): String {
        return taskId.trim()
    }

    private fun taskChatFile(taskId: String): File {
        val encodedTaskId = Base64.encodeToString(
            normalizeTaskId(taskId).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return File(taskChatsDir, "$encodedTaskId.json")
    }
}
