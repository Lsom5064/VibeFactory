package kr.ac.kangwon.hai.vibefactory

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private val preferencesStore by lazy {
        HostPreferencesStore(this, GsonBuilder().create(), "VibeFactorySettings")
    }

    private val apiService by lazy {
        createVibeApiService(gson = GsonBuilder().create())
    }

    private val tokenUsageRepository by lazy {
        TokenUsageRepository(this, apiService, preferencesStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnBack = findViewById<ImageButton>(R.id.btnBackSettings)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val cardTokenLimit = findViewById<LinearLayout>(R.id.cardTokenLimit)
        val tokenLimitSummary = findViewById<TextView>(R.id.textTokenLimitSummary)
        val switchDarkMode = findViewById<SwitchCompat>(R.id.switchDarkMode)
        val profileName = findViewById<TextView>(R.id.settingsProfileName)

        profileName.text = getString(R.string.app_title)
        tokenLimitSummary.text = TokenUsageMockRepository.summary(this)
        switchDarkMode.isChecked = preferencesStore.loadDarkModeEnabled()
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            preferencesStore.saveDarkModeEnabled(isChecked)
            AppThemeController.applyDarkModePreference(isChecked)
            if (AppThemeController.shouldRecreateForPreference(this, isChecked)) {
                recreate()
            }
        }
        refreshTokenUsageSummary(tokenLimitSummary)

        btnBack.setOnClickListener { finish() }
        cardTokenLimit.setOnClickListener {
            val currentTaskId = preferencesStore.loadLastSelectedTaskId().orEmpty().trim()
            startActivity(
                Intent(this, TokenUsageActivity::class.java)
                    .putExtra(TokenUsageActivity.EXTRA_TASK_ID, currentTaskId)
            )
        }
        btnSave.setOnClickListener {
            preferencesStore.saveDarkModeEnabled(switchDarkMode.isChecked)
            AppThemeController.applyDarkModePreference(switchDarkMode.isChecked)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshTokenUsageSummary(tokenLimitSummary: TextView) {
        val currentTaskId = preferencesStore.loadLastSelectedTaskId().orEmpty().trim()
        if (currentTaskId.isBlank()) {
            tokenLimitSummary.text = getString(R.string.settings_token_limit_summary_empty)
            return
        }
        lifecycleScope.launch {
            runCatching {
                tokenUsageRepository.load(currentTaskId)
            }.onSuccess { snapshot ->
                tokenLimitSummary.text = getString(
                    R.string.settings_token_limit_summary_template,
                    snapshot.fiveHourRemainingPercent,
                    snapshot.weeklyRemainingPercent,
                    snapshot.currentModel
                )
            }
        }
    }
}
