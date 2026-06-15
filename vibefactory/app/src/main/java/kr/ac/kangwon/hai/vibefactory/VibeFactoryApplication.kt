package kr.ac.kangwon.hai.vibefactory

import android.app.Application

class VibeFactoryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppThemeController.applyStoredMode(this)
    }
}
