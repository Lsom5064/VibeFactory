package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView

/** Original geometry stays immutable. Only this preview's XML and view translations change. */
internal class UiMovePreviewLayout(
    private val canvas: FrameLayout,
    private val document: AndroidXmlDocument,
    private val nodeViews: Map<String, View>
) {
    val width = canvas.width.toFloat().coerceAtLeast(1f)
    val height = canvas.height.toFloat().coerceAtLeast(1f)
    private val density = canvas.resources.displayMetrics.density
    private val roots = (0 until canvas.childCount).map(canvas::getChildAt).filterNot { it is UiAnnotationOverlayView }
    private val baseY = linkedMapOf<View, Float>()
    private val boxes = linkedMapOf<View, UiPreviewBox>()
    private val ids = nodeViews.entries.associate { it.value to it.key }
    private var offsets = emptyMap<View, Float>()
    var previewDocument: AndroidXmlDocument = document
        private set

    init {
        fun capture(view: View) {
            if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return
            val rect = Rect(0, 0, view.width, view.height)
            canvas.offsetDescendantRectToMyCoords(view, rect)
            boxes[view] = UiPreviewBox(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
            baseY[view] = view.translationY
            if (view is ViewGroup) (0 until view.childCount).forEach { capture(view.getChildAt(it)) }
        }
        roots.forEach { root ->
            capture(root)
            // Extra scroll space must not remeasure match_parent source layouts into a taller viewport.
            root.layoutParams = root.layoutParams.apply { height = root.height }
        }
    }

    fun apply(moves: List<UiAnnotation>, updateXml: Boolean = true) {
        baseY.forEach { (view, y) -> view.translationY = y }
        val pinnedViews = moves.mapNotNull { targetView(it.target) }.toSet()
        fun containsPin(view: View) = pinnedViews.any { pin -> generateSequence(pin) { it.parent as? View }.any { it === view } }
        val units = linkedMapOf<String, View>()
        fun collect(view: View) {
            if (view !in boxes || view in pinnedViews) return
            val group = view as? ViewGroup
            val structural = view in roots || view is ScrollView || view is NestedScrollView ||
                (group != null && group.childCount > 0 && (view.background == null || containsPin(view)))
            if (structural && group != null) (0 until group.childCount).forEach { collect(group.getChildAt(it)) }
            else units[ids[view] ?: "preview:${units.size}"] = view
        }
        roots.forEach(::collect)
        val reserved = moves.mapNotNull { move ->
            val x = move.destinationX ?: return@mapNotNull null
            val y = move.destinationY ?: return@mapNotNull null
            val source = move.target.bounds
            val halfW = (source.right - source.left) * width / 2
            val halfH = (source.bottom - source.top) * height / 2
            UiPreviewBox(x * width - halfW, y * height - halfH, x * width + halfW, y * height + halfH)
        }
        val calculated = UiMovePreviewGeometry.offsets(units.mapValues { boxes.getValue(it.value) }, reserved,
            pinnedViews.mapNotNull(boxes::get))
        offsets = calculated.mapKeys { units.getValue(it.key) }
        offsets.forEach { (view, dy) -> view.translationY = baseY.getValue(view) + dy }
        val bottom = boxes.keys.maxOfOrNull { view -> boxes.getValue(view).bottom + offsetFor(view) } ?: height
        canvas.minimumHeight = maxOf(height, bottom + canvas.paddingBottom).toInt()
        canvas.requestLayout()
        // Persistable preview XML uses standard Android attributes; the saved source XML stays intact.
        if (!updateXml) return
        previewDocument = if (offsets.isEmpty()) document else AndroidXmlDocument.parse(document.xml()).also { preview ->
            preview.mutate {
                roots.firstOrNull()?.let { root ->
                    preview.element(document.root.stableId)?.setAttributeNS(
                        ANDROID_NAMESPACE_URI, "android:layout_height", "${root.height}px")
                }
                offsets.forEach { (view, dy) -> ids[view]?.let(preview::element)?.setAttributeNS(
                    ANDROID_NAMESPACE_URI, "android:translationY", "${(baseY.getValue(view) + dy) / density}dp") }
            }
        }
    }

    fun offsetFor(view: View): Float = generateSequence(view) { it.parent as? View }
        .takeWhile { it !== canvas }.sumOf { (offsets[it] ?: 0f).toDouble() }.toFloat()

    fun displayBounds(target: UiAnnotationTarget): UiNormalizedRect {
        val view = targetView(target) ?: return target.bounds
        val dy = offsetFor(view) / height
        return target.bounds.copy(top = target.bounds.top + dy, bottom = target.bounds.bottom + dy)
    }

    private fun targetView(target: UiAnnotationTarget): View? {
        nodeViews[target.stableId]?.let { return it }
        if (!target.hierarchyPath.startsWith("rendered.")) return null
        var view: View = canvas
        for (part in target.hierarchyPath.removePrefix("rendered.").split('.')) {
            view = (view as? ViewGroup)?.getChildAt(part.toIntOrNull() ?: return null) ?: return null
        }
        return view.takeIf { it in boxes }
    }
}
