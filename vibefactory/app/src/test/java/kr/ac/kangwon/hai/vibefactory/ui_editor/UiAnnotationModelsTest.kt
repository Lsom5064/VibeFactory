package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAnnotationModelsTest {
    private val target = UiAnnotationTarget(
        stableId = "id:title",
        resourceId = "@+id/title",
        hierarchyPath = "0.1",
        className = "android.widget.TextView",
        text = "제목 & 안내",
        contentDescription = "설명 <확인>",
        bounds = UiNormalizedRect(0.1f, 0.2f, 0.7f, 0.3f),
        previousSibling = "id:header",
        nextSibling = "path:0.2"
    )

    @Test
    fun `normalized rectangle clamps and orders coordinates`() {
        val result = UiNormalizedRect(1.2f, 0.8f, -0.2f, 0.1f).normalized()

        assertEquals(0f, result.left)
        assertEquals(0.1f, result.top)
        assertEquals(1f, result.right)
        assertEquals(0.8f, result.bottom)
    }

    @Test
    fun `annotation XML round trips complete target and instructions`() {
        val annotations = listOf(
            UiAnnotation(
                annotationId = "delete_1",
                action = UiAnnotationAction.DELETE,
                target = target,
                instruction = "이 영역은 삭제해 주세요."
            ),
            UiAnnotation(
                annotationId = "move_1",
                action = UiAnnotationAction.MOVE,
                target = target,
                destinationX = 0.8f,
                destinationY = 0.75f,
                instruction = "목록 아래로 이동"
            ),
            UiAnnotation(
                annotationId = "behavior_1",
                action = UiAnnotationAction.BEHAVIOR,
                target = target,
                instruction = "누르면 상세 화면을 열고 상태를 유지",
                imageIds = listOf("reference_1", "reference_2")
            )
        )
        val encoded = UiAnnotationXmlCodec.encode(
            taskId = "task_1",
            revisionLabel = "rev_0004",
            layoutName = "activity_main",
            configuration = "layout-land",
            baseXmlSha256 = "a".repeat(64),
            annotations = annotations
        )

        assertTrue(encoded.contains("제목 &amp; 안내"))
        assertTrue(encoded.contains("설명 &lt;확인&gt;"))
        assertTrue(encoded.contains("<vf:image-ref id=\"reference_1\" />"))
        assertEquals(annotations, UiAnnotationXmlCodec.decode(encoded))
    }

    @Test
    fun `explicit arrow endpoint wins over destination view center`() {
        val destination = target.copy(
            stableId = "id:destination",
            bounds = UiNormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)
        )
        val annotation = UiAnnotation(
            action = UiAnnotationAction.MOVE,
            target = target,
            destination = destination,
            destinationX = 0.86f,
            destinationY = 0.74f
        )

        assertEquals(0.86f to 0.74f, annotation.resolvedDestinationPoint())
    }

    @Test
    fun `history keeps annotations only and supports undo redo`() {
        val history = UiAnnotationHistory(emptyList())
        val first = listOf(UiAnnotation(action = UiAnnotationAction.DELETE, target = target))
        val second = first + UiAnnotation(
            action = UiAnnotationAction.BEHAVIOR,
            target = target,
            instruction = "새 동작"
        )

        history.record(first)
        history.record(second)
        assertTrue(history.canUndo)
        assertEquals(first, history.undo())
        assertTrue(history.canRedo)
        assertEquals(second, history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun `annotation XML keeps more than thirty annotations in order`() {
        val annotations = (1..40).map { index ->
            UiAnnotation(
                annotationId = "annotation_$index",
                action = UiAnnotationAction.BEHAVIOR,
                target = target,
                instruction = "동작 변경 $index"
            )
        }
        val encoded = UiAnnotationXmlCodec.encode(
            taskId = "task_1",
            revisionLabel = "rev_0004",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = "a".repeat(64),
            annotations = annotations
        )

        val restored = UiAnnotationXmlCodec.decode(encoded)
        assertEquals(40, restored.size)
        assertEquals("annotation_1", restored.first().annotationId)
        assertEquals("annotation_40", restored.last().annotationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decoder rejects non annotation XML`() {
        UiAnnotationXmlCodec.decode("<LinearLayout />")
    }
}
