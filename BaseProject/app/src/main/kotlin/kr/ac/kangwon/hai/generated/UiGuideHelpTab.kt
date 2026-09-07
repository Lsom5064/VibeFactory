package kr.ac.kangwon.hai.generated

import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A small movable help affordance that stays clear of interactive app controls. */
internal class UiGuideHelpTab(
    private val activity: Activity,
    private val host: FrameLayout,
    private val preferences: SharedPreferences,
    private val onOpenGuide: () -> Unit,
) : FrameLayout(activity) {
    private enum class Edge { LEFT, RIGHT }

    private data class Placement(
        val edge: Edge,
        val x: Float,
        val y: Float,
        val overlapArea: Float,
        val preferenceDistance: Float,
    )

    private val touchSize = dp(48)
    private val normalIconSize = dp(36)
    private val compactIconSize = dp(26)
    private val edgeMargin = dp(4)
    private val candidateStep = dp(8)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val safeInsets = Rect()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val icon = ImageView(context)
    private val placementRunnable = Runnable { placeAtPreferredPosition(animate = false) }
    private val longPressRunnable = Runnable {
        if (pointerDown && !gestureMoved && !dragging) performLongClick()
    }
    private val scrollIdleRunnable = Runnable {
        compactForScroll = false
        updateIconAppearance()
        scheduleObstacleCheck()
    }

    private var preferredEdge = readPreferredEdge()
    private var preferredYRatio = readPreferredYRatio()
    private var displayedEdge = preferredEdge
    private var positioned = false
    private var dragging = false
    private var pointerDown = false
    private var gestureMoved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var compactForScroll = false
    private var compactForIme = false
    private var compactForCollision = false

    init {
        contentDescription = HELP_CONTENT_DESCRIPTION
        isClickable = true
        isLongClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false

        icon.apply {
            setImageResource(android.R.drawable.ic_menu_help)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = dp(6).toFloat()
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon)
        TooltipCompat.setTooltipText(this, HELP_CONTENT_DESCRIPTION)
        updateIconAppearance()

        setOnTouchListener { _, event -> handleTouch(event) }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = if (imeVisible) {
                insets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                systemBars
            }
            val nextInsets = Rect(
                max(systemBars.left, ime.left),
                max(systemBars.top, ime.top),
                max(systemBars.right, ime.right),
                max(systemBars.bottom, ime.bottom),
            )
            val insetsChanged = nextInsets != safeInsets
            safeInsets.set(nextInsets)
            if (compactForIme != imeVisible) {
                compactForIme = imeVisible
                updateIconAppearance()
            }
            if (insetsChanged) scheduleObstacleCheck()
            insets
        }
    }

    fun layoutParams() = LayoutParams(touchSize, touchSize, Gravity.TOP or Gravity.START)

    fun start() {
        ViewCompat.requestApplyInsets(this)
        post { placeAtPreferredPosition(animate = false) }
    }

    fun showTab() {
        visibility = VISIBLE
        scheduleObstacleCheck()
    }

    fun hideTab() {
        visibility = GONE
        animate().cancel()
        resetTouchState()
    }

    fun onHostLayoutChanged() {
        scheduleObstacleCheck()
    }

    fun onHostScrolled() {
        if (visibility != VISIBLE || dragging) return
        compactForScroll = true
        updateIconAppearance()
        mainHandler.removeCallbacks(scrollIdleRunnable)
        mainHandler.postDelayed(scrollIdleRunnable, SCROLL_IDLE_DELAY_MS)
    }

    fun dispose() {
        animate().cancel()
        mainHandler.removeCallbacks(placementRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(scrollIdleRunnable)
        resetTouchState()
        setOnTouchListener(null)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onOpenGuide()
        return true
    }

    override fun performLongClick(): Boolean {
        if (!pointerDown || gestureMoved || dragging) return false
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        compactForScroll = false
        compactForCollision = false
        updateIconAppearance()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        icon.animate()
            .scaleX(LONG_PRESS_SCALE)
            .scaleY(LONG_PRESS_SCALE)
            .setDuration(LONG_PRESS_FEEDBACK_DURATION_MS)
            .start()
        return true
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                mainHandler.removeCallbacks(longPressRunnable)
                pointerDown = true
                gestureMoved = false
                isPressed = true
                downRawX = event.rawX
                downRawY = event.rawY
                downViewX = x
                downViewY = y
                dragging = false
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (!dragging && max(abs(deltaX), abs(deltaY)) > touchSlop) {
                    gestureMoved = true
                    isPressed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    val bounds = movementBounds()
                    x = (downViewX + deltaX).coerceIn(bounds.left, bounds.right)
                    y = (downViewY + deltaY).coerceIn(bounds.top, bounds.bottom)
                    updateDisplayedEdge(
                        if (x + width / 2f < host.width / 2f) Edge.LEFT else Edge.RIGHT,
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    finishDrag()
                } else if (!gestureMoved) {
                    performClick()
                }
                resetTouchState()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasDragging = dragging
                resetTouchState()
                if (wasDragging) placeAtPreferredPosition(animate = true)
                return true
            }
        }
        return false
    }

    private fun resetTouchState() {
        mainHandler.removeCallbacks(longPressRunnable)
        pointerDown = false
        gestureMoved = false
        dragging = false
        isPressed = false
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(LONG_PRESS_FEEDBACK_DURATION_MS)
            .start()
    }

    private fun finishDrag() {
        val bounds = movementBounds()
        preferredEdge = if (x + width / 2f < host.width / 2f) Edge.LEFT else Edge.RIGHT
        preferredYRatio = if (bounds.height() <= 0f) {
            DEFAULT_Y_RATIO
        } else {
            ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }
        val placement = resolvePlacement()
        preferredEdge = placement.edge
        preferredYRatio = if (bounds.height() <= 0f) {
            DEFAULT_Y_RATIO
        } else {
            ((placement.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }
        savePreferredPosition()
        moveTo(placement, animate = true)
    }

    private fun scheduleObstacleCheck() {
        if (dragging || host.width <= 0 || host.height <= 0) return
        mainHandler.removeCallbacks(placementRunnable)
        mainHandler.postDelayed(placementRunnable, PLACEMENT_DEBOUNCE_MS)
    }

    private fun placeAtPreferredPosition(animate: Boolean) {
        if (dragging || host.width <= 0 || host.height <= 0) return
        moveTo(resolvePlacement(), animate && positioned)
        positioned = true
    }

    private fun resolvePlacement(): Placement {
        val bounds = movementBounds()
        val preferredY = bounds.top + bounds.height() * preferredYRatio
        val obstacles = collectInteractiveObstacles()
        val candidates = mutableListOf<Placement>()
        val edges = listOf(preferredEdge, preferredEdge.opposite())
        val yValues = candidateYValues(bounds, preferredY)

        edges.forEach { edge ->
            val candidateX = if (edge == Edge.LEFT) bounds.left else bounds.right
            yValues.forEach { candidateY ->
                val candidate = RectF(
                    candidateX,
                    candidateY,
                    candidateX + touchSize,
                    candidateY + touchSize,
                )
                val overlapArea = obstacles.sumOf { overlapArea(candidate, it).toDouble() }.toFloat()
                val edgePenalty = if (edge == preferredEdge) 0f else dp(24).toFloat()
                candidates += Placement(
                    edge = edge,
                    x = candidateX,
                    y = candidateY,
                    overlapArea = overlapArea,
                    preferenceDistance = abs(candidateY - preferredY) + edgePenalty,
                )
            }
        }

        return candidates.minWithOrNull(
            compareBy<Placement> { it.overlapArea }.thenBy { it.preferenceDistance },
        ) ?: Placement(preferredEdge, bounds.right, preferredY, 0f, 0f)
    }

    private fun candidateYValues(bounds: RectF, preferredY: Float): List<Float> {
        if (bounds.height() <= 0f) return listOf(bounds.top)
        val candidates = mutableSetOf(
            preferredY.coerceIn(bounds.top, bounds.bottom),
            bounds.top,
            bounds.top + bounds.height() * 0.25f,
            bounds.top + bounds.height() * 0.5f,
            bounds.top + bounds.height() * 0.75f,
            bounds.bottom,
        )
        var candidate = bounds.top
        while (candidate <= bounds.bottom) {
            candidates += candidate
            candidate += candidateStep
        }
        return candidates.sortedBy { abs(it - preferredY) }
    }

    private fun movementBounds(): RectF {
        val left = safeInsets.left + edgeMargin.toFloat()
        val top = safeInsets.top + edgeMargin.toFloat()
        val right = max(left, host.width - safeInsets.right - edgeMargin - touchSize.toFloat())
        val bottom = max(top, host.height - safeInsets.bottom - edgeMargin - touchSize.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun collectInteractiveObstacles(): List<RectF> {
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val hostArea = host.width.toLong() * host.height.toLong()
        val obstacles = mutableListOf<RectF>()

        fun visit(view: View) {
            if (view === this || view.visibility != VISIBLE || view.alpha <= 0.05f) return
            if (view.width <= 0 || view.height <= 0) return
            if (isInteractiveObstacle(view, hostArea)) {
                val visible = Rect()
                if (view.getGlobalVisibleRect(visible)) {
                    obstacles += RectF(
                        visible.left - hostLocation[0].toFloat(),
                        visible.top - hostLocation[1].toFloat(),
                        visible.right - hostLocation[0].toFloat(),
                        visible.bottom - hostLocation[1].toFloat(),
                    )
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }

        for (index in 0 until host.childCount) visit(host.getChildAt(index))
        return obstacles
    }

    private fun isInteractiveObstacle(view: View, hostArea: Long): Boolean {
        val className = view.javaClass.name.lowercase()
        val isNavigation = className.contains("bottomnavigation") ||
            className.contains("navigationbar") ||
            className.contains("navigationrail")
        val isKnownControl = view is Button ||
            view is EditText ||
            view is ImageButton ||
            view is CompoundButton ||
            view is SeekBar ||
            view is Spinner ||
            className.contains("floatingactionbutton") ||
            className.contains("textinputlayout")
        val isClickableLeaf = view.isClickable && (view !is ViewGroup || view.childCount == 0)
        if (!isNavigation && !isKnownControl && !isClickableLeaf) return false

        val viewArea = view.width.toLong() * view.height.toLong()
        return isNavigation || hostArea <= 0L || viewArea * 2L < hostArea
    }

    private fun moveTo(placement: Placement, animate: Boolean) {
        compactForCollision = placement.overlapArea > 0f
        updateDisplayedEdge(placement.edge)
        updateIconAppearance()
        if (!animate) {
            x = placement.x
            y = placement.y
            return
        }
        animate()
            .x(placement.x)
            .y(placement.y)
            .setDuration(SNAP_DURATION_MS)
            .start()
    }

    private fun updateDisplayedEdge(edge: Edge) {
        if (displayedEdge == edge && icon.layoutParams != null) return
        displayedEdge = edge
        updateIconAppearance()
    }

    private fun updateIconAppearance() {
        val compact = compactForScroll || compactForIme || compactForCollision
        val iconSize = if (compact) compactIconSize else normalIconSize
        icon.layoutParams = LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_VERTICAL or
                if (displayedEdge == Edge.LEFT) Gravity.START else Gravity.END
        }
        icon.alpha = if (compact) 0.72f else 1f
        icon.translationX = when {
            !compact -> 0f
            displayedEdge == Edge.LEFT -> -dp(5).toFloat()
            else -> dp(5).toFloat()
        }
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(HELP_COLOR)
        }
    }

    private fun readPreferredEdge(): Edge =
        runCatching { Edge.valueOf(preferences.getString(positionKey("edge"), null).orEmpty()) }
            .getOrDefault(Edge.RIGHT)

    private fun readPreferredYRatio(): Float =
        preferences.getFloat(positionKey("y_ratio"), DEFAULT_Y_RATIO).coerceIn(0f, 1f)

    private fun savePreferredPosition() {
        preferences.edit()
            .putString(positionKey("edge"), preferredEdge.name)
            .putFloat(positionKey("y_ratio"), preferredYRatio)
            .apply()
    }

    private fun positionKey(suffix: String): String {
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "portrait"
        }
        return "help_tab:${activity.javaClass.name}:$orientation:$suffix"
    }

    private fun Edge.opposite() = if (this == Edge.LEFT) Edge.RIGHT else Edge.LEFT

    private fun overlapArea(first: RectF, second: RectF): Float {
        val width = min(first.right, second.right) - max(first.left, second.left)
        val height = min(first.bottom, second.bottom) - max(first.top, second.top)
        return if (width > 0f && height > 0f) width * height else 0f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val HELP_CONTENT_DESCRIPTION = "사용법 다시 보기"
        const val HELP_COLOR = 0xFF186B4D.toInt()
        const val DEFAULT_Y_RATIO = 0.5f
        const val PLACEMENT_DEBOUNCE_MS = 100L
        const val SCROLL_IDLE_DELAY_MS = 850L
        const val SNAP_DURATION_MS = 180L
        const val LONG_PRESS_SCALE = 1.12f
        const val LONG_PRESS_FEEDBACK_DURATION_MS = 120L
        val LONG_PRESS_TIMEOUT_MS = ViewConfiguration.getLongPressTimeout().toLong()
    }
}
