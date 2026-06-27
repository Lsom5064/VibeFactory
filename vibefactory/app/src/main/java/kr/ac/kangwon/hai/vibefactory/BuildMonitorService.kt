package kr.ac.kangwon.hai.vibefactory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

class BuildMonitorService : Service() {

    companion object {
        private const val TAG = "BuildMonitorService"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val SELECTED_TASK_ID_EXTRA = "selected_task_id"
        private const val ACTION_MONITOR_TASK = "kr.ac.kangwon.hai.vibefactory.MONITOR_TASK"
        private const val ACTION_STOP_TASK = "kr.ac.kangwon.hai.vibefactory.STOP_MONITORING_TASK"
        private const val EXTRA_TASK_ID = "task_id"
        private const val MONITOR_CHANNEL_ID = "build_monitor"
        private const val BUILD_NOTIFICATION_CHANNEL_ID = "build_complete_alerts"
        private const val FOREGROUND_NOTIFICATION_ID = 2601

        fun startMonitoring(context: Context, taskId: String) {
            val normalizedTaskId = taskId.trim()
            if (normalizedTaskId.isBlank()) return
            val intent = Intent(context, BuildMonitorService::class.java).apply {
                action = ACTION_MONITOR_TASK
                putExtra(EXTRA_TASK_ID, normalizedTaskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopMonitoring(context: Context, taskId: String) {
            val normalizedTaskId = taskId.trim()
            if (normalizedTaskId.isBlank()) return
            val intent = Intent(context, BuildMonitorService::class.java).apply {
                action = ACTION_STOP_TASK
                putExtra(EXTRA_TASK_ID, normalizedTaskId)
            }
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to stop build monitor from background context", e)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val taskLock = Any()
    private val monitoredTaskIds = linkedSetOf<String>()
    private lateinit var apiService: VibeApiService
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        apiService = createApiService()
        createNotificationChannels()
        synchronized(taskLock) {
            monitoredTaskIds += loadStringSet(HostAppConfig.PREF_MONITORED_TASK_IDS)
        }
        pruneHiddenTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MONITOR_TASK -> addTask(intent.getStringExtra(EXTRA_TASK_ID))
            ACTION_STOP_TASK -> removeTask(intent.getStringExtra(EXTRA_TASK_ID))
        }
        pruneHiddenTasks()

        if (snapshotTaskIds().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        ensureMonitorLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createApiService(): VibeApiService {
        return createVibeApiService(
            readTimeoutSeconds = 30,
            writeTimeoutSeconds = 30,
            callTimeoutSeconds = null
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                getString(R.string.notification_channel_build_monitor),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BUILD_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_builds),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun addTask(rawTaskId: String?) {
        val taskId = rawTaskId?.trim().orEmpty()
        if (taskId.isBlank()) return
        if (isTaskHidden(taskId)) {
            synchronized(taskLock) {
                monitoredTaskIds -= taskId
                persistMonitoredTasksLocked()
            }
            clearTerminalNotificationMarkers(taskId)
            return
        }
        synchronized(taskLock) {
            monitoredTaskIds += taskId
            persistMonitoredTasksLocked()
        }
        clearTerminalNotificationMarkers(taskId)
    }

    private fun removeTask(rawTaskId: String?) {
        val taskId = rawTaskId?.trim().orEmpty()
        if (taskId.isBlank()) return
        synchronized(taskLock) {
            monitoredTaskIds -= taskId
            persistMonitoredTasksLocked()
        }
    }

    private fun snapshotTaskIds(): List<String> {
        return synchronized(taskLock) { monitoredTaskIds.toList() }
    }

    private fun ensureMonitorLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                pruneHiddenTasks()
                val taskIds = snapshotTaskIds()
                if (taskIds.isEmpty()) {
                    withContext(Dispatchers.Main) { stopWhenIdle() }
                    break
                }

                taskIds.forEach { taskId ->
                    pollTask(taskId)
                }

                withContext(Dispatchers.Main) {
                    if (snapshotTaskIds().isEmpty()) {
                        stopWhenIdle()
                    } else {
                        updateForegroundNotification()
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollTask(taskId: String) {
        try {
            val response = apiService.getStatus(
                taskId = taskId,
                deviceId = loadString(HostAppConfig.PREF_DEVICE_ID),
                userId = null,
                phoneNumber = loadString(HostAppConfig.PREF_PHONE_NUMBER).ifBlank { null }
            )
            persistMonitoredTaskName(taskId, resolveMonitoredTaskName(taskId, response))
            val statusKey = normalizeStatusKey(response.status)
            if (!shouldPoll(statusKey)) {
                removeTask(taskId)
                showTerminalNotificationIfNeeded(taskId, response, statusKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Build monitor poll failed task_id=$taskId", e)
        }
    }

    private fun showTerminalNotificationIfNeeded(taskId: String, response: StatusResponse, statusKey: String) {
        val success = statusKey == "success"
        val prefKey = if (success) {
            HostAppConfig.PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS
        } else {
            HostAppConfig.PREF_NOTIFIED_TERMINAL_TASK_IDS
        }
        val stored = loadStringSet(prefKey)
        if (taskId in stored) return
        persistStringSet(prefKey, stored + taskId)
        showTerminalNotification(taskId, response, statusKey)
    }

    private fun showTerminalNotification(taskId: String, response: StatusResponse, statusKey: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val titleRes = when (statusKey) {
            "success" -> R.string.notification_build_success_title
            "failed", "error" -> R.string.notification_build_failed_title
            else -> R.string.notification_build_attention_title
        }
        val bodyRes = when (statusKey) {
            "success" -> R.string.notification_build_success_body
            "failed", "error" -> R.string.notification_build_failed_body
            else -> R.string.notification_build_attention_body
        }
        val taskName = buildTaskContentTitle(
            initialPrompt = extractConversationField(response.conversation_state, "initial_user_prompt"),
            appName = taskDisplayName(response.generated_app_name) ?: taskDisplayName(response.app_name),
            conversationState = response.conversation_state
        ) ?: taskId
        val pendingIntent = PendingIntent.getActivity(
            this,
            taskId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(SELECTED_TASK_ID_EXTRA, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, BUILD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(titleRes))
            .setContentText(getString(bodyRes, taskName))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(taskId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied for terminal build notification", e)
        }
    }

    private fun clearTerminalNotificationMarkers(taskId: String) {
        listOf(
            HostAppConfig.PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS,
            HostAppConfig.PREF_NOTIFIED_TERMINAL_TASK_IDS
        ).forEach { key ->
            val stored = loadStringSet(key)
            if (taskId in stored) {
                persistStringSet(key, stored - taskId)
            }
        }
        NotificationManagerCompat.from(this).cancel(taskId.hashCode())
    }

    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        val notification = buildForegroundNotification()
        try {
            NotificationManagerCompat.from(this).notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied for foreground update", e)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val taskIds = snapshotTaskIds()
        val taskId = taskIds.firstOrNull().orEmpty()
        val taskName = loadMonitoredTaskName(taskId)
            .ifBlank { taskId }
            .ifBlank { getString(R.string.untitled_task) }
        val body = if (taskIds.size <= 1) {
            getString(R.string.notification_build_monitor_body, taskName)
        } else {
            getString(R.string.notification_build_monitor_body_multiple, taskIds.size)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            FOREGROUND_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (taskId.isNotBlank()) putExtra(SELECTED_TASK_ID_EXTRA, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_build_monitor_title))
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun stopWhenIdle() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shouldPoll(statusKey: String): Boolean {
        return statusKey in setOf(
            "pending decision",
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
    }

    private fun normalizeStatusKey(status: String): String {
        return compactStatusLabel(status)
            .lowercase(Locale.ROOT)
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun compactStatusLabel(status: String): String {
        return status.trim()
            .split('\n')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun taskDisplayName(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.takeUnless {
            normalizeStatusKey(it) in setOf(
                "pending decision",
                "clarification needed",
                "processing",
                "reviewing",
                "repairing",
                "success",
                "failed",
                "error",
                "rejected"
            )
        }
    }

    private fun extractConversationField(conversationState: JsonElement?, fieldName: String): String? {
        val obj = conversationState?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return obj.get(fieldName)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildTaskContentTitle(initialPrompt: String?, appName: String?, conversationState: JsonElement?): String? {
        val summary = extractConversationField(conversationState, "latest_summary")
        return listOf(
            summarizeTaskTitleCandidate(appName),
            summarizeTaskTitleCandidate(summary),
            summarizeTaskTitleCandidate(initialPrompt)
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun resolveMonitoredTaskName(taskId: String, response: StatusResponse): String {
        return buildTaskContentTitle(
            initialPrompt = extractConversationField(response.conversation_state, "initial_user_prompt"),
            appName = taskDisplayName(response.generated_app_name) ?: taskDisplayName(response.app_name),
            conversationState = response.conversation_state
        ) ?: taskId
    }

    private fun summarizeTaskTitleCandidate(rawValue: String?): String? {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isBlank()) return null
        val cleaned = normalized
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace(Regex("^[-*•]\\s*"), "")
            .replace(Regex("(만들어줘|생성해줘|개발해줘|구현해줘|빌드해줘|수정해줘|추가해줘|변경해줘)$"), "")
            .replace(Regex("(을|를) 만들게요$"), "")
            .replace(Regex("(을|를) 이렇게 수정할게요$"), "")
            .replace(Regex("^기존\\s+"), "")
            .trim()
            .trimEnd('.', '!', '?')
        if (cleaned.isBlank()) return null
        return if (cleaned.length > 32) "${cleaned.take(29).trimEnd()}..." else cleaned
    }

    private fun loadString(key: String): String {
        return getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
    }

    private fun loadStringSet(key: String): Set<String> {
        return getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .getStringSet(key, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun loadHiddenTaskIds(): Set<String> {
        val rawValue = getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(HostAppConfig.PREF_HIDDEN_TASK_IDS, null)
            ?.trim()
            .orEmpty()
        if (rawValue.isBlank()) return emptySet()
        return runCatching {
            val values = JSONArray(rawValue)
            buildSet {
                for (index in 0 until values.length()) {
                    val taskId = values.optString(index).trim()
                    if (taskId.isNotBlank()) {
                        add(taskId)
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to restore hidden task ids", it)
            emptySet()
        }
    }

    private fun isTaskHidden(taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return false
        return normalizedTaskId in loadHiddenTaskIds()
    }

    private fun pruneHiddenTasks() {
        val hiddenTaskIds = loadHiddenTaskIds()
        if (hiddenTaskIds.isEmpty()) return
        val removedTaskIds = synchronized(taskLock) {
            val hiddenMonitoredTaskIds = monitoredTaskIds.filter { it in hiddenTaskIds }
            if (hiddenMonitoredTaskIds.isNotEmpty()) {
                monitoredTaskIds.removeAll(hiddenMonitoredTaskIds.toSet())
                persistMonitoredTasksLocked()
            }
            hiddenMonitoredTaskIds
        }
        removedTaskIds.forEach(::clearTerminalNotificationMarkers)
    }

    private fun monitoredTaskNameKey(taskId: String): String {
        return "${HostAppConfig.PREF_MONITORED_TASK_APP_NAMES}_$taskId"
    }

    private fun persistMonitoredTaskName(taskId: String, appName: String) {
        val normalizedTaskId = taskId.trim()
        val normalizedAppName = appName.trim()
        if (normalizedTaskId.isBlank() || normalizedAppName.isBlank()) return
        getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(monitoredTaskNameKey(normalizedTaskId), normalizedAppName)
            .apply()
    }

    private fun loadMonitoredTaskName(taskId: String): String {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return ""
        return getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(monitoredTaskNameKey(normalizedTaskId), "")
            .orEmpty()
    }

    private fun persistStringSet(key: String, values: Set<String>) {
        getSharedPreferences(HostAppConfig.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(key, values.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    private fun persistMonitoredTasksLocked() {
        persistStringSet(HostAppConfig.PREF_MONITORED_TASK_IDS, monitoredTaskIds.toSet())
    }
}
