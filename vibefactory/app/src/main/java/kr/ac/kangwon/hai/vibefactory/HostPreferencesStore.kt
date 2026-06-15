package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val prefs
        get() = context.getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)

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
        } else {
            runCatching {
                val type = object : TypeToken<Map<String, List<ChatMessage>>>() {}.type
                gson.fromJson<Map<String, List<ChatMessage>>>(timelineJson, type).orEmpty()
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

    fun saveTaskChats(taskConversationMessages: Map<String, List<ChatMessage>>): Boolean {
        return runCatching {
            prefs.edit()
                .putString(HostAppConfig.PREF_TASK_TIMELINES, gson.toJson(taskConversationMessages))
                .remove(HostAppConfig.PREF_TASK_STATUS_BUBBLES)
                .commit()
        }.getOrElse {
            Log.e(logTag, "Failed to persist task chats", it)
            false
        }
    }

    fun loadPendingRuntimeErrors(): Map<String, RuntimeErrorRecord> {
        val json = prefs.getString(HostAppConfig.PREF_PENDING_RUNTIME_ERRORS, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, RuntimeErrorRecord>>() {}.type
            gson.fromJson<Map<String, RuntimeErrorRecord>>(json, type).orEmpty()
        }.getOrElse {
            Log.e(logTag, "Failed to restore pending runtime errors", it)
            emptyMap()
        }
    }

    fun savePendingRuntimeErrors(pendingRuntimeErrors: Map<String, RuntimeErrorRecord>) {
        runCatching {
            prefs.edit()
                .putString(HostAppConfig.PREF_PENDING_RUNTIME_ERRORS, gson.toJson(pendingRuntimeErrors))
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
}
