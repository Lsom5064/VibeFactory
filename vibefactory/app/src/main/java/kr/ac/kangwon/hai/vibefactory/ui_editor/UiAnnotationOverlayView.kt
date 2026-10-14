package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
    var sketchPreviews: Map<String, android.graphics.Bitmap> = emptyMap()
        set(value) { field = value; invalidate() }
    private var referenceWidth = 0
    private var referenceHeight = 0
    private val coordinateWidth get() = referenceWidth.takeIf { it > 0 } ?: width
    private val coordinateHeight get() = referenceHeight.takeIf { it > 0 } ?: height
    fun setReferenceSize(width: Int, height: Int) {
        referenceWidth = width
        referenceHeight = height
    }
    var movePreviewListener: ((Float?, Float?) -> Unit)? = null
    internal var alignmentTargetsProvider: (() -> List<UiSnapTarget>)? = null
    internal var alignmentTargetBoundsProvider: ((String) -> UiPreviewBox?)? = null
    private val alignmentSnapper = UiAlignmentSnapper()
    private var alignmentTargets: List<UiSnapTarget> = emptyList()
    private var alignmentGuides: List<UiSnapGuide> = emptyList()
    private var rawPointX: Float? = null
    private var rawPointY: Float? = null
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
    private var moveDestinationBounds: Map<String, UiNormalizedRect> = emptyMap()
    private var pendingMoveBounds: UiNormalizedRect? = null

    fun showMoveDestinationBounds(saved: Map<String, UiNormalizedRect>, pending: UiNormalizedRect?) {
        moveDestinationBounds = saved.toMap()
        pendingMoveBounds = pending
        invalidate()
    }
    var destinationTapListener: ((Float, Float) -> Unit)? = null
    var destinationDragListener: ((Float, Float, Boolean) -> Unit)? = null
    var targetTapListener: ((Float, Float) -> Unit)? = null
    /** Select a move source on DOWN so the same touch can continue into a drag. */
    var moveSourceTouchListener: ((Float, Float) -> Boolean)? = null
    private var selectedMoveOnDown = false
    private var moveGestureDragged = false
    private var targetSelectionEnabled = false
    var annotationTapListener: ((List<UiAnnotation>) -> Unit)? = null
    var additionBoundsChanged: ((UiNormalizedRect, Boolean) -> Unit)? = null
    private var pendingAddition: UiAnnotation? = null
    private var downX = 0f
    private var downY = 0f
    private var tappedAnnotations: List<UiAnnotation> = emptyList()
    private var resizeCorner = -1
    private var additionStartBounds: UiNormalizedRect? = null
    private var additionCanDrag = false
    private var additionDragging = false
    private var additionCornerWasSelected = false

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
        clearAlignment()
        pendingMoveBounds = null
        pendingMoveSource = source
        pendingPointX = x
        pendingPointY = y
        rawPointX = x; rawPointY = y
        updateClickable()
        invalidate()
    }

    fun enableTargetSelection(enabled: Boolean) {
        targetSelectionEnabled = enabled
        updateClickable()
    }

    fun showAdditionPlacement(annotation: UiAnnotation?) {
        resetAdditionGesture()
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
                val destination = moveDestinationRect(annotation.target.bounds, it.first * coordinateWidth,
                    it.second * coordinateHeight, moveDestinationBounds[annotation.annotationId])
                val endX = destination.centerX(); val endY = destination.centerY()
                val start = rect(annotation.target.bounds)
                val dx = endX - start.centerX(); val dy = endY - start.centerY()
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0f) 0f else
                    (((x - start.centerX()) * dx + (y - start.centerY()) * dy) / lengthSquared).coerceIn(0f, 1f)
                destination.contains(x, y) ||
                    kotlin.math.hypot(x - start.centerX() - t * dx, y - start.centerY() - t * dy) <= 18f * density
            })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pendingAddition != null) return handleAdditionTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y
            moveGestureDragged = false
            selectedMoveOnDown = pendingMoveSource == null && targetSelectionEnabled &&
                moveSourceTouchListener?.invoke(event.x / coordinateWidth.coerceAtLeast(1),
                    event.y / coordinateHeight.coerceAtLeast(1)) == true
            tappedAnnotations = if (pendingMoveSource == null && !targetSelectionEnabled) annotationsAt(event.x, event.y) else emptyList()
            if (pendingMoveSource != null) beginAlignmentGesture()
        }
        if (pendingMoveSource == null && !targetSelectionEnabled && tappedAnnotations.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pendingMoveSource != null) parent.requestDisallowInterceptTouchEvent(true)
                // A tap only selects the source; release must not open the confirmation sheet yet.
                if (selectedMoveOnDown) return true
                if (pendingMoveSource != null) {
                    destinationDragListener?.invoke(event.x, event.y, true)
                    updatePendingMovePoint(event.x, event.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop ||
                    abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) moveGestureDragged = true
                if (selectedMoveOnDown && !moveGestureDragged) return true
                if (pendingMoveSource != null) {
                    destinationDragListener?.invoke(event.x, event.y, true)
                    updatePendingMovePoint(event.x, event.y)
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
                    if (!selectedMoveOnDown || moveGestureDragged) destinationTapListener?.invoke(x, y)
                } else if (abs(event.x - downX) <= ViewConfiguration.get(context).scaledTouchSlop &&
                    abs(event.y - downY) <= ViewConfiguration.get(context).scaledTouchSlop) {
                    if (tappedAnnotations.isNotEmpty()) {
                        annotationTapListener?.invoke(tappedAnnotations)
                    } else {
                        targetTapListener?.invoke(event.x / coordinateWidth.coerceAtLeast(1), event.y / coordinateHeight.coerceAtLeast(1))
                    }
                }
                tappedAnnotations = emptyList()
                clearAlignment()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                destinationDragListener?.invoke(event.x, event.y, false)
                tappedAnnotations = emptyList()
                clearAlignment()
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
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                additionStartBounds = spec.bounds
                beginAlignmentGesture()
                additionDragging = false
                additionCornerWasSelected = resizeCorner >= 0
                val b = spec.bounds
                val touchedCorner = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
                    .withIndex().filter { (_, point) ->
                        abs(event.x - point.first * coordinateWidth) <= 24f * density &&
                            abs(event.y - point.second * coordinateHeight) <= 24f * density
                    }.minByOrNull { (_, point) ->
                        kotlin.math.hypot(event.x - point.first * coordinateWidth, event.y - point.second * coordinateHeight)
                    }?.index ?: -1
                if (!additionCornerWasSelected) resizeCorner = touchedCorner
                additionCanDrag = touchedCorner >= 0 || UiAdditionGeometry.contains(spec.bounds,
                    event.x / coordinateWidth.coerceAtLeast(1), event.y / coordinateHeight.coerceAtLeast(1))
                // Keep drags inside the region out of both preview scroll views.
                // Outside touches may still scroll, or reposition the region with a tap.
                if (additionCanDrag) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> updateAdditionDrag(event)
            MotionEvent.ACTION_UP -> {
                val b = additionStartBounds ?: return true
                updateAdditionDrag(event)
                if (additionDragging) {
                    pendingAddition?.addition?.bounds?.let { additionBoundsChanged?.invoke(it, true) }
                    resizeCorner = -1
                    resetAdditionGesture()
                    invalidate()
                    return true
                }
                val isTap = abs(event.x - downX) <= ViewConfiguration.get(context).scaledTouchSlop &&
                    abs(event.y - downY) <= ViewConfiguration.get(context).scaledTouchSlop
                resetAdditionGesture()
                if (!isTap) { resizeCorner = -1; invalidate(); return true }
                if (resizeCorner >= 0 && !additionCornerWasSelected) {
                    invalidate(); performClick(); return true
                }
                val x = (event.x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                val y = (event.y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                val result = if (resizeCorner < 0) {
                    UiAdditionGeometry.translate(b, x - (b.left + b.right) / 2, y - (b.top + b.bottom) / 2)
                } else resizeAddition(b, x - (if (resizeCorner == 0 || resizeCorner == 2) b.left else b.right),
                    y - (if (resizeCorner < 2) b.top else b.bottom))
                beginAlignmentGesture()
                val snapped = snapAdditionBounds(result)
                resizeCorner = -1
                updateAdditionBounds(snapped, true)
                clearAlignment()
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (additionDragging) additionStartBounds?.let { updateAdditionBounds(it, true) }
                resizeCorner = -1
                resetAdditionGesture()
                invalidate()
            }
        }
        return true
    }

    private fun updateAdditionDrag(event: MotionEvent) {
        val start = additionStartBounds ?: return
        if (!additionCanDrag) return
        val dx = event.x - downX
        val dy = event.y - downY
        if (!additionDragging && abs(dx) <= ViewConfiguration.get(context).scaledTouchSlop &&
            abs(dy) <= ViewConfiguration.get(context).scaledTouchSlop) return
        additionDragging = true
        val normalizedX = dx / coordinateWidth.coerceAtLeast(1)
        val normalizedY = dy / coordinateHeight.coerceAtLeast(1)
        val bounds = if (resizeCorner < 0) UiAdditionGeometry.translate(start, normalizedX, normalizedY)
            else resizeAddition(start, normalizedX, normalizedY)
        val snapped = snapAdditionBounds(bounds)
        if (snapped != pendingAddition?.addition?.bounds) updateAdditionBounds(snapped, false) else invalidate()
    }

    private fun resizeAddition(bounds: UiNormalizedRect, dx: Float, dy: Float): UiNormalizedRect =
        UiAdditionGeometry.resize(bounds, resizeCorner, dx, dy,
            64f * density / coordinateWidth.coerceAtLeast(1), 48f * density / coordinateHeight.coerceAtLeast(1))

    private fun updateAdditionBounds(bounds: UiNormalizedRect, finished: Boolean) {
        pendingAddition = pendingAddition?.let { annotation ->
            annotation.copy(addition = annotation.addition?.copy(bounds = bounds))
        }
        additionBoundsChanged?.invoke(bounds, finished)
        invalidate()
    }

    private fun resetAdditionGesture() {
        parent?.requestDisallowInterceptTouchEvent(false)
        additionStartBounds = null
        additionCanDrag = false
        additionDragging = false
        clearAlignment()
    }

    fun offsetPendingPointBy(deltaX: Float, deltaY: Float) {
        if (pendingMoveSource == null) return
        updatePendingMovePoint((rawPointX ?: pendingPointX ?: 0f) * coordinateWidth + deltaX,
            (rawPointY ?: pendingPointY ?: 0f) * coordinateHeight + deltaY)
        invalidate()
    }

    private fun beginAlignmentGesture() {
        alignmentSnapper.reset()
        alignmentGuides = emptyList()
        alignmentTargets = alignmentTargetsProvider?.invoke().orEmpty()
    }

    private fun clearAlignment() {
        alignmentSnapper.reset()
        alignmentGuides = emptyList()
        alignmentTargets = emptyList()
    }

    private fun visibleAlignmentTargets(): List<UiSnapTarget> {
        val visible = Rect()
        if (!getLocalVisibleRect(visible)) return emptyList()
        return alignmentTargets.filter { target -> target.box.left < visible.right && target.box.right > visible.left &&
            target.box.top < visible.bottom && target.box.bottom > visible.top }
    }

    private fun snappingDistance(dp: Float): Float {
        var scale = scaleX
        var ancestor = parent as? View
        while (ancestor != null) { scale *= ancestor.scaleX; ancestor = ancestor.parent as? View }
        return dp * density / scale.coerceAtLeast(.01f)
    }

    private fun pixelBox(bounds: UiNormalizedRect) = UiPreviewBox(bounds.left * coordinateWidth, bounds.top * coordinateHeight,
        bounds.right * coordinateWidth, bounds.bottom * coordinateHeight)
    private fun normalizedBox(box: UiPreviewBox) = UiNormalizedRect(box.left / coordinateWidth.coerceAtLeast(1),
        box.top / coordinateHeight.coerceAtLeast(1), box.right / coordinateWidth.coerceAtLeast(1), box.bottom / coordinateHeight.coerceAtLeast(1)).normalized()
    private fun alignmentCanvas() = UiPreviewBox(0f, 0f, coordinateWidth.toFloat(), coordinateHeight.toFloat())

    private fun updatePendingMovePoint(x: Float, y: Float) {
        val source = pendingMoveSource ?: return
        rawPointX = (x / coordinateWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        rawPointY = (y / coordinateHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        val b = pixelBox(source.bounds)
        val cx = rawPointX!! * coordinateWidth; val cy = rawPointY!! * coordinateHeight
        val result = alignmentSnapper.translate(UiPreviewBox(cx - b.width / 2, cy - b.height / 2,
            cx + b.width / 2, cy + b.height / 2), visibleAlignmentTargets(), snappingDistance(8f), snappingDistance(14f),
            alignmentCanvas(), containEdges = false)
        alignmentGuides = result.guides
        pendingPointX = (result.box.left + result.box.right) / (2 * coordinateWidth.coerceAtLeast(1))
        pendingPointY = (result.box.top + result.box.bottom) / (2 * coordinateHeight.coerceAtLeast(1))
        movePreviewListener?.invoke(pendingPointX, pendingPointY)
    }

    private fun snapAdditionBounds(raw: UiNormalizedRect): UiNormalizedRect {
        val box = pixelBox(raw)
        val result = if (resizeCorner < 0) alignmentSnapper.translate(box, visibleAlignmentTargets(),
            snappingDistance(8f), snappingDistance(14f), alignmentCanvas(), containEdges = true)
        else alignmentSnapper.resize(box, resizeCorner, visibleAlignmentTargets(), snappingDistance(8f), snappingDistance(14f),
            alignmentCanvas(), minOf(64f * density, box.width), minOf(48f * density, box.height))
        alignmentGuides = result.guides
        return normalizedBox(result.box)
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
            val destination = moveDestinationRect(source.bounds, destinationX, destinationY, pendingMoveBounds)
            drawDestination(canvas, destination, MOVE_COLOR)
            drawArrow(canvas, sourceRect.centerX(), sourceRect.centerY(), destination.centerX(), destination.centerY(), MOVE_COLOR)
        }
        drawAlignmentGuides(canvas)
    }

    private fun drawAlignmentGuides(canvas: Canvas) {
        val box = pendingAddition?.addition?.bounds?.let(::pixelBox) ?: pendingMoveSource?.let { source ->
            pendingMoveBounds?.let(::pixelBox) ?: pixelBox(source.bounds).let { b ->
                val cx = (pendingPointX ?: return@let b) * coordinateWidth
                val cy = (pendingPointY ?: return@let b) * coordinateHeight
                UiPreviewBox(cx - b.width / 2, cy - b.height / 2, cx + b.width / 2, cy + b.height / 2)
            }
        } ?: return
        val visible = Rect()
        if (!getLocalVisibleRect(visible)) return
        val guidePaint = borderPaint
        guidePaint.color = Color.rgb(196, 45, 168)
        guidePaint.strokeWidth = 1.2f * density
        guidePaint.pathEffect = null
        alignmentGuides.forEach { guide ->
            val target = if (alignmentTargetBoundsProvider == null) guide.target
                else alignmentTargetBoundsProvider?.invoke(guide.targetId) ?: return@forEach
            val anchor = if (guide.axis == UiSnapAxis.X) box.left + box.width * guide.sourceAnchor
                else box.top + box.height * guide.sourceAnchor
            // Row allocation takes precedence; never show an alignment line the final dashed box does not meet.
            if (abs(anchor - guide.position) > 2f) return@forEach
            val targetAnchor = if (guide.axis == UiSnapAxis.X) target.left + target.width * guide.targetAnchor
                else target.top + target.height * guide.targetAnchor
            if (guide.kind == UiSnapKind.ALIGN && abs(targetAnchor - guide.position) > 2f) return@forEach
            if (guide.kind == UiSnapKind.WIDTH && abs(target.width - box.width) > 2f) return@forEach
            if (guide.kind == UiSnapKind.HEIGHT && abs(target.height - box.height) > 2f) return@forEach
            if (guide.kind == UiSnapKind.ALIGN) {
                if (guide.axis == UiSnapAxis.X) canvas.drawLine(guide.position, maxOf(visible.top.toFloat(), minOf(box.top, target.top) - 8f * density),
                    guide.position, minOf(visible.bottom.toFloat(), maxOf(box.bottom, target.bottom) + 8f * density), guidePaint)
                else canvas.drawLine(maxOf(visible.left.toFloat(), minOf(box.left, target.left) - 8f * density), guide.position,
                    minOf(visible.right.toFloat(), maxOf(box.right, target.right) + 8f * density), guide.position, guidePaint)
            } else {
                val sameWidth = guide.kind == UiSnapKind.WIDTH
                val label = context.getString(if (sameWidth) kr.ac.kangwon.hai.vibefactory.R.string.ui_annotation_same_width
                    else kr.ac.kangwon.hai.vibefactory.R.string.ui_annotation_same_height)
                if (sameWidth) canvas.drawLine(box.left, box.top, box.right, box.top, guidePaint)
                else canvas.drawLine(box.right, box.top, box.right, box.bottom, guidePaint)
                val labelWidth = instructionPaint.measureText(label) + 12f * density
                val labelHeight = 22f * density
                val left = (if (sameWidth) (box.left + box.right - labelWidth) / 2 else box.right + 4f * density)
                    .coerceIn(visible.left.toFloat(), maxOf(visible.left.toFloat(), visible.right - labelWidth))
                val top = (if (sameWidth) box.top - labelHeight - 4f * density else (box.top + box.bottom - labelHeight) / 2)
                    .coerceIn(visible.top.toFloat(), maxOf(visible.top.toFloat(), visible.bottom - labelHeight))
                fillPaint.color = guidePaint.color
                canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + labelHeight), 4f * density, 4f * density, fillPaint)
                canvas.drawText(label, left + 6f * density, top + 15f * density, instructionPaint)
            }
        }
        borderPaint.strokeWidth = 3f * density
    }

    private fun drawAnnotation(canvas: Canvas, annotation: UiAnnotation, number: Int) {
        val color = colorFor(annotation.action)
        val targetRect = rect(annotation.addition?.bounds ?: annotation.target.bounds)
        annotation.addition?.let { spec ->
            fillPaint.color = spec.backgroundColor
            canvas.drawRect(targetRect, fillPaint)
            UiSketchRenderer.draw(canvas, targetRect, spec.strokes)
        }
        annotation.sketch?.let { UiSketchRenderer.draw(canvas, targetRect, it.strokes) }
        if (annotation.canvasImages.isNotEmpty()) sketchPreviews[annotation.sketchImageId]?.let {
            canvas.drawBitmap(it, null, targetRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        drawBox(canvas, targetRect, color, symbolFor(annotation.action), number.takeIf { it > 0 }, dashed = false)
        if (annotation.action == UiAnnotationAction.MOVE) {
            val (normalizedX, normalizedY) = annotation.resolvedDestinationPoint()
            val endX = normalizedX * coordinateWidth
            val endY = normalizedY * coordinateHeight
            val destination = moveDestinationRect(annotation.target.bounds, endX, endY, moveDestinationBounds[annotation.annotationId])
            drawDestination(canvas, destination, color)
            drawArrow(canvas, targetRect.centerX(), targetRect.centerY(), destination.centerX(), destination.centerY(), color)
        }
    }

    internal fun moveDestinationRect(sourceBounds: UiNormalizedRect, centerX: Float, centerY: Float,
        previewBounds: UiNormalizedRect? = null): RectF {
        if (previewBounds != null) return RectF(previewBounds.left * coordinateWidth, previewBounds.top * coordinateHeight,
            previewBounds.right * coordinateWidth, previewBounds.bottom * coordinateHeight)
        val source = sourceBounds.normalized()
        val halfWidth = (source.right - source.left) * coordinateWidth / 2f
        val halfHeight = (source.bottom - source.top) * coordinateHeight / 2f
        // Free placement keeps its source size. Row placement above uses the shared slot geometry.
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
