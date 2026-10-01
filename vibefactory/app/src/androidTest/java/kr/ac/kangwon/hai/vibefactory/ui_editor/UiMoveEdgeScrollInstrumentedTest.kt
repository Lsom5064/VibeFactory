package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real editor with an in-memory fixture; no production task/draft API is called. */
@RunWith(AndroidJUnit4::class)
class UiMoveEdgeScrollInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixtureTask = "move_preview_fixture"

    private fun withEditor(sourceWidth: Int = 80, rowChildren: Int = 1, block: (ActivityScenario<UiAnnotationEditorActivity>) -> Unit) {
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is UiAnnotationEditorActivity &&
                activity.intent.getStringExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID) == fixtureTask) {
                if (stage == Stage.PRE_ON_CREATE) {
                    ViewModelProvider(activity)[UiAnnotationViewModel::class.java].initialize(
                        fixtureTask, "rev_0001", UiLayoutSummaryDto(layout_name = "activity_main"),
                        AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent" android:layout_height="1800dp" android:orientation="vertical">
                            <Space android:layout_width="1dp" android:layout_height="100dp"/>
                            <TextView android:id="@+id/source" android:layout_width="${sourceWidth}dp" android:layout_height="48dp"
                                android:text="이동 UI" android:background="#BBDEFB"/>
                            <Space android:layout_width="1dp" android:layout_height="80dp"/>
                            ${rowXml(rowChildren)}
                        </LinearLayout>"""), ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
                }
                if (stage == Stage.CREATED) activity.findViewById<View>(R.id.uiAnnotationCanvas).let {
                    it.layoutParams = it.layoutParams.apply { width = (800 * activity.resources.displayMetrics.density).toInt() }
                }
            }
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        try {
            val intent = Intent(instrumentation.targetContext, UiAnnotationEditorActivity::class.java)
                .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, fixtureTask)
                .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, "rev_0001")
                .putExtra(UiAnnotationEditorActivity.EXTRA_APP_NAME, "이동 도구 검증")
            ActivityScenario.launch<UiAnnotationEditorActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250)
                try { block(scenario) } finally {
                    scenario.onActivity {
                        it.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
                        ViewModelProvider(it)[UiAnnotationViewModel::class.java].session = null
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
        }
    }

    private fun rowXml(count: Int): String {
        if (count == 1) return """<TextView android:id="@+id/other" android:layout_width="320dp" android:layout_height="80dp"
            android:text="기존 UI" android:background="#EEEEEE"/>"""
        val children = (0 until count).joinToString("") { index ->
            """<TextView android:id="@+id/row_$index" android:layout_width="0dp" android:layout_weight="1"
                android:layout_height="80dp" android:text="기존 UI ${index + 1}" android:background="${if (index % 2 == 0) "#EEEEEE" else "#DDDDDD"}"/>"""
        }
        return """<LinearLayout android:id="@+id/other" android:layout_width="320dp" android:layout_height="80dp"
            android:orientation="horizontal" android:background="#FFFFFF">$children</LinearLayout>"""
    }

    private fun overlay(activity: UiAnnotationEditorActivity) =
        activity.findViewById<FrameLayout>(R.id.uiAnnotationCanvas).let { canvas ->
            (0 until canvas.childCount).map(canvas::getChildAt).filterIsInstance<UiAnnotationOverlayView>().single()
        }

    private fun location(view: View) = IntArray(2).also(view::getLocationOnScreen)
    private fun send(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun selectSource(activity: UiAnnotationEditorActivity) {
        activity.findViewById<View>(R.id.btnUiAnnotationMoveTool).performClick()
        val source = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:source")
        val sourceLocation = location(source)
        val overlay = overlay(activity)
        val overlayLocation = location(overlay)
        val x = sourceLocation[0] - overlayLocation[0] + source.width / 2f
        val y = sourceLocation[1] - overlayLocation[1] + source.height / 2f
        send(overlay, MotionEvent.ACTION_DOWN, x, y)
        send(overlay, MotionEvent.ACTION_UP, x, y)
    }

    @Test fun fourEdgesKeepScrollingWithStationaryFingerAndCancelStopsImmediately() = withEditor { scenario ->
        scenario.onActivity(::selectSource)
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, 1 to 1).forEach { (dx, dy) ->
            var beforeX = 0
            var beforeY = 0
            var fingerX = 0f
            var fingerY = 0f
            scenario.onActivity { activity ->
                val h = activity.findViewById<HorizontalScrollView>(R.id.uiAnnotationHorizontalViewport)
                val v = activity.findViewById<ScrollView>(R.id.uiAnnotationVerticalViewport)
                h.scrollTo(400, 0); v.scrollTo(0, 500)
                beforeX = h.scrollX; beforeY = v.scrollY
                val overlay = overlay(activity)
                val hLocation = location(h)
                val vLocation = location(v)
                val oLocation = location(overlay)
                val screenX = hLocation[0] + when (dx) {
                    -1 -> h.paddingLeft + 2
                    1 -> h.width - h.paddingRight - 2
                    else -> h.width / 2
                }
                val screenY = vLocation[1] + when (dy) { -1 -> 2; 1 -> v.height - 2; else -> v.height / 2 }
                fingerX = screenX.toFloat()
                fingerY = screenY.toFloat()
                send(overlay, MotionEvent.ACTION_DOWN, (screenX - oLocation[0]).toFloat(), (screenY - oLocation[1]).toFloat())
            }
            SystemClock.sleep(250) // No ACTION_MOVE: the held pointer must continue scrolling.
            scenario.onActivity { activity ->
                val h = activity.findViewById<HorizontalScrollView>(R.id.uiAnnotationHorizontalViewport)
                val v = activity.findViewById<ScrollView>(R.id.uiAnnotationVerticalViewport)
                assertEquals("horizontal direction $dx,$dy", dx, (h.scrollX - beforeX).compareTo(0))
                assertEquals("vertical direction $dx,$dy", dy, (v.scrollY - beforeY).compareTo(0))
                @Suppress("UNCHECKED_CAST")
                val point = UiAnnotationEditorActivity::class.java.getDeclaredField("previewDestination").let {
                    it.isAccessible = true
                    it.get(activity) as Pair<Float, Float>
                }
                val session = ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session!!
                val density = activity.resources.displayMetrics.density
                val origin = location(overlay(activity))
                assertEquals(fingerX, origin[0] + point.first * session.referenceCanvasWidthDp!! * density, 1f)
                assertEquals(fingerY, origin[1] + point.second * session.referenceCanvasHeightDp!! * density, 1f)
                println("MOVE_EDGE $dx,$dy delta=${h.scrollX - beforeX},${v.scrollY - beforeY}")
                // Verify both cancellation and releasing into the description sheet stop scrolling.
                send(overlay(activity), if (dx == 1 && dy == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, 0f, 0f)
                beforeX = h.scrollX; beforeY = v.scrollY
            }
            SystemClock.sleep(100)
            scenario.onActivity { activity ->
                assertEquals(beforeX, activity.findViewById<View>(R.id.uiAnnotationHorizontalViewport).scrollX)
                assertEquals(beforeY, activity.findViewById<View>(R.id.uiAnnotationVerticalViewport).scrollY)
            }
        }
    }

    @Test fun leftAndRightPreviewAndCancellationWorkInTheEditor() = withEditor { scenario ->
        var originalWidth = 0
        scenario.onActivity { activity ->
            originalWidth = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:other").width
            selectSource(activity)
        }
        listOf(true, false).forEach { left ->
            scenario.onActivity { activity ->
                val overlay = overlay(activity)
                val row = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:other")
                val density = activity.resources.displayMetrics.density
                val x = (if (left) 40 else 280) * density + activity.findViewById<View>(R.id.uiAnnotationCanvas).paddingLeft
                val y = row.top + row.height / 2f + activity.findViewById<View>(R.id.uiAnnotationCanvas).paddingTop
                send(overlay, MotionEvent.ACTION_DOWN, x, y)
            }
            SystemClock.sleep(200)
            scenario.onActivity { activity ->
                val row = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:other")
                assertEquals(originalWidth * .5f, row.width.toFloat(), 2f)
                assertEquals(if (left) originalWidth * .5f else 0f, row.translationX, 2f)
                assertEquals(0f, row.translationY, 1f)
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val name = if (left) "move_left.png" else "move_right.png"
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            scenario.onActivity { activity -> send(overlay(activity), MotionEvent.ACTION_CANCEL, 0f, 0f) }
            SystemClock.sleep(100)
            scenario.onActivity { activity ->
                val row = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:other")
                assertEquals(originalWidth, row.width)
                assertEquals(0f, row.translationX, 1f)
            }
        }
    }

    @Test fun wideDottedBoxShrinksWithItsNeighbourAndGrowsBackInFreeSpace() = withEditor(sourceWidth = 320) { scenario ->
        var originalWidth = 0
        scenario.onActivity { activity ->
            originalWidth = activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>("id:source").width
            selectSource(activity)
        }
        listOf(true, false).forEach { left ->
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<View>(R.id.uiAnnotationCanvas)
                val row = canvas.findViewWithTag<View>("id:other")
                val x = (if (left) 80 else 240) * activity.resources.displayMetrics.density + canvas.paddingLeft
                send(overlay(activity), MotionEvent.ACTION_DOWN, x, row.top + row.height / 2f + canvas.paddingTop)
            }
            SystemClock.sleep(150)
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<View>(R.id.uiAnnotationCanvas)
                val row = canvas.findViewWithTag<View>("id:other")
                val fitted = UiAnnotationOverlayView::class.java.getDeclaredField("pendingMoveBounds").let {
                    it.isAccessible = true; it.get(overlay(activity)) as UiNormalizedRect
                }
                assertEquals(originalWidth / 2f, (fitted.right - fitted.left) * canvas.width, 2f)
                assertEquals(originalWidth / 2f, row.width.toFloat(), 2f)
                assertEquals(0f, row.translationY, 1f)
                assertEquals(originalWidth, canvas.findViewWithTag<View>("id:source").width)
            }
            val name = if (left) "move_responsive_left.png" else "move_responsive_right.png"
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                send(overlay(activity), MotionEvent.ACTION_MOVE, 160f * density, 440f * density)
            }
            SystemClock.sleep(100)
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<View>(R.id.uiAnnotationCanvas)
                val fitted = UiAnnotationOverlayView::class.java.getDeclaredField("pendingMoveBounds").let {
                    it.isAccessible = true; it.get(overlay(activity)) as UiNormalizedRect
                }
                assertEquals(originalWidth.toFloat(), (fitted.right - fitted.left) * canvas.width, 2f)
                assertEquals(originalWidth, canvas.findViewWithTag<View>("id:other").width)
                send(overlay(activity), MotionEvent.ACTION_CANCEL, 0f, 0f)
            }
            SystemClock.sleep(100)
        }
    }
    @Test fun existingTwoControlsAndMoveHaveEqualThirdsAtEveryInsertionSlot() = withEditor(sourceWidth = 320, rowChildren = 2) { scenario ->
        scenario.onActivity(::selectSource)
        listOf(20f, 130f, 300f).forEachIndexed { index, pointerDp ->
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<View>(R.id.uiAnnotationCanvas)
                val row = canvas.findViewWithTag<View>("id:other")
                val density = activity.resources.displayMetrics.density
                send(overlay(activity), MotionEvent.ACTION_DOWN, pointerDp * density + canvas.paddingLeft,
                    row.top + row.height / 2f + canvas.paddingTop)
            }
            SystemClock.sleep(150)
            fun checkSlots(activity: UiAnnotationEditorActivity) {
                val canvas = activity.findViewById<View>(R.id.uiAnnotationCanvas)
                val row = canvas.findViewWithTag<View>("id:other")
                val fitted = UiAnnotationOverlayView::class.java.getDeclaredField("pendingMoveBounds").let {
                    it.isAccessible = true; it.get(overlay(activity)) as UiNormalizedRect
                }
                val width = row.width / 3f
                assertEquals(width, (fitted.right - fitted.left) * canvas.width, 1f)
                assertEquals(row.left + canvas.paddingLeft + width * index, fitted.left * canvas.width, 1f)
                repeat(2) { member -> assertEquals(width, canvas.findViewWithTag<View>("id:row_$member").width.toFloat(), 1f) }
                assertEquals(row.width, canvas.findViewWithTag<View>("id:source").width)
            }
            scenario.onActivity(::checkSlots)
            if (index == 1) {
                scenario.recreate()
                instrumentation.waitForIdleSync()
                SystemClock.sleep(200)
                scenario.onActivity(::checkSlots)
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "equal_row_$index.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            scenario.onActivity { activity -> send(overlay(activity), MotionEvent.ACTION_CANCEL, 0f, 0f) }
            SystemClock.sleep(100)
        }
    }

}
