package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiMovePreviewGeometryTest {
    @Test fun destinationDisplacesIntersectingContentAndFollowingRows() {
        val blocks = mapOf("first" to UiPreviewBox(0f, 100f, 100f, 150f),
            "second" to UiPreviewBox(0f, 155f, 100f, 205f), "otherColumn" to UiPreviewBox(200f, 100f, 300f, 150f))
        val result = UiMovePreviewGeometry.offsets(blocks, listOf(UiPreviewBox(0f, 90f, 100f, 140f)), emptyList())
        assertEquals(40f, result.getValue("first"), .001f)
        assertEquals(35f, result.getValue("second"), .001f)
        assertFalse(result.containsKey("otherColumn"))
    }

    @Test fun displacementPassesPinnedSourceWithoutMovingIt() {
        val blocks = mapOf("row" to UiPreviewBox(0f, 100f, 100f, 160f))
        val result = UiMovePreviewGeometry.offsets(blocks, listOf(UiPreviewBox(0f, 95f, 100f, 150f)),
            listOf(UiPreviewBox(0f, 190f, 100f, 240f)))
        assertEquals(140f, result.getValue("row"), .001f)
    }

    @Test fun removingMoveRestoresAllOriginalPositions() {
        assertTrue(UiMovePreviewGeometry.offsets(mapOf("row" to UiPreviewBox(0f, 100f, 100f, 160f)),
            emptyList(), emptyList()).isEmpty())
    }

    @Test fun multipleDestinationsRemainReservedAfterCascade() {
        val result = UiMovePreviewGeometry.offsets(mapOf("row" to UiPreviewBox(0f, 100f, 100f, 160f)),
            listOf(UiPreviewBox(0f, 100f, 100f, 150f), UiPreviewBox(0f, 190f, 100f, 250f)), emptyList())
        assertEquals(150f, result.getValue("row"), .001f)
    }
}
