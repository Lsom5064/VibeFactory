package kr.ac.kangwon.hai.vibefactory.ui_editor

import kotlin.math.roundToInt

internal object UiEditorEdgeScrollPolicy {
    fun step(
        pointer: Float,
        viewportStart: Int,
        viewportEnd: Int,
        edgeSize: Int,
        maxStep: Int
    ): Int {
        if (viewportEnd <= viewportStart || edgeSize <= 0 || maxStep <= 0) return 0
        val startBoundary = viewportStart + edgeSize
        val endBoundary = viewportEnd - edgeSize
        return when {
            pointer < startBoundary -> {
                val strength = ((startBoundary - pointer) / edgeSize).coerceIn(0f, 1f)
                -(maxStep * strength).roundToInt().coerceAtLeast(1)
            }
            pointer > endBoundary -> {
                val strength = ((pointer - endBoundary) / edgeSize).coerceIn(0f, 1f)
                (maxStep * strength).roundToInt().coerceAtLeast(1)
            }
            else -> 0
        }
    }
}
