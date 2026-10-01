package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAdditionTouchInstrumentedTest {
    private val target = UiAnnotationTarget("id:button", "@+id/button", "0.1", "Button", "버튼", "",
        UiNormalizedRect(.1f, .2f, .5f, .4f), "", "")
    private fun attach(view: View) {
        FrameLayout(view.context).apply {
            addView(view, FrameLayout.LayoutParams(1000, 1000))
            measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1000, 1000)
        }
    }
    private fun send(view: View, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 20, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    @Test
    fun appliedToolsCanBeTappedWithoutTurningScrollIntoDeletion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            UiAnnotationAction.entries.forEach { action ->
                val overlay = UiAnnotationOverlayView(context)
                attach(overlay)
                val annotation = UiAnnotation(action = action, target = target, destinationX = .9f, destinationY = .8f,
                    addition = if (action == UiAnnotationAction.ADD) UiAdditionSpec(UiNormalizedRect(.2f, .2f, .6f, .5f), -1) else null)
                var tapped: List<UiAnnotation> = emptyList()
                overlay.annotationTapListener = { tapped = it }
                overlay.showAnnotations(listOf(annotation))
                send(overlay, MotionEvent.ACTION_DOWN, 300f, 300f)
                send(overlay, MotionEvent.ACTION_UP, 300f, 300f)
                assertEquals(listOf(annotation), tapped)
                if (action == UiAnnotationAction.MOVE) {
                    tapped = emptyList()
                    send(overlay, MotionEvent.ACTION_DOWN, 600f, 550f)
                    send(overlay, MotionEvent.ACTION_UP, 600f, 550f)
                    assertEquals(listOf(annotation), tapped)
                }
                tapped = emptyList()
                send(overlay, MotionEvent.ACTION_DOWN, 300f, 300f)
                send(overlay, MotionEvent.ACTION_MOVE, 300f, 500f)
                send(overlay, MotionEvent.ACTION_CANCEL, 300f, 500f)
                assertTrue(tapped.isEmpty())
            }
        }
    }
    @Test
    fun sketchCanBeErasedUndoneAndRestored() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val drawing = UiSketchCanvasView(ApplicationProvider.getApplicationContext())
            attach(drawing)
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            drawing.draw(Canvas(bitmap))
            send(drawing, MotionEvent.ACTION_DOWN, 200f, 200f)
            send(drawing, MotionEvent.ACTION_MOVE, 700f, 700f)
            send(drawing, MotionEvent.ACTION_UP, 800f, 800f)
            val original = drawing.strokes
            assertEquals(1, original.size)
            drawing.erasing = true
            send(drawing, MotionEvent.ACTION_DOWN, 200f, 200f)
            send(drawing, MotionEvent.ACTION_UP, 200f, 200f)
            assertTrue(drawing.strokes.isEmpty())
            drawing.undo()
            assertEquals(original, drawing.strokes)
            drawing.redo()
            assertTrue(drawing.strokes.isEmpty())
            drawing.restore(original)
            assertEquals(original, drawing.strokes)
            bitmap.recycle()
        }
    }

    @Test
    fun pendingRegionUsesPositionTapAndTwoCornerTaps() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val overlay = UiAnnotationOverlayView(ApplicationProvider.getApplicationContext())
            attach(overlay)
            var bounds = UiNormalizedRect(.2f, .2f, .8f, .6f)
            var finished = false
            overlay.additionBoundsChanged = { changed, committed -> bounds = changed; finished = committed }
            overlay.showAdditionPlacement(UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                addition = UiAdditionSpec(bounds, -1)))
            send(overlay, MotionEvent.ACTION_DOWN, 500f, 850f)
            send(overlay, MotionEvent.ACTION_MOVE, 500f, 750f)
            send(overlay, MotionEvent.ACTION_UP, 500f, 750f)
            assertFalse("Scrolling outside the region must not move it", finished)
            assertEquals(.2f, bounds.left, .0001f)
            send(overlay, MotionEvent.ACTION_DOWN, 600f, 450f)
            send(overlay, MotionEvent.ACTION_UP, 600f, 450f)
            assertTrue(finished)
            assertEquals(.3f, bounds.left, .0001f)
            assertEquals(.65f, bounds.bottom, .0001f)
            finished = false
            send(overlay, MotionEvent.ACTION_DOWN, 900f, 650f)
            send(overlay, MotionEvent.ACTION_UP, 900f, 650f)
            assertFalse("Selecting a corner must wait for its new position", finished)
            send(overlay, MotionEvent.ACTION_DOWN, 1000f, 850f)
            send(overlay, MotionEvent.ACTION_UP, 1000f, 850f)
            assertTrue(finished)
            assertEquals(1f, bounds.right, .0001f)
            assertEquals(.85f, bounds.bottom, .0001f)
            assertEquals(.3f, bounds.left, .0001f)
        }
    }

    @Test
    fun pendingRegionFollowsDragAndCommitsOnlyOnRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val overlay = UiAnnotationOverlayView(ApplicationProvider.getApplicationContext())
            attach(overlay)
            val initial = UiNormalizedRect(.2f, .2f, .8f, .6f)
            var annotation = UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                addition = UiAdditionSpec(initial, -1))
            val commits = mutableListOf<Boolean>()
            overlay.additionBoundsChanged = { bounds, finished ->
                annotation = annotation.copy(addition = annotation.addition!!.copy(bounds = bounds))
                commits += finished
            }
            overlay.showAdditionPlacement(annotation)
            send(overlay, MotionEvent.ACTION_DOWN, 450f, 350f)
            send(overlay, MotionEvent.ACTION_MOVE, 500f, 400f)
            assertEquals(.25f, annotation.addition!!.bounds.left, .0001f)
            assertEquals(.25f, annotation.addition!!.bounds.top, .0001f)
            assertFalse(commits.any { it })
            send(overlay, MotionEvent.ACTION_MOVE, 550f, 450f)
            assertEquals(.3f, annotation.addition!!.bounds.left, .0001f)
            send(overlay, MotionEvent.ACTION_UP, 550f, 450f)
            assertEquals(1, commits.count { it })
            assertEquals(.9f, annotation.addition!!.bounds.right, .0001f)
            val xml = UiAnnotationXmlCodec.encode("drag_test", "rev_0001", "activity_main", "layout", "a".repeat(64), listOf(annotation))
            assertEquals(annotation, UiAnnotationXmlCodec.decode(xml).single())
        }
    }

    @Test
    fun eachCornerResizesWithoutMovingTheOppositeCorner() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val initial = UiNormalizedRect(.2f, .2f, .8f, .6f)
            listOf(200f to 200f, 800f to 200f, 200f to 600f, 800f to 600f).forEachIndexed { corner, point ->
                val overlay = UiAnnotationOverlayView(context)
                attach(overlay)
                var bounds = initial
                var finished = false
                overlay.additionBoundsChanged = { changed, committed -> bounds = changed; finished = committed }
                overlay.showAdditionPlacement(UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                    addition = UiAdditionSpec(initial, -1)))
                val dx = if (corner == 0 || corner == 2) -100f else 100f
                val dy = if (corner < 2) -100f else 100f
                // Grabbing within a handle must preserve the finger-to-corner offset.
                send(overlay, MotionEvent.ACTION_DOWN, point.first + 10f, point.second + 10f)
                send(overlay, MotionEvent.ACTION_MOVE, point.first + 10f + dx, point.second + 10f + dy)
                assertFalse(finished)
                assertEquals(if (dx < 0) .1f else .2f, bounds.left, .0001f)
                assertEquals(if (dy < 0) .1f else .2f, bounds.top, .0001f)
                assertEquals(if (dx > 0) .9f else .8f, bounds.right, .0001f)
                assertEquals(if (dy > 0) .7f else .6f, bounds.bottom, .0001f)
                send(overlay, MotionEvent.ACTION_UP, point.first + 10f + dx, point.second + 10f + dy)
                assertTrue(finished)
            }
        }
    }

    @Test
    fun cancelledDragRestoresRegionAndDoesNotTurnReleaseIntoATap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val overlay = UiAnnotationOverlayView(ApplicationProvider.getApplicationContext())
            attach(overlay)
            val initial = UiNormalizedRect(.2f, .2f, .8f, .6f)
            var bounds = initial
            overlay.additionBoundsChanged = { changed, _ -> bounds = changed }
            overlay.showAdditionPlacement(UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                addition = UiAdditionSpec(initial, -1)))
            send(overlay, MotionEvent.ACTION_DOWN, 500f, 400f)
            send(overlay, MotionEvent.ACTION_MOVE, 600f, 450f)
            assertNotEquals(initial, bounds)
            send(overlay, MotionEvent.ACTION_CANCEL, 600f, 450f)
            assertEquals(initial, bounds)
            send(overlay, MotionEvent.ACTION_UP, 600f, 450f)
            assertEquals(initial, bounds)
        }
    }

    @Test
    fun draggingUsesReferenceCanvasAndClampsWithoutChangingRegionSize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val overlay = UiAnnotationOverlayView(ApplicationProvider.getApplicationContext())
            attach(overlay)
            overlay.setReferenceSize(800, 1600)
            val initial = UiNormalizedRect(.2f, .2f, .8f, .6f)
            var bounds = initial
            overlay.additionBoundsChanged = { changed, _ -> bounds = changed }
            overlay.showAdditionPlacement(UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                addition = UiAdditionSpec(initial, -1)))
            send(overlay, MotionEvent.ACTION_DOWN, 400f, 640f)
            send(overlay, MotionEvent.ACTION_MOVE, 480f, 800f)
            assertEquals(.3f, bounds.left, .0001f)
            assertEquals(.3f, bounds.top, .0001f)
            send(overlay, MotionEvent.ACTION_UP, 1600f, 3200f)
            assertEquals(.4f, bounds.left, .0001f)
            assertEquals(.6f, bounds.top, .0001f)
            assertEquals(1f, bounds.right, .0001f)
            assertEquals(1f, bounds.bottom, .0001f)
        }
    }

    @Test
    fun regionDragKeepsViewportStillAndOutsideDragScrolls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val overlay = UiAnnotationOverlayView(context)
            val canvas = FrameLayout(context).apply {
                minimumHeight = 2000
                addView(overlay, FrameLayout.LayoutParams(1000, 2000))
            }
            val viewport = ScrollView(context).apply {
                addView(canvas, FrameLayout.LayoutParams(1000, 2000))
            }
            attach(viewport)
            assertEquals(1000, overlay.width)
            assertEquals(2000, overlay.height)
            var bounds = UiNormalizedRect(.2f, .1f, .8f, .3f)
            overlay.additionBoundsChanged = { changed, _ -> bounds = changed }
            overlay.showAdditionPlacement(UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                addition = UiAdditionSpec(bounds, -1)))
            send(viewport, MotionEvent.ACTION_DOWN, 500f, 400f)
            send(viewport, MotionEvent.ACTION_MOVE, 500f, 300f)
            send(viewport, MotionEvent.ACTION_UP, 500f, 250f)
            assertEquals(0, viewport.scrollY)
            assertEquals(.025f, bounds.top, .0001f)
            val afterMove = bounds
            // A previously tapped corner must not capture an outside scroll either.
            send(viewport, MotionEvent.ACTION_DOWN, 800f, 450f)
            send(viewport, MotionEvent.ACTION_UP, 800f, 450f)
            send(viewport, MotionEvent.ACTION_DOWN, 50f, 800f)
            send(viewport, MotionEvent.ACTION_MOVE, 50f, 650f)
            send(viewport, MotionEvent.ACTION_MOVE, 50f, 450f)
            send(viewport, MotionEvent.ACTION_UP, 50f, 450f)
            assertTrue("Outside drag should scroll the preview", viewport.scrollY > 0)
            assertEquals(afterMove, bounds)
        }
    }
}
