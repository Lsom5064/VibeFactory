package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAnnotationDragScrollGateTest {
    @Test
    fun `ignores toolbar edge until drag crosses safe zone`() {
        val gate = UiAnnotationDragScrollGate()

        assertFalse(gate.shouldAutoScroll(isInsideSafeZone = false))
        assertFalse(gate.shouldAutoScroll(isInsideSafeZone = true))
        assertTrue(gate.shouldAutoScroll(isInsideSafeZone = false))
    }

    @Test
    fun `reset requires safe zone crossing again`() {
        val gate = UiAnnotationDragScrollGate()
        gate.shouldAutoScroll(isInsideSafeZone = true)
        gate.reset()

        assertFalse(gate.shouldAutoScroll(isInsideSafeZone = false))
    }
}
