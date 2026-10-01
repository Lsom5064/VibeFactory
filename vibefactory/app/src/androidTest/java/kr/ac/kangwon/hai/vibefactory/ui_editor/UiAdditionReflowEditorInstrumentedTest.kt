package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.graphics.Bitmap
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
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real editor gestures using local fixtures; no operational task or server draft is modified. */
@RunWith(AndroidJUnit4::class)
class UiAdditionReflowEditorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val task = "addition_reflow_fixture"

    private fun session(activity: UiAnnotationEditorActivity) = ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session!!
    private fun canvas(activity: UiAnnotationEditorActivity) = activity.findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
    private fun overlay(activity: UiAnnotationEditorActivity) = canvas(activity).let { canvas ->
        (0 until canvas.childCount).map(canvas::getChildAt).filterIsInstance<UiAnnotationOverlayView>().single()
    }
    private fun withEditor(minimumWidthDp: Int = 0, block: (ActivityScenario<UiAnnotationEditorActivity>) -> Unit) {
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is UiAnnotationEditorActivity && stage == Stage.PRE_ON_CREATE &&
                activity.intent.getStringExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID) == task &&
                ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session == null) {
                ViewModelProvider(activity)[UiAnnotationViewModel::class.java].initialize(task, "rev_0001",
                    UiLayoutSummaryDto(layout_name = "activity_main"), AndroidXmlDocument.parse("""
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent" android:layout_height="800dp" android:orientation="vertical">
                        <TextView android:layout_width="300dp" android:layout_height="80dp" android:text="추가 도구 검증"/>
                        <LinearLayout android:id="@+id/row" android:layout_width="300dp" android:layout_height="80dp" android:orientation="horizontal">
                            <TextView android:id="@+id/a" android:minWidth="${minimumWidthDp}dp" android:layout_width="0dp" android:layout_weight="1" android:layout_height="80dp" android:text="기존 UI 1" android:background="#EEEEEE"/>
                            <TextView android:id="@+id/b" android:minWidth="${minimumWidthDp}dp" android:layout_width="0dp" android:layout_weight="1" android:layout_height="80dp" android:text="기존 UI 2" android:background="#DDDDDD"/>
                        </LinearLayout>
                        <TextView android:id="@+id/next" android:layout_width="300dp" android:layout_height="60dp" android:text="다음 행" android:background="#FFF3E0"/>
                    </LinearLayout>"""), ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
            }
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch<UiAnnotationEditorActivity>(Intent(instrumentation.targetContext, UiAnnotationEditorActivity::class.java)
                .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, task)
                .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, "rev_0001")
                .putExtra(UiAnnotationEditorActivity.EXTRA_APP_NAME, "추가 영역 재배치 검증")).use { scenario ->
                settle()
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

    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(180) }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun start(activity: UiAnnotationEditorActivity) {
        activity.findViewById<View>(R.id.btnUiAnnotationAddTool).performClick()
        val canvas = canvas(activity)
        val row = canvas.findViewWithTag<View>("id:row")
        val x = canvas.paddingLeft + row.width / 2f
        val y = canvas.paddingTop + row.top + row.height / 2f
        touch(overlay(activity), MotionEvent.ACTION_DOWN, x, y)
        touch(overlay(activity), MotionEvent.ACTION_UP, x, y)
        assertNotNull(session(activity).pendingAddition)
    }
    private fun drag(activity: UiAnnotationEditorActivity, dx: Float, dy: Float, corner: Boolean = false) {
        val session = session(activity)
        val density = activity.resources.displayMetrics.density
        val b = session.pendingAddition!!.addition!!.bounds
        val w = session.referenceCanvasWidthDp!! * density
        val h = session.referenceCanvasHeightDp!! * density
        val x = (if (corner) b.right else (b.left + b.right) / 2f) * w
        val y = (if (corner) b.bottom else (b.top + b.bottom) / 2f) * h
        touch(overlay(activity), MotionEvent.ACTION_DOWN, x, y)
        touch(overlay(activity), MotionEvent.ACTION_MOVE, x + dx * density, y + dy * density)
        touch(overlay(activity), MotionEvent.ACTION_UP, x + dx * density, y + dy * density)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "addition_reflow_$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun cancelServerSave(activity: UiAnnotationEditorActivity) {
        // Undo/redo also persist real drafts. Cancel the delayed job in this same main-thread
        // turn, before it can contact the production API for this in-memory fixture.
        UiAnnotationEditorActivity::class.java.getDeclaredField("remoteSaveJob").also { it.isAccessible = true }
            .get(activity).let { (it as? kotlinx.coroutines.Job)?.cancel() }
    }

    @Test fun repeatedMoveAdjustmentsWaitForBannerConfirmationAndSurviveRecreation() = withEditor { scenario ->
        var finalPosition: Pair<Float, Float>? = null
        scenario.onActivity { it.findViewById<View>(R.id.btnUiAnnotationMoveTool).performClick() }
        settle()
        scenario.onActivity { activity ->
            val banner = activity.findViewById<View>(R.id.uiAnnotationInstructionBar)
            val workspace = activity.findViewById<View>(R.id.uiAnnotationWorkspace)
            val b = IntArray(2).also(banner::getLocationOnScreen)
            val w = IntArray(2).also(workspace::getLocationOnScreen)
            assertTrue("Instruction must reserve space above the preview", b[1] + banner.height <= w[1])
            val root = canvas(activity)
            val source = root.findViewWithTag<View>("id:a")
            val bounds = android.graphics.Rect().also(source::getDrawingRect)
            root.offsetDescendantRectToMyCoords(source, bounds)
            val targetX = root.width * .6f
            val targetY = root.height * .5f
            val view = overlay(activity)
            touch(view, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
            touch(view, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
            val confirm = activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove)
            assertEquals(View.VISIBLE, confirm.visibility)
            assertFalse(confirm.isEnabled)
            touch(view, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
            touch(view, MotionEvent.ACTION_MOVE, targetX, targetY)
            touch(view, MotionEvent.ACTION_UP, targetX, targetY)
            assertTrue(session(activity).annotations.isEmpty())
            val dialogField = UiAnnotationEditorActivity::class.java.getDeclaredField("instructionDialog")
                .also { it.isAccessible = true }
            assertNull("Releasing a drag must not open the sheet", dialogField.get(activity))
            assertTrue(confirm.isEnabled)
            touch(view, MotionEvent.ACTION_DOWN, targetX, targetY)
            touch(view, MotionEvent.ACTION_MOVE, targetX + 20f, targetY + 25f)
            touch(view, MotionEvent.ACTION_UP, targetX + 20f, targetY + 25f)
            assertNull("Repeated adjustments must keep the preview editable", dialogField.get(activity))
            assertTrue(session(activity).annotations.isEmpty())
            @Suppress("UNCHECKED_CAST")
            finalPosition = UiAnnotationEditorActivity::class.java.getDeclaredField("previewDestination")
                .also { it.isAccessible = true }.get(activity) as Pair<Float, Float>
        }
        scenario.recreate()
        settle()
        scenario.onActivity { activity ->
            val confirm = activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove)
            assertEquals(View.VISIBLE, confirm.visibility)
            assertTrue(confirm.isEnabled)
            assertNull(UiAnnotationEditorActivity::class.java.getDeclaredField("instructionDialog")
                .also { it.isAccessible = true }.get(activity))
            confirm.performClick()
            val dialog = UiAnnotationEditorActivity::class.java.getDeclaredField("instructionDialog")
                .also { it.isAccessible = true }.get(activity) as com.google.android.material.bottomsheet.BottomSheetDialog
            assertTrue(dialog.isShowing)
            assertTrue(session(activity).annotations.isEmpty())
            dialog.findViewById<View>(R.id.btnSaveUiAnnotationInstruction)!!.performClick()
            cancelServerSave(activity)
            assertEquals(1, session(activity).annotations.size)
            assertEquals(UiAnnotationAction.MOVE, session(activity).annotations.single().action)
            assertEquals(finalPosition!!.first, session(activity).annotations.single().destinationX!!, .001f)
            assertEquals(finalPosition!!.second, session(activity).annotations.single().destinationY!!, .001f)
            assertEquals(View.GONE, confirm.visibility)
        }
    }

    @Test fun cancellingMovePlacementHidesConfirmationAndRestoresThePreview() = withEditor { scenario ->
        scenario.onActivity { activity ->
            val root = canvas(activity)
            val source = root.findViewWithTag<View>("id:a")
            val originalWidth = source.width
            val bounds = android.graphics.Rect().also(source::getDrawingRect)
            root.offsetDescendantRectToMyCoords(source, bounds)
            activity.findViewById<View>(R.id.btnUiAnnotationMoveTool).performClick()
            touch(overlay(activity), MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
            touch(overlay(activity), MotionEvent.ACTION_MOVE, root.width * .7f, root.height * .6f)
            touch(overlay(activity), MotionEvent.ACTION_UP, root.width * .7f, root.height * .6f)
            assertTrue(activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove).isEnabled)
            touch(overlay(activity), MotionEvent.ACTION_DOWN, root.width * .7f, root.height * .6f)
            touch(overlay(activity), MotionEvent.ACTION_CANCEL, root.width * .7f, root.height * .6f)
            assertFalse(activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove).isEnabled)
            activity.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnUiAnnotationConfirmMove).visibility)
            assertTrue(session(activity).annotations.isEmpty())
            assertEquals(originalWidth, source.width)
            assertNull(UiAnnotationEditorActivity::class.java.getDeclaredField("instructionDialog")
                .also { it.isAccessible = true }.get(activity))
        }
    }

    @Test fun tallAdditionDraggedLeftAndRightKeepsItsWidthAndMakesSpaceBesideBothRows() = withEditor(180) { scenario ->
        var originalWidth = 0
        var savedBounds: UiNormalizedRect? = null
        scenario.onActivity { activity ->
            originalWidth = canvas(activity).findViewWithTag<View>("id:a").width
            start(activity)
            val state = session(activity)
            val b = state.pendingAddition!!.addition!!.bounds
            val currentWidthDp = (b.right - b.left) * state.referenceCanvasWidthDp!!
            drag(activity, 120f - currentWidthDp, 0f, corner = true)
            overlay(activity).showAdditionPlacement(state.pendingAddition)
        }
        settle()
        listOf(0f, 180f).forEach { leftDp ->
            scenario.onActivity { activity ->
                val state = session(activity)
                val b = state.pendingAddition!!.addition!!.bounds
                val d = activity.resources.displayMetrics.density
                val canvas = canvas(activity)
                val row = canvas.findViewWithTag<View>("id:row")
                val left = canvas.paddingLeft / d + leftDp
                val top = (canvas.paddingTop + row.top) / d
                drag(activity, left - b.left * state.referenceCanvasWidthDp!!,
                    top - b.top * state.referenceCanvasHeightDp!!)
            }
            settle()
            scenario.onActivity { activity ->
                val d = activity.resources.displayMetrics.density
                val state = session(activity)
                val b = state.pendingAddition!!.addition!!.bounds
                assertEquals(120f, (b.right - b.left) * state.referenceCanvasWidthDp!!, 1f)
                assertEquals(160f, (b.bottom - b.top) * state.referenceCanvasHeightDp!!, 1f)
                listOf("id:a", "id:b").forEach { id ->
                    val view = canvas(activity).findViewWithTag<View>(id)
                    assertEquals(90f * d, view.width.toFloat(), 2f)
                    assertEquals(0f, view.translationY, 1f)
                }
                val next = canvas(activity).findViewWithTag<View>("id:next")
                assertEquals(180f * d, next.width.toFloat(), 2f)
                assertEquals(0f, next.translationY, 1f)
                assertEquals(if (leftDp == 0f) 120f * d else 0f,
                    canvas(activity).findViewWithTag<View>("id:a").translationX, 2f)
                savedBounds = b
            }
            screenshot(if (leftDp == 0f) "horizontal_left" else "horizontal_right")
        }
        scenario.recreate()
        settle()
        scenario.onActivity { activity ->
            assertEquals(savedBounds, session(activity).pendingAddition!!.addition!!.bounds)
            val d = activity.resources.displayMetrics.density
            assertEquals(90f * d, canvas(activity).findViewWithTag<View>("id:a").width.toFloat(), 2f)
            activity.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
            assertEquals(originalWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:a").translationX, 1f)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:next").translationY, 1f)
            assertFalse(session(activity).document.hasChanges)
        }
    }

    @Test fun savedAdditionUndoRedoAndEditingCancellationRestoreTheSameLayout() = withEditor { scenario ->
        var originalWidth = 0
        var saved: UiAnnotation? = null
        var savedWidth = 0
        var savedY = 0f
        scenario.onActivity { activity ->
            originalWidth = canvas(activity).findViewWithTag<View>("id:a").width
            start(activity)
            val session = session(activity)
            saved = session.pendingAddition!!.copy(instruction = "기록 버튼 추가")
            session.replaceAnnotations(listOf(saved!!))
            session.history.record(session.annotations)
            activity.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
            savedWidth = canvas(activity).findViewWithTag<View>("id:a").width
            savedY = canvas(activity).findViewWithTag<View>("id:a").translationY
            activity.findViewById<View>(R.id.btnUiAnnotationUndo).performClick()
            cancelServerSave(activity)
            assertTrue(session.annotations.isEmpty())
            assertEquals(originalWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
            activity.findViewById<View>(R.id.btnUiAnnotationRedo).performClick()
            cancelServerSave(activity)
            assertEquals(savedWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(savedY, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
            // Editing the same ID replaces its reservation, instead of adding a second one.
            session.pendingAddition = saved
            UiAnnotationEditorActivity::class.java.getDeclaredMethod("showAdditionPlacement").also {
                it.isAccessible = true; it.invoke(activity)
            }
            drag(activity, 0f, 280f)
            assertEquals(originalWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
            activity.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
            assertEquals(savedWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(savedY, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
        }
        scenario.recreate()
        settle()
        scenario.onActivity { activity ->
            assertEquals(saved!!.addition, session(activity).annotations.single().addition)
            assertEquals(savedWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(savedY, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
        }
    }

    @Test fun draggingResizingRecreationAndCancelKeepTheFixedRegionAndRestoreSurroundings() = withEditor { scenario ->
        var originalWidth = 0
        var region: UiNormalizedRect? = null
        var previewWidth = 0
        var previewY = 0f
        scenario.onActivity { originalWidth = canvas(it).findViewWithTag<View>("id:a").width; start(it) }
        settle()
        screenshot("initial")
        scenario.onActivity { activity ->
            assertTrue(canvas(activity).findViewWithTag<View>("id:a").translationY > 0f ||
                canvas(activity).findViewWithTag<View>("id:a").width != originalWidth)
            drag(activity, 0f, 280f)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(originalWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
            val before = session(activity).pendingAddition!!.addition!!.bounds
            drag(activity, -70f, -80f, corner = true)
            val after = session(activity).pendingAddition!!.addition!!.bounds
            assertTrue(after.right < before.right)
            assertTrue(after.bottom < before.bottom)
            // Corner handles remain selected; tapping the centre switches back to moving.
            overlay(activity).showAdditionPlacement(session(activity).pendingAddition)
        }
        settle()
        scenario.onActivity { activity ->
            val b = session(activity).pendingAddition!!.addition!!.bounds
            val d = activity.resources.displayMetrics.density
            val row = canvas(activity).findViewWithTag<View>("id:row")
            val desiredLeft = canvas(activity).paddingLeft / d
            val desiredTop = (canvas(activity).paddingTop + row.top) / d
            drag(activity, desiredLeft - b.left * session(activity).referenceCanvasWidthDp!!,
                desiredTop - b.top * session(activity).referenceCanvasHeightDp!!)
            region = session(activity).pendingAddition!!.addition!!.bounds
            previewWidth = canvas(activity).findViewWithTag<View>("id:a").width
            previewY = canvas(activity).findViewWithTag<View>("id:a").translationY
        }
        settle()
        screenshot("resized")
        scenario.recreate()
        settle()
        scenario.onActivity { activity ->
            assertEquals(region, session(activity).pendingAddition!!.addition!!.bounds)
            assertEquals(previewWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(previewY, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
            activity.findViewById<View>(R.id.btnCancelUiAnnotationAction).performClick()
            assertNull(session(activity).pendingAddition)
            assertEquals(originalWidth, canvas(activity).findViewWithTag<View>("id:a").width)
            assertEquals(0f, canvas(activity).findViewWithTag<View>("id:a").translationY, 1f)
        }
        settle()
        screenshot("cancelled")
    }
}
