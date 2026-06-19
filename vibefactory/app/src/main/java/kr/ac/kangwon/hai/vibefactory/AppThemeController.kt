package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object AppThemeController {
    fun applyStoredMode(context: Context) {
        val enabled = context.getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(HostAppConfig.PREF_DARK_MODE_ENABLED, false)
        applyDarkModePreference(enabled)
    }

    fun applyDarkModePreference(enabled: Boolean): Boolean {
        val mode = nightModeForPreference(enabled)
        if (AppCompatDelegate.getDefaultNightMode() == mode) {
            return false
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        return true
    }

    fun nightModeForPreference(enabled: Boolean): Int {
        return if (enabled) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
    }

    fun shouldRecreateForPreference(context: Context, enabled: Boolean): Boolean {
        val currentNight = isNightMode(context.resources.configuration)
        val targetNight = enabled
        return currentNight != targetNight
    }

    private fun isNightMode(configuration: Configuration): Boolean {
        return configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
