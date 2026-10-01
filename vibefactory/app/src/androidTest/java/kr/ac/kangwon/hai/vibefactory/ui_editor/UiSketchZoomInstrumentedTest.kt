package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
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
class UiSketchZoomInstrumentedTest {
    @Test fun zoomButtonsKeepInkAndImagesInOriginalCoordinatesAndResetTheView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val drawing = UiSketchCanvasView(context)
            drawing.layout(0, 0, 600, 600)
            val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            drawing.draw(Canvas(bitmap))
            val strokes = listOf(UiSketchStroke(listOf(UiSketchPoint(.2f, .3f), UiSketchPoint(.8f, .7f)), -16777216))
            val images = listOf(UiSketchImageLayer("reference", UiNormalizedRect(.1f, .2f, .6f, .8f)))
            drawing.restore(strokes, images)
            val controls = UiSketchZoomControls(context, drawing)
            val zoomIn = controls.findViewById<View>(kr.ac.kangwon.hai.vibefactory.R.id.uiSketchZoomIn)
            repeat(10) { zoomIn.performClick() }
            assertEquals(4f, drawing.zoomScale, .001f)
            assertFalse(zoomIn.isEnabled)
            assertEquals(strokes, drawing.strokes)
            assertEquals(images, drawing.imageLayers)
            controls.findViewById<View>(kr.ac.kangwon.hai.vibefactory.R.id.uiSketchZoomOut).performClick()
            assertEquals(3.2f, drawing.zoomScale, .001f)
            controls.findViewById<View>(kr.ac.kangwon.hai.vibefactory.R.id.uiSketchZoomReset).performClick()
            assertEquals(1f, drawing.zoomScale, .001f)
            assertEquals(strokes, drawing.strokes)
            assertEquals(images, drawing.imageLayers)
            bitmap.recycle()
        }
    }

    @Test fun pinchDoesNotDrawOrEraseAndZoomedInkKeepsRegionCoordinates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = UiSketchCanvasView(ApplicationProvider.getApplicationContext())
            FrameLayout(view.context).apply {
                addView(view, FrameLayout.LayoutParams(1000, 1000))
                measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1000, 1000)
            }
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            fun draw() = view.draw(Canvas(bitmap))
            fun touch(action: Int, vararg points: Pair<Float, Float>) {
                val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coordinates = points.map { p -> MotionEvent.PointerCoords().apply { x = p.first; y = p.second; pressure = 1f; size = 1f } }.toTypedArray()
                val event = MotionEvent.obtain(0, 20, action, points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                view.dispatchTouchEvent(event); event.recycle(); draw()
            }
            draw()
            val original = listOf(UiSketchStroke(listOf(UiSketchPoint(.25f, .5f), UiSketchPoint(.75f, .5f)), -16777216))
            view.restore(original)
            for (erase in listOf(false, true)) {
                view.resetViewport(); draw(); view.erasing = erase
                touch(MotionEvent.ACTION_DOWN, 300f to 500f)
                touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f to 500f, 700f to 500f)
                touch(MotionEvent.ACTION_MOVE, 100f to 500f, 900f to 500f)
                touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f to 500f, 900f to 500f)
                touch(MotionEvent.ACTION_MOVE, 200f to 500f)
                touch(MotionEvent.ACTION_UP, 200f to 500f)
                assertEquals(original, view.strokes)
                assertEquals(2f, view.viewport.scale, .01f)
            }
            view.erasing = false
            val start = view.viewport.screenPoint(UiSketchPoint(.4f, .4f))
            val end = view.viewport.screenPoint(UiSketchPoint(.6f, .6f))
            touch(MotionEvent.ACTION_DOWN, start.x to start.y)
            touch(MotionEvent.ACTION_UP, end.x to end.y)
            val ink = view.strokes.last()
            assertEquals(.4f, ink.points.first().x, .001f)
            assertEquals(.6f, ink.points.last().y, .001f)
            view.undo(); assertEquals(original, view.strokes)
            view.redo(); assertEquals(ink, view.strokes.last())
            view.resetViewport(); assertEquals(ink, view.strokes.last())
            bitmap.recycle()
        }
    }
}
