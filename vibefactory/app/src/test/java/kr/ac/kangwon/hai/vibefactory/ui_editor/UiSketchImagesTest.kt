package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiSketchImagesTest {
    @Test fun `inserted image fits the canvas without stretching`() {
        for (imageAspect in listOf(.2f, 1f, 5f)) for (canvasAspect in listOf(.5f, 1f, 4f)) {
            val b = UiSketchImages.fit(imageAspect, canvasAspect)
            assertTrue(b.left >= 0 && b.top >= 0 && b.right <= 1 && b.bottom <= 1)
            assertEquals(imageAspect, (b.right-b.left)/(b.bottom-b.top)*canvasAspect, .001f)
        }
    }
    @Test fun `resizing every corner preserves aspect and stays in bounds`() {
        val original = UiNormalizedRect(.2f,.3f,.8f,.7f)
        for (corner in 0..3) for (delta in listOf(-3f,-.1f,.1f,3f)) {
            val b = UiSketchImages.resize(original,corner,delta,-delta)
            assertTrue(b.left >= -.0001f && b.top >= -.0001f && b.right <= 1.0001f && b.bottom <= 1.0001f)
            assertTrue(b.right > b.left && b.bottom > b.top)
            assertEquals(1.5f,(b.right-b.left)/(b.bottom-b.top),.001f)
        }
    }
}
