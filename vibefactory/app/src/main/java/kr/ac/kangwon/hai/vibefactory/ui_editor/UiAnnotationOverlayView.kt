package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class UiAnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
    }
    private var annotations: List<UiAnnotation> = emptyList()
    private var hoverAction: UiAnnotationAction? = null
    private var hoverBounds: UiNormalizedRect? = null
    private var pendingMoveSource: UiAnnotationTarget? = null
    private var pendingPointX: Float? = null
    private var pendingPointY: Float? = null
    var destinationTapListener: ((Float, Float) -> Unit)? = null
    var destinationDragListener: ((Float, Float, Boolean) -> Unit)? = null
    var targetTapListener: ((Float, Float) -> Unit)? = null
    private var targetSelectionEnabled = false

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showAnnotations(value: List<UiAnnotation>) {
        annotations = value.toList()
        invalidate()
    }

    fun showHover(action: UiAnnotationAction?, bounds: UiNormalizedRect?) {
        hoverAction = action
        hoverBounds = bounds
        invalidate()
    }

    fun showPendingMove(source: UiAnnotationTarget?, x: Float? = null, y: Float? = null) {
        pendingMoveSource = source
        pendingPointX = x
        pendingPointY = y
        isClickable = source != null || targetSelectionEnabled
        invalidate()
    }

    fun enableTargetSelection(enabled: Boolean) {
        targetSelectionEnabled = enabled
        isClickable = enabled || pendingMoveSource != null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pendingMoveSource == null && !targetSelectionEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pendingMoveSource != null) parent.requestDisallowInterceptTouchEvent(true)
                pendingPointX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                pendingPointY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pendingPointX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                pendingPointY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (pendingMoveSource != null) {
                    destinationDragListener?.invoke(event.x, event.y, true)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val touchX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val touchY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                if (pendingMoveSource != null) {
                    val x = pendingPointX ?: touchX
                    val y = pendingPointY ?: touchY
                    destinationDragListener?.invoke(event.x, event.y, false)
                    destinationTapListener?.invoke(x, y)
                } else {
                    targetTapListener?.invoke(touchX, touchY)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                destinationDragListener?.invoke(event.x, event.y, false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun offsetPendingPointBy(deltaX: Float, deltaY: Float) {
        if (pendingMoveSource == null) return
        pendingPointX = ((pendingPointX ?: 0f) + deltaX / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        pendingPointY = ((pendingPointY ?: 0f) + deltaY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        annotations.forEachIndexed { index, annotation -> drawAnnotation(canvas, annotation, index + 1) }
        val action = hoverAction
        val bounds = hoverBounds
        if (action != null && bounds != null) {
            drawBox(canvas, rect(bounds), colorFor(action), symbolFor(action), null, dashed = true)
        }
        val source = pendingMoveSource
        if (source != null) {
            val sourceRect = rect(source.bounds)
            drawBox(canvas, sourceRect, MOVE_COLOR, "↗", null, dashed = true)
            val destinationX = (pendingPointX ?: source.bounds.right).coerceIn(0f, 1f) * width
            val destinationY = (pendingPointY ?: source.bounds.bottom).coerceIn(0f, 1f) * height
            drawArrow(canvas, sourceRect.centerX(), sourceRect.centerY(), destinationX, destinationY, MOVE_COLOR)
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: UiAnnotation, number: Int) {
        val color = colorFor(annotation.action)
        val targetRect = rect(annotation.target.bounds)
        drawBox(canvas, targetRect, color, symbolFor(annotation.action), number, dashed = false)
        if (annotation.action == UiAnnotationAction.MOVE) {
            val destination = annotation.destination?.bounds?.let(::rect)
            val (normalizedX, normalizedY) = annotation.resolvedDestinationPoint()
            val endX = normalizedX * width
            val endY = normalizedY * height
            drawArrow(canvas, targetRect.centerX(), targetRect.centerY(), endX, endY, color)
            destination?.let { drawDestination(canvas, it, color) }
        }
    }

    private fun drawBox(
        canvas: Canvas,
        bounds: RectF,
        color: Int,
        symbol: String,
        number: Int?,
        dashed: Boolean
    ) {
        fillPaint.color = colorWithAlpha(color, if (dashed) 34 else 24)
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, fillPaint)
        borderPaint.color = color
        borderPaint.pathEffect = if (dashed) {
            android.graphics.DashPathEffect(floatArrayOf(7f * density, 4f * density), 0f)
        } else null
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, borderPaint)
        borderPaint.pathEffect = null

        val radius = 12f * density
        val badgeX = (bounds.right - radius * 0.15f).coerceAtMost(width - radius)
        val badgeY = (bounds.top + radius * 0.15f).coerceAtLeast(radius)
        fillPaint.color = color
        canvas.drawCircle(badgeX, badgeY, radius, fillPaint)
        canvas.drawText(symbol, badgeX, badgeY - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2, badgeTextPaint)
        number?.let {
            val label = it.toString()
            val labelWidth = instructionPaint.measureText(label) + 12f * density
            val left = bounds.left.coerceAtLeast(0f)
            val top = (bounds.bottom + 3f * density).coerceAtMost(height - 18f * density)
            fillPaint.color = color
            canvas.drawRoundRect(left, top, left + labelWidth, top + 18f * density, 4f * density, 4f * density, fillPaint)
            canvas.drawText(label, left + 6f * density, top + 13f * density, instructionPaint)
        }
    }

    private fun drawDestination(canvas: Canvas, bounds: RectF, color: Int) {
        borderPaint.color = color
        borderPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, borderPaint)
        borderPaint.pathEffect = null
    }

    private fun drawArrow(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, color: Int) {
        borderPaint.color = color
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 3f * density
        canvas.drawLine(startX, startY, endX, endY, borderPaint)
        val angle = atan2(endY - startY, endX - startX)
        val size = 12f * density
        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(
                endX - size * cos(angle - Math.PI.toFloat() / 6f),
                endY - size * sin(angle - Math.PI.toFloat() / 6f)
            )
            moveTo(endX, endY)
            lineTo(
                endX - size * cos(angle + Math.PI.toFloat() / 6f),
                endY - size * sin(angle + Math.PI.toFloat() / 6f)
            )
        }
        canvas.drawPath(path, borderPaint)
    }

    private fun rect(bounds: UiNormalizedRect): RectF {
        val normalized = bounds.normalized()
        val minSize = 12f * density
        val left = normalized.left * width
        val top = normalized.top * height
        return RectF(
            left,
            top,
            maxOf(normalized.right * width, left + minSize).coerceAtMost(width.toFloat()),
            maxOf(normalized.bottom * height, top + minSize).coerceAtMost(height.toFloat())
        )
    }

    private fun colorFor(action: UiAnnotationAction): Int = when (action) {
        UiAnnotationAction.DELETE -> DELETE_COLOR
        UiAnnotationAction.MOVE -> MOVE_COLOR
        UiAnnotationAction.BEHAVIOR -> BEHAVIOR_COLOR
    }

    private fun symbolFor(action: UiAnnotationAction): String = when (action) {
        UiAnnotationAction.DELETE -> "×"
        UiAnnotationAction.MOVE -> "↗"
        UiAnnotationAction.BEHAVIOR -> "⚙"
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    companion object {
        val DELETE_COLOR: Int = Color.rgb(211, 47, 47)
        val MOVE_COLOR: Int = Color.rgb(25, 118, 210)
        val BEHAVIOR_COLOR: Int = Color.rgb(123, 31, 162)
    }
}
