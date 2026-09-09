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
