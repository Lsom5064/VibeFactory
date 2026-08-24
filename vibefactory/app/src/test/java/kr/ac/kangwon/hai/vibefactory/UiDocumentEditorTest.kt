package kr.ac.kangwon.hai.vibefactory

import kr.ac.kangwon.hai.vibefactory.ui_editor.AndroidXmlDocument
import kr.ac.kangwon.hai.vibefactory.ui_editor.ANDROID_NAMESPACE_URI
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiDocumentEditor
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorHistory
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorSnapshot
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiElementPatch
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiPaletteElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDocumentEditorTest {
    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:keep="yes">
    <!-- keep-comment -->
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Before"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
    <com.example.Locked
        android:id="@+id/locked"
        android:layout_width="20dp"
        android:layout_height="20dp" />
</androidx.constraintlayout.widget.ConstraintLayout>"""

    @Test
    fun patchAndMoveProduceResponsiveConstraintMargins() {
        val document = AndroidXmlDocument.parse(xml)
        assertTrue(
            UiDocumentEditor.applyPatch(
                document,
                "id:title",
                UiElementPatch(text = "After", width = "160dp", textColor = "#126E52")
            )
        )
        assertTrue(
            UiDocumentEditor.moveBy(
                document,
                "id:title",
                24,
                40,
                renderedWidthDp = 180,
                renderedHeightDp = 48
            )
        )

        val edited = document.xml()
        assertTrue(edited.contains("android:text=\"After\""))
        assertTrue(edited.contains("android:layout_width=\"160dp\""))
        assertTrue(edited.contains("android:layout_marginStart=\"24dp\""))
        assertTrue(edited.contains("app:layout_constraintStart_toStartOf=\"parent\""))
        assertFalse(edited.contains("app:layout_constraintEnd_toEndOf"))
        assertTrue(edited.contains("tools:keep=\"yes\""))
        assertTrue(edited.contains("keep-comment"))
    }

    @Test
    fun movePreservesRenderedSizeForMatchConstraintElement() {
        val matchConstraintXml = xml
            .replace("android:layout_width=\"wrap_content\"", "android:layout_width=\"0dp\"")
            .replace("android:layout_height=\"wrap_content\"", "android:layout_height=\"0dp\"")
        val document = AndroidXmlDocument.parse(matchConstraintXml)

        assertTrue(
            UiDocumentEditor.moveBy(
                document,
                "id:title",
                24,
                40,
                renderedWidthDp = 180,
                renderedHeightDp = 48
            )
        )

        val moved = document.root.descendantsAndSelf().first { it.stableId == "id:title" }
        assertEquals("180dp", moved.androidAttribute("layout_width"))
        assertEquals("48dp", moved.androidAttribute("layout_height"))
        assertEquals("24dp", moved.androidAttribute("layout_marginStart"))
        assertEquals("40dp", moved.androidAttribute("layout_marginTop"))
    }

    @Test
    fun patchPreservesValidThemeAndDrawableReferencesFromExistingXml() {
        val themedXml = xml.replace(
            "android:text=\"Before\"",
            "android:text=\"Before\"\n        android:textColor=\"?attr/colorOnSurface\"\n        android:background=\"@drawable/title_background\""
        )
        val document = AndroidXmlDocument.parse(themedXml)

        assertTrue(
            UiDocumentEditor.applyPatch(
                document,
                "id:title",
                UiElementPatch(
                    text = "After",
                    textColor = "?attr/colorOnSurface",
                    background = "@drawable/title_background"
                )
            )
        )

        val edited = document.xml()
        assertTrue(edited.contains("android:text=\"After\""))
        assertTrue(edited.contains("android:textColor=\"?attr/colorOnSurface\""))
        assertTrue(edited.contains("android:background=\"@drawable/title_background\""))
    }

    @Test
    fun propertyCoordinatesReplaceExistingConstraintAxes() {
        val document = AndroidXmlDocument.parse(xml)

        assertTrue(
            UiDocumentEditor.applyPatch(
                document,
                "id:title",
                UiElementPatch(marginStartDp = 32, marginTopDp = 240)
            )
        )

        val edited = document.xml()
        assertTrue(edited.contains("android:layout_marginStart=\"32dp\""))
        assertTrue(edited.contains("android:layout_marginTop=\"240dp\""))
        assertTrue(edited.contains("app:layout_constraintStart_toStartOf=\"parent\""))
        assertTrue(edited.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        assertFalse(edited.contains("app:layout_constraintEnd_toEndOf"))
        assertFalse(edited.contains("app:layout_constraintBottom_toBottomOf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun patchRejectsExecutableLookingBackgroundValues() {
        val document = AndroidXmlDocument.parse(xml)
        UiDocumentEditor.applyPatch(
            document,
            "id:title",
            UiElementPatch(background = "javascript:alert(1)")
        )
    }

    @Test
    fun addDuplicateAndDeleteMaintainUniqueIds() {
        val document = AndroidXmlDocument.parse(xml)
        val addedId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.BUTTON)
        assertNotNull(addedId)
        val copiedId = UiDocumentEditor.duplicate(document, "id:title")
        assertNotNull(copiedId)
        assertTrue(copiedId != "id:title")
        assertTrue(UiDocumentEditor.delete(document, addedId!!))
        assertFalse(document.xml().contains(addedId.removePrefix("id:")))
        assertTrue(document.xml().contains(copiedId!!.removePrefix("id:")))
    }

    @Test
    fun tappedElementIsConstrainedBelowLastConstraintChild() {
        val document = AndroidXmlDocument.parse(xml)

        val addedId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.TEXT)!!

        val added = document.element(addedId)!!
        assertEquals(
            "@id/locked",
            added.getAttributeNS("http://schemas.android.com/apk/res-auto", "layout_constraintTop_toBottomOf")
        )
        assertFalse(
            added.hasAttributeNS(
                "http://schemas.android.com/apk/res-auto",
                "layout_constraintTop_toTopOf"
            )
        )
        assertEquals(
            "16dp",
            added.getAttributeNS("http://schemas.android.com/apk/res/android", "layout_marginTop")
        )
    }

    @Test
    fun draggedElementKeepsExplicitConstraintCoordinates() {
        val document = AndroidXmlDocument.parse(xml)

        val addedId = UiDocumentEditor.addElement(
            document,
            "id:root",
            UiPaletteElement.BUTTON,
            marginStartDp = 64,
            marginTopDp = 80
        )!!

        val added = document.element(addedId)!!
        assertEquals(
            "parent",
            added.getAttributeNS("http://schemas.android.com/apk/res-auto", "layout_constraintTop_toTopOf")
        )
        assertEquals(
            "64dp",
            added.getAttributeNS("http://schemas.android.com/apk/res/android", "layout_marginStart")
        )
        assertEquals(
            "80dp",
            added.getAttributeNS("http://schemas.android.com/apk/res/android", "layout_marginTop")
        )
    }

    @Test
    fun tappedFrameElementUsesFirstFreeVerticalPosition() {
        val frameXml = """<FrameLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <ImageView
                android:id="@+id/image"
                android:layout_width="120dp"
                android:layout_height="100dp"
                android:layout_marginTop="24dp" />
        </FrameLayout>"""
        val document = AndroidXmlDocument.parse(frameXml)

        val addedId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.TEXT)!!

        assertEquals(
            "140dp",
            document.element(addedId)!!
                .getAttributeNS("http://schemas.android.com/apk/res/android", "layout_marginTop")
        )
    }

    @Test
    fun addElementUsesInnerContainerWhenRootAcceptsOnlyOneChild() {
        val scrollXml = """<androidx.core.widget.NestedScrollView
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/root_scroll"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <androidx.constraintlayout.widget.ConstraintLayout
                android:id="@+id/content"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </androidx.core.widget.NestedScrollView>"""
        val document = AndroidXmlDocument.parse(scrollXml)

        val addedId = UiDocumentEditor.addElement(document, "id:root_scroll", UiPaletteElement.IMAGE)

        assertNotNull(addedId)
        val root = document.element("id:root_scroll")!!
        val directElementChildren = generateSequence(root.firstChild) { it.nextSibling }
            .count { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertEquals(1, directElementChildren)
        assertTrue(document.xml().contains(addedId!!.removePrefix("id:")))
        assertTrue(document.element(addedId)?.parentNode === document.element("id:content"))
    }

    @Test
    fun rootAndUnsupportedElementsCannotBeDeletedOrEdited() {
        val document = AndroidXmlDocument.parse(xml)
        assertFalse(UiDocumentEditor.delete(document, "id:root"))
        assertFalse(UiDocumentEditor.delete(document, "id:locked"))
        assertFalse(UiDocumentEditor.applyPatch(document, "id:locked", UiElementPatch(width = "40dp")))
    }

    @Test
    fun imageReferenceIsAppliedOnlyToImageView() {
        val document = AndroidXmlDocument.parse(xml)
        val imageId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.IMAGE)!!
        assertTrue(UiDocumentEditor.setImageReference(document, imageId, "vibe_uploaded_photo"))
        assertFalse(UiDocumentEditor.setImageReference(document, "id:title", "wrong"))
        assertTrue(document.xml().contains("@drawable/vibe_uploaded_photo"))
    }

    @Test
    fun imageReferenceCanUpdateExistingImageButton() {
        val imageButtonXml = """<FrameLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <ImageButton
                android:id="@+id/action"
                android:layout_width="48dp"
                android:layout_height="48dp" />
        </FrameLayout>"""
        val document = AndroidXmlDocument.parse(imageButtonXml)

        assertTrue(UiDocumentEditor.setImageReference(document, "id:action", "vibe_action_icon"))
        assertTrue(document.xml().contains("@drawable/vibe_action_icon"))
    }

    @Test
    fun historyDropsRedoBranchAfterNewEdit() {
        val first = UiEditorSnapshot("one", emptyMap(), emptyList())
        val second = UiEditorSnapshot("two", mapOf("id:title" to "description"), emptyList())
        val replacement = UiEditorSnapshot("replacement", emptyMap(), emptyList())
        val history = UiEditorHistory(first)
        history.record(second)

        assertEquals(first, history.undo())
        history.record(replacement)
        assertFalse(history.canRedo)
        assertEquals(replacement, history.current)
    }

    @Test
    fun siblingOrderCanMoveForwardAndBackward() {
        val document = AndroidXmlDocument.parse(xml)
        val copiedId = UiDocumentEditor.duplicate(document, "id:title")!!
        val originalOrder = document.root.children.map { it.stableId }
        assertTrue(UiDocumentEditor.reorderSibling(document, "id:title", moveForward = true))
        val movedForward = document.root.children.map { it.stableId }
        assertEquals(originalOrder.indexOf("id:title") + 1, movedForward.indexOf("id:title"))
        assertTrue(movedForward.contains(copiedId))
        assertTrue(UiDocumentEditor.reorderSibling(document, "id:title", moveForward = false))
        val movedBackward = document.root.children.map { it.stableId }
        assertEquals(originalOrder, movedBackward)
    }

    @Test
    fun addingConstraintAttributeDeclaresMissingAppNamespace() {
        val withoutAppNamespace = """<androidx.constraintlayout.widget.ConstraintLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />"""
        val document = AndroidXmlDocument.parse(withoutAppNamespace)

        val addedId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.TEXT)

        assertNotNull(addedId)
        val edited = document.xml()
        assertTrue(edited.contains("xmlns:app=\"http://schemas.android.com/apk/res-auto\""))
        AndroidXmlDocument.parse(edited)
    }

    @Test
    fun layoutPaletteCreatesEditableContainerStructures() {
        val document = AndroidXmlDocument.parse(xml)

        val verticalId = UiDocumentEditor.addElement(
            document,
            "id:root",
            UiPaletteElement.VERTICAL_LINEAR_LAYOUT
        )!!
        val horizontalId = UiDocumentEditor.addElement(
            document,
            "id:root",
            UiPaletteElement.HORIZONTAL_LINEAR_LAYOUT
        )!!
        val scrollId = UiDocumentEditor.addElement(
            document,
            "id:root",
            UiPaletteElement.SCROLL_VIEW
        )!!

        val vertical = document.element(verticalId)!!
        val horizontal = document.element(horizontalId)!!
        val scroll = document.element(scrollId)!!
        assertEquals("LinearLayout", vertical.tagName)
        assertEquals("vertical", vertical.getAttributeNS(ANDROID_NAMESPACE_URI, "orientation"))
        assertEquals("horizontal", horizontal.getAttributeNS(ANDROID_NAMESPACE_URI, "orientation"))
        assertEquals("ScrollView", scroll.tagName)
        assertEquals(1, scroll.childNodes.length)
        assertEquals("LinearLayout", scroll.firstChild.nodeName)
        AndroidXmlDocument.parse(document.xml())
    }

    @Test
    fun iconPaletteCreatesAndChangesAccessiblePlatformIcon() {
        val document = AndroidXmlDocument.parse(xml)

        val iconId = UiDocumentEditor.addElement(document, "id:root", UiPaletteElement.ICON)!!
        val added = document.element(iconId)!!

        assertEquals("ImageView", added.tagName)
        assertEquals(
            "@android:drawable/ic_menu_info_details",
            added.getAttributeNS(ANDROID_NAMESPACE_URI, "src")
        )
        assertEquals("정보 아이콘", added.getAttributeNS(ANDROID_NAMESPACE_URI, "contentDescription"))
        assertTrue(
            UiDocumentEditor.setPlatformIconReference(
                document,
                iconId,
                "ic_menu_search",
                "검색"
            )
        )
        assertEquals(
            "@android:drawable/ic_menu_search",
            added.getAttributeNS(ANDROID_NAMESPACE_URI, "src")
        )
        assertEquals("검색", added.getAttributeNS(ANDROID_NAMESPACE_URI, "contentDescription"))
        AndroidXmlDocument.parse(document.xml())
    }

    @Test
    fun addingFromSelectedLeafUsesItsNearestContainer() {
        val nestedXml = """<androidx.constraintlayout.widget.ConstraintLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <LinearLayout
                android:id="@+id/group"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                app:layout_constraintTop_toTopOf="parent">
                <TextView
                    android:id="@+id/label"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />
            </LinearLayout>
        </androidx.constraintlayout.widget.ConstraintLayout>"""
        val document = AndroidXmlDocument.parse(nestedXml)

        val insertionParent = UiDocumentEditor.insertionParent(document, "id:label")
        val addedId = UiDocumentEditor.addElement(document, "id:label", UiPaletteElement.CHECKBOX)!!

        assertEquals("id:group", insertionParent?.stableId)
        assertTrue(document.element(addedId)?.parentNode === document.element("id:group"))
    }

    @Test
    fun placeAtUsesRenderedAbsoluteSlotAndResponsiveConstraints() {
        val document = AndroidXmlDocument.parse(xml)

        assertTrue(
            UiDocumentEditor.placeAt(
                document = document,
                stableId = "id:title",
                startDp = 72,
                topDp = 96,
                renderedWidthDp = 180,
                renderedHeightDp = 48
            )
        )

        val placed = document.root.descendantsAndSelf().first { it.stableId == "id:title" }
        assertEquals("72dp", placed.androidAttribute("layout_marginStart"))
        assertEquals("96dp", placed.androidAttribute("layout_marginTop"))
        assertEquals("parent", placed.appAttribute("layout_constraintStart_toStartOf"))
        assertEquals("parent", placed.appAttribute("layout_constraintTop_toTopOf"))
        assertFalse(document.xml().contains("layout_constraintEnd_toEndOf"))
    }

    @Test
    fun siblingCanMoveDirectlyToAnotherLinearLayoutSlot() {
        val linearXml = """<LinearLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">
            <TextView android:id="@+id/first" android:layout_width="wrap_content" android:layout_height="wrap_content" />
            <Button android:id="@+id/second" android:layout_width="wrap_content" android:layout_height="wrap_content" />
            <Switch android:id="@+id/third" android:layout_width="wrap_content" android:layout_height="wrap_content" />
        </LinearLayout>"""
        val document = AndroidXmlDocument.parse(linearXml)

        assertTrue(
            UiDocumentEditor.reorderSiblingRelative(
                document = document,
                stableId = "id:first",
                targetStableId = "id:third",
                insertAfterTarget = true
            )
        )

        assertEquals(listOf("id:second", "id:third", "id:first"), document.root.children.map { it.stableId })
        assertFalse(
            UiDocumentEditor.reorderSiblingRelative(
                document = document,
                stableId = "id:first",
                targetStableId = "id:third",
                insertAfterTarget = true
            )
        )
    }

    @Test
    fun nestedLinearCollisionCanReparentControlBeforeTarget() {
        val nestedXml = """<androidx.constraintlayout.widget.ConstraintLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <TextView
                android:id="@+id/floating"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="parent" />
            <LinearLayout
                android:id="@+id/actions"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent">
                <Button android:id="@+id/primary" android:layout_width="wrap_content" android:layout_height="wrap_content" />
                <Button android:id="@+id/secondary" android:layout_width="wrap_content" android:layout_height="wrap_content" />
            </LinearLayout>
        </androidx.constraintlayout.widget.ConstraintLayout>"""
        val document = AndroidXmlDocument.parse(nestedXml)

        assertTrue(
            UiDocumentEditor.reparentRelative(
                document = document,
                stableId = "id:floating",
                targetStableId = "id:primary",
                insertAfterTarget = false,
                renderedWidthDp = 160,
                renderedHeightDp = 48
            )
        )

        val actionChildren = document.root.children
            .first { it.stableId == "id:actions" }
            .children
            .map { it.stableId }
        assertEquals(listOf("id:floating", "id:primary", "id:secondary"), actionChildren)
        val moved = document.root.descendantsAndSelf().first { it.stableId == "id:floating" }
        assertEquals("160dp", moved.androidAttribute("layout_width"))
        assertEquals(null, moved.appAttribute("layout_constraintStart_toStartOf"))
        assertEquals(null, moved.appAttribute("layout_constraintTop_toTopOf"))
    }
}
