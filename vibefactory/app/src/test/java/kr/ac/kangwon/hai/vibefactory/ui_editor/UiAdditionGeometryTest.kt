package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.assertEquals
import org.junit.Test

class UiAdditionGeometryTest {
    private val bounds = UiNormalizedRect(.2f, .2f, .8f, .6f)

    @Test
    fun cornersCannotCrossTheirOppositeEdges() {
        for (corner in 0..3) {
            val left = corner == 0 || corner == 2
            val top = corner < 2
            val resized = UiAdditionGeometry.resize(bounds, corner,
                if (left) 2f else -2f, if (top) 2f else -2f, .1f, .08f)
            assertEquals(.1f, resized.right - resized.left, .0001f)
            assertEquals(.08f, resized.bottom - resized.top, .0001f)
            assertEquals(if (left) bounds.right else bounds.left, if (left) resized.right else resized.left, 0f)
            assertEquals(if (top) bounds.bottom else bounds.top, if (top) resized.bottom else resized.top, 0f)
        }
    }

    @Test
    fun resizingClampsAtCanvasEdges() {
        val topLeft = UiAdditionGeometry.resize(bounds, 0, -2f, -2f, .1f, .08f)
        assertEquals(UiNormalizedRect(0f, 0f, .8f, .6f), topLeft)
        val bottomRight = UiAdditionGeometry.resize(bounds, 3, 2f, 2f, .1f, .08f)
        assertEquals(UiNormalizedRect(.2f, .2f, 1f, 1f), bottomRight)
    }

    @Test
    fun smallRestoredRegionDoesNotGrowJustBecauseMinimumSizeIsLarger() {
        val small = UiNormalizedRect(.2f, .2f, .23f, .22f)
        val resized = UiAdditionGeometry.resize(small, 3, -1f, -1f, .1f, .08f)
        assertEquals(small, resized)
    }
}
