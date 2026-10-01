package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

internal object UiSketchRenderer {
    fun draw(canvas: Canvas, bounds: RectF, strokes: List<UiSketchStroke>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.save()
        canvas.clipRect(bounds)
        strokes.forEach { stroke ->
            paint.color = stroke.color
            paint.strokeWidth = stroke.width * minOf(bounds.width(), bounds.height())
            val path = Path()
            stroke.points.forEachIndexed { index, p ->
                val x = bounds.left + p.x * bounds.width()
                val y = bounds.top + p.y * bounds.height()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.points.size == 1) {
                val p = stroke.points.first()
                canvas.drawPoint(bounds.left + p.x * bounds.width(), bounds.top + p.y * bounds.height(), paint)
            } else canvas.drawPath(path, paint)
        }
        canvas.restore()
    }
}

class UiSketchCanvasView(context: Context) : View(context) {
    var strokes: List<UiSketchStroke> = emptyList()
        private set
    var changed: ((List<UiSketchStroke>) -> Unit)? = null
    var contentChanged: ((List<UiSketchStroke>, List<UiSketchImageLayer>) -> Unit)? = null
    var selectionChanged: (() -> Unit)? = null
    var viewportChanged: ((Float) -> Unit)? = null
    val zoomScale: Float get() = viewport.scale
    var imageLayers: List<UiSketchImageLayer> = emptyList()
        private set
    var imageBitmaps: Map<String, Bitmap> = emptyMap()
        set(value) { field = value; invalidate() }
    var selectedImageId: String? = null
        private set
    var penWidth = .009f
    var erasing = false
    var drawOnOriginal = false
    var borderColor = Color.rgb(24, 121, 91)
    var backgroundColorValue = Color.WHITE
    var aspectRatio = 1f
    var original: Bitmap? = null
    var showOriginal = false
        set(value) { field = value; invalidate() }
    private data class Content(val strokes: List<UiSketchStroke>, val images: List<UiSketchImageLayer>)
    private fun content() = Content(strokes, imageLayers)
    private val history = mutableListOf(Content(emptyList(), emptyList()))
    private var historyIndex = 0
    private val activePoints = mutableListOf<UiSketchPoint>()
    private var gestureBefore = Content(emptyList(), emptyList())
    private val area = RectF()
    internal val viewport = UiSketchViewport()
    private var navigating = false
    private var drawingGesture = false
    private var previousSpan = 0f
    private var previousFocus = UiSketchPoint(0f, 0f)
    private var imageStart: UiSketchImageLayer? = null
    private var imageCorner = -1
    private var downX = 0f
    private var downY = 0f
    private var longPress: Runnable? = null

    fun resetViewport() { viewport.reset(); viewportChanged?.invoke(zoomScale); invalidate() }

    fun zoomBy(factor: Float) {
        if (!isEnabled) return
        viewport.transform(width / 2f, height / 2f, width / 2f, height / 2f, factor)
        viewportChanged?.invoke(zoomScale)
        invalidate()
    }

    init { isClickable = true; contentDescription = "UI 그리기. 두 손가락으로 확대하거나 이동할 수 있습니다." }

    fun restore(value: List<UiSketchStroke>, images: List<UiSketchImageLayer> = emptyList()) {
        strokes = value; imageLayers = images
        history.clear(); history += content(); historyIndex = 0
        invalidate()
    }

    fun selectDrawingTool(erase: Boolean) { erasing = erase; selectImage(null) }

    private fun selectImage(id: String?) {
        selectedImageId = id; selectionChanged?.invoke(); invalidate()
    }

    fun insertImages(layers: List<UiSketchImageLayer>) {
        if (layers.isEmpty()) return
        imageLayers = imageLayers + layers
        recordContent(); selectImage(layers.last().imageId)
    }

    fun removeSelectedImage() {
        val id = selectedImageId ?: return
        imageLayers = imageLayers.filterNot { it.imageId == id }
        recordContent(); selectImage(null)
    }

    private fun applyContent(value: Content) {
        strokes = value.strokes; imageLayers = value.images
    }

    private fun notifyContent() {
        changed?.invoke(strokes); contentChanged?.invoke(strokes, imageLayers); invalidate()
    }

    private fun recordContent() {
        if (content() == history[historyIndex]) return
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        history += content()
        if (history.size > 100) history.removeAt(0)
        historyIndex = history.lastIndex; notifyContent()
    }

    fun undo() {
        if (historyIndex > 0) { applyContent(history[--historyIndex]); selectImage(null); notifyContent() }
    }

    fun redo() {
        if (historyIndex < history.lastIndex) { applyContent(history[++historyIndex]); selectImage(null); notifyContent() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = 10f * resources.displayMetrics.density
        viewport.resize(width.toFloat(), height.toFloat(), aspectRatio, margin)
        val topLeft = viewport.screenPoint(UiSketchPoint(0f, 0f))
        val bottomRight = viewport.screenPoint(UiSketchPoint(1f, 1f))
        area.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(area, Paint().apply { color = backgroundColorValue })
        if (showOriginal || drawOnOriginal) original?.let { canvas.drawBitmap(it, null, area, Paint(Paint.FILTER_BITMAP_FLAG)) }
        if (!showOriginal) {
            UiSketchImages.draw(canvas, area, imageLayers, imageBitmaps)
            UiSketchRenderer.draw(canvas, area, strokes)
            if (activePoints.isNotEmpty()) UiSketchRenderer.draw(canvas, area, listOf(activeStroke()))
        }
        canvas.drawRect(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
        })
        if (!showOriginal) drawImageSelection(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || showOriginal || area.width() <= 0) return false
        val point = viewport.normalizedPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                navigating = false; drawingGesture = point != null; previousSpan = 0f
                gestureBefore = content(); activePoints.clear(); imageStart = null
                downX = event.x; downY = event.y
                val selected = imageLayers.firstOrNull { it.imageId == selectedImageId }
                if (selected != null) {
                    drawingGesture = false
                    val delete = deletePoint(selected)
                    if (hypot(event.x-delete.x,event.y-delete.y) <= 14*resources.displayMetrics.density) {
                        removeSelectedImage(); gestureBefore = content(); navigating = true
                    } else {
                        imageCorner = corners(selected).indexOfFirst { near(event.x, event.y, it) }
                        if (imageCorner >= 0 || (point != null && UiAdditionGeometry.contains(selected.bounds, point.x, point.y))) imageStart = selected
                        else selectImage(null)
                    }
                } else if (point != null) {
                    updatePoint(point)
                    val hit = imageLayers.lastOrNull { UiAdditionGeometry.contains(it.bounds, point.x, point.y) }
                    if (hit != null) {
                        longPress = Runnable {
                            applyContent(gestureBefore); activePoints.clear(); drawingGesture = false
                            imageStart = hit; imageCorner = -1; selectImage(hit.imageId)
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // The first finger may already have drawn/erased: cancel that provisional stroke.
                cancelLongPressSelection()
                applyContent(gestureBefore); activePoints.clear(); navigating = true; drawingGesture = false; imageStart = null
                rememberNavigation(event)
            }
            MotionEvent.ACTION_POINTER_UP -> { previousSpan = 0f }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x-downX, event.y-downY) > ViewConfiguration.get(context).scaledTouchSlop) cancelLongPressSelection()
                if (navigating && event.pointerCount >= 2) {
                    val focus = UiSketchPoint((event.getX(0) + event.getX(1)) / 2,
                        (event.getY(0) + event.getY(1)) / 2)
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    if (previousSpan > 0f && span > 0f) viewport.transform(previousFocus.x, previousFocus.y,
                        focus.x, focus.y, span / previousSpan)
                    previousFocus = focus; previousSpan = span
                    viewportChanged?.invoke(zoomScale)
                } else if (!navigating && imageStart != null) {
                    val start = requireNotNull(imageStart)
                    val dx = (event.x-downX)/(viewport.contentWidth*viewport.scale)
                    val dy = (event.y-downY)/(viewport.contentHeight*viewport.scale)
                    val b = if (imageCorner >= 0) UiSketchImages.resize(start.bounds, imageCorner, dx, dy)
                        else UiAdditionGeometry.translate(start.bounds, dx, dy)
                    imageLayers = imageLayers.map { if (it.imageId == start.imageId) it.copy(bounds = b) else it }
                } else if (!navigating && drawingGesture && point != null) updatePoint(point)
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPressSelection()
                if (!navigating && drawingGesture && point != null) updatePoint(point)
                if (!navigating && !erasing && activePoints.isNotEmpty()) strokes = strokes + activeStroke()
                activePoints.clear()
                if (content() != gestureBefore) recordContent()
                imageStart = null
                parent?.requestDisallowInterceptTouchEvent(false); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressSelection(); applyContent(gestureBefore); activePoints.clear(); imageStart = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate(); return true
    }

    private fun cancelLongPressSelection() { longPress?.let(::removeCallbacks); longPress = null }

    private fun corners(layer: UiSketchImageLayer): List<UiSketchPoint> = layer.bounds.let { b ->
        listOf(UiSketchPoint(b.left,b.top), UiSketchPoint(b.right,b.top), UiSketchPoint(b.left,b.bottom), UiSketchPoint(b.right,b.bottom))
            .map(viewport::screenPoint)
    }

    private fun deletePoint(layer: UiSketchImageLayer): UiSketchPoint {
        val b = layer.bounds
        val p = viewport.screenPoint(UiSketchPoint((b.left+b.right)/2, b.top))
        val margin = 14*resources.displayMetrics.density
        return UiSketchPoint(p.x, (p.y-18*resources.displayMetrics.density).coerceIn(margin, maxOf(margin, height-margin)))
    }

    private fun near(x: Float, y: Float, point: UiSketchPoint): Boolean =
        hypot(x-point.x, y-point.y) <= 22*resources.displayMetrics.density

    private fun drawImageSelection(canvas: Canvas) {
        val layer = imageLayers.firstOrNull { it.imageId == selectedImageId } ?: return
        val p = corners(layer); val density = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = borderColor; style = Paint.Style.STROKE; strokeWidth = 2*density }
        canvas.drawRect(p[0].x,p[0].y,p[3].x,p[3].y,paint)
        p.forEach { point ->
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(point.x,point.y,7*density,paint)
            paint.style = Paint.Style.STROKE; paint.color = borderColor; canvas.drawCircle(point.x,point.y,7*density,paint)
        }
        val delete = deletePoint(layer)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(delete.x,delete.y,12*density,paint)
        paint.color = Color.rgb(180,40,40); paint.strokeWidth = 2*density
        val s = 5*density
        canvas.drawLine(delete.x-s,delete.y-s,delete.x+s,delete.y+s,paint)
        canvas.drawLine(delete.x-s,delete.y+s,delete.x+s,delete.y-s,paint)
    }

    override fun onDetachedFromWindow() { cancelLongPressSelection(); super.onDetachedFromWindow() }

    private fun rememberNavigation(event: MotionEvent) {
        previousFocus = UiSketchPoint((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
        previousSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun updatePoint(point: UiSketchPoint) {
        if (erasing) strokes = strokes.filterNot { UiAdditionGeometry.isNearStroke(it, point, 0.035f / viewport.scale) }
        else if (strokes.size < 256 && strokes.sumOf { it.points.size } + activePoints.size < 8192 &&
            (activePoints.isEmpty() || hypot(activePoints.last().x - point.x, activePoints.last().y - point.y) >= 0.002f)) {
            activePoints += point
        }
    }

    private fun activeStroke(): UiSketchStroke {
        val bright = Color.red(backgroundColorValue) * 0.299 + Color.green(backgroundColorValue) * 0.587 + Color.blue(backgroundColorValue) * 0.114
        return UiSketchStroke(activePoints.toList(), if (drawOnOriginal) Color.rgb(123, 31, 162)
            else if (bright < 128) Color.WHITE else Color.rgb(25, 33, 29), penWidth / viewport.scale)
    }
    override fun performClick(): Boolean = super.performClick()
}
