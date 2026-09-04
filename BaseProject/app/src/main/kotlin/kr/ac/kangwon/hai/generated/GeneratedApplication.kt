package kr.ac.kangwon.hai.generated

import android.app.Application

class GeneratedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VibeCrashReporter.initialize(this)
        UiGuideController.initialize(this)
    }
}
