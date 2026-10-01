package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.w3c.dom.Element

data class UiSketchPoint(val x: Float, val y: Float)
data class UiSketchStroke(val points: List<UiSketchPoint>, val color: Int, val width: Float = 0.009f)

data class UiAdditionSpec(
    val bounds: UiNormalizedRect,
    val backgroundColor: Int,
    val strokes: List<UiSketchStroke> = emptyList(),
    val replaceTargets: List<UiAnnotationTarget> = emptyList(),
    val referenceCanvasWidthDp: Float? = null,
    val referenceCanvasHeightDp: Float? = null
)

internal object UiAdditionGeometry {
    fun isNearStroke(stroke: UiSketchStroke, point: UiSketchPoint, tolerance: Float = 0.035f): Boolean {
        fun near(x: Float, y: Float) = (point.x - x) * (point.x - x) + (point.y - y) * (point.y - y) <= tolerance * tolerance
        if (stroke.points.any { near(it.x, it.y) }) return true
        return stroke.points.zipWithNext().any { (a, b) ->
            val dx = b.x - a.x; val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) false else {
                val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
                near(a.x + t * dx, a.y + t * dy)
            }
        }
    }

    fun at(x: Float, y: Float, width: Float = 0.65f, height: Float = 0.22f): UiNormalizedRect {
        val w = width.coerceIn(0.05f, 1f)
        val h = height.coerceIn(0.03f, 1f)
        val left = (x - w / 2).coerceIn(0f, 1f - w)
        val top = (y - h / 2).coerceIn(0f, 1f - h)
        return UiNormalizedRect(left, top, left + w, top + h)
    }

    fun translate(bounds: UiNormalizedRect, dx: Float, dy: Float): UiNormalizedRect {
        val x = dx.coerceIn(-bounds.left, 1f - bounds.right)
        val y = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
        return UiNormalizedRect(bounds.left + x, bounds.top + y, bounds.right + x, bounds.bottom + y)
    }

    fun resize(bounds: UiNormalizedRect, corner: Int, dx: Float, dy: Float,
               minWidth: Float, minHeight: Float): UiNormalizedRect {
        require(corner in 0..3)
        val width = minWidth.coerceIn(0f, bounds.right - bounds.left)
        val height = minHeight.coerceIn(0f, bounds.bottom - bounds.top)
        return UiNormalizedRect(
            if (corner == 0 || corner == 2) (bounds.left + dx).coerceIn(0f, bounds.right - width) else bounds.left,
            if (corner < 2) (bounds.top + dy).coerceIn(0f, bounds.bottom - height) else bounds.top,
            if (corner == 1 || corner == 3) (bounds.right + dx).coerceIn(bounds.left + width, 1f) else bounds.right,
            if (corner >= 2) (bounds.bottom + dy).coerceIn(bounds.top + height, 1f) else bounds.bottom
        )
    }

    fun contains(bounds: UiNormalizedRect, x: Float, y: Float): Boolean =
        x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
}

/** Addition data extends v1 without changing existing delete/move/behavior documents. */
internal object UiAdditionXmlCodec {
    fun encodeModification(spec: UiModificationSketch): String = buildString {
        append("    <vf:sketch backgroundColor=\"").append(color(spec.backgroundColor)).append("\">\n")
        appendStrokes(spec.strokes)
        append("    </vf:sketch>\n")
    }

    fun decodeModification(element: Element) = UiModificationSketch(
        readColor(element.getAttribute("backgroundColor")), readStrokes(element))

    fun encode(spec: UiAdditionSpec, appendTarget: (String, UiAnnotationTarget) -> String): String = buildString {
        val b = spec.bounds
        append("    <vf:addition left=\"").append(number(b.left))
        append("\" top=\"").append(number(b.top))
        append("\" right=\"").append(number(b.right))
        append("\" bottom=\"").append(number(b.bottom))
        append("\" backgroundColor=\"").append(color(spec.backgroundColor)).append("\"")
        if (spec.referenceCanvasWidthDp != null && spec.referenceCanvasHeightDp != null) {
            append(" canvasWidthDp=\"").append(number(spec.referenceCanvasWidthDp))
            append("\" canvasHeightDp=\"").append(number(spec.referenceCanvasHeightDp)).append("\"")
        }
        append(">\n")
        spec.replaceTargets.forEach { append(appendTarget("replace-target", it)) }
        appendStrokes(spec.strokes)
        append("    </vf:addition>\n")
    }

    private fun StringBuilder.appendStrokes(strokes: List<UiSketchStroke>) {
        strokes.forEach { stroke ->
            append("      <vf:stroke color=\"").append(color(stroke.color))
            append("\" width=\"").append(number(stroke.width)).append("\">")
            stroke.points.forEach { point ->
                append("<vf:point x=\"").append(number(point.x))
                append("\" y=\"").append(number(point.y)).append("\" />")
            }
            append("</vf:stroke>\n")
        }
    }

    fun decode(element: Element, readTarget: (Element) -> UiAnnotationTarget): UiAdditionSpec = UiAdditionSpec(
        bounds = UiNormalizedRect(value(element, "left"), value(element, "top"), value(element, "right"), value(element, "bottom")),
        backgroundColor = readColor(element.getAttribute("backgroundColor")),
        strokes = readStrokes(element),
        replaceTargets = children(element, "replace-target").map(readTarget),
        referenceCanvasWidthDp = dimension(element, "canvasWidthDp"),
        referenceCanvasHeightDp = dimension(element, "canvasHeightDp")
    )

    private fun readStrokes(element: Element) = children(element, "stroke").map { stroke ->
        UiSketchStroke(children(stroke, "point").map { UiSketchPoint(value(it, "x"), value(it, "y")) },
            readColor(stroke.getAttribute("color")), value(stroke, "width"))
    }

    private fun dimension(element: Element, name: String): Float? = element.getAttribute(name).takeIf { it.isNotBlank() }
        ?.toFloat()?.also { require(it.isFinite() && it > 0f) }

    private fun children(element: Element, name: String): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }
        .filter { it.localName == name && it.namespaceURI == UiAnnotationXmlCodec.NAMESPACE }
    private fun value(e: Element, name: String): Float = e.getAttribute(name).toFloat().also {
        require(it.isFinite() && it in 0f..1f)
    }
    private fun readColor(value: String): Int {
        require(value.matches(Regex("#[0-9A-Fa-f]{8}")))
        return value.substring(1).toLong(16).toInt()
    }
    private fun color(value: Int): String = "#%08X".format(java.util.Locale.US, value)
    private fun number(value: Float): String = java.lang.Float.toString(value)
}
