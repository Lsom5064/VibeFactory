package kr.ac.kangwon.hai.vibefactory.ui_editor

/** A view transform only. Persisted strokes always use unzoomed region coordinates. */
internal class UiSketchViewport {
    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    var viewWidth = 1f
        private set
    var viewHeight = 1f
        private set
    var contentWidth = 1f
        private set
    var contentHeight = 1f
        private set

    fun resize(width: Float, height: Float, aspectRatio: Float, margin: Float) {
        viewWidth = width.coerceAtLeast(1f)
        viewHeight = height.coerceAtLeast(1f)
        val ratio = aspectRatio.coerceIn(0.05f, 20f)
        contentWidth = minOf((width - 2 * margin).coerceAtLeast(1f),
            (height - 2 * margin).coerceAtLeast(1f) * ratio)
        contentHeight = contentWidth / ratio
        constrain()
    }

    fun reset() { scale = 1f; offsetX = 0f; offsetY = 0f }

    fun transform(previousX: Float, previousY: Float, focusX: Float, focusY: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val next = (scale * factor).coerceIn(1f, 4f)
        offsetX = focusX - viewWidth / 2 - (previousX - viewWidth / 2 - offsetX) * next / scale
        offsetY = focusY - viewHeight / 2 - (previousY - viewHeight / 2 - offsetY) * next / scale
        scale = next
        constrain()
    }

    fun screenPoint(point: UiSketchPoint) = UiSketchPoint(
        viewWidth / 2 + offsetX + (point.x - 0.5f) * contentWidth * scale,
        viewHeight / 2 + offsetY + (point.y - 0.5f) * contentHeight * scale)

    fun normalizedPoint(x: Float, y: Float): UiSketchPoint? {
        val px = (x - viewWidth / 2 - offsetX) / (contentWidth * scale) + 0.5f
        val py = (y - viewHeight / 2 - offsetY) / (contentHeight * scale) + 0.5f
        return if (px in 0f..1f && py in 0f..1f) UiSketchPoint(px, py) else null
    }

    private fun constrain() {
        val maxX = ((contentWidth * scale - viewWidth) / 2).coerceAtLeast(0f)
        val maxY = ((contentHeight * scale - viewHeight) / 2).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }
}
