package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiMoveDestinationInstrumentedTest {
    @Test fun sourceTouchCanContinueIntoDragWithoutCommittingAnAnnotation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            var sourceSelected = false
            var requestedDestination: Pair<Float, Float>? = null
            view.enableTargetSelection(true)
            view.moveSourceTouchListener = { _, _ ->
                sourceSelected = true
                view.enableTargetSelection(false)
                view.showPendingMove(source)
                true
            }
            view.destinationTapListener = { x, y -> requestedDestination = x to y }
            send(view, MotionEvent.ACTION_DOWN, 150f, 120f)
            assertTrue(sourceSelected)
            send(view, MotionEvent.ACTION_UP, 150f, 120f)
            assertEquals(null, requestedDestination)
            // Start again to exercise a single uninterrupted source-to-destination drag.
            view.showPendingMove(null)
            view.enableTargetSelection(true)
            send(view, MotionEvent.ACTION_DOWN, 150f, 120f)
            send(view, MotionEvent.ACTION_MOVE, 700f, 560f)
            assertEquals(null, requestedDestination)
            send(view, MotionEvent.ACTION_UP, 700f, 560f)
            assertEquals(.7f, requestedDestination!!.first, .001f)
            assertEquals(.7f, requestedDestination!!.second, .001f)
            assertTrue(view.annotationsAt(700f, 560f).isEmpty())
        }
    }

    @Test fun fittedDestinationIsUsedForDrawingAndTappingWhileSourceStaysFullWidth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            val annotation = move().copy(target = source.copy(bounds = UiNormalizedRect(.05f, .1f, .85f, .2f)))
            val fitted = UiNormalizedRect(.6f, .65f, .8f, .75f)
            view.showAnnotations(listOf(annotation))
            view.showMoveDestinationBounds(mapOf(annotation.annotationId to fitted), null)
            assertDestinationOutline(view)
            assertTrue(view.annotationsAt(780f, 590f).contains(annotation))
            assertTrue(view.annotationsAt(980f, 590f).isEmpty())
            view.showAnnotations(emptyList())
            view.showPendingMove(annotation.target, .7f, .7f)
            view.showMoveDestinationBounds(emptyMap(), fitted)
            assertDestinationOutline(view)
            val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertTrue("The solid source keeps its width", bluePixels(bitmap, 600, 76, 750, 85) > 100)
            } finally { bitmap.recycle() }
        }
    }

    private val source = UiAnnotationTarget(
        "id:source", "@+id/source", "0.1", "Button", "이동할 버튼", "",
        UiNormalizedRect(.05f, .1f, .25f, .2f), "", ""
    )
    private val destination = source.copy(
        stableId = "id:destination", bounds = UiNormalizedRect(.6f, .5f, .95f, .9f)
    )

    private fun overlay(): UiAnnotationOverlayView = UiAnnotationOverlayView(ApplicationProvider.getApplicationContext()).also { view ->
        FrameLayout(view.context).apply {
            addView(view, FrameLayout.LayoutParams(1000, 800))
            measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1000, 800)
        }
    }

    private fun move(destinationTarget: UiAnnotationTarget? = destination) = UiAnnotation(
        action = UiAnnotationAction.MOVE, target = source, destination = destinationTarget,
        destinationX = .7f, destinationY = .7f
    )

    private fun send(view: View, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 20, action, x, y, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun bluePixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 100 && Color.blue(pixel) > Color.red(pixel) + 60) count++
        }
        return count
    }

    private fun assertDestinationOutline(view: UiAnnotationOverlayView) {
        val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            // Original UI is 200 x 80, centered on (700, 560): [600, 520, 800, 600].
            // These bands avoid the arrow and detect the dashed top/bottom edges.
            assertTrue("Missing source-sized top edge", bluePixels(bitmap, 690, 516, 775, 525) > 20)
            assertTrue("Missing source-sized bottom edge", bluePixels(bitmap, 650, 596, 775, 605) > 20)
            assertEquals("Destination UI must not get its own outline", 0,
                bluePixels(bitmap, 880, 396, 920, 405))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun savedMoveUsesSourceSizeAroundExactArrowEndpoint() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            view.showAnnotations(listOf(move()))
            assertDestinationOutline(view)
        }
    }

    @Test
    fun emptyDestinationStillShowsTheMovedUiOutline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            view.showAnnotations(listOf(move(null)))
            assertDestinationOutline(view)
        }
    }

    @Test
    fun outlineFollowsTheDestinationWhileDragging() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            view.showPendingMove(source)
            send(view, MotionEvent.ACTION_DOWN, 400f, 300f)
            send(view, MotionEvent.ACTION_MOVE, 700f, 560f)
            assertDestinationOutline(view)
            send(view, MotionEvent.ACTION_CANCEL, 700f, 560f)
        }
    }

    @Test
    fun pendingSourceKeepsASolidOutline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            view.showPendingMove(source, .7f, .7f)
            val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals(150, bluePixels(bitmap, 70, 80, 220, 81))
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun canvasEdgeDoesNotShiftTheCenterOrShrinkTheOutline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bounds = overlay().moveDestinationRect(source.bounds, 980f, 16f)
            assertEquals(980f, bounds.centerX(), .001f)
            assertEquals(16f, bounds.centerY(), .001f)
            assertEquals(200f, bounds.width(), .001f)
            assertEquals(80f, bounds.height(), .001f)
            assertTrue(bounds.top < 0f)
            assertTrue(bounds.right > 1000f)
        }
    }

    @Test
    fun smallUiKeepsItsSizeInsteadOfTheSelectionMinimum() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bounds = overlay().moveDestinationRect(UiNormalizedRect(.1f, .2f, .11f, .21f), 500f, 400f)
            assertEquals(10f, bounds.width(), .001f)
            assertEquals(8f, bounds.height(), .001f)
        }
    }

    @Test
    fun tappingDestinationOutlineSelectsTheExistingMove() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay()
            val annotation = move()
            var selected = emptyList<UiAnnotation>()
            view.annotationTapListener = { selected = it }
            view.showAnnotations(listOf(annotation))
            send(view, MotionEvent.ACTION_DOWN, 780f, 590f)
            send(view, MotionEvent.ACTION_UP, 780f, 590f)
            assertEquals(listOf(annotation), selected)
        }
    }
}
