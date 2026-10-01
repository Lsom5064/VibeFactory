package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real touch/save/reopen flow with an in-memory task, without server writes. */
@RunWith(AndroidJUnit4::class)
class UiMoveRowInsertionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val task = "move_row_insertion_fixture"
    private fun session(activity: UiAnnotationEditorActivity) = ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session!!
    private fun canvas(activity: UiAnnotationEditorActivity) = activity.findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
    private fun preview(activity: UiAnnotationEditorActivity) = field(activity, "movePreview") as UiMovePreviewLayout
    private fun field(activity: UiAnnotationEditorActivity, name: String): Any? =
        UiAnnotationEditorActivity::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(activity)
    private fun cancelSave(activity: UiAnnotationEditorActivity) { (field(activity, "remoteSaveJob") as? kotlinx.coroutines.Job)?.cancel() }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(150) }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }

    @Test fun eightControlsFitByTouchingVisibleGapsAndSurviveUndoRedoAndRecreation() {
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is UiAnnotationEditorActivity && stage == Stage.PRE_ON_CREATE &&
                activity.intent.getStringExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID) == task &&
                ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session == null) {
                val sources = (0..6).joinToString("\n") { index -> """
                    <Button android:id="@+id/source$index" android:layout_width="44dp" android:layout_height="48dp"
                        android:minWidth="96dp" android:padding="0dp" android:text="${index + 1}"/>
                """ }
                ViewModelProvider(activity)[UiAnnotationViewModel::class.java].initialize(task, "rev_0001",
                    UiLayoutSummaryDto(layout_name = "activity_main"), AndroidXmlDocument.parse("""
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent" android:layout_height="640dp" android:orientation="vertical">
                        <TextView android:layout_width="320dp" android:layout_height="48dp" android:text="같은 줄 삽입 검증"/>
                        <LinearLayout android:layout_width="320dp" android:layout_height="48dp" android:orientation="horizontal">
                            $sources
                        </LinearLayout>
                        <Button android:id="@+id/row" android:layout_width="320dp" android:layout_height="64dp"
                            android:minWidth="200dp" android:padding="0dp" android:text="기존 UI"/>
                    </LinearLayout>"""), ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
            }
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch<UiAnnotationEditorActivity>(Intent(instrumentation.targetContext, UiAnnotationEditorActivity::class.java)
                .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, task)
                .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, "rev_0001")
                .putExtra(UiAnnotationEditorActivity.EXTRA_APP_NAME, "이동 줄 삽입 검증")).use { scenario ->
                settle()
                var rowWidth = 0
                var rowTop = 0f
                var sourceBounds = emptyList<Rect>()
                scenario.onActivity { activity ->
                    val canvas = canvas(activity)
                    rowWidth = canvas.findViewWithTag<View>("id:row").width
                    rowTop = bounds(canvas, canvas.findViewWithTag("id:row")).top.toFloat()
                    sourceBounds = (0..6).map { bounds(canvas, canvas.findViewWithTag("id:source$it")) }
                }
                repeat(7) { index ->
                    scenario.onActivity { activity ->
                        val canvas = canvas(activity)
                        val overlay = (0 until canvas.childCount).map(canvas::getChildAt).filterIsInstance<UiAnnotationOverlayView>().single()
                        val row = bounds(canvas, canvas.findViewWithTag("id:row"))
                        // First use the row edge, then the visible gap between the last dashed
                        // box and the resized real control. Original XML centres are irrelevant.
                        val x = row.left + if (index == 0) 1f else 0f
                        val y = row.centerY().toFloat()
                        activity.findViewById<View>(R.id.btnUiAnnotationMoveTool).performClick()
                        val source = sourceBounds[index]
                        touch(overlay, MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY())
                        touch(overlay, MotionEvent.ACTION_UP, source.exactCenterX(), source.exactCenterY())
                        touch(overlay, MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY())
                        touch(overlay, MotionEvent.ACTION_MOVE, x, y)
                        touch(overlay, MotionEvent.ACTION_UP, x, y)
                        assertNull(field(activity, "instructionDialog"))
                        activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove).performClick()
                        val dialog = field(activity, "instructionDialog") as Dialog
                        dialog.findViewById<View>(R.id.btnSaveUiAnnotationInstruction).performClick()
                        cancelSave(activity)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val preview = preview(activity)
                        assertEquals(index + 1, session(activity).annotations.size)
                        assertTrue(session(activity).annotations.all { it.equalWidthRow == true })
                        val widths = preview.destinationBounds.values.map { (it.right - it.left) * preview.width }
                        widths.forEach { assertEquals(rowWidth / (index + 2f), it, 1f) }
                        val row = bounds(canvas(activity), canvas(activity).findViewWithTag("id:row"))
                        assertEquals(rowWidth / (index + 2f), row.width().toFloat(), 1f)
                        assertEquals(rowTop, row.top.toFloat(), 1f)
                        sourceBounds.forEachIndexed { i, original ->
                            assertEquals(original, bounds(canvas(activity), canvas(activity).findViewWithTag("id:source$i")))
                        }
                    }
                }
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(instrumentation.targetContext.getExternalFilesDir(null), "move_row_eight_controls.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnUiAnnotationUndo).performClick()
                    cancelSave(activity)
                }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(6, session(activity).annotations.size)
                    activity.findViewById<View>(R.id.btnUiAnnotationRedo).performClick()
                    cancelSave(activity)
                }
                settle()
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    assertEquals(7, session(activity).annotations.size)
                    assertEquals(rowWidth / 8f, canvas(activity).findViewWithTag<View>("id:row").width.toFloat(), 1f)
                    assertTrue(session(activity).annotations.all { it.equalWidthRow == true })
                    val originalOrder = session(activity).annotations.map { it.annotationId }
                    assertEquals(originalOrder, preview(activity).destinationBounds.entries.sortedBy { it.value.left }.map { it.key })
                    assertFalse(session(activity).document.hasChanges)
                    cancelSave(activity)
                    ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session = null
                }
            }
        } finally {
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
        }
    }

    private fun bounds(canvas: FrameLayout, view: View): Rect {
        // offsetDescendantRectToMyCoords omits the preview's translationX/Y.
        var x = 0f
        var y = 0f
        var current = view
        while (current !== canvas) {
            val parent = current.parent as View
            x += current.left + current.translationX - parent.scrollX
            y += current.top + current.translationY - parent.scrollY
            current = parent
        }
        val left = kotlin.math.round(x).toInt()
        val top = kotlin.math.round(y).toInt()
        return Rect(left, top, left + view.width, top + view.height)
    }
}
