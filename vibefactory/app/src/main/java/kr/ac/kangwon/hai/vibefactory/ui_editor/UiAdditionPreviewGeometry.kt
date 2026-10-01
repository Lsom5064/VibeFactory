package kr.ac.kangwon.hai.vibefactory.ui_editor

/** Addition rectangles are fixed reservations; only existing controls can resize or move. */
internal object UiAdditionPreviewGeometry {
    private data class Row(val scope: String?, val members: MutableList<String>)
    private data class Interval(val left: Float, val right: Float)

    fun arrange(
        blocks: Map<String, UiPreviewBox>, reserved: List<UiPreviewBox>,
        pinned: List<UiPreviewBox> = emptyList(), scopes: Map<String, String> = emptyMap()
    ): Map<String, UiPreviewBox> {
        if (reserved.isEmpty()) return blocks
        val rows = mutableListOf<Row>()
        blocks.entries.sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.key })).forEach { (id, box) ->
            val row = rows.firstOrNull { row -> row.scope == scopes[id] && row.members.all { member ->
                val other = blocks.getValue(member)
                minOf(box.bottom, other.bottom) - maxOf(box.top, other.top) > minOf(box.height, other.height) / 2f &&
                    (box.left >= other.right - .01f || box.right <= other.left + .01f)
            } }
            if (row == null) rows += Row(scopes[id], mutableListOf(id)) else row.members += id
        }
        val proposed = blocks.toMutableMap()
        rows.forEach { row ->
            row.members.sortBy { blocks.getValue(it).left }
            val members = row.members.map(blocks::getValue)
            val left = members.minOf { it.left }
            val right = members.maxOf { it.right }
            val top = members.maxOf { it.top }
            val bottom = members.minOf { it.bottom }
            // A tall side region can cover several short rows even when its centre lies
            // outside each row. Use substantial vertical overlap, not centre containment.
            // Small top/bottom overlaps still insert vertically.
            val cuts = reserved.filter { box ->
                minOf(box.bottom, bottom) - maxOf(box.top, top) > minOf(box.height, bottom - top) / 2f &&
                    members.any(box::overlaps)
            }.sortedBy { it.left }
            if (cuts.isEmpty()) return@forEach
            val merged = mutableListOf<Interval>()
            cuts.forEach { box ->
                val interval = Interval(maxOf(left, box.left), minOf(right, box.right))
                val last = merged.lastOrNull()
                if (last != null && interval.left <= last.right) merged[merged.lastIndex] = last.copy(right = maxOf(last.right, interval.right))
                else merged += interval
            }
            val spaces = (0..merged.size).map { i ->
                Interval(if (i == 0) left else merged[i - 1].right, if (i == merged.size) right else merged[i].left)
            }
            if (spaces.all { it.right - it.left < .01f }) return@forEach
            val groups = row.members.groupBy { id ->
                val box = blocks.getValue(id)
                val center = (box.left + box.right) / 2f
                val preferred = merged.count { center > (it.left + it.right) / 2f }
                // An edge insertion has no space on its outer side: all controls belong to
                // the remaining side, even if the new rectangle covers their old centres.
                if (spaces[preferred].right - spaces[preferred].left > .01f) preferred else
                    spaces.indices.filter { spaces[it].right - spaces[it].left > .01f }
                        .minBy { kotlin.math.abs(it - preferred) }
            }
            groups.forEach { (index, ids) ->
                val space = spaces[index]
                val originalGap = ids.zipWithNext { a, b -> (blocks.getValue(b).left - blocks.getValue(a).right).coerceAtLeast(0f) }
                    .average().takeIf { it.isFinite() }?.toFloat() ?: 0f
                val total = ids.sumOf { blocks.getValue(it).width.toDouble() }.toFloat()
                // A requested side insertion explicitly permits shrinking controls. Theme
                // minWidth/48dp must not force a vertical move. Only require a drawable pixel
                // per control, reducing gaps first while keeping relative control widths.
                val required = ids.maxOf { total / blocks.getValue(it).width }
                val gap = if (ids.size == 1) 0f else minOf(originalGap, (space.right - space.left) / (2f * ids.size),
                    ((space.right - space.left - required) / (ids.size - 1)).coerceAtLeast(0f))
                val available = (space.right - space.left - gap * (ids.size - 1)).coerceAtLeast(0f)
                val fits = available + .01f >= required
                if (fits && available > 0f) {
                    var x = space.left
                    ids.forEach { id ->
                        val box = blocks.getValue(id)
                        val width = box.width * available / total
                        proposed[id] = box.copy(left = x, right = x + width)
                        x += width + gap
                    }
                } else {
                    // No renderable side space remains. Keep original order on the next line;
                    // the settling pass propagates its height to following controls.
                    val nextTop = maxOf(members.maxOf { it.bottom }, cuts.maxOf { it.bottom })
                    ids.forEach { id ->
                        val box = blocks.getValue(id)
                        proposed[id] = box.shifted(nextTop - top)
                    }
                }
            }
        }
        val placed = linkedMapOf<String, UiPreviewBox>()
        blocks.entries.sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.key })).forEach { (id, original) ->
            var box = proposed.getValue(id)
            for (pass in 0..(reserved.size + pinned.size + placed.size)) {
                val obstacles = reserved + (if (box != original) pinned else emptyList()) + placed.filter { (otherId, after) ->
                    val before = blocks.getValue(otherId)
                    before != after && !original.overlaps(before)
                }.values
                val edge = obstacles.filter(box::overlaps).maxOfOrNull { it.bottom } ?: break
                box = box.shifted(edge - box.top)
            }
            placed[id] = box
        }
        return placed
    }
}
