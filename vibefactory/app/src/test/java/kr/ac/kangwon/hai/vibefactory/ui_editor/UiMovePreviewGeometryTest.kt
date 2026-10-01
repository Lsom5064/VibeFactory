package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiMovePreviewGeometryTest {
    @Test fun insertBetweenDisplayedDestinationAndExistingControl() {
        val blocks = mapOf("row" to UiPreviewBox(0f, 100f, 300f, 200f))
        val requested = linkedMapOf("first" to UiPreviewBox(0f, 125f, 100f, 175f))
        val first = UiMovePreviewGeometry.arrange(blocks, requested, emptyList())
        val boundary = first.destinations.getValue("first").right
        requested["second"] = UiPreviewBox(boundary - 50f, 125f, boundary + 50f, 175f)
        val result = UiMovePreviewGeometry.arrange(blocks, requested, emptyList())
        assertEquals(setOf("first", "second"), result.equalRowMoves)
        assertEquals(0f, result.destinations.getValue("first").left, .001f)
        assertEquals(100f, result.destinations.getValue("second").left, .001f)
        assertEquals(200f, result.blocks.getValue("row").left, .001f)
        assertEquals(100f, result.blocks.getValue("row").top, .001f)
    }

    @Test fun repeatedVisibleSlotInsertionFitsTwelveControlsAndCanUndo() {
        val blocks = mapOf("row" to UiPreviewBox(0f, 100f, 360f, 200f))
        val requests = linkedMapOf<String, UiPreviewBox>()
        var boundary = 1f
        repeat(11) { index ->
            requests["move$index"] = UiPreviewBox(boundary - 30f, 125f, boundary + 30f, 175f)
            val result = UiMovePreviewGeometry.arrange(blocks, requests, emptyList())
            assertEquals(index + 1, result.equalRowMoves.size)
            val all = (blocks + result.blocks).values + result.destinations.values
            assertTrue(all.all { kotlin.math.abs(it.width - 360f / (index + 2)) < .001f })
            assertTrue(all.all { it.top in 100f..125f })
            assertTrue(all.sortedBy { it.left }.zipWithNext().none { (a, b) -> a.overlaps(b) })
            boundary = result.blocks.getValue("row").left
            assertEquals(result, UiMovePreviewGeometry.arrange(blocks, requests, emptyList()))
        }
        requests.remove("move10")
        assertEquals(360f / 11, UiMovePreviewGeometry.arrange(blocks, requests, emptyList())
            .destinations.getValue("move0").width, .001f)
    }

    @Test fun visibleSlotBetweenTwoDestinationsAndStandaloneDestinationAreTargets() {
        val blocks = mapOf("row" to UiPreviewBox(0f, 100f, 300f, 200f))
        val requests = linkedMapOf("first" to UiPreviewBox(0f, 125f, 100f, 175f),
            "second" to UiPreviewBox(100f, 125f, 200f, 175f),
            "middle" to UiPreviewBox(50f, 125f, 150f, 175f))
        val result = UiMovePreviewGeometry.arrange(blocks, requests, emptyList())
        assertEquals(listOf("first", "middle", "second"), result.destinations.entries.sortedBy { it.value.left }.map { it.key })
        assertTrue(result.destinations.values.all { it.width == 75f })
        val free = UiMovePreviewGeometry.arrange(emptyMap(), linkedMapOf(
            "first" to UiPreviewBox(0f, 100f, 300f, 200f),
            "second" to UiPreviewBox(240f, 125f, 340f, 175f)), emptyList())
        assertEquals(setOf("first", "second"), free.equalRowMoves)
        assertTrue(free.destinations.values.all { it.width == 150f })
    }

    @Test fun oneExistingControlAndMoveAlwaysSplitTheRowEqually() {
        val row = UiPreviewBox(20f, 100f, 320f, 200f)
        listOf(35f, 80f, 140f, 210f, 300f).forEach { x ->
            val plan = UiMovePreviewGeometry.arrange(mapOf("row" to row),
                mapOf("move" to UiPreviewBox(x - 120f, 120f, x + 120f, 180f)), emptyList())
            val moved = plan.destinations.getValue("move")
            val existing = plan.blocks.getValue("row")
            assertEquals(150f, moved.width, .001f)
            assertEquals(moved.width, existing.width, .001f)
            assertEquals(if (x < 170f) 20f else 170f, moved.left, .001f)
            assertEquals(row.top, existing.top, 0f)
            assertFalse(moved.overlaps(existing))
        }
    }

    @Test fun twoControlsAndMoveBecomeThreeEqualSlotsAtLeftMiddleOrRight() {
        val blocks = mapOf("a" to UiPreviewBox(0f, 100f, 150f, 200f), "b" to UiPreviewBox(150f, 100f, 300f, 200f))
        listOf(10f, 150f, 290f).forEachIndexed { index, x ->
            val plan = UiMovePreviewGeometry.arrange(blocks,
                mapOf("move" to UiPreviewBox(x - 150f, 125f, x + 150f, 175f)), emptyList())
            val moved = plan.destinations.getValue("move")
            assertEquals(100f, moved.width, .001f)
            assertEquals(index * 100f, moved.left, .001f)
            assertTrue(plan.blocks.values.all { kotlin.math.abs(it.width - 100f) < .001f })
            assertTrue(plan.blocks.values.none(moved::overlaps))
        }
    }

    @Test fun gapsAndInsetsArePreservedAndDifferentParentsAreNotMerged() {
        val blocks = mapOf("a" to UiPreviewBox(20f, 100f, 160f, 200f), "b" to UiPreviewBox(180f, 100f, 320f, 200f))
        val request = mapOf("move" to UiPreviewBox(130f, 125f, 210f, 175f))
        val plan = UiMovePreviewGeometry.arrange(blocks, request, emptyList())
        assertEquals(260f / 3, plan.destinations.getValue("move").width, .001f)
        assertEquals(20f, plan.blocks.getValue("a").left, .001f)
        assertEquals(320f, plan.blocks.getValue("b").right, .001f)
        val separated = UiMovePreviewGeometry.arrange(blocks, request, emptyList(), mapOf("a" to "1", "b" to "2"))
        assertEquals(70f, separated.destinations.getValue("move").width, .001f)
        assertFalse(separated.blocks.containsKey("b"))
    }

    @Test fun multipleMovesInOneRowShareTheSameWidthWithoutOverlaps() {
        val blocks = mapOf("row" to UiPreviewBox(0f, 100f, 300f, 200f))
        val plan = UiMovePreviewGeometry.arrange(blocks, linkedMapOf(
            "left" to UiPreviewBox(-100f, 120f, 200f, 180f),
            "right" to UiPreviewBox(100f, 120f, 400f, 180f)), emptyList())
        val all = plan.blocks.values + plan.destinations.values
        assertTrue(all.all { it.width == 100f })
        assertEquals(listOf(0f, 100f, 200f), all.map { it.left }.sorted())
    }

    @Test fun emptySpaceAndTopOrBottomInsertionKeepOriginalWidth() {
        val row = UiPreviewBox(0f, 100f, 300f, 200f)
        listOf(UiPreviewBox(0f, 80f, 300f, 120f), UiPreviewBox(0f, 180f, 300f, 220f),
            UiPreviewBox(0f, 250f, 300f, 290f)).forEach { destination ->
            val plan = UiMovePreviewGeometry.arrange(mapOf("row" to row), mapOf("move" to destination), emptyList())
            assertEquals(destination, plan.destinations["move"])
            assertTrue(plan.equalRowMoves.isEmpty())
            assertEquals(300f, (plan.blocks["row"] ?: row).width, .001f)
        }
        assertTrue(UiMovePreviewGeometry.arrange(mapOf("row" to row), emptyMap(), emptyList()).blocks.isEmpty())
    }

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
