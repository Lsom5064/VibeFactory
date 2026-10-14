package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiAlignmentSnapperTest {
    private val canvas = UiPreviewBox(0f, 0f, 500f, 800f)
    private val peer = UiSnapTarget("peer", UiPreviewBox(100f, 100f, 180f, 140f))

    @Test fun translationAlignsNearbyEdgesWithoutResizing() {
        val raw = UiPreviewBox(104f, 104f, 184f, 144f)
        val result = UiAlignmentSnapper().translate(raw, listOf(peer), 8f, 14f, canvas, true)
        assertEquals(peer.box, result.box)
        assertEquals(raw.width, result.box.width, .001f)
        assertEquals(raw.height, result.box.height, .001f)
        assertEquals(2, result.guides.size)
        assertTrue(result.guides.all { it.kind == UiSnapKind.ALIGN })
    }

    @Test fun unequalHeightBoxesCanShareVerticalCenter() {
        val raw = UiPreviewBox(220f, 83f, 300f, 163f)
        val result = UiAlignmentSnapper().translate(raw, listOf(peer), 8f, 14f, canvas, true)
        assertEquals(80f, result.box.top, .001f)
        assertEquals(160f, result.box.bottom, .001f)
        assertEquals(.5f, result.guides.single().sourceAnchor)
    }

    @Test fun continuesPastReleaseThresholdToAllowFreePlacement() {
        val snapper = UiAlignmentSnapper()
        fun at(y: Float) = snapper.translate(UiPreviewBox(220f, y, 300f, y + 40), listOf(peer), 8f, 14f, canvas, true)
        assertEquals(100f, at(104f).box.top, .001f)
        assertEquals(100f, at(111f).box.top, .001f)
        assertEquals(116f, at(116f).box.top, .001f)
        assertTrue(at(116f).guides.isEmpty())
    }

    @Test fun doesNotOscillateBetweenTwoClosePeers() {
        val snapper = UiAlignmentSnapper()
        val second = UiSnapTarget("second", peer.box.shifted(10f))
        fun at(y: Float) = snapper.translate(UiPreviewBox(220f, y, 300f, y + 40), listOf(peer, second), 8f, 14f, canvas, true)
        assertEquals(100f, at(103f).box.top, .001f)
        assertEquals(100f, at(108f).box.top, .001f)
        assertEquals(110f, at(118f).box.top, .001f)
    }

    @Test fun resizingMatchesWidthAndHeightAndKeepsOppositeCornerFixed() {
        val raw = UiPreviewBox(250f, 300f, 333f, 343f)
        val result = UiAlignmentSnapper().resize(raw, 3, listOf(peer), 8f, 14f, canvas, 20f, 20f)
        assertEquals(UiPreviewBox(250f, 300f, 330f, 340f), result.box)
        assertEquals(setOf(UiSnapKind.WIDTH, UiSnapKind.HEIGHT), result.guides.map { it.kind }.toSet())
    }

    @Test fun allFourResizeCornersPreserveTheirOppositeCorner() {
        val starts = listOf(
            UiPreviewBox(247f, 297f, 330f, 340f), UiPreviewBox(250f, 297f, 333f, 340f),
            UiPreviewBox(247f, 300f, 330f, 343f), UiPreviewBox(250f, 300f, 333f, 343f)
        )
        starts.forEachIndexed { corner, raw ->
            val result = UiAlignmentSnapper().resize(raw, corner, listOf(peer), 8f, 14f, canvas, 20f, 20f)
            assertEquals(UiPreviewBox(250f, 300f, 330f, 340f), result.box)
        }
    }

    @Test fun sizeMatchCannotViolateMinimumOrLeaveCanvas() {
        val tiny = UiSnapTarget("tiny", UiPreviewBox(50f, 50f, 68f, 68f))
        val raw = UiPreviewBox(300f, 300f, 321f, 321f)
        val result = UiAlignmentSnapper().resize(raw, 3, listOf(tiny), 8f, 14f, canvas, 20f, 20f)
        assertEquals(raw, result.box)
        val outside = UiSnapTarget("outside", UiPreviewBox(-5f, 200f, 75f, 240f))
        val nearLeft = UiPreviewBox(2f, 200f, 82f, 240f)
        val contained = UiAlignmentSnapper().translate(nearLeft, listOf(outside), 8f, 14f, canvas, true)
        assertTrue(contained.box.left >= 0f)
    }

    @Test fun preferredSiblingWinsAnOtherwiseEqualMatch() {
        val first = peer.copy(id = "a", box = peer.box.shifted(-4f))
        val second = peer.copy(id = "b", preferred = true, box = peer.box.shifted(4f))
        val result = UiAlignmentSnapper().translate(UiPreviewBox(220f, 100f, 300f, 140f), listOf(first, second), 8f, 14f, canvas, true)
        assertEquals(104f, result.box.top, .001f)
    }

    @Test fun resetClearsPreviousGestureLock() {
        val snapper = UiAlignmentSnapper()
        snapper.translate(UiPreviewBox(220f, 104f, 300f, 144f), listOf(peer), 8f, 14f, canvas, true)
        snapper.reset()
        val result = snapper.translate(UiPreviewBox(220f, 111f, 300f, 151f), listOf(peer), 8f, 14f, canvas, true)
        assertEquals(111f, result.box.top, .001f)
        assertTrue(result.guides.isEmpty())
    }

    @Test fun snappingProducesExactNormalizedCoordinatesForXmlRoundTrip() {
        val result = UiAlignmentSnapper().resize(UiPreviewBox(250f, 300f, 333f, 343f), 3, listOf(peer), 8f, 14f, canvas, 20f, 20f)
        val bounds = UiNormalizedRect(result.box.left / canvas.width, result.box.top / canvas.height,
            result.box.right / canvas.width, result.box.bottom / canvas.height)
        val target = UiAnnotationTarget("target", "", "0", "Button", "", "", bounds, "", "")
        val addition = UiAnnotation(action = UiAnnotationAction.ADD, target = target,
            instruction = "추가", addition = UiAdditionSpec(bounds, -1))
        val xml = UiAnnotationXmlCodec.encode("test", "rev_0001", "activity_main", "xml", "sha", listOf(addition))
        val saved = UiAnnotationXmlCodec.decode(xml).single().addition!!.bounds
        assertEquals(bounds, saved)
        assertEquals(.16f, saved.right - saved.left, .001f)
        assertEquals(.05f, saved.bottom - saved.top, .001f)
    }
}
