package kr.ac.kangwon.hai.vibefactory

import kr.ac.kangwon.hai.vibefactory.ui_editor.AndroidXmlDocument
import kr.ac.kangwon.hai.vibefactory.ui_editor.ResolvedUiResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiEditorXmlDocumentTest {
    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:unknown="keep-this">
    <!-- preserve this comment -->
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/title" />
    <com.example.CustomWidget
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:tag="unknown-attribute" />
</androidx.constraintlayout.widget.ConstraintLayout>
"""

    @Test
    fun noOpReturnsExactCanonicalXmlAndStableIds() {
        val document = AndroidXmlDocument.parse(xml, AndroidXmlDocument.sha256(xml))

        assertFalse(document.hasChanges)
        assertEquals(xml, document.xml())
        assertEquals("id:root", document.root.stableId)
        assertEquals("id:title", document.root.children[0].stableId)
        assertTrue(document.root.children[1].stableId.startsWith("path:"))
    }

    @Test
    fun unsupportedTagsAndUnknownAttributesRemainLockedAndPresent() {
        val document = AndroidXmlDocument.parse(xml)
        val custom = document.root.children.last()

        assertFalse(custom.supported)
        assertTrue(custom.locked)
        assertEquals("unknown-attribute", custom.androidAttribute("tag"))
        assertTrue(document.xml().contains("tools:unknown=\"keep-this\""))
        assertTrue(document.xml().contains("preserve this comment"))
    }

    @Test
    fun rejectsDtdBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidXmlDocument.parse(
                "<!DOCTYPE x [<!ENTITY y SYSTEM \"file:///etc/passwd\">]><TextView>&y;</TextView>"
            )
        }
    }

    @Test
    fun rejectsUnexpectedServerHash() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidXmlDocument.parse(xml, "0".repeat(64))
        }
    }

    @Test
    fun resolvesValuesResourcesWithoutReplacingUnknownReferences() {
        val resources = ResolvedUiResources.from(
            listOf(
                UiResourceFileDto(
                    resource_path = "res/values/editor.xml",
                    kind = "xml",
                    content = """<resources>
                        <string name="title">화면 제목</string>
                        <color name="accent">#126E52</color>
                        <dimen name="space">16dp</dimen>
                        <style name="Card"><item name="android:padding">12dp</item></style>
                    </resources>"""
                )
            )
        )

        assertEquals("화면 제목", resources.text("@string/title"))
        assertEquals("@string/missing", resources.text("@string/missing"))
        assertEquals("#126E52", resources.color("@color/accent"))
        assertEquals("16dp", resources.dimen("@dimen/space"))
        assertEquals("12dp", resources.styles.getValue("Card").getValue("android:padding"))
    }

    @Test
    fun commonNativeContainersAndStatusViewsRemainEditable() {
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

        assertTrue(document.root.supported)
        assertFalse(document.root.locked)
        assertEquals("CoordinatorLayout", document.root.simpleTag)
        assertTrue(document.root.children.single().supported)
        assertFalse(document.root.children.single().locked)
    }
}
