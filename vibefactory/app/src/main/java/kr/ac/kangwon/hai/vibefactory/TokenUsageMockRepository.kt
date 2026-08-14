package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import retrofit2.HttpException

data class TokenUsageSnapshot(
    val currentModel: String,
    val fiveHourWindowLabel: String,
    val fiveHourRemainingPercent: Int?,
    val fiveHourResetAtLabel: String,
    val weeklyWindowLabel: String,
    val weeklyRemainingPercent: Int?,
    val weeklyResetAtLabel: String,
    val totalTokensLabel: String,
    val inputTokensLabel: String,
    val outputTokensLabel: String,
    val cachedInputTokensLabel: String,
    val reasoningTokensLabel: String,
    val statusMessage: String,
    val isFallback: Boolean = false
)

object TokenUsageFallbackFactory {
    fun load(context: Context): TokenUsageSnapshot {
        return TokenUsageSnapshot(
            currentModel = context.getString(R.string.token_usage_limit_name_unknown),
            fiveHourWindowLabel = "5시간 한도",
            fiveHourRemainingPercent = null,
            fiveHourResetAtLabel = context.getString(R.string.token_usage_reset_unknown),
            weeklyWindowLabel = "주간 한도",
            weeklyRemainingPercent = null,
            weeklyResetAtLabel = context.getString(R.string.token_usage_reset_unknown),
            totalTokensLabel = formatTokenCount(context, null),
            inputTokensLabel = formatTokenCount(context, null),
            outputTokensLabel = formatTokenCount(context, null),
            cachedInputTokensLabel = formatTokenCount(context, null),
            reasoningTokensLabel = formatTokenCount(context, null),
            statusMessage = context.getString(R.string.token_usage_status_unavailable),
            isFallback = true
        )
    }
}

fun formatTokenUsageSummary(context: Context, snapshot: TokenUsageSnapshot): String {
    val windows = listOfNotNull(
        snapshot.fiveHourRemainingPercent?.let {
            context.getString(R.string.settings_token_limit_window, snapshot.fiveHourWindowLabel, it)
        },
        snapshot.weeklyRemainingPercent?.let {
            context.getString(R.string.settings_token_limit_window, snapshot.weeklyWindowLabel, it)
        }
    )
    if (windows.isEmpty()) {
        return context.getString(R.string.settings_token_limit_summary_unavailable)
    }
    return context.getString(
        R.string.settings_token_limit_summary_template,
        windows.joinToString(" · "),
        snapshot.currentModel
    )
}

class TokenUsageRepository(
    private val context: Context,
    private val apiService: VibeApiService,
    private val preferencesStore: HostPreferencesStore
) {
    suspend fun loadGlobal(): TokenUsageSnapshot {
        return apiService.getCodexUsage(
            deviceId = preferencesStore.getOrCreateDeviceId(),
            userId = null,
            phoneNumber = preferencesStore.loadPhoneNumber()
        ).toSnapshot(context)
    }

    suspend fun load(taskId: String): TokenUsageSnapshot {
        if (taskId.isBlank()) {
            return loadGlobal()
        }
        val deviceId = preferencesStore.getOrCreateDeviceId()
        val response = try {
            apiService.getTaskUsage(
                taskId = taskId,
                deviceId = deviceId,
                userId = null,
                phoneNumber = preferencesStore.loadPhoneNumber()
            )
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return loadGlobal()
            }
            throw e
        }
        return response.toSnapshot(context)
    }
}

private fun TokenUsageResponse.toSnapshot(context: Context): TokenUsageSnapshot {
    val primaryWindow = primary_window
    val secondaryWindow = secondary_window
    val usageSnapshot = usage
    val fallbackMessage = (status_message ?: status ?: "").trim()
    return TokenUsageSnapshot(
        currentModel = limit_name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.token_usage_limit_name_unknown),
        fiveHourWindowLabel = primaryWindow?.window_label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.token_usage_card_5h),
        fiveHourRemainingPercent = normalizeRemainingPercent(primaryWindow),
        fiveHourResetAtLabel = formatResetAt(context, primaryWindow?.resets_at),
        weeklyWindowLabel = secondaryWindow?.window_label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.token_usage_card_weekly),
        weeklyRemainingPercent = normalizeRemainingPercent(secondaryWindow),
        weeklyResetAtLabel = formatResetAt(context, secondaryWindow?.resets_at),
        totalTokensLabel = formatTokenCount(context, usageSnapshot?.total_tokens),
        inputTokensLabel = formatTokenCount(context, usageSnapshot?.input_tokens),
        outputTokensLabel = formatTokenCount(context, usageSnapshot?.output_tokens),
        cachedInputTokensLabel = formatTokenCount(context, usageSnapshot?.cached_input_tokens),
        reasoningTokensLabel = formatTokenCount(context, usageSnapshot?.reasoning_output_tokens),
        statusMessage = fallbackMessage.ifBlank { context.getString(R.string.token_usage_status_ready) },
        isFallback = false
    )
}

internal fun normalizeRemainingPercent(window: TokenUsageWindowDto?): Int? {
    return window?.remaining_percent?.coerceIn(0, 100)
}

private fun formatResetAt(context: Context, epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) {
        return context.getString(R.string.token_usage_reset_unknown)
    }
    val formatter = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    return context.getString(
        R.string.token_usage_reset_at,
        formatter.format(Date(epochSeconds * 1000L))
    )
}

private fun formatTokenCount(context: Context, value: Int?): String {
    if (value == null || value < 0) {
        return context.getString(R.string.token_usage_value_unavailable)
    }
    return context.getString(
        R.string.token_usage_token_count,
        NumberFormat.getNumberInstance(Locale.KOREA).format(value)
    )
}
