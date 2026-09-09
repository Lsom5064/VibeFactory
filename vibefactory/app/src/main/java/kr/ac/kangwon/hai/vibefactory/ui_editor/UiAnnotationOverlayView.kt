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
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class UiAnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var referenceWidth = 0
    private var referenceHeight = 0
    private val coordinateWidth get() = referenceWidth.takeIf { it > 0 } ?: width
    private val coordinateHeight get() = referenceHeight.takeIf { it > 0 } ?: height
    fun setReferenceSize(width: Int, height: Int) {
        referenceWidth = width
        referenceHeight = height
    }
    var movePreviewListener: ((Float?, Float?) -> Unit)? = null
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
    private var selectedDeleteTargets: List<UiAnnotationTarget> = emptyList()

    fun showDeleteSelection(targets: List<UiAnnotationTarget>) {
        selectedDeleteTargets = targets.toList()
        invalidate()
    }
    private var hoverAction: UiAnnotationAction? = null
    private var hoverBounds: UiNormalizedRect? = null
    private var pendingMoveSource: UiAnnotationTarget? = null
    private var pendingPointX: Float? = null
    private var pendingPointY: Float? = null
    var destinationTapListener: ((Float, Float) -> Unit)? = null
    var destinationDragListener: ((Float, Float, Boolean) -> Unit)? = null
    var targetTapListener: ((Float, Float) -> Unit)? = null
    private var targetSelectionEnabled = false
    var annotationTapListener: ((List<UiAnnotation>) -> Unit)? = null
    var additionBoundsChanged: ((UiNormalizedRect, Boolean) -> Unit)? = null
    private var pendingAddition: UiAnnotation? = null
    private var downX = 0f
    private var downY = 0f
    private var tappedAnnotations: List<UiAnnotation> = emptyList()
    private var resizeCorner = -1

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showAnnotations(value: List<UiAnnotation>) {
        annotations = value.toList()
        updateClickable()
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
        updateClickable()
        invalidate()
    }

    fun enableTargetSelection(enabled: Boolean) {
        targetSelectionEnabled = enabled
        updateClickable()
    }

    fun showAdditionPlacement(annotation: UiAnnotation?) {
        pendingAddition = annotation
        resizeCorner = -1
        updateClickable()
        invalidate()
    }

    private fun updateClickable() {
        isClickable = targetSelectionEnabled || pendingMoveSource != null || pendingAddition != null || annotations.isNotEmpty()
    }

    internal fun annotationsAt(x: Float, y: Float): List<UiAnnotation> = annotations.asReversed().filter { annotation ->
        val b = annotation.addition?.bounds ?: annotation.target.bounds
        val badgeX = (b.right * coordinateWidth - 1.8f * density).coerceAtMost(coordinateWidth - 12f * density)
        val badgeY = (b.top * coordinateHeight + 1.8f * density).coerceAtLeast(12f * density)
        UiAdditionGeometry.contains(b, x / coordinateWidth.coerceAtLeast(1), y / coordinateHeight.coerceAtLeast(1)) ||
            (abs(x - badgeX) <= 18f * density && abs(y - badgeY) <= 18f * density) ||
            (annotation.action == UiAnnotationAction.MOVE && annotation.resolvedDestinationPoint().let {
                val endX = it.first * coordinateWidth; val endY = it.second * coordinateHeight
                val start = rect(annotation.target.bounds)
                val dx = endX - start.centerX(); val dy = endY - start.centerY()
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0f) 0f else
                    (((x - start.centerX()) * dx + (y - start.centerY()) * dy) / lengthSquared).coerceIn(0f, 1f)
                moveDestinationRect(annotation.target.bounds, endX, endY).contains(x, y) ||
                    kotlin.math.hypot(x - start.centerX() - t * dx, y - start.centerY() - t * dy) <= 18f * density
            })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pendingAddition != null) return handleAdditionTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y
            tappedAnnotations = if (pendingMoveSource == null && !targetSelectionEnabled) annotationsAt(event.x, event.y) else emptyList()
        }
        if (pendingMoveSource == null && !targetSelectionEnabled && tappedAnnotations.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pendingMoveSource != null) parent.requestDisallowInterceptTouchEvent(true)
                pendingPointX = (event.x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                pendingPointY = (event.y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (pendingMoveSource != null) movePreviewListener?.invoke(pendingPointX, pendingPointY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pendingPointX = (event.x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                pendingPointY = (event.y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (pendingMoveSource != null) {
                    destinationDragListener?.invoke(event.x, event.y, true)
                    movePreviewListener?.invoke(pendingPointX, pendingPointY)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val touchX = (event.x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                val touchY = (event.y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                if (pendingMoveSource != null) {
                    val x = pendingPointX ?: touchX
                    val y = pendingPointY ?: touchY
                    destinationDragListener?.invoke(event.x, event.y, false)
                    destinationTapListener?.invoke(x, y)
                } else if (abs(event.x - downX) <= ViewConfiguration.get(context).scaledTouchSlop &&
                    abs(event.y - downY) <= ViewConfiguration.get(context).scaledTouchSlop) {
                    if (tappedAnnotations.isNotEmpty()) annotationTapListener?.invoke(tappedAnnotations)
                    else
                    targetTapListener?.invoke(event.x / coordinateWidth.coerceAtLeast(1), event.y / coordinateHeight.coerceAtLeast(1))
                }
                tappedAnnotations = emptyList()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                destinationDragListener?.invoke(event.x, event.y, false)
                tappedAnnotations = emptyList()
                if (pendingMoveSource != null) {
                    pendingPointX = null; pendingPointY = null
                    movePreviewListener?.invoke(null, null)
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleAdditionTouch(event: MotionEvent): Boolean {
        val annotation = pendingAddition ?: return false
        val spec = annotation.addition ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop ||
                    abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) return true
                val x = (event.x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                val y = (event.y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                val b = spec.bounds
                if (resizeCorner < 0) {
                    val corners = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
                    resizeCorner = corners.indexOfFirst {
                        abs(event.x - it.first * coordinateWidth) < 24f * density && abs(event.y - it.second * coordinateHeight) < 24f * density
                    }
                    if (resizeCorner >= 0) { invalidate(); performClick(); return true }
                }
                val minW = minOf(64f * density / coordinateWidth.coerceAtLeast(1), b.right - b.left)
                val minH = minOf(48f * density / coordinateHeight.coerceAtLeast(1), b.bottom - b.top)
                val result = if (resizeCorner < 0) {
                    UiAdditionGeometry.translate(b, x - (b.left + b.right) / 2, y - (b.top + b.bottom) / 2)
                } else UiNormalizedRect(
                    if (resizeCorner == 0 || resizeCorner == 2) x.coerceIn(0f, b.right - minW) else b.left,
                    if (resizeCorner < 2) y.coerceIn(0f, b.bottom - minH) else b.top,
                    if (resizeCorner == 1 || resizeCorner == 3) x.coerceIn(b.left + minW, 1f) else b.right,
                    if (resizeCorner >= 2) y.coerceIn(b.top + minH, 1f) else b.bottom
                )
                resizeCorner = -1
                pendingAddition = annotation.copy(addition = spec.copy(bounds = result))
                additionBoundsChanged?.invoke(result, true)
                performClick()
                invalidate()
            }
        }
        return true
    }

    fun offsetPendingPointBy(deltaX: Float, deltaY: Float) {
        if (pendingMoveSource == null) return
        pendingPointX = ((pendingPointX ?: 0f) + deltaX / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        pendingPointY = ((pendingPointY ?: 0f) + deltaY / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        movePreviewListener?.invoke(pendingPointX, pendingPointY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        annotations.forEachIndexed { index, annotation -> drawAnnotation(canvas, annotation, index + 1) }
        selectedDeleteTargets.forEach { target -> drawBox(canvas, rect(target.bounds), DELETE_COLOR, "✓", null, false) }
        pendingAddition?.let { annotation ->
            drawAnnotation(canvas, annotation, 0)
            annotation.addition?.bounds?.let { b ->
                fillPaint.color = ADD_COLOR
                listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom).forEachIndexed { index, point ->
                    fillPaint.color = ADD_COLOR
                    canvas.drawCircle(point.first * coordinateWidth, point.second * coordinateHeight, 8f * density, fillPaint)
                    if (index == resizeCorner) {
                        fillPaint.color = Color.WHITE
                        canvas.drawCircle(point.first * coordinateWidth, point.second * coordinateHeight, 5f * density, fillPaint)
                    }
                }
            }
        }
        val action = hoverAction
        val bounds = hoverBounds
        if (action != null && bounds != null) {
            drawBox(canvas, rect(bounds), colorFor(action), symbolFor(action), null, dashed = true)
        }
        val source = pendingMoveSource
        if (source != null) {
            val sourceRect = rect(source.bounds)
            drawBox(canvas, sourceRect, MOVE_COLOR, "↗", null, dashed = false)
            val destinationX = (pendingPointX ?: source.bounds.right).coerceIn(0f, 1f) * coordinateWidth
            val destinationY = (pendingPointY ?: source.bounds.bottom).coerceIn(0f, 1f) * coordinateHeight
            drawDestination(canvas, moveDestinationRect(source.bounds, destinationX, destinationY), MOVE_COLOR)
            drawArrow(canvas, sourceRect.centerX(), sourceRect.centerY(), destinationX, destinationY, MOVE_COLOR)
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: UiAnnotation, number: Int) {
        val color = colorFor(annotation.action)
        val targetRect = rect(annotation.addition?.bounds ?: annotation.target.bounds)
        annotation.addition?.let { spec ->
            fillPaint.color = spec.backgroundColor
            canvas.drawRect(targetRect, fillPaint)
            UiSketchRenderer.draw(canvas, targetRect, spec.strokes)
        }
        drawBox(canvas, targetRect, color, symbolFor(annotation.action), number.takeIf { it > 0 }, dashed = false)
        if (annotation.action == UiAnnotationAction.MOVE) {
            val (normalizedX, normalizedY) = annotation.resolvedDestinationPoint()
            val endX = normalizedX * coordinateWidth
            val endY = normalizedY * coordinateHeight
            drawDestination(canvas, moveDestinationRect(annotation.target.bounds, endX, endY), color)
            drawArrow(canvas, targetRect.centerX(), targetRect.centerY(), endX, endY, color)
        }
    }

    internal fun moveDestinationRect(sourceBounds: UiNormalizedRect, centerX: Float, centerY: Float): RectF {
        val source = sourceBounds.normalized()
        val halfWidth = (source.right - source.left) * coordinateWidth / 2f
        val halfHeight = (source.bottom - source.top) * coordinateHeight / 2f
        // Keep the source's actual size and the chosen center. Canvas clipping
        // handles edges; clamping or the selection box's minimum size would
        // misrepresent the requested move.
        return RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
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
        val badgeX = (bounds.right - radius * 0.15f).coerceAtMost(coordinateWidth - radius)
        val badgeY = (bounds.top + radius * 0.15f).coerceAtLeast(radius)
        fillPaint.color = color
        canvas.drawCircle(badgeX, badgeY, radius, fillPaint)
        canvas.drawText(symbol, badgeX, badgeY - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2, badgeTextPaint)
        number?.let {
            val label = it.toString()
            val labelWidth = instructionPaint.measureText(label) + 12f * density
            val left = bounds.left.coerceAtLeast(0f)
            val top = (bounds.bottom + 3f * density).coerceAtMost(coordinateHeight - 18f * density)
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
        val normalized = bounds
        val minSize = 12f * density
        val left = normalized.left * coordinateWidth
        val top = normalized.top * coordinateHeight
        return RectF(
            left,
            top,
            maxOf(normalized.right * coordinateWidth, left + minSize).coerceAtMost(coordinateWidth.toFloat()),
            maxOf(normalized.bottom * coordinateHeight, top + minSize).coerceAtMost(coordinateHeight.toFloat())
        )
    }

    private fun colorFor(action: UiAnnotationAction): Int = when (action) {
        UiAnnotationAction.DELETE -> DELETE_COLOR
        UiAnnotationAction.MOVE -> MOVE_COLOR
        UiAnnotationAction.BEHAVIOR -> BEHAVIOR_COLOR
        UiAnnotationAction.ADD -> ADD_COLOR
    }

    private fun symbolFor(action: UiAnnotationAction): String = when (action) {
        UiAnnotationAction.DELETE -> "×"
        UiAnnotationAction.MOVE -> "↗"
        UiAnnotationAction.BEHAVIOR -> "⚙"
        UiAnnotationAction.ADD -> "+"
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
        val ADD_COLOR: Int = Color.rgb(24, 121, 91)
    }
}
