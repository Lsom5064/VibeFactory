package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiAnnotationCoordinateSpaceTest {
    @Test fun dpDimensionsRemainSeparateFromNormalizedCoordinates() {
        val target = UiAnnotationTarget("id:source", "", "0.1", "Button", "원본", "",
            UiNormalizedRect(.1f, .2f, .4f, .3f), "", "")
        val annotation = UiAnnotation(action = UiAnnotationAction.MOVE, target = target,
            destinationX = .5f, destinationY = .75f)
        val xml = UiAnnotationXmlCodec.encode("task", "rev_0001", "activity_main", "layout", "sha",
            listOf(annotation), referenceCanvasWidthDp = 360f, referenceCanvasHeightDp = 640f,
            previewCanvasHeightDp = 820f)
        val root = SecureAndroidXml.parse(xml).documentElement
        assertEquals(360f, root.getAttribute("referenceCanvasWidthDp").toFloat(), .001f)
        assertEquals(640f, root.getAttribute("referenceCanvasHeightDp").toFloat(), .001f)
        assertEquals(820f, root.getAttribute("previewCanvasHeightDp").toFloat(), .001f)
        assertEquals(listOf(annotation), UiAnnotationXmlCodec.decode(xml))
    }
}
