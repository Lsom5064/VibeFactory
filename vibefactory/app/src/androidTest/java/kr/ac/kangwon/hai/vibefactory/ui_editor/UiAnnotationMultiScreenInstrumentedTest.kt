package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.TaskLogDetailActivity
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic editor drafts and an empty log activity only; no participant Task, preference or server writes. */
@RunWith(AndroidJUnit4::class)
class UiAnnotationMultiScreenInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val xml = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
        <TextView android:layout_width="match_parent" android:layout_height="60dp" android:text="상단 UI" />
        <Space android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" />
        <Button android:id="@+id/bottom" android:layout_width="match_parent" android:layout_height="52dp" android:text="맨 아래 UI" />
        </LinearLayout>"""

    @Test fun everyScreenSurvivesCacheEvictionAndReloadWithSketchAndDescriptionIntact() = runBlocking {
        val task = "multiscreen_test_${UUID.randomUUID()}"
        val store = UiAnnotationDraftStore(context, Gson())
        val model = UiAnnotationViewModel()
        val document = AndroidXmlDocument.parse(xml)
        val target = UiAnnotationTarget("id:bottom", "@+id/bottom", "0.2", "Button", "맨 아래 UI", "",
            UiNormalizedRect(0f, .8f, 1f, 1f), "", "")
        repeat(7) { index ->
            model.annotationCounts()
            val session = model.initialize(task, "rev_0001", UiLayoutSummaryDto(layout_name = "page_$index"),
                document, ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
            session.annotations += if (index == 0) UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                instruction = "오늘 날짜로 돌아갈 수 있는 버튼", addition = UiAdditionSpec(target.bounds, -1,
                    listOf(UiSketchStroke(points = listOf(UiSketchPoint(.1f, .2f), UiSketchPoint(.8f, .7f)), color = -16777216, width = .009f))))
                else UiAnnotation(action = UiAnnotationAction.BEHAVIOR, target = target, instruction = "화면 $index 변경")
            if (index == 0) {
                val image = store.persistSketch(session, session.annotations.single(), 2f)
                session.images += image
                session.annotations[0] = session.annotations[0].copy(imageIds = listOf(image.imageId), sketchImageId = image.imageId)
            }
            model.persistRecord(store.recordFor(session), index.toLong() + 1, store::save)
        }
        val records = store.loadAll(task, "rev_0001")
        assertEquals(7, records.size)
        assertTrue(model.cachedSessions().size <= 4)
        val calendar = records.single { it.layoutName == "page_0" }
        assertEquals("오늘 날짜로 돌아갈 수 있는 버튼", calendar.annotations.single().instruction)
        assertEquals(1, calendar.annotations.single().addition!!.strokes.size)
        assertEquals(calendar.images.single().imageId, calendar.annotations.single().sketchImageId)
        assertTrue(java.io.File(calendar.images.single().localPath).isFile)
        assertTrue(store.loadAll(task, "rev_0002").isEmpty())
    }

    @Test fun bottomControlCanScrollAboveToolbarAndBeSelectedWithToolHelpVisible() {
        ActivityScenario.launch<TaskLogDetailActivity>(Intent(context, TaskLogDetailActivity::class.java)).use { scenario ->
            lateinit var scroll: ScrollView
            lateinit var canvas: FrameLayout
            lateinit var toolbar: View
            lateinit var bottom: View
            lateinit var overlay: UiAnnotationOverlayView
            var selected = false
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.setContentView(R.layout.activity_ui_annotation_editor)
                activity.findViewById<View>(R.id.uiAnnotationStateOverlay).visibility = View.GONE
                activity.findViewById<View>(R.id.uiAnnotationInstructionBar).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.switchUiAnnotationAlignment).visibility = View.VISIBLE
                activity.findViewById<TextView>(R.id.uiAnnotationInstruction).text = "이동 또는 추가할 UI를 선택하세요. 안내가 열린 상태에서도 아래까지 스크롤할 수 있어요."
                scroll = activity.findViewById(R.id.uiAnnotationVerticalViewport)
                canvas = activity.findViewById(R.id.uiAnnotationCanvas)
                toolbar = activity.findViewById(R.id.uiAnnotationFloatingToolbar)
                val document = AndroidXmlDocument.parse(xml)
                val rendered = UiPreviewRenderer(activity).render(document, ResolvedUiResources.EMPTY, canvas)
                bottom = rendered.nodeViews.getValue("id:bottom")
                overlay = UiAnnotationOverlayView(activity)
                canvas.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                canvas.post {
                    overlay.setReferenceSize(canvas.width, canvas.height)
                    overlay.enableTargetSelection(true)
                    overlay.targetTapListener = { x, y ->
                        val location = IntArray(2); val origin = IntArray(2)
                        bottom.getLocationOnScreen(location); canvas.getLocationOnScreen(origin)
                        selected = x * canvas.width in (location[0] - origin[0]).toFloat()..(location[0] - origin[0] + bottom.width).toFloat() &&
                            y * canvas.height in (location[1] - origin[1]).toFloat()..(location[1] - origin[1] + bottom.height).toFloat()
                    }
                    scroll.scrollTo(0, canvas.height + scroll.paddingBottom)
                    ready.countDown()
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            lateinit var tap: Pair<Float, Float>
            instrumentation.runOnMainSync {
                assertTrue(scroll.isVerticalScrollBarEnabled)
                assertFalse(scroll.isScrollbarFadingEnabled)
                assertTrue(scroll.scrollY > 0)
                val box = Rect(); val tool = Rect()
                assertTrue(bottom.getGlobalVisibleRect(box))
                toolbar.getGlobalVisibleRect(tool)
                assertTrue("Bottom UI must be reachable above the floating tools", box.bottom <= tool.top)
                tap = box.exactCenterX() to box.exactCenterY()
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tap.first, tap.second, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            instrumentation.waitForIdleSync()
            assertTrue("Tool must select the scrolled bottom UI", selected)
        }
    }
}
