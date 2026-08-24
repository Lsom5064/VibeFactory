package kr.ac.kangwon.hai.vibefactory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CrashReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CRASH_REPORT) return

        val taskId = intent.getStringExtra("task_id")?.trim().orEmpty()
        val packageName = intent.getStringExtra("package_name")?.trim().orEmpty()
        if (!CrashReportSenderPolicy.accepts(this, packageName)) return
        val errorMessage = intent.getStringExtra("error_message")?.trim().orEmpty()
        val reportKind = intent.getStringExtra("report_kind")?.trim().orEmpty()
        val stackTrace = RuntimeErrorStoragePolicy.compactStackTrace(
            intent.getStringExtra("stack_trace").orEmpty()
        )
        if (taskId.isBlank() || stackTrace.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val existingJson = prefs.getString(PREF_PENDING_RUNTIME_ERRORS, null)
        val type = object : TypeToken<Map<String, RuntimeErrorRecord>>() {}.type
        val current = if (existingJson.isNullOrBlank()) {
            mutableMapOf()
        } else {
            gson.fromJson<Map<String, RuntimeErrorRecord>>(existingJson, type)
                ?.toMutableMap()
                ?: mutableMapOf()
        }

        val analysis = RuntimeErrorAnalyzer.analyze(
            stackTrace = stackTrace,
            errorMessage = errorMessage.ifBlank { null },
            reportKind = reportKind.ifBlank { null }
        )
        current[taskId] = RuntimeErrorRecord(
            packageName = packageName.ifBlank { "알 수 없는 앱" },
            stackTrace = stackTrace,
            summary = analysis.summary,
            errorMessage = errorMessage.ifBlank { null },
            reportKind = reportKind.ifBlank { null },
            awaitingUserConfirmation = true,
            serverReported = false
        )

        prefs.edit()
            .putString(PREF_PENDING_RUNTIME_ERRORS, gson.toJson(current))
            .apply()
    }

    companion object {
        private const val ACTION_CRASH_REPORT = "kr.ac.kangwon.hai.action.CRASH_REPORT"
        private const val PREFS_NAME = "vibefactory_prefs"
        private const val PREF_PENDING_RUNTIME_ERRORS = "pending_runtime_errors"
    }
}

internal object CrashReportSenderPolicy {
    fun accepts(receiver: BroadcastReceiver, reportedPackageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return accepts(
            reportedPackageName = reportedPackageName,
            senderPackageName = receiver.sentFromPackage,
            senderIdentityAvailable = true,
        )
    }

    fun accepts(
        reportedPackageName: String,
        senderPackageName: String?,
        senderIdentityAvailable: Boolean,
    ): Boolean {
        if (!senderIdentityAvailable) return true
        val reported = reportedPackageName.trim()
        val sender = senderPackageName?.trim().orEmpty()
        return reported.isNotBlank() && sender == reported
    }
}
