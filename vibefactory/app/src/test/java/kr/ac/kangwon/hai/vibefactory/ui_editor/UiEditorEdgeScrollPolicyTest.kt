package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.assertEquals
import org.junit.Test

class UiEditorEdgeScrollPolicyTest {
    @Test
    fun `does not scroll outside edge zones`() {
        assertEquals(0, UiEditorEdgeScrollPolicy.step(100f, 0, 200, 40, 8))
    }

    @Test
    fun `scrolls toward each nearby edge`() {
        assertEquals(-4, UiEditorEdgeScrollPolicy.step(20f, 0, 200, 40, 8))
        assertEquals(4, UiEditorEdgeScrollPolicy.step(180f, 0, 200, 40, 8))
    }

    @Test
    fun `caps scrolling when pointer moves beyond viewport`() {
        assertEquals(-8, UiEditorEdgeScrollPolicy.step(-100f, 0, 200, 40, 8))
        assertEquals(8, UiEditorEdgeScrollPolicy.step(300f, 0, 200, 40, 8))
    }
}
