package kr.ac.kangwon.hai.generated

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
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
            onView(withText("완료")).perform(click())
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(300L)
            onView(withText("앱 제목")).check(doesNotExist())
            var originalScrollY = 0
            var scrollDimensions = ""
            val scrollReady = CountDownLatch(1)
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val scroll = content.getChildAt(0) as NestedScrollView
                val child = scroll.getChildAt(0)
                scroll.removeView(child)
                val longContent = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        child,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        View(activity),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            maxOf(scroll.height * 2, 2_400),
                        ),
                    )
                }
                scroll.addView(
                    longContent,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                scroll.postDelayed({
                    scroll.scrollTo(0, scroll.height / 2)
                    originalScrollY = scroll.scrollY
                    scrollDimensions = "scroll=${scroll.height}, content=${longContent.height}"
                    scrollReady.countDown()
                }, 250L)
            }
            assertTrue("Test screen did not finish laying out", scrollReady.await(1, TimeUnit.SECONDS))
            assertTrue("Test screen must be scrollable ($scrollDimensions)", originalScrollY > 0)
            onView(withContentDescription("사용법 다시 보기")).perform(click())
            assertDisplayedWithin(withText("앱 제목"), 1_000L)
            onView(withText("다음")).perform(click())
            onView(withText("완료")).perform(click())
            SystemClock.sleep(150L)
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val scroll = content.getChildAt(0) as NestedScrollView
                assertTrue(
                    "Guide must restore the user's scroll position",
                    kotlin.math.abs(scroll.scrollY - originalScrollY) <= 2,
                )
            }
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
