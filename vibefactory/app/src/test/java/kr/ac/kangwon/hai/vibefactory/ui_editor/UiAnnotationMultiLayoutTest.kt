package kr.ac.kangwon.hai.vibefactory.ui_editor

import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UiAnnotationMultiLayoutTest {
    private val xml = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent" android:layout_height="match_parent">
        <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        </LinearLayout>"""
    private fun record(layout: String, configuration: String = "layout", revision: String = "rev_0001") = UiAnnotationDraftRecord(
        "synthetic", revision, layout, configuration, "sha", xml, "annotations", emptyList(), updatedAt = "2026-10-16T00:00:00Z")

    @Test fun newerWriteOnOneScreenDoesNotDiscardAnEarlierWriteOnAnother() = runBlocking {
        val model = UiAnnotationViewModel()
        val writes = mutableListOf<UiAnnotationDraftRecord>()
        model.persistRecord(record("main"), 4) { writes += it }
        model.persistRecord(record("calendar"), 3) { writes += it }
        model.persistRecord(record("calendar"), 2) { fail("Stale write replaced this screen's newer draft") }
        model.persistRecord(record("main", "layout-land"), 1) { writes += it }
        model.persistRecord(record("main", revision = "rev_0002"), 1) { writes += it }
        assertEquals(4, writes.size)
        assertEquals("calendar", writes[1].layoutName)
    }

    @Test fun allEditedScreensRemainCountedAfterRenderingCacheEvictionAndEmptyScreenSelection() {
        val model = UiAnnotationViewModel()
        val document = AndroidXmlDocument.parse(xml)
        val target = UiAnnotationTarget("id:title", "@+id/title", "0.0", "TextView", "title", "",
            UiNormalizedRect(.1f, .1f, .4f, .2f), "", "")
        repeat(7) { index ->
            model.annotationCounts()
            val session = model.initialize("synthetic", "rev_0001", UiLayoutSummaryDto(layout_name = "page_$index"),
                document, ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
            session.annotations += UiAnnotation(action = UiAnnotationAction.BEHAVIOR, target = target, instruction = "change")
        }
        model.annotationCounts()
        model.initialize("synthetic", "rev_0001", UiLayoutSummaryDto(layout_name = "empty"),
            document, ResolvedUiResources.EMPTY, 0, emptyList(), emptySet(), emptySet(), null)
        assertEquals(7, model.annotationCounts().values.sum())
        assertEquals(7, model.annotationCounts().values.count { it > 0 })
        assertTrue(model.cachedSessions().size <= 4)
    }
}
