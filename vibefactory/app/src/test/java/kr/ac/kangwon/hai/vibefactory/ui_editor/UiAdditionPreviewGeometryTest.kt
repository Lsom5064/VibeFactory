package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiAdditionPreviewGeometryTest {
    private fun arrange(blocks: Map<String, UiPreviewBox>, vararg added: UiPreviewBox) =
        UiAdditionPreviewGeometry.arrange(blocks, added.toList())

    @Test fun tallSideRegionReservesHorizontalSpaceForEveryOverlappedRow() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 20f, 180f, 68f), "b" to UiPreviewBox(180f, 20f, 360f, 68f),
            "next" to UiPreviewBox(0f, 78f, 360f, 126f))
        listOf(UiPreviewBox(0f, 0f, 100f, 160f), UiPreviewBox(260f, 0f, 360f, 160f)).forEach { added ->
            val result = arrange(blocks, added)
            assertEquals(130f, result.getValue("a").width, .01f)
            assertEquals(130f, result.getValue("b").width, .01f)
            assertEquals(260f, result.getValue("next").width, .01f)
            blocks.forEach { (id, box) -> assertEquals(box.top, result.getValue(id).top, .01f) }
            assertEquals(if (added.left == 0f) 100f else 0f, result.getValue("a").left, .01f)
            assertTrue(result.values.none(added::overlaps))
        }
    }

    @Test fun narrowRemainderShrinksProportionallyInsteadOfForcingAVerticalMove() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 0f, 120f, 80f), "b" to UiPreviewBox(120f, 0f, 360f, 80f))
        val added = UiPreviewBox(0f, 0f, 330f, 80f)
        val result = arrange(blocks, added)
        assertEquals(UiPreviewBox(330f, 0f, 340f, 80f), result["a"])
        assertEquals(UiPreviewBox(340f, 0f, 360f, 80f), result["b"])
    }

    @Test fun leftAndRightKeepTheRequestedHundredAndGiveExistingControlsOneHundredThirtyEach() {
        val row = mapOf("a" to UiPreviewBox(0f, 0f, 180f, 80f), "b" to UiPreviewBox(180f, 0f, 360f, 80f))
        listOf(UiPreviewBox(0f, 0f, 100f, 80f), UiPreviewBox(260f, 0f, 360f, 80f)).forEach { added ->
            val result = arrange(row, added)
            assertEquals(130f, result.getValue("a").width, .01f)
            assertEquals(130f, result.getValue("b").width, .01f)
            assertEquals(if (added.left == 0f) 100f else 0f, result.getValue("a").left, .01f)
            assertTrue(result.values.none(added::overlaps))
        }
    }

    @Test fun remainingControlsKeepTheirOriginalWidthRatioAndSpacing() {
        val row = mapOf("a" to UiPreviewBox(0f, 0f, 120f, 80f), "b" to UiPreviewBox(130f, 0f, 370f, 80f))
        val result = arrange(row, UiPreviewBox(0f, 0f, 60f, 80f))
        assertEquals(100f, result.getValue("a").width, .01f)
        assertEquals(200f, result.getValue("b").width, .01f)
        assertEquals(10f, result.getValue("b").left - result.getValue("a").right, .01f)
    }

    @Test fun wideEdgeAdditionKeepsBothControlsOnTheRemainingSide() {
        val row = mapOf("a" to UiPreviewBox(0f, 0f, 180f, 80f), "b" to UiPreviewBox(180f, 0f, 360f, 80f))
        listOf(UiPreviewBox(0f, 0f, 200f, 80f), UiPreviewBox(160f, 0f, 360f, 80f)).forEach { added ->
            val result = arrange(row, added)
            result.values.forEach {
                assertEquals(80f, it.width, .01f)
                assertEquals(0f, it.top, .01f)
            }
            assertTrue(result.values.none(added::overlaps))
        }
    }

    @Test fun offCentreMiddleAdditionLeavesDifferentWidthsOnEachSide() {
        val row = mapOf("a" to UiPreviewBox(0f, 0f, 180f, 80f), "b" to UiPreviewBox(180f, 0f, 360f, 80f))
        val added = UiPreviewBox(100f, 0f, 200f, 80f)
        val result = arrange(row, added)
        assertEquals(UiPreviewBox(0f, 0f, 100f, 80f), result["a"])
        assertEquals(UiPreviewBox(200f, 0f, 360f, 80f), result["b"])
    }

    @Test fun noSideSpaceWrapsAndPushesFollowingRows() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 0f, 180f, 80f), "b" to UiPreviewBox(180f, 0f, 360f, 80f),
            "next" to UiPreviewBox(0f, 100f, 360f, 180f))
        val added = UiPreviewBox(0f, 0f, 360f, 80f)
        val result = arrange(blocks, added)
        assertEquals(180f, result.getValue("b").width, .01f)
        assertEquals(80f, result.getValue("b").top, .01f)
        assertEquals(160f, result.getValue("next").top, .01f)
        assertTrue(result.values.none(added::overlaps))
    }

    @Test fun betweenRowsPushesOnlyTheOverlapAndItsFollowers() {
        val blocks = mapOf("above" to UiPreviewBox(0f, 0f, 360f, 80f),
            "below" to UiPreviewBox(0f, 100f, 360f, 180f), "next" to UiPreviewBox(0f, 190f, 360f, 240f))
        val result = arrange(blocks, UiPreviewBox(0f, 85f, 360f, 155f))
        assertEquals(blocks["above"], result["above"])
        assertEquals(155f, result.getValue("below").top, .01f)
        assertEquals(235f, result.getValue("next").top, .01f)
    }

    @Test fun emptySpaceAndClearingReservationsKeepOriginalGeometry() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 0f, 150f, 80f), "b" to UiPreviewBox(210f, 0f, 360f, 80f))
        assertEquals(blocks, arrange(blocks, UiPreviewBox(155f, 0f, 205f, 80f)))
        assertEquals(blocks, arrange(blocks))
    }

    @Test fun separateParentsDoNotShareWidthAndPinnedMoveSourceStaysReserved() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 0f, 180f, 80f), "b" to UiPreviewBox(180f, 0f, 360f, 80f))
        val result = UiAdditionPreviewGeometry.arrange(blocks, listOf(UiPreviewBox(0f, 0f, 60f, 80f)),
            scopes = mapOf("a" to "one", "b" to "two"))
        assertEquals(120f, result.getValue("a").width, .01f)
        assertEquals(blocks["b"], result["b"])
        val pinned = UiPreviewBox(0f, 80f, 360f, 120f)
        val wrapped = UiAdditionPreviewGeometry.arrange(blocks, listOf(UiPreviewBox(0f, 0f, 360f, 80f)), listOf(pinned))
        assertTrue(wrapped.values.all { it.top >= 120f })
    }

    @Test fun multipleAdditionsReserveTheirOwnFixedWidths() {
        val row = (0..2).associate { "$it" to UiPreviewBox(it * 120f, 0f, (it + 1) * 120f, 80f) }
        val added = arrayOf(UiPreviewBox(70f, 0f, 110f, 80f), UiPreviewBox(225f, 0f, 265f, 80f))
        val result = arrange(row, *added)
        assertEquals(70f, result.getValue("0").width, .01f)
        assertEquals(115f, result.getValue("1").width, .01f)
        assertEquals(95f, result.getValue("2").width, .01f)
        assertTrue(result.values.none { box -> added.any(box::overlaps) })
    }

    @Test fun horizontalGapsShrinkBeforeTheRowIsForcedDown() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 0f, 120f, 80f), "b" to UiPreviewBox(220f, 0f, 460f, 80f))
        val added = UiPreviewBox(0f, 0f, 450f, 80f)
        val result = arrange(blocks, added)
        assertEquals(0f, result.getValue("a").top, .01f)
        assertEquals(0f, result.getValue("b").top, .01f)
        assertEquals(2.5f, result.getValue("a").width, .01f)
        assertEquals(5f, result.getValue("b").width, .01f)
        assertEquals(460f, result.getValue("b").right, .01f)
        assertTrue(result.values.none(added::overlaps))
    }

    @Test fun smallTopOrBottomOverlapStillDisplacesVertically() {
        val row = mapOf("row" to UiPreviewBox(0f, 100f, 360f, 180f))
        listOf(UiPreviewBox(0f, 50f, 100f, 110f), UiPreviewBox(260f, 170f, 360f, 230f)).forEach { added ->
            val result = arrange(row, added).getValue("row")
            assertEquals(360f, result.width, .01f)
            assertEquals(added.bottom, result.top, .01f)
        }
    }
}
