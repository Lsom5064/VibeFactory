package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.w3c.dom.Element
import java.time.Instant
import java.util.UUID

enum class UiAnnotationAction(val wireName: String) {
    DELETE("delete"),
    MOVE("move"),
    BEHAVIOR("behavior");

    companion object {
        fun fromWireName(value: String): UiAnnotationAction? = entries.firstOrNull { it.wireName == value }
    }
}

data class UiNormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): UiNormalizedRect {
        val clampedLeft = left.coerceIn(0f, 1f)
        val clampedTop = top.coerceIn(0f, 1f)
        val clampedRight = right.coerceIn(0f, 1f)
        val clampedBottom = bottom.coerceIn(0f, 1f)
        return UiNormalizedRect(
            left = minOf(clampedLeft, clampedRight),
            top = minOf(clampedTop, clampedBottom),
            right = maxOf(clampedLeft, clampedRight),
            bottom = maxOf(clampedTop, clampedBottom)
        )
    }
}

data class UiAnnotationTarget(
    val stableId: String,
    val resourceId: String,
    val hierarchyPath: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: UiNormalizedRect,
    val previousSibling: String,
    val nextSibling: String
)

data class UiAnnotation(
    val annotationId: String = UUID.randomUUID().toString().replace("-", ""),
    val action: UiAnnotationAction,
    val target: UiAnnotationTarget,
    val destination: UiAnnotationTarget? = null,
    val destinationX: Float? = null,
    val destinationY: Float? = null,
    val instruction: String = "",
    val imageIds: List<String> = emptyList(),
    val createdAt: String = Instant.now().toString()
)

internal fun UiAnnotation.resolvedDestinationPoint(): Pair<Float, Float> {
    val exactX = destinationX
    val exactY = destinationY
    if (exactX != null && exactY != null) {
        return exactX.coerceIn(0f, 1f) to exactY.coerceIn(0f, 1f)
    }
    destination?.bounds?.normalized()?.let { bounds ->
        return ((bounds.left + bounds.right) / 2f) to ((bounds.top + bounds.bottom) / 2f)
    }
    val fallback = target.bounds.normalized()
    return fallback.right to fallback.bottom
}

class UiAnnotationHistory(initial: List<UiAnnotation>, private val limit: Int = 100) {
    private val entries = mutableListOf(initial.toList())
    private var index = 0

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.lastIndex
    val current: List<UiAnnotation> get() = entries[index].toList()

    fun record(annotations: List<UiAnnotation>) {
        val snapshot = annotations.toList()
        if (snapshot == entries[index]) return
        if (index < entries.lastIndex) entries.subList(index + 1, entries.size).clear()
        entries += snapshot
        index += 1
        if (entries.size > limit) {
            entries.removeAt(0)
            index -= 1
        }
    }

    fun undo(): List<UiAnnotation>? {
        if (!canUndo) return null
        index -= 1
        return current
    }

    fun redo(): List<UiAnnotation>? {
        if (!canRedo) return null
        index += 1
        return current
    }
}

object UiAnnotationXmlCodec {
    const val SCHEMA_VERSION = 1
    const val NAMESPACE = "urn:vibefactory:ui-annotations"

    fun encode(
        taskId: String,
        revisionLabel: String,
        layoutName: String,
        configuration: String,
        baseXmlSha256: String,
        annotations: List<UiAnnotation>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<vf:ui-annotations xmlns:vf=\"").append(NAMESPACE).append("\"")
        attribute("schemaVersion", SCHEMA_VERSION.toString())
        attribute("taskId", taskId)
        attribute("revisionLabel", revisionLabel)
        attribute("layoutName", layoutName)
        attribute("configuration", configuration)
        attribute("baseXmlSha256", baseXmlSha256)
        append(">\n")
        annotations.forEach { annotation ->
            append("  <vf:annotation")
            attribute("id", annotation.annotationId)
            attribute("action", annotation.action.wireName)
            attribute("createdAt", annotation.createdAt)
            append(">\n")
            appendTarget("target", annotation.target, "    ")
            annotation.destination?.let { appendTarget("destination", it, "    ") }
            if (annotation.destinationX != null && annotation.destinationY != null) {
                append("    <vf:destination-point")
                attribute("x", decimal(annotation.destinationX))
                attribute("y", decimal(annotation.destinationY))
                append(" />\n")
            }
            append("    <vf:instruction>")
                .append(escapeText(annotation.instruction))
                .append("</vf:instruction>\n")
            annotation.imageIds.distinct().forEach { imageId ->
                append("    <vf:image-ref")
                attribute("id", imageId)
                append(" />\n")
            }
            append("  </vf:annotation>\n")
        }
        append("</vf:ui-annotations>\n")
    }

    fun decode(xml: String): List<UiAnnotation> {
        if (xml.isBlank()) return emptyList()
        val root = SecureAndroidXml.parse(xml).documentElement
        require(root.localName == "ui-annotations" && root.namespaceURI == NAMESPACE) {
            "Unsupported UI annotation document"
        }
        require(root.getAttribute("schemaVersion").toIntOrNull() == SCHEMA_VERSION) {
            "Unsupported UI annotation schema version"
        }
        return buildList {
            val nodes = root.childNodes
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.localName != "annotation" || element.namespaceURI != NAMESPACE) continue
                val action = UiAnnotationAction.fromWireName(element.getAttribute("action")) ?: continue
                val target = element.childElements().firstOrNull { it.localName == "target" }
                    ?.let(::readTarget) ?: continue
                val destination = element.childElements().firstOrNull { it.localName == "destination" }
                    ?.let(::readTarget)
                val point = element.childElements().firstOrNull { it.localName == "destination-point" }
                add(
                    UiAnnotation(
                        annotationId = element.getAttribute("id").ifBlank {
                            UUID.randomUUID().toString().replace("-", "")
                        },
                        action = action,
                        target = target,
                        destination = destination,
                        destinationX = point?.getAttribute("x")?.toFloatOrNull(),
                        destinationY = point?.getAttribute("y")?.toFloatOrNull(),
                        instruction = element.childElements()
                            .firstOrNull { it.localName == "instruction" }
                            ?.textContent.orEmpty(),
                        imageIds = element.childElements()
                            .filter { it.localName == "image-ref" && it.namespaceURI == NAMESPACE }
                            .mapNotNull { image -> image.getAttribute("id").takeIf(String::isNotBlank) }
                            .distinct(),
                        createdAt = element.getAttribute("createdAt").ifBlank { Instant.EPOCH.toString() }
                    )
                )
            }
        }
    }

    private fun StringBuilder.appendTarget(name: String, target: UiAnnotationTarget, indent: String) {
        append(indent).append("<vf:").append(name)
        attribute("stableId", target.stableId)
        attribute("resourceId", target.resourceId)
        attribute("hierarchyPath", target.hierarchyPath)
        attribute("className", target.className)
        attribute("text", target.text)
        attribute("contentDescription", target.contentDescription)
        attribute("previousSibling", target.previousSibling)
        attribute("nextSibling", target.nextSibling)
        attribute("left", decimal(target.bounds.left))
        attribute("top", decimal(target.bounds.top))
        attribute("right", decimal(target.bounds.right))
        attribute("bottom", decimal(target.bounds.bottom))
        append(" />\n")
    }

    private fun readTarget(element: Element): UiAnnotationTarget = UiAnnotationTarget(
        stableId = element.getAttribute("stableId"),
        resourceId = element.getAttribute("resourceId"),
        hierarchyPath = element.getAttribute("hierarchyPath"),
        className = element.getAttribute("className"),
        text = element.getAttribute("text"),
        contentDescription = element.getAttribute("contentDescription"),
        bounds = UiNormalizedRect(
            left = element.getAttribute("left").toFloatOrNull() ?: 0f,
            top = element.getAttribute("top").toFloatOrNull() ?: 0f,
            right = element.getAttribute("right").toFloatOrNull() ?: 0f,
            bottom = element.getAttribute("bottom").toFloatOrNull() ?: 0f
        ).normalized(),
        previousSibling = element.getAttribute("previousSibling"),
        nextSibling = element.getAttribute("nextSibling")
    )

    private fun Element.childElements(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) (children.item(index) as? Element)?.let(::add)
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
    }

    private fun decimal(value: Float): String = "%.6f".format(java.util.Locale.US, value.coerceIn(0f, 1f))

    private fun escapeAttribute(value: String): String = escapeText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
