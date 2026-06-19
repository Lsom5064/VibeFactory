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
    val fiveHourRemainingPercent: Int,
    val fiveHourResetAtLabel: String,
    val weeklyWindowLabel: String,
    val weeklyRemainingPercent: Int,
    val weeklyResetAtLabel: String,
    val totalTokensLabel: String,
    val inputTokensLabel: String,
    val outputTokensLabel: String,
    val cachedInputTokensLabel: String,
    val reasoningTokensLabel: String,
    val statusMessage: String,
    val isFallback: Boolean = false
)

object TokenUsageMockRepository {
    fun load(context: Context): TokenUsageSnapshot {
        return TokenUsageSnapshot(
            currentModel = context.getString(R.string.token_usage_limit_name_unknown),
            fiveHourWindowLabel = "5시간 한도",
            fiveHourRemainingPercent = 72,
            fiveHourResetAtLabel = context.getString(R.string.token_usage_reset_unknown),
            weeklyWindowLabel = "주간 한도",
            weeklyRemainingPercent = 54,
            weeklyResetAtLabel = context.getString(R.string.token_usage_reset_unknown),
            totalTokensLabel = formatTokenCount(context, 12430),
            inputTokensLabel = formatTokenCount(context, 8200),
            outputTokensLabel = formatTokenCount(context, 4230),
            cachedInputTokensLabel = formatTokenCount(context, 2100),
            reasoningTokensLabel = formatTokenCount(context, 900),
            statusMessage = context.getString(R.string.token_usage_status_fallback),
            isFallback = true
        )
    }

    fun summary(context: Context): String {
        val snapshot = load(context)
        return context.getString(
            R.string.settings_token_limit_summary_template,
            snapshot.fiveHourRemainingPercent,
            snapshot.weeklyRemainingPercent,
            snapshot.currentModel
        )
    }
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
        fiveHourRemainingPercent = clampPercent(primaryWindow?.remaining_percent),
        fiveHourResetAtLabel = formatResetAt(context, primaryWindow?.resets_at),
        weeklyWindowLabel = secondaryWindow?.window_label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.token_usage_card_weekly),
        weeklyRemainingPercent = clampPercent(secondaryWindow?.remaining_percent),
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

private fun clampPercent(value: Int?): Int {
    return (value ?: 0).coerceIn(0, 100)
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
