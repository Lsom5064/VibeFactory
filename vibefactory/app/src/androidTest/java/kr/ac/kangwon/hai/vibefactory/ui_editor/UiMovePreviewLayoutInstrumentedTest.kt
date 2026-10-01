package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiMovePreviewLayoutInstrumentedTest {
    @Test fun wideDestinationAndNeighbourShareSpaceAndRestoreAfterDraftRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val document = AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@+id/source" android:layout_width="900px" android:layout_height="100px"/>
                <TextView android:id="@+id/other" android:layout_width="900px" android:layout_height="200px"/>
            </LinearLayout>""")
            val canvas = FrameLayout(context)
            val views = UiPreviewRenderer(context).render(document, ResolvedUiResources.EMPTY, canvas).nodeViews
            canvas.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
            canvas.layout(0, 0, 1000, 1200)
            val target = UiAnnotationTarget("id:source", "@+id/source", "0.0", "TextView", "", "",
                UiNormalizedRect(0f, 0f, .9f, 100f / 1200), "", "")
            val preview = UiMovePreviewLayout(canvas, document, views)
            listOf(.15f, .75f).forEach { x ->
                val move = UiAnnotation(action = UiAnnotationAction.MOVE, target = target, destinationX = x, destinationY = 200f / 1200)
                preview.apply(listOf(move))
                val fitted = preview.destinationBounds.getValue(move.annotationId)
                assertEquals(.45f, fitted.right - fitted.left, .001f)
                assertEquals(if (x < .5f) .225f else .675f, (fitted.left + fitted.right) / 2, .001f)
                assertEquals(450, views.getValue("id:other").width)
                assertEquals(900, views.getValue("id:source").width)
                assertEquals(target.bounds, preview.displayBounds(target))
                val saved = UiAnnotationXmlCodec.encode("fixture", "rev_0001", "activity_main", "layout", document.originalSha256, listOf(preview.resolvedMove(move)))
                preview.apply(emptyList())
                assertTrue(preview.destinationBounds.isEmpty())
                assertEquals(900, views.getValue("id:other").width)
                preview.apply(UiAnnotationXmlCodec.decode(saved))
                val restored = preview.destinationBounds.getValue(move.annotationId)
                assertEquals(fitted.left, restored.left, .00001f)
                assertEquals(fitted.right, restored.right, .00001f)
                assertEquals(fitted.top, restored.top, .00001f)
                assertEquals(fitted.bottom, restored.bottom, .00001f)
                preview.apply(listOf(move.copy(destinationY = .6f)))
                val free = preview.destinationBounds.getValue(move.annotationId)
                assertEquals(.9f, free.right - free.left, .001f)
                assertEquals(900, views.getValue("id:other").width)
            }
            assertFalse(document.hasChanges)
        }
    }

    @Test fun sideInsertionRemeasuresNestedContentAndRestoresWidthsAndXml() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val document = AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@+id/source" android:layout_width="200px" android:layout_height="100px" android:text="이동 UI"/>
                <LinearLayout android:id="@+id/card" android:layout_width="900px" android:layout_height="200px"
                    android:orientation="vertical" android:background="#EEEEEE">
                    <TextView android:id="@+id/child" android:layout_width="match_parent" android:layout_height="match_parent" android:text="기존 UI"/>
                </LinearLayout>
                <TextView android:id="@+id/next" android:layout_width="900px" android:layout_height="100px" android:text="다음 UI"/>
            </LinearLayout>""")
            val canvas = FrameLayout(context)
            val renderer = UiPreviewRenderer(context)
            val views = renderer.render(document, ResolvedUiResources.EMPTY, canvas).nodeViews
            fun layout(view: View) {
                view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1000, 1200)
            }
            layout(canvas)
            val target = UiAnnotationTarget("id:source", "@+id/source", "0.0", "TextView", "", "",
                UiNormalizedRect(0f, 0f, .2f, 100f / 1200), "", "")
            val preview = UiMovePreviewLayout(canvas, document, views)
            listOf(.1f, .8f, .1f).forEach { x ->
                val move = UiAnnotation(action = UiAnnotationAction.MOVE, target = target,
                    destinationX = x, destinationY = 200f / 1200)
                preview.apply(listOf(move))
                layout(canvas) // A subsequent parent layout must not undo the live result.
                assertEquals(450, views.getValue("id:card").width)
                assertEquals(450, views.getValue("id:child").width)
                assertEquals(1f, views.getValue("id:card").scaleX, 0f)
                assertEquals(if (x < .5f) 450f else 0f, views.getValue("id:card").translationX, 1f)
                assertEquals(0f, views.getValue("id:card").translationY, .01f)
                assertEquals(target.bounds, preview.displayBounds(target))
                assertEquals(900, views.getValue("id:next").width)
                assertFalse(document.hasChanges)
                val rebuiltCanvas = FrameLayout(context)
                val rebuilt = renderer.render(preview.previewDocument, ResolvedUiResources.EMPTY, rebuiltCanvas)
                layout(rebuiltCanvas)
                assertEquals(450, rebuilt.nodeViews.getValue("id:card").width)
                assertEquals(views.getValue("id:card").translationX, rebuilt.nodeViews.getValue("id:card").translationX, 1f)
            }
            preview.apply(emptyList())
            layout(canvas)
            assertEquals(900, views.getValue("id:card").width)
            assertEquals(900, views.getValue("id:child").width)
            assertEquals(0f, views.getValue("id:card").translationX, .01f)
            assertEquals(document.xml(), preview.previewDocument.xml())
        }
    }

    @Test fun horizontalWeightedSourceKeepsItsOriginalPositionAndSize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val document = AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="1000px" android:layout_height="300px" android:orientation="horizontal">
                <TextView android:id="@+id/other" android:layout_width="0px" android:layout_weight="4" android:layout_height="300px"/>
                <TextView android:id="@+id/source" android:layout_width="0px" android:layout_weight="1" android:layout_height="100px"/>
            </LinearLayout>""")
            val canvas = FrameLayout(context)
            val views = UiPreviewRenderer(context).render(document, ResolvedUiResources.EMPTY, canvas).nodeViews
            canvas.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            canvas.layout(0, 0, 1000, 600)
            val target = UiAnnotationTarget("id:source", "@+id/source", "0.1", "TextView", "", "",
                UiNormalizedRect(.8f, 0f, 1f, 100f / 600), "", "")
            val preview = UiMovePreviewLayout(canvas, document, views)
            val move = UiAnnotation(action = UiAnnotationAction.MOVE, target = target,
                destinationX = .1f, destinationY = .25f)
            repeat(3) {
                preview.apply(listOf(move))
                assertEquals(400, views.getValue("id:other").width)
                assertEquals(200, views.getValue("id:source").width)
                assertEquals(target.bounds, preview.displayBounds(target))
                preview.apply(emptyList())
                assertEquals(800, views.getValue("id:other").width)
                assertEquals(target.bounds, preview.displayBounds(target))
            }
        }
    }

    @Test fun reservedDestinationChangesPreviewXmlAndPreservesSourceThroughUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val document = AndroidXmlDocument.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@+id/source" android:layout_width="120dp" android:layout_height="50dp" android:text="원본"/>
                <TextView android:id="@+id/other" android:layout_width="120dp" android:layout_height="50dp" android:text="기존 UI"/>
                <TextView android:id="@+id/next" android:layout_width="120dp" android:layout_height="50dp" android:text="다음 UI"/>
            </LinearLayout>""")
            val canvas = FrameLayout(context)
            val renderer = UiPreviewRenderer(context)
            val result = renderer.render(document, ResolvedUiResources.EMPTY, canvas)
            fun layout(view: View) {
                view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1000, 400)
            }
            layout(canvas)
            val source = result.nodeViews.getValue("id:source")
            val other = result.nodeViews.getValue("id:other")
            val next = result.nodeViews.getValue("id:next")
            val sourceRect = Rect(0, 0, source.width, source.height)
            canvas.offsetDescendantRectToMyCoords(source, sourceRect)
            val bounds = UiNormalizedRect(sourceRect.left / 1000f, sourceRect.top / 400f,
                sourceRect.right / 1000f, sourceRect.bottom / 400f)
            val target = UiAnnotationTarget("id:source", "@+id/source", "0.0", "TextView", "원본", "", bounds, "", "")
            val move = UiAnnotation(action = UiAnnotationAction.MOVE, target = target,
                destinationX = source.width / 2000f, destinationY = (other.top + other.height / 2f) / 400f)
            val preview = UiMovePreviewLayout(canvas, document, result.nodeViews)
            preview.apply(listOf(move))
            assertEquals(0f, source.translationY, .01f)
            assertTrue(other.translationY >= source.height - 1)
            assertTrue(next.translationY >= source.height - 1)
            assertEquals(bounds, preview.displayBounds(target))
            assertFalse(document.hasChanges)
            assertTrue(preview.previewDocument.xml().contains("android:translationY"))
            assertTrue(canvas.minimumHeight > 400)
            assertEquals(400f, preview.height, .01f)
            // Re-rendering the edited XML produces the same translated UI.
            val rebuilt = renderer.render(preview.previewDocument, ResolvedUiResources.EMPTY, FrameLayout(context))
            assertEquals(other.translationY, rebuilt.nodeViews.getValue("id:other").translationY, 1f)
            preview.apply(emptyList())
            assertEquals(0f, other.translationY, .01f)
            assertEquals(0f, next.translationY, .01f)
            assertEquals(document.xml(), preview.previewDocument.xml())
        }
    }
}
