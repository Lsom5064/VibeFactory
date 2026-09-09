package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
            send(overlay, MotionEvent.ACTION_DOWN, 500f, 400f)
            send(overlay, MotionEvent.ACTION_UP, 600f, 450f)
            assertFalse("Scrolling must not move the region", finished)
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
}
