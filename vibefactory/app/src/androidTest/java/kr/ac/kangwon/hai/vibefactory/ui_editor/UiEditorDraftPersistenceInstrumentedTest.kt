package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UiEditorDraftPersistenceInstrumentedTest {
    @Test
    fun materialWidgetsRenderUnderHostAppCompatThemeWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val document = AndroidXmlDocument.parse(
            """
                <?xml version="1.0" encoding="utf-8"?>
                <androidx.constraintlayout.widget.ConstraintLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">
                    <com.google.android.material.textview.MaterialTextView
                        android:id="@+id/title"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Note" />
                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/input_container"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="Memo">
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/input"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content" />
                    </com.google.android.material.textfield.TextInputLayout>
                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/save"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Save" />
                    <com.google.android.material.card.MaterialCardView
                        android:id="@+id/card"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content">
                        <TextView
                            android:id="@+id/card_text"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="Card" />
                    </com.google.android.material.card.MaterialCardView>
                </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent()
        )
        val canvas = FrameLayout(context)

        val result = UiPreviewRenderer(context).render(
            document = document,
            resources = ResolvedUiResources.EMPTY,
            canvas = canvas
        )
        val nodesById = document.root.descendantsAndSelf().associateBy { it.androidAttribute("id") }

        fun renderedView(id: String) = result.nodeViews.getValue(nodesById.getValue("@+id/$id").stableId)

        assertTrue(renderedView("title") is TextView)
        assertTrue(renderedView("input_container") is LinearLayout)
        assertTrue(renderedView("input") is EditText)
        assertEquals("Memo", (renderedView("input") as EditText).hint)
        assertTrue(renderedView("save") is Button)
        assertTrue(renderedView("card") is FrameLayout)
        assertEquals(1, canvas.childCount)
    }

    @Test
    fun savedDraftRestoresXmlDescriptionsSelectionAndServerVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = UiEditorDraftStore(context, Gson())
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val taskId = "instrumented_$suffix"
        val originalXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Before" />
            </LinearLayout>
        """.trimIndent()
        val base = AndroidXmlDocument.parse(originalXml)
        val title = base.root.children.single()
        UiDocumentEditor.applyPatch(base, title.stableId, UiElementPatch(text = "After"))
        val record = UiEditorDraftRecord(
            taskId = taskId,
            revisionLabel = "rev_0001",
            layoutName = "activity_main",
            configuration = "layout",
            baseXmlSha256 = base.originalSha256,
            editedXml = base.xml(),
            descriptions = mapOf(title.stableId to "Counter title remains readable."),
            images = emptyList(),
            selectedElementId = title.stableId,
            status = "draft",
            updatedAt = "2026-08-20T00:00:00Z",
            serverDraftId = "draft_$suffix",
            serverDraftVersion = 4
        )

        store.save(record)
        val restored = store.load(taskId, "rev_0001", "activity_main", "layout")
        assertNotNull(restored)

        val recreated = UiEditorViewModel().initialize(
            taskId = taskId,
            revisionLabel = "rev_0001",
            layout = UiLayoutSummaryDto(layout_name = "activity_main", configuration = "layout"),
            baseDocument = AndroidXmlDocument.parse(originalXml),
            resources = ResolvedUiResources.EMPTY,
            draft = restored,
            unresolvedResourceCount = 3
        )
        val recreatedTitle = recreated.document.root.descendantsAndSelf()
            .firstOrNull { it.stableId == title.stableId }
        assertEquals("After", recreatedTitle?.androidAttribute("text"))
        assertEquals("Counter title remains readable.", recreated.descriptions[title.stableId])
        assertEquals(title.stableId, recreated.selectedElementId)
        assertEquals("draft_$suffix", recreated.serverDraftId)
        assertEquals(4, recreated.serverDraftVersion)
        assertEquals(3, recreated.unresolvedResourceCount)
    }

    @Test
    fun commonNativeContainersRenderWithoutLockingTheirChildren() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val document = AndroidXmlDocument.parse(
            """<androidx.coordinatorlayout.widget.CoordinatorLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/root"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <ProgressBar
                    android:id="@+id/progress"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />
            </androidx.coordinatorlayout.widget.CoordinatorLayout>"""
        )
        val canvas = FrameLayout(context)

        val result = UiPreviewRenderer(context).render(document, ResolvedUiResources.EMPTY, canvas)
        val progressNode = document.root.children.single()

        assertTrue(result.rootView is FrameLayout)
        assertTrue(result.nodeViews.getValue(progressNode.stableId) is ProgressBar)
        assertTrue(result.warnings.none { it.contains("CoordinatorLayout") || it.contains("ProgressBar") })
    }
}
