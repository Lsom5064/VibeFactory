package kr.ac.kangwon.hai.vibefactory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class TerminalBuildNotification(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
) {
    SUCCESS(R.string.notification_build_success_title, R.string.notification_build_success_body),
    FAILED(R.string.notification_build_failed_title, R.string.notification_build_failed_body),
    ATTENTION(R.string.notification_build_attention_title, R.string.notification_build_attention_body)
}

object BuildNotificationController {
    const val MONITOR_CHANNEL_ID = "build_monitor"
    const val BUILD_CHANNEL_ID = "build_complete_alerts"
    private const val TAG = "BuildNotification"

    fun createChannels(context: Context, includeMonitorChannel: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (includeMonitorChannel) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MONITOR_CHANNEL_ID,
                    context.getString(R.string.notification_channel_build_monitor),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(
                BUILD_CHANNEL_ID,
                context.getString(R.string.notification_channel_builds),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun showTerminal(
        context: Context,
        taskId: String,
        taskName: String,
        type: TerminalBuildNotification
    ): Boolean {
        if (!canPostNotifications(context)) return false
        val pendingIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SELECTED_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, BUILD_CHANNEL_ID)
            .setSmallIcon(R.drawable.logo3)
            .setContentTitle(context.getString(type.titleRes))
            .setContentText(context.getString(type.bodyRes, taskName))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(taskId.hashCode(), notification)
            true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission denied task_id=$taskId", exception)
            false
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
