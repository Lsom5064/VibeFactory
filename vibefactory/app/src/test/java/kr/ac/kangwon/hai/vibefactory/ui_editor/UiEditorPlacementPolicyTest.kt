package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiEditorPlacementPolicyTest {
    @Test
    fun verticalFlowMovesInterveningSiblingsIntoVacatedSlots() {
        val decision = UiEditorPlacementPolicy.resolve(
            flow = UiEditorParentFlow.VERTICAL,
            draggedStableId = "title",
            dropLeft = 0,
            dropTop = 105,
            dropWidth = 100,
            dropHeight = 40,
            items = listOf(
                item("title", 0, 0, 100, 40),
                item("button", 0, 50, 100, 90),
                item("switch", 0, 100, 100, 140)
            )
        )

        assertEquals("switch", decision.targetStableId)
        assertTrue(decision.insertAfterTarget)
        assertEquals(UiEditorPlacementOffset(0, -50), decision.siblingOffsets.getValue("button"))
        assertEquals(UiEditorPlacementOffset(0, -50), decision.siblingOffsets.getValue("switch"))
        assertEquals(100, decision.snapTop)
    }

    @Test
    fun horizontalFlowMovesEarlierSiblingsTowardReleasedSlot() {
        val decision = UiEditorPlacementPolicy.resolve(
            flow = UiEditorParentFlow.HORIZONTAL,
            draggedStableId = "switch",
            dropLeft = 0,
            dropTop = 0,
            dropWidth = 40,
            dropHeight = 40,
            items = listOf(
                item("title", 0, 0, 40, 40),
                item("button", 50, 0, 90, 40),
                item("switch", 100, 0, 140, 40)
            )
        )

        assertEquals("title", decision.targetStableId)
        assertEquals(false, decision.insertAfterTarget)
        assertEquals(UiEditorPlacementOffset(50, 0), decision.siblingOffsets.getValue("title"))
        assertEquals(UiEditorPlacementOffset(50, 0), decision.siblingOffsets.getValue("button"))
        assertEquals(0, decision.snapLeft)
    }

    @Test
    fun freeformCollisionSwapsWithLargestOverlappingControl() {
        val decision = UiEditorPlacementPolicy.resolve(
            flow = UiEditorParentFlow.FREEFORM,
            draggedStableId = "title",
            dropLeft = 85,
            dropTop = 10,
            dropWidth = 50,
            dropHeight = 40,
            items = listOf(
                item("title", 0, 0, 50, 40),
                item("button", 80, 0, 140, 50),
                item("switch", 120, 0, 180, 50)
            )
        )

        assertEquals("button", decision.targetStableId)
        assertEquals(80, decision.snapLeft)
        assertEquals(0, decision.snapTop)
        assertEquals(UiEditorPlacementOffset(-80, 0), decision.siblingOffsets.getValue("button"))
    }

    @Test
    fun freeformIgnoresIntentionalOverlayCandidate() {
        val decision = UiEditorPlacementPolicy.resolve(
            flow = UiEditorParentFlow.FREEFORM,
            draggedStableId = "title",
            dropLeft = 80,
            dropTop = 0,
            dropWidth = 50,
            dropHeight = 40,
            items = listOf(
                item("title", 0, 0, 50, 40),
                item("background", 70, 0, 200, 160, canDisplace = false)
            )
        )

        assertNull(decision.targetStableId)
        assertEquals(80, decision.snapLeft)
        assertTrue(decision.siblingOffsets.isEmpty())
    }

    private fun item(
        id: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        canDisplace: Boolean = true
    ) = UiEditorPlacementItem(id, left, top, right, bottom, canDisplace)
}
