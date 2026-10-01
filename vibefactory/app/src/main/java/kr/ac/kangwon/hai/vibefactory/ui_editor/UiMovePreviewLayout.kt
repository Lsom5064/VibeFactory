package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView

/** Source XML/geometry stay immutable; preview widths and positions can be restored independently. */
internal class UiMovePreviewLayout(
    private val canvas: FrameLayout,
    private val document: AndroidXmlDocument,
    private val nodeViews: Map<String, View>
) {
    val width = canvas.width.toFloat().coerceAtLeast(1f)
    val height = canvas.height.toFloat().coerceAtLeast(1f)
    private val density = canvas.resources.displayMetrics.density
    private val roots = (0 until canvas.childCount).map(canvas::getChildAt).filterNot { it is UiAnnotationOverlayView }
    private val baseX = linkedMapOf<View, Float>()
    private val baseY = linkedMapOf<View, Float>()
    private data class Size(val width: Int, val height: Int, val weight: Float?)
    private val sizes = linkedMapOf<View, Size>()
    private var resizedViews = emptySet<View>()
    private val boxes = linkedMapOf<View, UiPreviewBox>()
    private val ids = nodeViews.entries.associate { it.value to it.key }
    var previewDocument: AndroidXmlDocument = document
        private set
    private var equalRowMoves: Set<String> = emptySet()
    var destinationBounds: Map<String, UiNormalizedRect> = emptyMap()
        private set

    init {
        fun capture(view: View) {
            if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return
            boxes[view] = boxOf(view)
            baseX[view] = view.translationX
            baseY[view] = view.translationY
            sizes[view] = Size(view.layoutParams.width, view.layoutParams.height,
                (view.layoutParams as? LinearLayout.LayoutParams)?.weight)
            if (view is ViewGroup) (0 until view.childCount).forEach { capture(view.getChildAt(it)) }
        }
        roots.forEach { root ->
            capture(root)
            // Extra scroll space must not remeasure match_parent source layouts into a taller viewport.
            root.layoutParams = root.layoutParams.apply { height = root.height }
        }
    }

    fun apply(moves: List<UiAnnotation>, updateXml: Boolean = true, additions: List<UiAnnotation> = emptyList()) {
        baseX.forEach { (view, x) -> view.translationX = x }
        baseY.forEach { (view, y) -> view.translationY = y }
        val pinnedViews = moves.mapNotNull { targetView(it.target) }.toSet()
        val replacedViews = additions.flatMap { it.addition?.replaceTargets.orEmpty() }.mapNotNull(::targetView).toSet()
        fun containsProtected(view: View) = (pinnedViews + replacedViews).any { pin ->
            generateSequence(pin) { it.parent as? View }.any { it === view }
        }
        val units = linkedMapOf<String, View>()
        fun collect(view: View) {
            if (view !in boxes || view in pinnedViews || view in replacedViews) return
            val group = view as? ViewGroup
            val structural = view in roots || view is ScrollView || view is NestedScrollView ||
                (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL && view.childCount > 1) ||
                (group != null && group.childCount > 0 && (view.background == null || containsProtected(view)))
            if (structural && group != null) (0 until group.childCount).forEach { collect(group.getChildAt(it)) }
            else units[ids[view] ?: "preview:${units.size}"] = view
        }
        roots.forEach(::collect)
        val blocks = units.mapValues { boxes.getValue(it.value) }
        val destinations = moves.mapNotNull { move ->
            val x = move.moveAnchorX ?: move.destinationX ?: return@mapNotNull null
            val y = move.moveAnchorY ?: move.destinationY ?: return@mapNotNull null
            val source = move.target.bounds
            val halfW = (source.right - source.left) * width / 2
            val halfH = (source.bottom - source.top) * height / 2
            move.annotationId to UiPreviewBox(x * width - halfW, y * height - halfH, x * width + halfW, y * height + halfH)
        }.toMap()
        val parents = units.values.map { it.parent }.distinct()
        val scopes = units.mapValues { parents.indexOf(it.value.parent).toString() }
        val placement = UiMovePreviewGeometry.arrange(blocks, destinations, pinnedViews.mapNotNull(boxes::get),
            scopes)
        destinationBounds = placement.destinations.mapValues { (_, box) -> normalizedBox(box) }
        equalRowMoves = placement.equalRowMoves
        val regions = additions.mapNotNull { it.addition?.bounds }.map { box ->
            UiPreviewBox(box.left * width, box.top * height, box.right * width, box.bottom * height)
        }
        val arranged = UiAdditionPreviewGeometry.arrange(blocks + placement.blocks, regions,
            pinnedViews.mapNotNull(boxes::get) + placement.destinations.values, scopes)
        val frames = arranged.filter { (id, box) -> box != blocks.getValue(id) }.mapKeys { units.getValue(it.key) }
        val hasResize = frames.any { (view, box) -> box.width != boxes.getValue(view).width }
        // Freeze the measured footprint of siblings (including weighted sources), then remeasure
        // the narrower control. Scaling would squash text/icons and leave hit bounds incorrect.
        val fixedViews = if (hasResize) units.values.toSet() + pinnedViews else emptySet()
        var needsMeasure = false
        (resizedViews + fixedViews).forEach { view ->
            val original = sizes.getValue(view)
            val frame = frames[view] ?: boxes.getValue(view)
            val w = if (view in fixedViews) frame.width.roundToInt() else original.width
            val h = if (view in fixedViews) frame.height.roundToInt() else original.height
            val weight = if (view in fixedViews) 0f else original.weight
            val params = view.layoutParams
            if (params.width != w || params.height != h ||
                (params is LinearLayout.LayoutParams && weight != null && params.weight != weight)) {
                params.width = w
                params.height = h
                if (params is LinearLayout.LayoutParams && weight != null) params.weight = weight
                view.layoutParams = params
                needsMeasure = true
            }
        }
        resizedViews = fixedViews
        if (needsMeasure) roots.forEach { root ->
            root.measure(View.MeasureSpec.makeMeasureSpec(boxes.getValue(root).width.roundToInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(boxes.getValue(root).height.roundToInt(), View.MeasureSpec.EXACTLY))
            root.layout(root.left, root.top, root.left + root.measuredWidth, root.top + root.measuredHeight)
        }
        // Remeasuring a row can change sibling positions. Compensate from immutable geometry,
        // keeping every source (the solid blue outline) in its original position.
        val leaves = units.values.toSet() + pinnedViews
        fun position(view: View) {
            val wanted = frames[view] ?: boxes[view] ?: return
            val current = boxOf(view)
            view.translationX += wanted.left - current.left
            view.translationY += wanted.top - current.top
            if (view !in leaves && view is ViewGroup) (0 until view.childCount).forEach { position(view.getChildAt(it)) }
        }
        roots.forEach(::position)
        val bottom = maxOf(boxes.keys.maxOfOrNull { boxOf(it).bottom } ?: height,
            (regions + placement.destinations.values).maxOfOrNull { it.bottom } ?: height)
        val previewHeight = maxOf(height, bottom + canvas.paddingBottom).toInt()
        if (canvas.minimumHeight != previewHeight) canvas.minimumHeight = previewHeight
        // Preview XML uses Android layout attributes. The saved source XML stays intact.
        if (!updateXml) return
        previewDocument = if (frames.isEmpty()) document else AndroidXmlDocument.parse(document.xml()).also { preview ->
            preview.mutate {
                roots.firstOrNull()?.let { root ->
                    preview.element(document.root.stableId)?.setAttributeNS(
                        ANDROID_NAMESPACE_URI, "android:layout_height", "${root.height}px")
                }
                boxes.keys.forEach { view -> ids[view]?.let(preview::element)?.let { element ->
                    fun attribute(name: String, value: String) = element.setAttributeNS(ANDROID_NAMESPACE_URI, "android:$name", value)
                    if (view.translationX != baseX.getValue(view)) attribute("translationX", "${view.translationX / density}dp")
                    if (view.translationY != baseY.getValue(view)) attribute("translationY", "${view.translationY / density}dp")
                    if (view in fixedViews) {
                        attribute("layout_width", "${view.width}px")
                        attribute("layout_height", "${view.height}px")
                        if (view.layoutParams is LinearLayout.LayoutParams) attribute("layout_weight", "0")
                    }
                } }
            }
        }
    }

    fun resolvedMove(move: UiAnnotation): UiAnnotation {
        val box = destinationBounds[move.annotationId] ?: return move
        return move.copy(
            moveAnchorX = move.moveAnchorX ?: move.destinationX,
            moveAnchorY = move.moveAnchorY ?: move.destinationY,
            destinationX = (box.left + box.right) / 2f,
            destinationY = (box.top + box.bottom) / 2f,
            destinationWidth = box.right - box.left,
            equalWidthRow = move.annotationId in equalRowMoves)
    }

    private fun boxOf(view: View): UiPreviewBox {
        var x = 0f
        var y = 0f
        var current = view
        while (current !== canvas) {
            val parent = current.parent as? View ?: break
            x += current.left + current.translationX - parent.scrollX
            y += current.top + current.translationY - parent.scrollY
            current = parent
        }
        return UiPreviewBox(x, y, x + view.width, y + view.height)
    }

    fun displayBounds(target: UiAnnotationTarget): UiNormalizedRect {
        val view = targetView(target) ?: return target.bounds
        return normalizedBox(boxOf(view))
    }

    private fun normalizedBox(box: UiPreviewBox) =
        UiNormalizedRect(box.left / width, box.top / height, box.right / width, box.bottom / height)

    private fun targetView(target: UiAnnotationTarget): View? {
        nodeViews[target.stableId]?.let { return it.takeIf { view -> view in boxes } }
        if (!target.hierarchyPath.startsWith("rendered.")) return null
        var view: View = canvas
        for (part in target.hierarchyPath.removePrefix("rendered.").split('.')) {
            view = (view as? ViewGroup)?.getChildAt(part.toIntOrNull() ?: return null) ?: return null
        }
        return view.takeIf { it in boxes }
    }
}
