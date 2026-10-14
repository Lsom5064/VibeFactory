package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiReflowWidthSnapperTest {
    private val canvas = UiPreviewBox(0f, 0f, 500f, 800f)
    private val row = mapOf("a" to UiPreviewBox(16f, 16f, 176f, 80f), "b" to UiPreviewBox(184f, 16f, 344f, 80f))
    private fun predict(box: UiPreviewBox) = UiAdditionPreviewGeometry.arrange(row, listOf(box))
        .map { (id, bounds) -> UiSnapTarget(id, bounds) }
    private fun resize(snapper: UiAlignmentSnapper, raw: UiPreviewBox, corner: Int = 2): UiSnapResult =
        snapper.resize(raw, corner, row.map { (id, box) -> UiSnapTarget(id, box) }, 8f, 14f, canvas, 64f, 48f) { adjusted ->
            UiReflowWidthSnapper.targets(adjusted, corner, canvas, 64f, 14f, ::predict)
        }

    @Test fun newRegionAndBothShrinkingNeighboursHaveTheSameFinalWidth() {
        val result = resize(UiAlignmentSnapper(), UiPreviewBox(234f, 16f, 344f, 80f))
        assertEquals(320f / 3, result.box.width, .05f)
        predict(result.box).forEach { assertEquals(result.box.width, it.box.width, .05f) }
        assertTrue(result.guides.any { it.kind == UiSnapKind.WIDTH })
        assertEquals(344f, result.box.right, .001f)
    }

    @Test fun repeatedRawPointerCoordinatesDoNotChaseTheShrinkingPeer() {
        val snapper = UiAlignmentSnapper()
        val raw = UiPreviewBox(234f, 16f, 344f, 80f)
        val first = resize(snapper, raw)
        repeat(10) { assertEquals(first.box, resize(snapper, raw).box) }
        assertEquals(first.box.width, resize(snapper, raw.copy(left = 227f)).box.width, .05f)
        val released = resize(snapper, raw.copy(left = 217f))
        assertEquals(127f, released.box.width, .05f)
        assertTrue(released.guides.none { it.kind == UiSnapKind.WIDTH })
    }

    @Test fun leftAndRightInsertionsMatchAfterResizingFromEachCorner() {
        for (corner in 0..3) {
            val fromLeft = corner == 0 || corner == 2
            val raw = if (fromLeft) UiPreviewBox(234f, 16f, 344f, 80f) else UiPreviewBox(16f, 16f, 126f, 80f)
            val result = resize(UiAlignmentSnapper(), raw, corner)
            assertEquals(320f / 3, result.box.width, .05f)
            predict(result.box).forEach { assertEquals(result.box.width, it.box.width, .05f) }
            assertEquals(if (fromLeft) raw.right else raw.left, if (fromLeft) result.box.right else result.box.left, 0f)
        }
    }

    @Test fun proportionalNeighboursKeepTheirRatioWhenOneMatchesTheNewRegion() {
        val unequal = mapOf("a" to UiPreviewBox(0f, 0f, 120f, 80f), "b" to UiPreviewBox(120f, 0f, 360f, 80f))
        fun layout(box: UiPreviewBox) = UiAdditionPreviewGeometry.arrange(unequal, listOf(box))
            .map { (id, b) -> UiSnapTarget(id, b) }
        val raw = UiPreviewBox(268f, 0f, 360f, 80f)
        val target = UiReflowWidthSnapper.targets(raw, 2, canvas, 64f, 14f, ::layout).single { it.id == "a" }
        assertEquals(90f, target.box.width, .05f)
        val final = layout(raw.copy(left = 360f - target.box.width)).associateBy { it.id }
        assertEquals(90f, final.getValue("a").box.width, .05f)
        assertEquals(180f, final.getValue("b").box.width, .05f)
    }

    @Test fun aLayoutBranchJumpWithoutEqualityDoesNotBecomeASizeMatch() {
        val targets = UiReflowWidthSnapper.targets(UiPreviewBox(200f, 200f, 300f, 264f), 3, canvas, 64f, 14f) { box ->
            listOf(UiSnapTarget("jump", UiPreviewBox(0f, 0f, if (box.width < 100f) 80f else 160f, 64f)))
        }
        assertTrue(targets.isEmpty())
    }

    @Test fun candidatesRespectMinimumWidthAndTheFixedOppositeEdge() {
        val raw = UiPreviewBox(0f, 200f, 66f, 264f)
        val targets = UiReflowWidthSnapper.targets(raw, 2, canvas, 64f, 14f) { box ->
            assertEquals(raw.right, box.right, 0f)
            assertTrue(box.left >= canvas.left && box.width >= 64f)
            listOf(UiSnapTarget("too-small", UiPreviewBox(100f, 0f, 160f, 64f)))
        }
        assertTrue(targets.isEmpty())
    }

    @Test fun sharedPredictionsDoNotRepeatLayoutWorkForIdenticalWidths() {
        var predictions = 0
        val targets = UiReflowWidthSnapper.targets(UiPreviewBox(200f, 200f, 283f, 264f), 3, canvas, 64f, 14f) {
            predictions++
            (1..50).map { id -> UiSnapTarget("$id", UiPreviewBox(0f, 0f, 80f, 64f)) }
        }
        assertEquals(50, targets.size)
        assertTrue("Reuse counterfactual layouts across peers", predictions <= 4)
    }

    @Test fun finalHeightAlignmentIsAppliedBeforePredictingRowWidths() {
        val raw = UiPreviewBox(200f, 200f, 283f, 267f)
        var predicted: UiPreviewBox? = null
        UiAlignmentSnapper().resize(raw, 3, listOf(UiSnapTarget("peer", UiPreviewBox(0f, 0f, 80f, 64f))),
            8f, 14f, canvas, 64f, 48f) { adjusted -> predicted = adjusted; emptyList() }
        assertEquals(264f, predicted!!.bottom, .001f)
        assertEquals(raw.left, predicted!!.left, 0f)
        assertEquals(raw.right, predicted!!.right, 0f)
    }
}
