package kr.ac.kangwon.hai.vibefactory.ui_editor

import kotlin.math.abs

enum class UiEditorParentFlow {
    HORIZONTAL,
    VERTICAL,
    FREEFORM
}

data class UiEditorPlacementItem(
    val stableId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val canDisplace: Boolean = true
) {
    val centerX: Int
        get() = left + (right - left) / 2

    val centerY: Int
        get() = top + (bottom - top) / 2
}

data class UiEditorPlacementOffset(
    val deltaX: Int,
    val deltaY: Int
)

data class UiEditorPlacementDecision(
    val targetStableId: String?,
    val snapLeft: Int,
    val snapTop: Int,
    val insertAfterTarget: Boolean,
    val siblingOffsets: Map<String, UiEditorPlacementOffset>
)

object UiEditorPlacementPolicy {
    fun resolve(
        flow: UiEditorParentFlow,
        draggedStableId: String,
        dropLeft: Int,
        dropTop: Int,
        dropWidth: Int,
        dropHeight: Int,
        items: List<UiEditorPlacementItem>
    ): UiEditorPlacementDecision {
        val sourceIndex = items.indexOfFirst { it.stableId == draggedStableId }
        if (sourceIndex < 0) {
            return freePlacement(dropLeft, dropTop)
        }
        return when (flow) {
            UiEditorParentFlow.HORIZONTAL,
            UiEditorParentFlow.VERTICAL -> resolveLinear(
                flow = flow,
                sourceIndex = sourceIndex,
                dropLeft = dropLeft,
                dropTop = dropTop,
                dropWidth = dropWidth,
                dropHeight = dropHeight,
                items = items
            )

            UiEditorParentFlow.FREEFORM -> resolveFreeform(
                sourceIndex = sourceIndex,
                dropLeft = dropLeft,
                dropTop = dropTop,
                dropWidth = dropWidth,
                dropHeight = dropHeight,
                items = items
            )
        }
    }

    private fun resolveLinear(
        flow: UiEditorParentFlow,
        sourceIndex: Int,
        dropLeft: Int,
        dropTop: Int,
        dropWidth: Int,
        dropHeight: Int,
        items: List<UiEditorPlacementItem>
    ): UiEditorPlacementDecision {
        val dropCenter = if (flow == UiEditorParentFlow.HORIZONTAL) {
            dropLeft + dropWidth / 2
        } else {
            dropTop + dropHeight / 2
        }
        val targetIndex = items.indices.minByOrNull { index ->
            val itemCenter = if (flow == UiEditorParentFlow.HORIZONTAL) {
                items[index].centerX
            } else {
                items[index].centerY
            }
            abs(dropCenter - itemCenter)
        } ?: sourceIndex
        val source = items[sourceIndex]
        if (targetIndex == sourceIndex) {
            return UiEditorPlacementDecision(
                targetStableId = null,
                snapLeft = source.left,
                snapTop = source.top,
                insertAfterTarget = false,
                siblingOffsets = emptyMap()
            )
        }

        val offsets = linkedMapOf<String, UiEditorPlacementOffset>()
        if (targetIndex > sourceIndex) {
            for (index in (sourceIndex + 1)..targetIndex) {
                offsets[items[index].stableId] = offset(items[index], items[index - 1])
            }
        } else {
            for (index in targetIndex until sourceIndex) {
                offsets[items[index].stableId] = offset(items[index], items[index + 1])
            }
        }
        val target = items[targetIndex]
        return UiEditorPlacementDecision(
            targetStableId = target.stableId,
            snapLeft = target.left,
            snapTop = target.top,
            insertAfterTarget = targetIndex > sourceIndex,
            siblingOffsets = offsets
        )
    }

    private fun resolveFreeform(
        sourceIndex: Int,
        dropLeft: Int,
        dropTop: Int,
        dropWidth: Int,
        dropHeight: Int,
        items: List<UiEditorPlacementItem>
    ): UiEditorPlacementDecision {
        val dropRight = dropLeft + dropWidth
        val dropBottom = dropTop + dropHeight
        val target = items.asSequence()
            .filterIndexed { index, item -> index != sourceIndex && item.canDisplace }
            .map { item -> item to intersectionArea(dropLeft, dropTop, dropRight, dropBottom, item) }
            .filter { (_, area) -> area > 0 }
            .maxByOrNull { (_, area) -> area }
            ?.first
            ?: return freePlacement(dropLeft, dropTop)
        val source = items[sourceIndex]
        return UiEditorPlacementDecision(
            targetStableId = target.stableId,
            snapLeft = target.left,
            snapTop = target.top,
            insertAfterTarget = false,
            siblingOffsets = mapOf(target.stableId to offset(target, source))
        )
    }

    private fun freePlacement(left: Int, top: Int) = UiEditorPlacementDecision(
        targetStableId = null,
        snapLeft = left,
        snapTop = top,
        insertAfterTarget = false,
        siblingOffsets = emptyMap()
    )

    private fun offset(from: UiEditorPlacementItem, to: UiEditorPlacementItem) = UiEditorPlacementOffset(
        deltaX = to.left - from.left,
        deltaY = to.top - from.top
    )

    private fun intersectionArea(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        item: UiEditorPlacementItem
    ): Int {
        val width = (minOf(right, item.right) - maxOf(left, item.left)).coerceAtLeast(0)
        val height = (minOf(bottom, item.bottom) - maxOf(top, item.top)).coerceAtLeast(0)
        return width * height
    }
}
