package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.UUID

enum class UiPaletteElement(
    val tagName: String,
    val idPrefix: String,
    val defaultWidth: String,
    val defaultHeight: String
) {
    TEXT("TextView", "text", "wrap_content", "wrap_content"),
    BUTTON("Button", "button", "wrap_content", "wrap_content"),
    INPUT("EditText", "input", "match_parent", "wrap_content"),
    IMAGE("ImageView", "image", "120dp", "100dp"),
    CHECKBOX("CheckBox", "checkbox", "wrap_content", "wrap_content"),
    SWITCH("Switch", "switch", "wrap_content", "wrap_content"),
    CARD("com.google.android.material.card.MaterialCardView", "card", "match_parent", "120dp"),
    LIST("androidx.recyclerview.widget.RecyclerView", "list", "match_parent", "240dp"),
    VERTICAL_LINEAR_LAYOUT("LinearLayout", "vertical_group", "match_parent", "160dp"),
    HORIZONTAL_LINEAR_LAYOUT("LinearLayout", "horizontal_group", "match_parent", "120dp"),
    CONSTRAINT_LAYOUT("androidx.constraintlayout.widget.ConstraintLayout", "constraint_group", "match_parent", "180dp"),
    FRAME_LAYOUT("FrameLayout", "frame_group", "match_parent", "160dp"),
    SCROLL_VIEW("ScrollView", "scroll_group", "match_parent", "240dp")
}

data class UiElementPatch(
    val text: String? = null,
    val width: String? = null,
    val height: String? = null,
    val marginStartDp: Int? = null,
    val marginTopDp: Int? = null,
    val textColor: String? = null,
    val background: String? = null,
    val contentDescription: String? = null
)

data class UiEditorImage(
    val imageId: String,
    val elementStableId: String,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val resourceName: String,
    val sha256: String,
    val sizeBytes: Long,
    val serverWorkspacePath: String? = null
)

data class UiEditorSnapshot(
    val xml: String,
    val descriptions: Map<String, String>,
    val images: List<UiEditorImage>
)

class UiEditorHistory(initial: UiEditorSnapshot, private val limit: Int = 50) {
    private val entries = mutableListOf(initial)
    private var index = 0

    val canUndo: Boolean
        get() = index > 0

    val canRedo: Boolean
        get() = index < entries.lastIndex

    val current: UiEditorSnapshot
        get() = entries[index]

    fun record(snapshot: UiEditorSnapshot) {
        if (snapshot == current) return
        if (index < entries.lastIndex) entries.subList(index + 1, entries.size).clear()
        entries += snapshot
        if (entries.size > limit) entries.removeAt(0) else index += 1
        if (entries.size == limit && index >= entries.size) index = entries.lastIndex
    }

    fun undo(): UiEditorSnapshot? {
        if (!canUndo) return null
        index -= 1
        return current
    }

    fun redo(): UiEditorSnapshot? {
        if (!canRedo) return null
        index += 1
        return current
    }
}

object UiDocumentEditor {
    fun applyPatch(document: AndroidXmlDocument, stableId: String, patch: UiElementPatch): Boolean {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return false
        if (node.locked) return false
        document.mutate {
            val element = document.element(stableId) ?: return@mutate
            patch.text?.let { element.setAndroidAttribute("text", it) }
            patch.width?.let { element.setAndroidAttribute("layout_width", normalizeSize(it)) }
            patch.height?.let { element.setAndroidAttribute("layout_height", normalizeSize(it)) }
            val parentIsConstraintLayout = (element.parentNode as? Element)
                ?.tagName
                ?.substringAfterLast('.') == "ConstraintLayout"
            patch.marginStartDp?.let {
                element.setAndroidAttribute("layout_marginStart", "${it.coerceAtLeast(0)}dp")
                if (parentIsConstraintLayout) {
                    element.removeConstraintAxis(horizontal = true)
                    element.setAppAttribute("layout_constraintStart_toStartOf", "parent")
                }
            }
            patch.marginTopDp?.let {
                element.setAndroidAttribute("layout_marginTop", "${it.coerceAtLeast(0)}dp")
                if (parentIsConstraintLayout) {
                    element.removeConstraintAxis(horizontal = false)
                    element.setAppAttribute("layout_constraintTop_toTopOf", "parent")
                }
            }
            patch.textColor?.let {
                setOrRemoveAndroidAttribute(element, "textColor", normalizeColorReference(it))
            }
            patch.background?.let {
                setOrRemoveAndroidAttribute(element, "background", normalizeBackgroundReference(it))
            }
            patch.contentDescription?.let { setOrRemoveAndroidAttribute(element, "contentDescription", it.trim()) }
        }
        return true
    }

    fun moveBy(
        document: AndroidXmlDocument,
        stableId: String,
        deltaXDp: Int,
        deltaYDp: Int,
        renderedWidthDp: Int? = null,
        renderedHeightDp: Int? = null
    ): Boolean {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return false
        if (node.locked || node === document.root) return false
        document.mutate {
            val element = document.element(stableId) ?: return@mutate
            val marginStart = parseDp(element.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_marginStart"))
            val marginTop = parseDp(element.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_marginTop"))
            if (element.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_width") == "0dp") {
                renderedWidthDp?.takeIf { it > 0 }?.let {
                    element.setAndroidAttribute("layout_width", "${it}dp")
                }
            }
            if (element.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_height") == "0dp") {
                renderedHeightDp?.takeIf { it > 0 }?.let {
                    element.setAndroidAttribute("layout_height", "${it}dp")
                }
            }
            element.setAndroidAttribute("layout_marginStart", "${(marginStart + deltaXDp).coerceAtLeast(0)}dp")
            element.setAndroidAttribute("layout_marginTop", "${(marginTop + deltaYDp).coerceAtLeast(0)}dp")
            val parent = element.parentNode as? Element
            if (parent?.tagName?.substringAfterLast('.') == "ConstraintLayout") {
                element.removeConstraintAxis(horizontal = true)
                element.removeConstraintAxis(horizontal = false)
                element.setAppAttribute("layout_constraintStart_toStartOf", "parent")
                element.setAppAttribute("layout_constraintTop_toTopOf", "parent")
            }
        }
        return true
    }

    fun resize(document: AndroidXmlDocument, stableId: String, widthDp: Int, heightDp: Int): Boolean {
        if (widthDp < 24 || heightDp < 24) return false
        return applyPatch(
            document,
            stableId,
            UiElementPatch(width = "${widthDp}dp", height = "${heightDp}dp")
        )
    }

    fun addElement(
        document: AndroidXmlDocument,
        parentStableId: String?,
        type: UiPaletteElement,
        marginStartDp: Int? = null,
        marginTopDp: Int? = null
    ): String? {
        val selectedNode = parentStableId
            ?.let { id -> document.root.descendantsAndSelf().firstOrNull { it.stableId == id } }
        val requestedParent = selectedNode
            ?.takeIf { it.supported && it.simpleTag in CONTAINER_TAGS }
            ?: selectedNode?.let { nearestContainerParent(document.root, it.stableId) }
        val insertionParent = requestedParent
            ?.let { resolveInsertionParent(document, it) }
            ?: resolveInsertionParent(document, document.root)
            ?: return null
        val parentElement = document.element(insertionParent.stableId) ?: return null
        val explicitPosition = marginStartDp != null || marginTopDp != null
        val resolvedMarginStart = (marginStartDp ?: DEFAULT_ELEMENT_MARGIN_DP).coerceAtLeast(0)
        val resolvedMarginTop = when {
            marginTopDp != null -> marginTopDp.coerceAtLeast(0)
            insertionParent.simpleTag == "FrameLayout" -> nextFrameLayoutTop(parentElement)
            else -> DEFAULT_ELEMENT_MARGIN_DP
        }
        val constraintAnchorId = if (
            insertionParent.simpleTag == "ConstraintLayout" && !explicitPosition
        ) {
            lastDirectChildResourceId(parentElement)
        } else {
            null
        }
        val resourceId = "vibe_${type.idPrefix}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        document.mutate { dom ->
            val element = dom.createElement(type.tagName)
            element.setAndroidAttribute("id", "@+id/$resourceId")
            element.setAndroidAttribute("layout_width", type.defaultWidth)
            element.setAndroidAttribute("layout_height", type.defaultHeight)
            element.setAndroidAttribute("layout_marginStart", "${resolvedMarginStart}dp")
            element.setAndroidAttribute("layout_marginTop", "${resolvedMarginTop}dp")
            when (type) {
                UiPaletteElement.TEXT -> element.setAndroidAttribute("text", "텍스트")
                UiPaletteElement.BUTTON -> element.setAndroidAttribute("text", "버튼")
                UiPaletteElement.INPUT -> element.setAndroidAttribute("hint", "입력")
                UiPaletteElement.IMAGE -> element.setAndroidAttribute("contentDescription", "추가한 이미지")
                UiPaletteElement.CHECKBOX -> element.setAndroidAttribute("text", "선택 항목")
                UiPaletteElement.SWITCH -> element.setAndroidAttribute("text", "설정")
                UiPaletteElement.VERTICAL_LINEAR_LAYOUT -> {
                    element.setAndroidAttribute("orientation", "vertical")
                    element.setAndroidAttribute("padding", "12dp")
                }
                UiPaletteElement.HORIZONTAL_LINEAR_LAYOUT -> {
                    element.setAndroidAttribute("orientation", "horizontal")
                    element.setAndroidAttribute("padding", "12dp")
                }
                UiPaletteElement.SCROLL_VIEW -> {
                    val contentId = "${resourceId}_content"
                    val content = dom.createElement("LinearLayout")
                    content.setAndroidAttribute("id", "@+id/$contentId")
                    content.setAndroidAttribute("layout_width", "match_parent")
                    content.setAndroidAttribute("layout_height", "wrap_content")
                    content.setAndroidAttribute("orientation", "vertical")
                    content.setAndroidAttribute("padding", "12dp")
                    element.appendChild(content)
                }
                UiPaletteElement.CARD,
                UiPaletteElement.LIST,
                UiPaletteElement.CONSTRAINT_LAYOUT,
                UiPaletteElement.FRAME_LAYOUT -> Unit
            }
            if (insertionParent.simpleTag == "ConstraintLayout") {
                element.setAppAttribute("layout_constraintStart_toStartOf", "parent")
                if (constraintAnchorId == null) {
                    element.setAppAttribute("layout_constraintTop_toTopOf", "parent")
                } else {
                    element.setAppAttribute("layout_constraintTop_toBottomOf", "@id/$constraintAnchorId")
                }
            }
            parentElement.appendChild(element)
        }
        return "id:$resourceId"
    }

    fun insertionParent(document: AndroidXmlDocument, selectedStableId: String?): UiNode? {
        val selectedNode = selectedStableId
            ?.let { id -> document.root.descendantsAndSelf().firstOrNull { it.stableId == id } }
        val requestedParent = selectedNode
            ?.takeIf { it.supported && it.simpleTag in CONTAINER_TAGS }
            ?: selectedNode?.let { nearestContainerParent(document.root, it.stableId) }
        return requestedParent
            ?.let { resolveInsertionParent(document, it) }
            ?: resolveInsertionParent(document, document.root)
    }

    private fun lastDirectChildResourceId(parent: Element): String? = parent.directChildElements()
        .mapNotNull { child ->
            child.getAttributeNS(ANDROID_NAMESPACE_URI, "id")
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
        }
        .lastOrNull()

    private fun nextFrameLayoutTop(parent: Element): Int {
        val occupiedBottom = parent.directChildElements().maxOfOrNull { child ->
            val top = parseDp(child.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_marginTop"))
            val height = parseDp(child.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_height"))
                .takeIf { it > 0 }
                ?: estimatedHeightDp(child.tagName.substringAfterLast('.'))
            top + height
        } ?: 0
        return occupiedBottom + DEFAULT_ELEMENT_MARGIN_DP
    }

    private fun estimatedHeightDp(simpleTag: String): Int = when (simpleTag) {
        "ImageView" -> 100
        "RecyclerView" -> 240
        "CardView", "MaterialCardView" -> 120
        "Button", "MaterialButton", "EditText", "TextInputLayout", "TextInputEditText" -> 56
        else -> 48
    }

    fun duplicate(document: AndroidXmlDocument, stableId: String): String? {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return null
        if (node.locked || node === document.root) return null
        val source = document.element(stableId) ?: return null
        val parent = source.parentNode ?: return null
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        var newRootId: String? = null
        document.mutate { dom ->
            val clone = dom.importNode(source, true) as Element
            val idMapping = linkedMapOf<String, String>()
            clone.elementSequence().forEach { element ->
                val oldId = element.getAttributeNS(ANDROID_NAMESPACE_URI, "id").substringAfterLast('/')
                    .takeIf { it.isNotBlank() }
                if (oldId != null) {
                    val newId = "${oldId}_copy_$suffix"
                    idMapping[oldId] = newId
                    element.setAndroidAttribute("id", "@+id/$newId")
                    if (element === clone) newRootId = "id:$newId"
                }
            }
            clone.elementSequence().forEach { element ->
                for (index in 0 until element.attributes.length) {
                    val attribute = element.attributes.item(index)
                    val referenceId = attribute.nodeValue.substringAfterLast('/')
                    idMapping[referenceId]?.let { replacement ->
                        attribute.nodeValue = attribute.nodeValue.substringBeforeLast('/') + "/" + replacement
                    }
                }
            }
            val marginTop = parseDp(clone.getAttributeNS(ANDROID_NAMESPACE_URI, "layout_marginTop"))
            clone.setAndroidAttribute("layout_marginTop", "${marginTop + 12}dp")
            parent.appendChild(clone)
        }
        return newRootId
    }

    fun delete(document: AndroidXmlDocument, stableId: String): Boolean {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return false
        if (node.locked || node === document.root) return false
        document.mutate {
            val element = document.element(stableId) ?: return@mutate
            element.parentNode?.removeChild(element)
        }
        return true
    }

    fun reorderSibling(document: AndroidXmlDocument, stableId: String, moveForward: Boolean): Boolean {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return false
        if (node.locked || node === document.root) return false
        val element = document.element(stableId) ?: return false
        val parent = element.parentNode ?: return false
        val siblings = buildList {
            var child = parent.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) add(child)
                child = child.nextSibling
            }
        }
        val index = siblings.indexOf(element)
        if (index < 0) return false
        if (moveForward && index >= siblings.lastIndex) return false
        if (!moveForward && index <= 0) return false
        document.mutate {
            if (moveForward) {
                parent.insertBefore(siblings[index + 1], element)
            } else {
                parent.insertBefore(element, siblings[index - 1])
            }
        }
        return true
    }

    fun setImageReference(document: AndroidXmlDocument, stableId: String, resourceName: String): Boolean {
        val node = document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return false
        if (node.locked || node.simpleTag !in IMAGE_TAGS) return false
        document.mutate {
            document.element(stableId)?.setAndroidAttribute("src", "@drawable/$resourceName")
        }
        return true
    }

    private fun Element.setAndroidAttribute(localName: String, value: String) {
        ensureNamespace("android", ANDROID_NAMESPACE_URI)
        setAttributeNS(ANDROID_NAMESPACE_URI, "android:$localName", value)
    }

    private fun Element.setAppAttribute(localName: String, value: String) {
        ensureNamespace("app", APP_NAMESPACE_URI)
        setAttributeNS(APP_NAMESPACE_URI, "app:$localName", value)
    }

    private fun Element.ensureNamespace(prefix: String, namespaceUri: String) {
        val root = ownerDocument.documentElement
        if (!root.hasAttributeNS(XMLNS_NAMESPACE_URI, prefix)) {
            root.setAttributeNS(XMLNS_NAMESPACE_URI, "xmlns:$prefix", namespaceUri)
        }
    }

    private fun Element.removeConstraintAxis(horizontal: Boolean) {
        val names = if (horizontal) {
            listOf(
                "layout_constraintStart_toStartOf",
                "layout_constraintStart_toEndOf",
                "layout_constraintEnd_toStartOf",
                "layout_constraintEnd_toEndOf"
            )
        } else {
            listOf(
                "layout_constraintTop_toTopOf",
                "layout_constraintTop_toBottomOf",
                "layout_constraintBottom_toTopOf",
                "layout_constraintBottom_toBottomOf"
            )
        }
        names.forEach { removeAttributeNS(APP_NAMESPACE_URI, it) }
    }

    private fun Element.elementSequence(): Sequence<Element> = sequence {
        yield(this@elementSequence)
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) yieldAll((child as Element).elementSequence())
            child = child.nextSibling
        }
    }

    private fun Element.directChildElements(): Sequence<Element> = sequence {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) yield(child as Element)
            child = child.nextSibling
        }
    }

    private fun setOrRemoveAndroidAttribute(element: Element, localName: String, value: String) {
        if (value.isBlank()) element.removeAttributeNS(ANDROID_NAMESPACE_URI, localName)
        else element.setAndroidAttribute(localName, value)
    }

    private fun normalizeSize(value: String): String {
        val normalized = value.trim().lowercase()
        require(
            normalized in setOf("match_parent", "wrap_content", "0dp") ||
                normalized.matches(Regex("^\\d+(?:\\.\\d+)?(?:dp|sp|px)$"))
        ) { "invalid element size" }
        return normalized
    }

    private fun normalizeColorReference(value: String): String {
        val normalized = value.trim()
        require(
            normalized.isBlank() ||
                normalized.matches(COLOR_LITERAL_PATTERN) ||
                normalized.matches(Regex("^@(?:android:)?color/[a-zA-Z_][a-zA-Z0-9_.]*$")) ||
                normalized.matches(THEME_ATTRIBUTE_PATTERN)
        ) { "invalid color" }
        return normalized
    }

    private fun normalizeBackgroundReference(value: String): String {
        val normalized = value.trim()
        require(
            normalized.isBlank() ||
                normalized.matches(COLOR_LITERAL_PATTERN) ||
                normalized.matches(Regex("^@(?:android:)?(?:color|drawable)/[a-zA-Z_][a-zA-Z0-9_.]*$")) ||
                normalized.matches(THEME_ATTRIBUTE_PATTERN)
        ) { "invalid background" }
        return normalized
    }

    private fun parseDp(value: String): Int = value.trim().removeSuffix("dp").toFloatOrNull()?.toInt() ?: 0

    private fun resolveInsertionParent(document: AndroidXmlDocument, candidate: UiNode): UiNode? {
        if (!candidate.supported || candidate.simpleTag !in CONTAINER_TAGS) return null
        if (candidate.simpleTag !in SINGLE_CHILD_CONTAINER_TAGS) return candidate
        val element = document.element(candidate.stableId) ?: return null
        val hasElementChild = generateSequence(element.firstChild) { it.nextSibling }
            .any { it.nodeType == Node.ELEMENT_NODE }
        if (!hasElementChild) return candidate
        return candidate.children.firstNotNullOfOrNull { child ->
            resolveInsertionParent(document, child)
        }
    }

    private fun nearestContainerParent(root: UiNode, childStableId: String): UiNode? {
        var currentStableId = childStableId
        while (true) {
            val parent = findParent(root, currentStableId) ?: return null
            if (parent.supported && parent.simpleTag in CONTAINER_TAGS) return parent
            currentStableId = parent.stableId
        }
    }

    private fun findParent(root: UiNode, childStableId: String): UiNode? {
        if (root.children.any { it.stableId == childStableId }) return root
        return root.children.firstNotNullOfOrNull { findParent(it, childStableId) }
    }

    private val CONTAINER_TAGS = setOf(
        "ConstraintLayout",
        "CoordinatorLayout",
        "LinearLayout",
        "FrameLayout",
        "ScrollView",
        "NestedScrollView",
        "AppBarLayout",
        "Toolbar",
        "MaterialToolbar",
        "TextInputLayout",
        "CardView",
        "MaterialCardView"
    )

    private val SINGLE_CHILD_CONTAINER_TAGS = setOf(
        "ScrollView",
        "NestedScrollView",
        "TextInputLayout",
        "CardView",
        "MaterialCardView"
    )

    private val COLOR_LITERAL_PATTERN = Regex("^#[0-9A-Fa-f]{3,4}(?:[0-9A-Fa-f]{3,4})?$")
    private val THEME_ATTRIBUTE_PATTERN = Regex("^\\?(?:android:)?attr/[a-zA-Z_][a-zA-Z0-9_.]*$")
    private val IMAGE_TAGS = setOf("ImageView", "ImageButton")

    private const val DEFAULT_ELEMENT_MARGIN_DP = 16
    private const val XMLNS_NAMESPACE_URI = "http://www.w3.org/2000/xmlns/"
}
