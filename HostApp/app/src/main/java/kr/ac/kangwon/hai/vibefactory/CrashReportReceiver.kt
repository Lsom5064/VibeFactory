package kr.ac.kangwon.hai.vibefactory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CrashReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CRASH_REPORT) return

        val taskId = intent.getStringExtra("task_id")?.trim().orEmpty()
        val packageName = intent.getStringExtra("package_name")?.trim().orEmpty()
        val stackTrace = intent.getStringExtra("stack_trace")?.trim().orEmpty()
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

        current[taskId] = RuntimeErrorRecord(
            packageName = packageName.ifBlank { "알 수 없는 앱" },
            stackTrace = stackTrace,
            summary = summarizeRuntimeError(stackTrace),
            awaitingUserConfirmation = true
        )

        prefs.edit()
            .putString(PREF_PENDING_RUNTIME_ERRORS, gson.toJson(current))
            .apply()
    }

    private fun summarizeRuntimeError(stackTrace: String): String {
        val normalized = stackTrace.trim()
        val lowercase = normalized.lowercase()
        return when {
            lowercase.contains("_elements.contains(element)") -> "Flutter 위젯 트리 상태 불일치 오류"
            lowercase.contains("renderflex overflowed") -> "레이아웃 overflow 오류"
            lowercase.contains("null check operator used on a null value") -> "null 값 처리 오류"
            lowercase.contains("setstate() or markneedsbuild() called during build") -> "빌드 중 상태 변경 오류"
            lowercase.contains("failed assertion") -> "Flutter framework assertion 오류"
            else -> normalized.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "알 수 없는 런타임 오류"
        }
    }

    companion object {
        private const val ACTION_CRASH_REPORT = "kr.ac.kangwon.hai.action.CRASH_REPORT"
        private const val PREFS_NAME = "vibefactory_prefs"
        private const val PREF_PENDING_RUNTIME_ERRORS = "pending_runtime_errors"
    }
}
