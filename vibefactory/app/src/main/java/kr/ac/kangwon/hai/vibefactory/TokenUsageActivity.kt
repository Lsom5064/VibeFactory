package kr.ac.kangwon.hai.vibefactory

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch

class TokenUsageActivity : AppCompatActivity() {
    private val preferencesStore by lazy {
        HostPreferencesStore(this, GsonBuilder().create(), "TokenUsageActivity")
    }

    private val apiService by lazy {
        createVibeApiService(gson = GsonBuilder().create())
    }

    private val tokenUsageRepository by lazy {
        TokenUsageRepository(this, apiService, preferencesStore)
    }

    private lateinit var textFiveHourTitle: TextView
    private lateinit var textFiveHourValue: TextView
    private lateinit var textFiveHourReset: TextView
    private lateinit var textWeeklyTitle: TextView
    private lateinit var textWeeklyValue: TextView
    private lateinit var textWeeklyReset: TextView
    private lateinit var textCurrentModelValue: TextView
    private lateinit var textStatusMessage: TextView
    private lateinit var textTokenUsageEmptyState: TextView
    private lateinit var textTotalTokensValue: TextView
    private lateinit var textInputTokensValue: TextView
    private lateinit var textOutputTokensValue: TextView
    private lateinit var textCachedInputTokensValue: TextView
    private lateinit var textReasoningTokensValue: TextView
    private lateinit var progressFiveHour: ProgressBar
    private lateinit var progressWeekly: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_token_usage)

        findViewById<ImageButton>(R.id.btnBackTokenUsage).setOnClickListener { finish() }
        bindViews()

        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        loadUsage(taskId)
    }

    private fun bindViews() {
        textFiveHourTitle = findViewById(R.id.textFiveHourTitle)
        textFiveHourValue = findViewById(R.id.textFiveHourValue)
        textFiveHourReset = findViewById(R.id.textFiveHourReset)
        textWeeklyTitle = findViewById(R.id.textWeeklyTitle)
        textWeeklyValue = findViewById(R.id.textWeeklyValue)
        textWeeklyReset = findViewById(R.id.textWeeklyReset)
        textCurrentModelValue = findViewById(R.id.textCurrentModelValue)
        textStatusMessage = findViewById(R.id.textTokenUsageStatus)
        textTokenUsageEmptyState = findViewById(R.id.textTokenUsageEmptyState)
        textTotalTokensValue = findViewById(R.id.textTotalTokensValue)
        textInputTokensValue = findViewById(R.id.textInputTokensValue)
        textOutputTokensValue = findViewById(R.id.textOutputTokensValue)
        textCachedInputTokensValue = findViewById(R.id.textCachedInputTokensValue)
        textReasoningTokensValue = findViewById(R.id.textReasoningTokensValue)
        progressFiveHour = findViewById(R.id.progressFiveHour)
        progressWeekly = findViewById(R.id.progressWeekly)
    }

    private fun loadUsage(taskId: String) {
        bindLoadingState()
        lifecycleScope.launch {
            runCatching {
                if (taskId.isBlank()) {
                    tokenUsageRepository.loadGlobal()
                } else {
                    tokenUsageRepository.load(taskId)
                }
            }.onSuccess { snapshot ->
                bindSnapshot(snapshot)
            }.onFailure { throwable ->
                val fallback = TokenUsageMockRepository.load(this@TokenUsageActivity)
                bindSnapshot(
                    fallback.copy(
                        statusMessage = getString(
                            R.string.token_usage_status_failed,
                            throwable.message ?: getString(R.string.token_usage_value_unavailable)
                        ),
                        isFallback = true
                    )
                )
                textTokenUsageEmptyState.visibility = View.VISIBLE
                textTokenUsageEmptyState.text = getString(R.string.token_usage_empty_fallback)
            }
        }
    }

    private fun bindLoadingState() {
        textTokenUsageEmptyState.visibility = View.GONE
        textStatusMessage.visibility = View.VISIBLE
        textStatusMessage.text = getString(R.string.token_usage_status_loading)
    }

    private fun bindEmptyState(message: String) {
        val fallback = TokenUsageMockRepository.load(this)
        bindSnapshot(fallback.copy(statusMessage = message, isFallback = true))
        textTokenUsageEmptyState.visibility = View.GONE
    }

    private fun bindSnapshot(snapshot: TokenUsageSnapshot) {
        textFiveHourTitle.text = snapshot.fiveHourWindowLabel
        textWeeklyTitle.text = snapshot.weeklyWindowLabel
        bindPercentCard(progressFiveHour, textFiveHourValue, snapshot.fiveHourRemainingPercent)
        bindPercentCard(progressWeekly, textWeeklyValue, snapshot.weeklyRemainingPercent)
        textFiveHourReset.text = snapshot.fiveHourResetAtLabel
        textWeeklyReset.text = snapshot.weeklyResetAtLabel
        textCurrentModelValue.text = snapshot.currentModel
        textTotalTokensValue.text = snapshot.totalTokensLabel
        textInputTokensValue.text = snapshot.inputTokensLabel
        textOutputTokensValue.text = snapshot.outputTokensLabel
        textCachedInputTokensValue.text = snapshot.cachedInputTokensLabel
        textReasoningTokensValue.text = snapshot.reasoningTokensLabel
        textStatusMessage.visibility = View.VISIBLE
        textStatusMessage.text = snapshot.statusMessage
        if (!snapshot.isFallback) {
            textTokenUsageEmptyState.visibility = View.GONE
        }
    }

    private fun bindPercentCard(progressBar: ProgressBar, valueView: TextView, percent: Int) {
        progressBar.progress = percent.coerceIn(0, 100)
        valueView.text = getString(R.string.token_usage_remaining_percent, percent.coerceIn(0, 100))
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
