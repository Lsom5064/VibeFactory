package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiSketchViewportTest {
    @Test fun zoomAndPanPreserveNormalizedCoordinatesAndLimitScale() {
        val viewport = UiSketchViewport().apply { resize(1000f, 800f, 1.5f, 10f) }
        val original = UiSketchPoint(.42f, .31f)
        viewport.transform(500f, 400f, 570f, 440f, 3f)
        val screen = viewport.screenPoint(original)
        val restored = viewport.normalizedPoint(screen.x, screen.y)!!
        assertEquals(original.x, restored.x, .00001f)
        assertEquals(original.y, restored.y, .00001f)
        viewport.transform(500f, 400f, 500f, 400f, 100f)
        assertEquals(4f, viewport.scale)
        viewport.transform(500f, 400f, 500f, 400f, .01f)
        assertEquals(1f, viewport.scale)
        assertEquals(0f, viewport.offsetX)
        assertEquals(0f, viewport.offsetY)
    }

    @Test fun pinchKeepsContentUnderFingersAndResetFitsWholeRegion() {
        val viewport = UiSketchViewport().apply { resize(1000f, 1000f, 1f, 10f) }
        val point = viewport.normalizedPoint(400f, 450f)!!
        viewport.transform(400f, 450f, 420f, 470f, 2f)
        val after = viewport.screenPoint(point)
        assertEquals(420f, after.x, .001f)
        assertEquals(470f, after.y, .001f)
        viewport.reset()
        assertEquals(10f, viewport.screenPoint(UiSketchPoint(0f, 0f)).x, .001f)
        assertNull(viewport.normalizedPoint(0f, 0f))
    }
}
