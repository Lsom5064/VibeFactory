package kr.ac.kangwon.hai.vibefactory.ui_editor

import kotlin.math.abs

/** Finds widths that still match after reserving the resized addition in the immutable source layout. */
internal object UiReflowWidthSnapper {
    fun targets(
        raw: UiPreviewBox, corner: Int, canvas: UiPreviewBox, minWidth: Float, distance: Float,
        predict: (UiPreviewBox) -> List<UiSnapTarget>
    ): List<UiSnapTarget> {
        require(corner in 0..3)
        val leftCorner = corner == 0 || corner == 2
        val maximum = if (leftCorner) raw.right - canvas.left else canvas.right - raw.left
        val lower = maxOf(minWidth, raw.width - distance)
        val upper = minOf(maximum, raw.width + distance)
        if (lower > upper) return emptyList()
        fun box(width: Float) = if (leftCorner) raw.copy(left = raw.right - width) else raw.copy(right = raw.left + width)
        val cache = mutableMapOf<Float, Map<String, UiSnapTarget>>()
        fun at(width: Float) = cache.getOrPut(width) { predict(box(width)).associateBy { it.id } }
        val before = at(lower)
        val after = at(upper)
        val tolerance = .05f
        return before.keys.intersect(after.keys).mapNotNull { id ->
            fun error(width: Float): Float? = at(width)[id]?.box?.width?.let { width - it }
            var lo = lower
            var hi = upper
            var flo = error(lo) ?: return@mapNotNull null
            val fhi = error(hi) ?: return@mapNotNull null
            val current = raw.width.coerceIn(lo, hi)
            var solved = current.takeIf { abs(error(it) ?: Float.POSITIVE_INFINITY) <= tolerance }
            if (solved == null) {
                if (flo * fhi > 0f) return@mapNotNull null
                // Proportional row allocation is affine within a layout branch, so usually one prediction suffices.
                val estimate = if (abs(fhi - flo) > .00001f) ((lo * fhi - hi * flo) / (fhi - flo)).coerceIn(lo, hi) else current
                solved = estimate.takeIf { abs(error(it) ?: Float.POSITIVE_INFINITY) <= tolerance }
                if (solved == null) {
                    for (pass in 0 until 12) {
                        val mid = (lo + hi) / 2f
                        val fm = error(mid) ?: break
                        if (abs(fm) <= tolerance) { solved = mid; break }
                        if (flo * fm <= 0f) hi = mid else { lo = mid; flo = fm }
                    }
                }
            }
            // A row/group transition can jump over equality. Do not advertise a size match at that discontinuity.
            val width = solved ?: return@mapNotNull null
            val target = at(width)[id] ?: return@mapNotNull null
            target.copy(box = target.box.copy(right = target.box.left + width))
        }
    }
}
