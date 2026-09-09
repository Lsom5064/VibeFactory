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
    var erasing = false
    var backgroundColorValue = Color.WHITE
    var aspectRatio = 1f
    var original: Bitmap? = null
    var showOriginal = false
        set(value) { field = value; invalidate() }
    private val history = mutableListOf<List<UiSketchStroke>>(emptyList())
    private var historyIndex = 0
    private val activePoints = mutableListOf<UiSketchPoint>()
    private var gestureBefore: List<UiSketchStroke> = emptyList()
    private val area = RectF()

    init { isClickable = true; contentDescription = "새 UI 그리기" }

    fun restore(value: List<UiSketchStroke>) {
        strokes = value
        history.clear(); history += value; historyIndex = 0
        invalidate()
    }

    fun undo() {
        if (historyIndex > 0) { strokes = history[--historyIndex]; changed?.invoke(strokes); invalidate() }
    }

    fun redo() {
        if (historyIndex < history.lastIndex) { strokes = history[++historyIndex]; changed?.invoke(strokes); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = 10f * resources.displayMetrics.density
        val w = (width - margin * 2).coerceAtLeast(1f)
        val h = (height - margin * 2).coerceAtLeast(1f)
        val rw = minOf(w, h * aspectRatio.coerceAtLeast(0.05f))
        val rh = rw / aspectRatio.coerceAtLeast(0.05f)
        area.set((width - rw) / 2, (height - rh) / 2, (width + rw) / 2, (height + rh) / 2)
        canvas.drawRect(area, Paint().apply { color = backgroundColorValue })
        if (showOriginal) original?.let { canvas.drawBitmap(it, null, area, Paint(Paint.FILTER_BITMAP_FLAG)) }
        else {
            UiSketchRenderer.draw(canvas, area, strokes)
            if (activePoints.isNotEmpty()) UiSketchRenderer.draw(canvas, area, listOf(activeStroke()))
        }
        canvas.drawRect(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 121, 91); style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showOriginal || area.width() <= 0) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !area.contains(event.x, event.y)) return false
        val point = UiSketchPoint(((event.x - area.left) / area.width()).coerceIn(0f, 1f),
            ((event.y - area.top) / area.height()).coerceIn(0f, 1f))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); gestureBefore = strokes; activePoints.clear(); updatePoint(point) }
            MotionEvent.ACTION_MOVE -> updatePoint(point)
            MotionEvent.ACTION_UP -> {
                updatePoint(point)
                if (!erasing && activePoints.isNotEmpty()) strokes = strokes + activeStroke()
                activePoints.clear()
                if (strokes != gestureBefore) {
                    while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
                    history += strokes
                    if (history.size > 100) history.removeAt(0)
                    historyIndex = history.lastIndex
                    changed?.invoke(strokes)
                }
                parent.requestDisallowInterceptTouchEvent(false); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { strokes = gestureBefore; activePoints.clear(); parent.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate(); return true
    }

    private fun updatePoint(point: UiSketchPoint) {
        if (erasing) strokes = strokes.filterNot { UiAdditionGeometry.isNearStroke(it, point) }
        else if (strokes.size < 256 && strokes.sumOf { it.points.size } + activePoints.size < 8192 &&
            (activePoints.isEmpty() || hypot(activePoints.last().x - point.x, activePoints.last().y - point.y) >= 0.002f)) {
            activePoints += point
        }
    }

    private fun activeStroke(): UiSketchStroke {
        val bright = Color.red(backgroundColorValue) * 0.299 + Color.green(backgroundColorValue) * 0.587 + Color.blue(backgroundColorValue) * 0.114
        return UiSketchStroke(activePoints.toList(), if (bright < 128) Color.WHITE else Color.rgb(25, 33, 29))
    }
    override fun performClick(): Boolean = super.performClick()
}
