package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAdditionPreviewInstrumentedTest {
    private fun fixture(minimumWidthDp: Int = 0, block: (AndroidXmlDocument, FrameLayout, Map<String, View>, UiMovePreviewLayout) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val document = AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@+id/source" android:layout_width="120dp" android:layout_height="60dp"/>
                <LinearLayout android:id="@+id/row" android:layout_width="360dp" android:layout_height="80dp" android:orientation="horizontal">
                    <TextView android:id="@+id/a" android:minWidth="${minimumWidthDp}dp" android:layout_width="0dp" android:layout_weight="1" android:layout_height="80dp"/>
                    <TextView android:id="@+id/b" android:layout_width="0dp" android:layout_weight="1" android:layout_height="80dp"/>
                </LinearLayout>
                <TextView android:id="@+id/next" android:layout_width="360dp" android:layout_height="60dp"/>
            </LinearLayout>""")
            val canvas = FrameLayout(context)
            val views = UiPreviewRenderer(context).render(document, ResolvedUiResources.EMPTY, canvas).nodeViews
            val density = context.resources.displayMetrics.density
            canvas.measure(View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.EXACTLY))
            canvas.layout(0, 0, canvas.measuredWidth, canvas.measuredHeight)
            block(document, canvas, views, UiMovePreviewLayout(canvas, document, views))
        }
    }

    private fun target(id: String, bounds: UiNormalizedRect = UiNormalizedRect(0f, 0f, 1f, 1f)) =
        UiAnnotationTarget(id, "@+id/${id.removePrefix("id:")}", "0", "TextView", "", "", bounds, "", "")

    private fun add(canvas: FrameLayout, left: Float, top: Float, width: Float, height: Float): UiAnnotation {
        val d = canvas.resources.displayMetrics.density
        return UiAnnotation(action = UiAnnotationAction.ADD, target = target("id:row"), instruction = "버튼 추가",
            addition = UiAdditionSpec(UiNormalizedRect(left * d / canvas.width, top * d / canvas.height,
                (left + width) * d / canvas.width, (top + height) * d / canvas.height), -1,
                strokes = listOf(UiSketchStroke(listOf(UiSketchPoint(.1f, .2f), UiSketchPoint(.8f, .9f)), -16777216)),
                referenceCanvasWidthDp = canvas.width / d, referenceCanvasHeightDp = canvas.height / d))
    }

    @Test fun fixedRegionRemeasuresEqualNeighboursRestoresXmlAndSurvivesSerialization() = fixture { doc, canvas, views, preview ->
        val d = canvas.resources.displayMetrics.density
        val annotation = add(canvas, 0f, 60f, 100f, 80f)
        val original = views.getValue("id:a").width
        preview.apply(emptyList(), additions = listOf(annotation))
        assertEquals(130f * d, views.getValue("id:a").width.toFloat(), 1f)
        assertEquals(130f * d, views.getValue("id:b").width.toFloat(), 1f)
        assertEquals(100f * d, views.getValue("id:a").translationX, 1f)
        assertEquals(0f, views.getValue("id:next").translationY, 1f)
        assertFalse(doc.hasChanges)
        assertTrue(preview.previewDocument.xml().contains("android:translationX"))
        val saved = UiAnnotationXmlCodec.encode("fixture", "rev_0001", "activity_main", "layout", doc.originalSha256, listOf(annotation))
        val decoded = UiAnnotationXmlCodec.decode(saved)
        assertEquals(annotation.addition, decoded.single().addition)
        preview.apply(emptyList())
        assertEquals(original, views.getValue("id:a").width)
        assertEquals(0f, views.getValue("id:a").translationX, .01f)
        assertEquals(doc.xml(), preview.previewDocument.xml())
        preview.apply(emptyList(), additions = decoded)
        assertEquals(130f * d, views.getValue("id:a").width.toFloat(), 1f)
    }

    @Test fun fullWidthRegionWrapsWithoutChangingSketchOrReferenceCanvas() = fixture { _, canvas, views, preview ->
        val d = canvas.resources.displayMetrics.density
        val annotation = add(canvas, 0f, 60f, 360f, 240f)
        val beforeWidth = views.getValue("id:b").width
        val referenceHeight = preview.height
        preview.apply(emptyList(), additions = listOf(annotation))
        assertEquals(beforeWidth, views.getValue("id:b").width)
        assertTrue(views.getValue("id:b").translationY >= 240f * d - 1f)
        assertTrue(canvas.minimumHeight > referenceHeight)
        assertEquals(referenceHeight, preview.height, 0f)
        assertEquals(.1f, annotation.addition!!.strokes.single().points.first().x, 0f)
        preview.apply(emptyList())
        assertEquals(0f, views.getValue("id:b").translationY, .01f)
        assertEquals(referenceHeight.toInt(), canvas.minimumHeight)
    }

    @Test fun explicitReplacementIsNotDisplacedAndCancelRestoresOtherControls() = fixture { _, canvas, views, preview ->
        val original = views.getValue("id:a").width
        val originalNeighbour = views.getValue("id:b").width
        val addition = add(canvas, 0f, 60f, 180f, 80f).let {
            it.copy(addition = it.addition!!.copy(replaceTargets = listOf(target("id:a"))))
        }
        preview.apply(emptyList(), additions = listOf(addition))
        assertEquals(original, views.getValue("id:a").width)
        assertEquals(originalNeighbour, views.getValue("id:b").width)
        assertEquals(0f, views.getValue("id:a").translationY, 0f)
        assertEquals(0f, views.getValue("id:b").translationY, 0f)
        preview.apply(emptyList())
        assertEquals(original, views.getValue("id:a").width)
    }

    @Test fun declaredMinimumWidthDoesNotForceSideInsertionDownward() = fixture(180) { _, canvas, views, preview ->
        val original = views.getValue("id:a").width
        val d = canvas.resources.displayMetrics.density
        assertEquals(original, views.getValue("id:a").minimumWidth)
        preview.apply(emptyList(), additions = listOf(add(canvas, 0f, 60f, 100f, 80f)))
        assertEquals(130f * d, views.getValue("id:a").width.toFloat(), 1f)
        assertEquals(130f * d, views.getValue("id:b").width.toFloat(), 1f)
        assertEquals(0f, views.getValue("id:a").translationY, 0f)
        assertEquals(0f, views.getValue("id:b").translationY, 0f)
        preview.apply(emptyList())
        assertEquals(original, views.getValue("id:a").width)
        assertEquals(original, views.getValue("id:a").minimumWidth)
    }

    @Test fun tallLeftAndRightRegionsResizeAllRowsBesideThemWithoutMovingDown() = fixture(180) { doc, canvas, views, preview ->
        val d = canvas.resources.displayMetrics.density
        listOf(0f, 260f).forEach { left ->
            val annotation = add(canvas, left, 60f, 100f, 240f)
            val bounds = annotation.addition!!.bounds
            preview.apply(emptyList(), additions = listOf(annotation))
            assertEquals(130f * d, views.getValue("id:a").width.toFloat(), 1f)
            assertEquals(130f * d, views.getValue("id:b").width.toFloat(), 1f)
            assertEquals(260f * d, views.getValue("id:next").width.toFloat(), 1f)
            listOf("id:a", "id:b", "id:next").forEach { id ->
                assertEquals(0f, views.getValue(id).translationY, 1f)
            }
            assertEquals(if (left == 0f) 100f * d else 0f, views.getValue("id:a").translationX, 1f)
            assertEquals(bounds, annotation.addition!!.bounds)
            assertFalse(doc.hasChanges)
        }
        preview.apply(emptyList())
        assertEquals(180f * d, views.getValue("id:a").width.toFloat(), 1f)
        assertEquals(360f * d, views.getValue("id:next").width.toFloat(), 1f)
    }

    @Test fun additionAndMoveCanBeRestoredIndependently() = fixture { _, canvas, views, preview ->
        val d = canvas.resources.displayMetrics.density
        val sourceView = views.getValue("id:source")
        val source = target("id:source", UiNormalizedRect(0f, 0f,
            sourceView.width.toFloat() / canvas.width, sourceView.height.toFloat() / canvas.height))
        val move = UiAnnotation(action = UiAnnotationAction.MOVE, target = source,
            destinationX = 30f * d / canvas.width, destinationY = 100f * d / canvas.height)
        preview.apply(listOf(move))
        val destination = preview.destinationBounds[move.annotationId]
        val movedWidth = views.getValue("id:a").width
        val addition = add(canvas, 0f, 140f, 360f, 60f)
        preview.apply(listOf(move), additions = listOf(addition))
        assertEquals(destination, preview.destinationBounds[move.annotationId])
        assertEquals(source.bounds, preview.displayBounds(source))
        assertTrue(views.getValue("id:next").translationY > 0f)
        preview.apply(listOf(move))
        assertEquals(movedWidth, views.getValue("id:a").width)
        assertEquals(0f, views.getValue("id:next").translationY, 1f)
        preview.apply(emptyList())
        assertEquals(180f * d, views.getValue("id:a").width.toFloat(), 1f)
    }
}
