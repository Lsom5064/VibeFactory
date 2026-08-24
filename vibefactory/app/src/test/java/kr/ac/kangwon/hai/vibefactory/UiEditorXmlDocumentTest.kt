package kr.ac.kangwon.hai.vibefactory

import kr.ac.kangwon.hai.vibefactory.ui_editor.AndroidXmlDocument
import kr.ac.kangwon.hai.vibefactory.ui_editor.ANDROID_NAMESPACE_URI
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
                        <color name="accent_alias">@color/accent</color>
                        <dimen name="space">16dp</dimen>
                        <dimen name="space_alias">@dimen/space</dimen>
                        <style name="Card"><item name="android:padding">12dp</item></style>
                        <style name="Card.Emphasis">
                            <item name="android:textColor">@color/accent_alias</item>
                        </style>
                        <style name="Explicit" parent="@style/Card">
                            <item name="android:textSize">18sp</item>
                        </style>
                    </resources>"""
                ),
                UiResourceFileDto(
                    resource_path = "res/layout/preview_row.xml",
                    kind = "xml",
                    content = """<TextView xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent" android:layout_height="48dp" />"""
                ),
                UiResourceFileDto(
                    resource_path = "res/drawable/card_background.xml",
                    kind = "xml",
                    content = """<shape xmlns:android="http://schemas.android.com/apk/res/android">
                        <solid android:color="@color/accent" />
                    </shape>"""
                )
            )
        )

        assertEquals("화면 제목", resources.text("@string/title"))
        assertEquals("@string/missing", resources.text("@string/missing"))
        assertEquals("#126E52", resources.color("@color/accent"))
        assertEquals("#126E52", resources.color("@color/accent_alias"))
        assertEquals("16dp", resources.dimen("@dimen/space"))
        assertEquals("16dp", resources.dimen("@dimen/space_alias"))
        assertEquals("12dp", resources.styles.getValue("Card").getValue("android:padding"))
        assertEquals(
            "12dp",
            resources.styleValue("@style/Card.Emphasis", ANDROID_NAMESPACE_URI, "padding")
        )
        assertEquals(
            "@color/accent_alias",
            resources.styleValue("@style/Card.Emphasis", ANDROID_NAMESPACE_URI, "textColor")
        )
        assertEquals("18sp", resources.styleValue("@style/Explicit", ANDROID_NAMESPACE_URI, "textSize"))
        assertTrue(resources.layout("@layout/preview_row")!!.contains("TextView"))
        assertTrue(resources.drawableXml("@drawable/card_background")!!.contains("shape"))
    }

    @Test
    fun exposesToolsAndUnqualifiedAttributesWithoutChangingRuntimeAttributes() {
        val document = AndroidXmlDocument.parse(
            """<TextView xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                style="@style/Preview"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Runtime"
                tools:text="Preview" />"""
        )

        assertEquals("Runtime", document.root.androidAttribute("text"))
        assertEquals("Preview", document.root.toolsAttribute("text"))
        assertEquals("@style/Preview", document.root.unqualifiedAttribute("style"))
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
