package kr.ac.kangwon.hai.vibefactory

import kr.ac.kangwon.hai.vibefactory.ui_editor.AndroidXmlDocument
import kr.ac.kangwon.hai.vibefactory.ui_editor.ResolvedUiResources
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorDraftRecord
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorHistory
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorSession
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorSnapshot
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiEditorDraftStoreTest {
    private val xml = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:id="@+id/root" android:layout_width="match_parent"
        android:layout_height="match_parent" />"""

    @Test
    fun matchingDraftRestoresXmlDescriptionsAndSelection() {
        val base = AndroidXmlDocument.parse(xml)
        val edited = xml.replace("match_parent\" />", "wrap_content\" />")
        val layout = UiLayoutSummaryDto(layout_name = "activity_main")
        val draft = UiEditorDraftRecord(
            taskId = "task",
            revisionLabel = "rev_0001",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = base.originalSha256,
            editedXml = edited,
            descriptions = mapOf("id:root" to "root description"),
            images = emptyList(),
            selectedElementId = "id:root",
            status = "draft",
            updatedAt = "now"
        )

        val session = UiEditorViewModel().initialize(
            "task",
            "rev_0001",
            layout,
            base,
            ResolvedUiResources.EMPTY,
            draft,
            unresolvedResourceCount = 3
        )

        assertEquals(edited, session.document.xml())
        assertEquals("root description", session.descriptions["id:root"])
        assertEquals("id:root", session.selectedElementId)
        assertEquals(3, session.unresolvedResourceCount)
        assertTrue(session.history.canUndo)
    }

    @Test
    fun staleDraftIsIgnoredWhenBaseShaChanged() {
        val base = AndroidXmlDocument.parse(xml)
        val draft = UiEditorDraftRecord(
            taskId = "task",
            revisionLabel = "rev_0001",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = "0".repeat(64),
            editedXml = "<invalid />",
            descriptions = mapOf("id:root" to "stale"),
            images = emptyList(),
            selectedElementId = "id:root",
            status = "draft",
            updatedAt = "now"
        )

        val session = UiEditorViewModel().initialize(
            "task",
            "rev_0001",
            UiLayoutSummaryDto(layout_name = "activity_main"),
            base,
            ResolvedUiResources.EMPTY,
            draft
        )

        assertEquals(xml, session.document.xml())
        assertTrue(session.descriptions.isEmpty())
        assertFalse(session.history.canUndo)
    }

    @Test
    fun restoringHistoryDropsSelectionWhenElementNoLongerExists() {
        val document = AndroidXmlDocument.parse(xml)
        val session = UiEditorSession(
            taskId = "task",
            revisionLabel = "rev_0001",
            layout = UiLayoutSummaryDto(layout_name = "activity_main"),
            baseXml = document.originalXml,
            baseXmlSha256 = document.originalSha256,
            document = document,
            resources = ResolvedUiResources.EMPTY,
            unresolvedResourceCount = 0,
            isNewLayout = false,
            descriptions = mutableMapOf(),
            images = mutableListOf(),
            selectedElementId = "id:missing",
            history = UiEditorHistory(UiEditorSnapshot(xml, emptyMap(), emptyList())),
            serverDraftId = null,
            serverDraftVersion = null
        )

        session.restore(UiEditorSnapshot(xml, emptyMap(), emptyList()))

        assertEquals(null, session.selectedElementId)
    }
}
