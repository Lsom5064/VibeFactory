package kr.ac.kangwon.hai.generated

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiGuideComponentReplayInstrumentedTest {
    @Test fun replayIncludesEarlyRegisteredControlsAndIgnoresDetachedRoots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("vibe_ui_guide", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val catalogField = UiGuideController::class.java.getDeclaredField("catalog").apply { isAccessible = true }
        var originalCatalog: Any? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withText("건너뛰기")).perform(click())
                originalCatalog = catalogField.get(UiGuideController)
                val version = originalCatalog!!.javaClass.getDeclaredField("guideVersion")
                    .apply { isAccessible = true }.get(originalCatalog) as String
                val overview = fixture("GuideElement", "action_bar_root", "전체 화면", "화면 전체 설명", 1)
                val input = fixture("GuideElement", "templateTitle", "메모 입력", "입력할 내용을 작성하세요.", 1)
                val save = fixture("GuideElement", "templateBody", "메모 저장", "입력한 메모를 저장하세요.", 2)
                val screen = fixture("GuideLayout", "activity_main", "layout", "메인 화면", "screen",
                    MainActivity::class.java.name, listOf(overview))
                val component = fixture("GuideLayout", "memo_header", "layout", "메모 작성", "component", "",
                    listOf(input, save))
                lateinit var componentRoot: View
                scenario.onActivity { activity ->
                    catalogField.set(UiGuideController, fixture("GuideCatalog", version, listOf(screen, component)))
                    componentRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val bounds = Rect(componentRoot.left, componentRoot.top, componentRoot.right, componentRoot.bottom)
                    componentRoot.layout(0, 0, 0, 0)
                    repeat(2) { UiGuideController.show(activity, "memo_header", componentRoot) }
                    componentRoot.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
                onView(withContentDescription("사용법 다시 보기")).perform(click())
                onView(withText("메모 입력")).check(matches(isDisplayed()))
                onView(withText("전체 화면")).check(doesNotExist())
                onView(withText("다음")).perform(click())
                onView(withText("메모 저장")).check(matches(isDisplayed()))
                // Two registrations must not create duplicate steps or append the screen container.
                onView(withText("완료")).perform(click())

                scenario.onActivity { activity ->
                    activity.findViewById<ViewGroup>(android.R.id.content).removeView(componentRoot)
                }
                onView(withContentDescription("사용법 다시 보기")).perform(click())
                onView(withText("전체 화면")).check(matches(isDisplayed()))
                onView(withText("메모 입력")).check(doesNotExist())
                onView(withText("완료")).perform(click())
            }
        } finally {
            originalCatalog?.let { catalogField.set(UiGuideController, it) }
            preferences.edit().clear().commit()
        }
    }

    // Supply catalog metadata without adding test-only layouts to generated release apps.
    private fun fixture(name: String, vararg args: Any): Any =
        Class.forName(UiGuideController::class.java.name + "\$" + name)
            .declaredConstructors.single { it.parameterCount == args.size }
            .apply { isAccessible = true }.newInstance(*args)
}
