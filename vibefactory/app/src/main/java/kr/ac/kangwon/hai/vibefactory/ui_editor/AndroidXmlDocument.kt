package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
const val APP_NAMESPACE_URI = "http://schemas.android.com/apk/res-auto"
const val TOOLS_NAMESPACE_URI = "http://schemas.android.com/tools"

data class UiXmlAttribute(
    val namespaceUri: String,
    val prefix: String,
    val localName: String,
    val qualifiedName: String,
    val value: String
)

data class UiNode(
    val stableId: String,
    val elementPath: List<Int>,
    val tagName: String,
    val simpleTag: String,
    val namespaceUri: String,
    val attributes: List<UiXmlAttribute>,
    val children: List<UiNode>,
    val supported: Boolean,
    val locked: Boolean
) {
    fun attribute(namespaceUri: String, localName: String): String? =
        attributes.firstOrNull {
            it.namespaceUri == namespaceUri && it.localName == localName
        }?.value

    fun androidAttribute(localName: String): String? = attribute(ANDROID_NAMESPACE_URI, localName)

    fun appAttribute(localName: String): String? = attribute(APP_NAMESPACE_URI, localName)

    fun toolsAttribute(localName: String): String? = attribute(TOOLS_NAMESPACE_URI, localName)

    fun unqualifiedAttribute(localName: String): String? =
        attributes.firstOrNull {
            it.namespaceUri.isBlank() && it.localName == localName
        }?.value

    fun descendantsAndSelf(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.descendantsAndSelf()) }
    }
}

object SecureAndroidXml {
    private val unsafeDeclaration = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)

    fun parse(xml: String): Document {
        require(xml.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "XML is larger than 2 MiB"
        }
        require(!unsafeDeclaration.containsMatchIn(xml)) {
            "DTD and entity declarations are not allowed"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's platform parser can reject even the disabled XInclude setter.
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml)))
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, enabled: Boolean) {
        runCatching { setFeature(name, enabled) }
    }

    private fun DocumentBuilderFactory.setAttributeIfSupported(name: String, value: String) {
        runCatching { setAttribute(name, value) }
    }
}

class AndroidXmlDocument private constructor(
    val originalXml: String,
    val originalSha256: String,
    internal val dom: Document
) {
    private var dirty = false
    private var stableElements: Map<String, Element> = emptyMap()

    var root: UiNode = rebuildTree()
        private set

    val hasChanges: Boolean
        get() = dirty

    fun xml(): String = if (dirty) serializeDom() else originalXml

    internal fun element(stableId: String): Element? = stableElements[stableId]

    internal fun mutate(block: (Document) -> Unit) {
        block(dom)
        dirty = true
        root = rebuildTree()
    }

    private fun rebuildTree(): UiNode {
        val elements = linkedMapOf<String, Element>()

        fun build(element: Element, path: List<Int>): UiNode {
            val attributes = (0 until element.attributes.length).map { index ->
                val attribute = element.attributes.item(index)
                UiXmlAttribute(
                    namespaceUri = attribute.namespaceURI.orEmpty(),
                    prefix = attribute.prefix.orEmpty(),
                    localName = attribute.localName ?: attribute.nodeName.substringAfter(':'),
                    qualifiedName = attribute.nodeName,
                    value = attribute.nodeValue.orEmpty()
                )
            }
            val declaredId = attributes.firstOrNull {
                it.namespaceUri == ANDROID_NAMESPACE_URI && it.localName == "id"
            }?.value?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val stableId = declaredId?.let { "id:$it" } ?: "path:${path.joinToString(".")}"
            elements.putIfAbsent(stableId, element)
            val childElements = buildList {
                var elementIndex = 0
                var child = element.firstChild
                while (child != null) {
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        add(build(child as Element, path + elementIndex))
                        elementIndex += 1
                    }
                    child = child.nextSibling
                }
            }
            val tagName = element.tagName
            val simpleTag = tagName.substringAfterLast('.')
            val supported = simpleTag in SUPPORTED_TAGS
            return UiNode(
                stableId = stableId,
                elementPath = path,
                tagName = tagName,
                simpleTag = simpleTag,
                namespaceUri = element.namespaceURI.orEmpty(),
                attributes = attributes,
                children = childElements,
                supported = supported,
                locked = !supported
            )
        }

        val tree = build(dom.documentElement, listOf(0))
        stableElements = elements
        return tree
    }

    private fun serializeDom(): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(dom), StreamResult(writer))
        return writer.toString()
    }

    companion object {
        val SUPPORTED_TAGS = setOf(
            "ConstraintLayout",
            "CoordinatorLayout",
            "LinearLayout",
            "FrameLayout",
            "ScrollView",
            "NestedScrollView",
            "AppBarLayout",
            "Toolbar",
            "MaterialToolbar",
            "TextView",
            "MaterialTextView",
            "Button",
            "MaterialButton",
            "EditText",
            "TextInputLayout",
            "TextInputEditText",
            "ImageView",
            "ImageButton",
            "CheckBox",
            "Switch",
            "SwitchCompat",
            "RadioButton",
            "ProgressBar",
            "FloatingActionButton",
            "View",
            "Space",
            "CardView",
            "MaterialCardView",
            "RecyclerView"
        )

        fun parse(xml: String, expectedSha256: String? = null): AndroidXmlDocument {
            val calculatedSha = sha256(xml)
            require(expectedSha256.isNullOrBlank() || expectedSha256.equals(calculatedSha, ignoreCase = true)) {
                "XML SHA-256 does not match the selected Revision"
            }
            return AndroidXmlDocument(
                originalXml = xml,
                originalSha256 = calculatedSha,
                dom = SecureAndroidXml.parse(xml)
            )
        }

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
