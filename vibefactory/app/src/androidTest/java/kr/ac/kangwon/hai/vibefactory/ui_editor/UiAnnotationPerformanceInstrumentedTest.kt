package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto

@RunWith(AndroidJUnit4::class)
class UiAnnotationPerformanceInstrumentedTest {
    @Test
    fun cachedLayoutMenuModelBuildsBelowOneHundredMilliseconds() {
        val layouts = (0 until 500).map { index ->
            UiLayoutSummaryDto(
                layout_name = when (index % 4) {
                    0 -> "activity_screen_$index"
                    1 -> "dialog_help_$index"
                    2 -> "component_summary_$index"
                    else -> "item_result_$index"
                },
                configuration = if (index % 5 == 0) "layout-land" else "layout",
                display_name = "화면 항목 $index",
                layout_kind = listOf("screen", "dialog", "component", "item")[index % 4],
                guide_available = index % 3 == 0
            )
        }

        val startedAt = SystemClock.elapsedRealtimeNanos()
        val groups = UiLayoutPresentation.groups(layouts)
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt

        println("UI_LAYOUT_MENU_MODEL_MS=${elapsedNanos / 1_000_000.0}")
        assertEquals(4, groups.size)
        assertEquals(500, groups.sumOf { it.layouts.size })
        assertTrue("Layout menu model exceeded 100ms", elapsedNanos < 100_000_000L)
    }

    @Test
    fun cachedLayoutSwitchRestoresAndRendersBelowThreeHundredMilliseconds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = UiAnnotationViewModel()
        val firstLayout = UiLayoutSummaryDto(
            layout_name = "activity_main",
            display_name = "메인 화면",
            layout_kind = "screen"
        )
        val secondLayout = UiLayoutSummaryDto(
            layout_name = "activity_settings",
            display_name = "설정 화면",
            layout_kind = "screen"
        )
        fun document(title: String) = AndroidXmlDocument.parse(
            """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:id="@+id/title" android:layout_width="wrap_content"
                    android:layout_height="wrap_content" android:text="$title" />
                <Button android:id="@+id/action" android:layout_width="wrap_content"
                    android:layout_height="wrap_content" android:text="확인" />
            </LinearLayout>""".trimIndent()
        )
        viewModel.initialize(
            "performance_task",
            "rev_0001",
            firstLayout,
            document("메인"),
            ResolvedUiResources.EMPTY,
            0,
            emptyList(),
            emptySet(),
            emptySet(),
            null
        )
        viewModel.initialize(
            "performance_task",
            "rev_0001",
            secondLayout,
            document("설정"),
            ResolvedUiResources.EMPTY,
            0,
            emptyList(),
            emptySet(),
            emptySet(),
            null
        )

        var elapsedNanos = Long.MAX_VALUE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val canvas = FrameLayout(context)
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val restored = checkNotNull(
                viewModel.restoreCachedSession("performance_task", "rev_0001", firstLayout)
            )
            UiPreviewRenderer(context).render(
                document = restored.document,
                resources = restored.resources,
                canvas = canvas,
                previewChildren = restored.previewChildren,
                dynamicTextViewIds = restored.previewDynamicTextViewIds,
                hiddenViewIds = restored.previewHiddenViewIds
            )
            elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
        }

        val elapsedMillis = elapsedNanos / 1_000_000.0
        println("UI_CACHED_LAYOUT_SWITCH_MS=$elapsedMillis")
        assertTrue("Cached layout switch took ${elapsedMillis}ms", elapsedNanos < 300_000_000L)
    }

    @Test
    fun moveEndpointKeepsTheAutoScrollAdjustedTouchPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var destination: Pair<Float, Float>? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            val overlay = UiAnnotationOverlayView(context)
            parent.addView(overlay, FrameLayout.LayoutParams(1000, 1000))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, 1000, 1000)
            overlay.showPendingMove(annotation(1).target)
            overlay.destinationTapListener = { x, y -> destination = x to y }

            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 200f, 300f, 0).also {
                overlay.dispatchTouchEvent(it)
                it.recycle()
            }
            overlay.offsetPendingPointBy(100f, 200f)
            MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, 200f, 300f, 0).also {
                overlay.dispatchTouchEvent(it)
                it.recycle()
            }
        }

        assertEquals(0.3f, destination?.first ?: -1f, 0.0001f)
        assertEquals(0.5f, destination?.second ?: -1f, 0.0001f)
    }

    @Test
    fun fortyAnnotationsKeepDragFeedbackBelowOneHundredMilliseconds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val annotations = (0 until 40).map { index -> annotation(index) }
        var maximumNanos = 0L

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val overlay = UiAnnotationOverlayView(context)
            val width = 1080
            val height = 1800
            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            overlay.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            overlay.showAnnotations(annotations)
            overlay.draw(canvas)

            repeat(30) { sample ->
                val startedAt = SystemClock.elapsedRealtimeNanos()
                overlay.showHover(
                    action = UiAnnotationAction.entries[sample % UiAnnotationAction.entries.size],
                    bounds = annotation(sample).target.bounds
                )
                overlay.draw(canvas)
                maximumNanos = maxOf(
                    maximumNanos,
                    SystemClock.elapsedRealtimeNanos() - startedAt
                )
            }
            bitmap.recycle()
        }

        val maximumMillis = maximumNanos / 1_000_000.0
        println("UI_ANNOTATION_OVERLAY_MAX_MS=$maximumMillis")
        assertTrue("Maximum feedback latency was ${maximumMillis}ms", maximumNanos < 100_000_000L)
    }

    @Test
    fun largeDraftWithFortyAnnotationsSavesAndRestoresBelowFiveHundredMilliseconds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = UiAnnotationDraftStore(context, Gson())
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val annotations = (0 until 40).map { index -> annotation(index) }
        val originalXml = "<LinearLayout><!--${"x".repeat(250_000)}--></LinearLayout>"
        val annotationXml = UiAnnotationXmlCodec.encode(
            taskId = "performance_$suffix",
            revisionLabel = "rev_0001",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = "a".repeat(64),
            annotations = annotations
        )
        val record = UiAnnotationDraftRecord(
            taskId = "performance_$suffix",
            revisionLabel = "rev_0001",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = "a".repeat(64),
            originalXml = originalXml,
            annotationXml = annotationXml,
            annotations = annotations,
            updatedAt = Instant.now().toString()
        )

        val saveStartedAt = SystemClock.elapsedRealtimeNanos()
        store.save(record)
        val saveNanos = SystemClock.elapsedRealtimeNanos() - saveStartedAt
        val loadStartedAt = SystemClock.elapsedRealtimeNanos()
        val restored = store.load(
            record.taskId,
            record.revisionLabel,
            record.layoutName,
            record.configuration
        )
        val loadNanos = SystemClock.elapsedRealtimeNanos() - loadStartedAt

        println("UI_ANNOTATION_DRAFT_SAVE_MS=${saveNanos / 1_000_000.0}")
        println("UI_ANNOTATION_DRAFT_LOAD_MS=${loadNanos / 1_000_000.0}")
        assertTrue("Draft save exceeded 500ms", saveNanos < 500_000_000L)
        assertTrue("Draft restore exceeded 500ms", loadNanos < 500_000_000L)
        assertEquals(40, restored?.annotations?.size)
        assertEquals(originalXml, restored?.originalXml)
    }

    private fun annotation(index: Int): UiAnnotation {
        val column = index % 4
        val row = index / 4
        val left = 0.03f + column * 0.24f
        val top = 0.02f + row * 0.085f
        return UiAnnotation(
            annotationId = "performance_$index",
            action = UiAnnotationAction.entries[index % UiAnnotationAction.entries.size],
            target = UiAnnotationTarget(
                stableId = if (index % 2 == 0) "id:item_$index" else "runtime:row_$index",
                resourceId = if (index % 2 == 0) "@+id/item_$index" else "",
                hierarchyPath = "0.${index / 4}.$column",
                className = "android.widget.TextView",
                text = "Item $index",
                contentDescription = "Item $index",
                bounds = UiNormalizedRect(left, top, left + 0.2f, top + 0.06f),
                previousSibling = "item_${index - 1}",
                nextSibling = "item_${index + 1}"
            ),
            destinationX = if (index % 3 == 1) 0.8f else null,
            destinationY = if (index % 3 == 1) 0.9f else null,
            instruction = "Performance annotation $index"
        )
    }
}
