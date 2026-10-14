package kr.ac.kangwon.hai.vibefactory.ui_editor

import kotlin.math.abs

internal data class UiSnapTarget(val id: String, val box: UiPreviewBox, val preferred: Boolean = false)
internal enum class UiSnapAxis { X, Y }
internal enum class UiSnapKind { ALIGN, WIDTH, HEIGHT }
internal data class UiSnapGuide(
    val axis: UiSnapAxis, val kind: UiSnapKind, val position: Float,
    val sourceAnchor: Float, val target: UiPreviewBox,
    val targetId: String = "", val targetAnchor: Float = 0f
)
internal data class UiSnapResult(val box: UiPreviewBox, val guides: List<UiSnapGuide>)

/** Pixel-space geometry only. Edge references stay frozen; width references may predict final reflow. */
internal class UiAlignmentSnapper {
    private data class Candidate(val key: String, val delta: Float, val guide: UiSnapGuide, val preferred: Boolean)
    private var lockedX: String? = null
    private var lockedY: String? = null

    fun reset() { lockedX = null; lockedY = null }

    fun translate(
        raw: UiPreviewBox, targets: List<UiSnapTarget>, acquire: Float, release: Float,
        canvas: UiPreviewBox, containEdges: Boolean
    ): UiSnapResult = snap(raw, targets, acquire, release, canvas, containEdges, null, 0f, 0f)

    fun resize(
        raw: UiPreviewBox, corner: Int, targets: List<UiSnapTarget>, acquire: Float, release: Float,
        canvas: UiPreviewBox, minWidth: Float, minHeight: Float,
        widthTargetsProvider: ((UiPreviewBox) -> List<UiSnapTarget>)? = null
    ): UiSnapResult {
        require(corner in 0..3)
        return snap(raw, targets, acquire, release, canvas, true, corner, minWidth, minHeight, widthTargetsProvider)
    }

    private fun snap(
        raw: UiPreviewBox, targets: List<UiSnapTarget>, acquire: Float, release: Float,
        canvas: UiPreviewBox, containEdges: Boolean, corner: Int?, minWidth: Float, minHeight: Float,
        widthTargetsProvider: ((UiPreviewBox) -> List<UiSnapTarget>)? = null
    ): UiSnapResult {
        var widthTargets = targets
        fun moved(axis: UiSnapAxis, delta: Float): UiPreviewBox = when {
            corner == null && axis == UiSnapAxis.X -> raw.copy(left = raw.left + delta, right = raw.right + delta)
            corner == null -> raw.copy(top = raw.top + delta, bottom = raw.bottom + delta)
            axis == UiSnapAxis.X && corner in listOf(0, 2) -> raw.copy(left = raw.left + delta)
            axis == UiSnapAxis.X -> raw.copy(right = raw.right + delta)
            corner < 2 -> raw.copy(top = raw.top + delta)
            else -> raw.copy(bottom = raw.bottom + delta)
        }
        fun valid(box: UiPreviewBox): Boolean {
            if (box.width < minWidth - .01f || box.height < minHeight - .01f) return false
            return if (containEdges) box.left >= canvas.left - .01f && box.right <= canvas.right + .01f &&
                box.top >= canvas.top - .01f && box.bottom <= canvas.bottom + .01f
            else (box.left + box.right) / 2f in canvas.left..canvas.right &&
                (box.top + box.bottom) / 2f in canvas.top..canvas.bottom
        }
        fun candidates(axis: UiSnapAxis): List<Candidate> = buildList {
            val start = if (axis == UiSnapAxis.X) raw.left else raw.top
            val size = if (axis == UiSnapAxis.X) raw.width else raw.height
            val activeAnchor = if (axis == UiSnapAxis.X) {
                if (corner in listOf(0, 2)) 0f else 1f
            } else if (corner != null && corner < 2) 0f else 1f
            for (target in targets) {
                val box = target.box
                if (box.width <= 0f || box.height <= 0f) continue
                val targetStart = if (axis == UiSnapAxis.X) box.left else box.top
                val targetSize = if (axis == UiSnapAxis.X) box.width else box.height
                val pairs = if (corner == null) listOf(0f to 0f, .5f to .5f, 1f to 1f, 0f to 1f, 1f to 0f)
                    else listOf(activeAnchor to 0f, activeAnchor to 1f)
                for ((sourceAnchor, targetAnchor) in pairs) {
                    val position = targetStart + targetSize * targetAnchor
                    val delta = position - (start + size * sourceAnchor)
                    if (valid(moved(axis, delta))) add(Candidate(
                        "${target.id}:$axis:$sourceAnchor:$targetAnchor", delta,
                        UiSnapGuide(axis, UiSnapKind.ALIGN, position, sourceAnchor, box, target.id, targetAnchor), target.preferred))
                }
            }
            if (corner != null) {
                for (target in if (axis == UiSnapAxis.X) widthTargets else targets) {
                    val box = target.box
                    val targetSize = if (axis == UiSnapAxis.X) box.width else box.height
                    if (targetSize <= 0f) continue
                    val delta = (targetSize - size) * if (activeAnchor == 0f) -1f else 1f
                    if (valid(moved(axis, delta))) add(Candidate(
                        "${target.id}:$axis:size", delta,
                        UiSnapGuide(axis, if (axis == UiSnapAxis.X) UiSnapKind.WIDTH else UiSnapKind.HEIGHT,
                            start + size * activeAnchor + delta, activeAnchor, box, target.id), target.preferred))
                }
            }
        }
        fun choose(axis: UiSnapAxis, locked: String?): Candidate? {
            val choices = candidates(axis)
            choices.firstOrNull { it.key == locked && abs(it.delta) <= release }?.let { return it }
            return choices.filter { abs(it.delta) <= acquire }.minWithOrNull(
                compareBy<Candidate> { abs(it.delta) }
                    .thenBy { !it.preferred }
                    .thenBy { if (corner != null && it.guide.kind != UiSnapKind.ALIGN) 0 else 1 }
                    .thenBy { abs(it.guide.target.left + it.guide.target.right - raw.left - raw.right) +
                        abs(it.guide.target.top + it.guide.target.bottom - raw.top - raw.bottom) }
                    .thenBy { it.key }
            )
        }
        val y = choose(UiSnapAxis.Y, lockedY)
        // Predict the final width after height alignment; vertical overlap determines the affected row.
        if (corner != null && widthTargetsProvider != null) widthTargets = widthTargetsProvider(moved(UiSnapAxis.Y, y?.delta ?: 0f))
        val x = choose(UiSnapAxis.X, lockedX)
        lockedX = x?.key; lockedY = y?.key
        val dx = x?.delta ?: 0f; val dy = y?.delta ?: 0f
        val snapped = if (corner == null) raw.copy(left = raw.left + dx, right = raw.right + dx,
            top = raw.top + dy, bottom = raw.bottom + dy)
        else UiPreviewBox(
            raw.left + if (corner in listOf(0, 2)) dx else 0f,
            raw.top + if (corner < 2) dy else 0f,
            raw.right + if (corner in listOf(1, 3)) dx else 0f,
            raw.bottom + if (corner >= 2) dy else 0f
        )
        return UiSnapResult(snapped, listOfNotNull(x?.guide, y?.guide))
    }
}
