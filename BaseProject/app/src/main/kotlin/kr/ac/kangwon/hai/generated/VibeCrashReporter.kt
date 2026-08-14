package kr.ac.kangwon.hai.generated

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object VibeCrashReporter {
    private const val TAG = "VibeCrashReporter"
    private const val HOST_PACKAGE = "kr.ac.kangwon.hai.vibefactory"
    private const val ACTION_CRASH_REPORT = "kr.ac.kangwon.hai.action.CRASH_REPORT"

    private val initialized = AtomicBoolean(false)
    private val firstFrameRendered = AtomicBoolean(false)
    private val fatalReportSent = AtomicBoolean(false)
    private var applicationContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        applicationContext = context.applicationContext
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(
                errorMessage = throwable.message ?: throwable.javaClass.name,
                stackTrace = throwable.stackTraceToString(),
                reportKind = if (firstFrameRendered.get()) "uncaught_error" else "fatal_uncaught_error",
                allowDuplicate = false,
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun markFirstFrameRendered() {
        firstFrameRendered.set(true)
    }

    fun report(
        errorMessage: String,
        stackTrace: String,
        reportKind: String = "manual_report",
        allowDuplicate: Boolean = true,
    ) {
        val context = applicationContext ?: return
        if (!allowDuplicate && !fatalReportSent.compareAndSet(false, true)) return
        runCatching {
            val intent = Intent(ACTION_CRASH_REPORT).apply {
                `package` = HOST_PACKAGE
                putExtra("task_id", BuildConfig.VIBE_TASK_ID)
                putExtra("package_name", context.packageName)
                putExtra("error_message", errorMessage)
                putExtra("stack_trace", stackTrace)
                putExtra("report_kind", reportKind)
            }
            context.sendBroadcast(intent)
        }.onFailure {
            Log.w(TAG, "Crash report delivery failed", it)
        }
    }
}
