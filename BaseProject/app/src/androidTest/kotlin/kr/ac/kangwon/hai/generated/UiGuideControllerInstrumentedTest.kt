package kr.ac.kangwon.hai.generated

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiGuideControllerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearGuideState() {
        context.getSharedPreferences("vibe_ui_guide", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUpGuideState() {
        context.getSharedPreferences("vibe_ui_guide", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun firstRunRotationCompletionAndReplayFollowGuideContract() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("앱 제목")).check(matches(isDisplayed()))
            onView(withText("다음")).perform(click())
            onView(withText("화면 안내")).check(matches(isDisplayed()))

            scenario.recreate()
            assertDisplayedWithin(withText("화면 안내"), 1_000L)
            onView(withText("닫기")).perform(click())
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(300L)
            onView(withText("앱 제목")).check(doesNotExist())
            onView(withContentDescription("사용법 다시 보기")).perform(click())
            assertDisplayedWithin(withText("앱 제목"), 1_000L)
            onView(withText("건너뛰기")).perform(click())
        }
    }

    private fun assertDisplayedWithin(matcher: Matcher<android.view.View>, timeoutMillis: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(25L)
            }
        }
        throw AssertionError("Expected view was not displayed within ${timeoutMillis}ms", lastFailure)
    }
}
