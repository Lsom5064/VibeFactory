package kr.ac.kangwon.hai.vibefactory.ui_editor

internal data class UiPreviewBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height get() = bottom - top
    fun shifted(y: Float) = copy(top = top + y, bottom = bottom + y)
    fun overlaps(other: UiPreviewBox) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal object UiMovePreviewGeometry {
    /** Reserve the exact destination; move intersecting blocks down and propagate their displacement. */
    fun offsets(blocks: Map<String, UiPreviewBox>, reserved: List<UiPreviewBox>, pinned: List<UiPreviewBox>): Map<String, Float> {
        if (reserved.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Float>()
        val placed = mutableListOf<Pair<UiPreviewBox, UiPreviewBox>>()
        blocks.entries.sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.key })).forEach { (id, original) ->
            var moved = original
            // Each pass crosses at least one obstacle; all moves are monotonic downward.
            for (pass in 0..(reserved.size + pinned.size + placed.size)) {
                val obstacles = reserved + (if (moved != original) pinned else emptyList()) +
                    placed.filter { (before, after) ->
                        before != after && !original.overlaps(before)
                    }.map { it.second }
                val bottom = obstacles.filter(moved::overlaps).maxOfOrNull { it.bottom } ?: break
                moved = moved.shifted(bottom - moved.top)
            }
            val offset = moved.top - original.top
            if (offset > 0f) result[id] = offset
            placed += original to moved
        }
        return result
    }
}
