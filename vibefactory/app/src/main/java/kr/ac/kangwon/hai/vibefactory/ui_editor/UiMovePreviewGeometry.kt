package kr.ac.kangwon.hai.vibefactory.ui_editor

internal data class UiPreviewBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun shifted(y: Float) = copy(top = top + y, bottom = bottom + y)
    fun overlaps(other: UiPreviewBox) = left < other.right - .01f && right > other.left + .01f && top < other.bottom - .01f && bottom > other.top + .01f
}

internal object UiMovePreviewGeometry {
    data class Placement(
        val blocks: Map<String, UiPreviewBox>,
        val destinations: Map<String, UiPreviewBox>,
        val equalRowMoves: Set<String>
    )

    private data class Member(val id: String, val isMove: Boolean, val scope: String?, val box: UiPreviewBox)
    private data class Row(val scope: String?, val members: MutableList<Member>)

    /** A side drop selects an insertion slot; every member of that row gets the same width. */
    fun arrange(
        blocks: Map<String, UiPreviewBox>, requested: Map<String, UiPreviewBox>,
        pinned: List<UiPreviewBox>, scopes: Map<String, String> = emptyMap()
    ): Placement {
        if (requested.isEmpty()) return Placement(emptyMap(), emptyMap(), emptySet())
        val placed = blocks.toMutableMap()
        val destinations = linkedMapOf<String, UiPreviewBox>()
        val destinationScopes = mutableMapOf<String, String?>()
        val equalRowMoves = mutableSetOf<String>()
        // Replay in annotation order. Each new pointer position refers to the preview after
        // earlier moves, including dashed boxes, rather than to the original XML positions.
        requested.forEach { (id, destination) ->
            val visible = placed.map { (key, box) -> Member(key, false, scopes[key], box) } +
                destinations.map { (key, box) -> Member(key, true, destinationScopes[key], box) }
            val rows = mutableListOf<Row>()
            visible.sortedWith(compareBy({ it.box.top }, { it.box.left }, { it.id })).forEach { member ->
                val box = member.box
                val row = rows.firstOrNull { row -> row.scope == member.scope && row.members.all { otherMember ->
                    val other = otherMember.box
                    minOf(box.bottom, other.bottom) - maxOf(box.top, other.top) > minOf(box.height, other.height) / 2f &&
                        (box.left >= other.right - .01f || box.right <= other.left + .01f)
                } }
                if (row == null) rows += Row(member.scope, mutableListOf(member)) else row.members += member
            }
            val x = (destination.left + destination.right) / 2f
            val y = (destination.top + destination.bottom) / 2f
            val candidate = visible.filter { member ->
                val box = member.box
                if (!box.overlaps(destination) || box.width <= 0f || box.height <= 0f) false else {
                    val horizontal = minOf(kotlin.math.abs(x - box.left), kotlin.math.abs(x - box.right)) / box.width
                    val vertical = minOf(kotlin.math.abs(y - box.top), kotlin.math.abs(y - box.bottom)) / box.height
                    y > box.top && y < box.bottom && horizontal < vertical
                }
            }.minByOrNull { member -> minOf(kotlin.math.abs(x - member.box.left), kotlin.math.abs(x - member.box.right)) }
            val row = candidate?.let { selected -> rows.first { selected in it.members } }
            destinations[id] = destination
            if (row != null) {
                val members = row.members.sortedBy { it.box.left }.toMutableList()
                val left = members.first().box.left
                val right = members.last().box.right
                val originalGap = members.zipWithNext { a, b -> (b.box.left - a.box.right).coerceAtLeast(0f) }
                    .average().takeIf { it.isFinite() }?.toFloat() ?: 0f
                val insertion = members.indexOfFirst { x <= (it.box.left + it.box.right) / 2f }
                    .takeIf { it >= 0 } ?: members.size
                members.add(insertion, Member(id, true, row.scope, destination))
                destinationScopes[id] = row.scope
                // No per-control minimum or item-count cap: all members share the row,
                // including previously placed destinations. Gaps shrink when necessary.
                val gap = minOf(originalGap, (right - left) / (2f * members.size))
                val slotWidth = ((right - left) - gap * (members.size - 1)) / members.size
                members.forEachIndexed { slot, member ->
                    val slotLeft = left + slot * (slotWidth + gap)
                    val box = member.box.copy(left = slotLeft, right = slotLeft + slotWidth)
                    if (member.isMove) {
                        destinations[member.id] = box
                        equalRowMoves += member.id
                    } else placed[member.id] = box
                }
            } else {
                // A free-standing destination can itself host a later side insertion,
                // without accidentally joining controls from unrelated XML parents.
                destinationScopes[id] = "destination:$id"
            }
            val displaced = offsets(placed, destinations.values.toList(), pinned)
            displaced.forEach { (key, offset) ->
                placed[key] = placed.getValue(key).shifted(offset)
            }
        }
        return Placement(placed.filter { (id, box) -> box != blocks.getValue(id) }, destinations, equalRowMoves)
    }

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
