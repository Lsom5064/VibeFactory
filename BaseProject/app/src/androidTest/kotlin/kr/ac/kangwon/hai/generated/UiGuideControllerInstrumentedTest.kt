package kr.ac.kangwon.hai.generated

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.junit.Assert.assertFalse
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
    fun hidingHelpSurvivesRecreationAndRestoresFromExplicitEntryPoint() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertDisplayedWithin(withText("도움말 버튼 숨기기"), 2_000L)
            onView(withText("도움말 버튼 숨기기")).perform(click())
            assertTrue(context.getSharedPreferences("vibe_ui_guide", Context.MODE_PRIVATE)
                .getBoolean("help_button_hidden", false))
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.window.decorView.findViewWithContentDescription("사용법 다시 보기").isShown)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.window.decorView.findViewWithContentDescription("사용법 다시 보기").isShown)
                activity.startActivity(android.content.Intent(activity, UiGuideRestoreActivity::class.java))
            }
            assertDisplayedWithin(withContentDescription("사용법 다시 보기"), 2_000L)
            assertFalse(context.getSharedPreferences("vibe_ui_guide", Context.MODE_PRIVATE)
                .getBoolean("help_button_hidden", true))
        }
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
            onView(withText("사용법")).check(doesNotExist())
            scenario.onActivity { activity ->
                val helpTab = activity.window.decorView.findViewWithContentDescription(
                    "사용법 다시 보기",
                )
                val minimumTouchSize = (48 * activity.resources.displayMetrics.density).toInt()
                assertFalse("Help replay control must be icon-only", helpTab is android.widget.TextView)
                assertTrue("Help tab must retain a 48dp touch width", helpTab.width >= minimumTouchSize)
                assertTrue("Help tab must retain a 48dp touch height", helpTab.height >= minimumTouchSize)
            }
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

    @Test
    fun helpTabMovesAwayFromAnOverlappingInteractiveControl() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("건너뛰기")).perform(click())
            assertDisplayedWithin(withContentDescription("사용법 다시 보기"), 1_000L)

            var blocker: Button? = null
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                val helpTab = content.findViewWithContentDescription("사용법 다시 보기")
                blocker = Button(activity).apply {
                    text = "겹침 확인"
                    isClickable = true
                    x = (helpTab.x - helpTab.width / 2f).coerceAtLeast(0f)
                    y = (helpTab.y - helpTab.height / 2f).coerceAtLeast(0f)
                }
                content.addView(
                    blocker,
                    FrameLayout.LayoutParams(helpTab.width * 2, helpTab.height * 2),
                )
            }

            SystemClock.sleep(800L)
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                val helpTab = content.findViewWithContentDescription("사용법 다시 보기")
                val helpBounds = Rect()
                val blockerBounds = Rect()
                helpTab.getGlobalVisibleRect(helpBounds)
                blocker?.getGlobalVisibleRect(blockerBounds)
                assertFalse(
                    "Help tab must move away from an overlapping button",
                    Rect.intersects(helpBounds, blockerBounds),
                )
                blocker?.let(content::removeView)
            }
        }
    }

    @Test
    fun helpTabMovesOnlyAfterLongPressAndRestoresItsEdge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("건너뛰기")).perform(click())
            assertDisplayedWithin(withContentDescription("사용법 다시 보기"), 1_000L)

            var initialBounds = Rect()
            var screenWidth = 0
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                content.findViewWithContentDescription("사용법 다시 보기")
                    .getGlobalVisibleRect(initialBounds)
                screenWidth = content.width
            }
            val targetX = if (initialBounds.centerX() < screenWidth / 2) {
                screenWidth - initialBounds.width() / 2f
            } else {
                initialBounds.width() / 2f
            }

            dragTab(
                startX = initialBounds.exactCenterX(),
                startY = initialBounds.exactCenterY(),
                targetX = targetX,
                targetY = initialBounds.exactCenterY(),
                holdBeforeDragMillis = 0L,
            )
            SystemClock.sleep(250L)

            var boundsAfterImmediateDrag = Rect()
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithContentDescription("사용법 다시 보기")
                    .getGlobalVisibleRect(boundsAfterImmediateDrag)
            }
            assertTrue(
                "The help tab must ignore movement before a long press",
                kotlin.math.abs(boundsAfterImmediateDrag.centerX() - initialBounds.centerX()) <= 2,
            )

            dragTab(
                startX = boundsAfterImmediateDrag.exactCenterX(),
                startY = boundsAfterImmediateDrag.exactCenterY(),
                targetX = targetX,
                targetY = boundsAfterImmediateDrag.exactCenterY(),
                holdBeforeDragMillis = ViewConfiguration.getLongPressTimeout().toLong() + 150L,
            )
            SystemClock.sleep(400L)

            var movedBounds = Rect()
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithContentDescription("사용법 다시 보기")
                    .getGlobalVisibleRect(movedBounds)
            }
            val initiallyOnLeft = initialBounds.centerX() < screenWidth / 2
            assertTrue(
                "The help tab must move to the opposite edge after a long press",
                (movedBounds.centerX() < screenWidth / 2) != initiallyOnLeft,
            )

            scenario.recreate()
            assertDisplayedWithin(withContentDescription("사용법 다시 보기"), 1_000L)
            scenario.onActivity { activity ->
                val restoredBounds = Rect()
                activity.window.decorView.findViewWithContentDescription("사용법 다시 보기")
                    .getGlobalVisibleRect(restoredBounds)
                assertTrue(
                    "The long-press drag position must survive Activity recreation",
                    (restoredBounds.centerX() < screenWidth / 2) != initiallyOnLeft,
                )
            }
        }
    }

    private fun dragTab(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        holdBeforeDragMillis: Long,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0),
        )
        if (holdBeforeDragMillis > 0L) SystemClock.sleep(holdBeforeDragMillis)
        val moveTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, targetX, targetY, 0),
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                targetX,
                targetY,
                0,
            ),
        )
    }

    private fun View.findViewWithContentDescription(description: String): View {
        fun findIn(view: View): View? {
            if (view.contentDescription == description) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findIn(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return findIn(this)
            ?: throw AssertionError("View with content description '$description' was not found")
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
