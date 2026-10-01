package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Rect
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Keeps the pointer in screen space while the canvas moves underneath it. */
internal class UiMoveEdgeScroller(
    private val horizontal: HorizontalScrollView,
    private val vertical: ScrollView,
    private val overlay: UiAnnotationOverlayView
) {
    private val density = overlay.resources.displayMetrics.density
    private var screenX = 0f
    private var screenY = 0f
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            scrollFrame()
            overlay.postDelayed(this, 16)
        }
    }

    fun update(x: Float, y: Float) {
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        screenX = location[0] + x
        screenY = location[1] + y
        if (!active) {
            active = true
            overlay.postOnAnimation(tick)
        }
    }

    fun stop() {
        active = false
        overlay.removeCallbacks(tick)
    }

    internal fun scrollFrame() {
        if (!active) return
        val viewport = contentRect(horizontal)
        if (!viewport.intersect(contentRect(vertical))) return
        val threshold = 52f * density
        fun direction(value: Float, start: Int, end: Int): Int {
            val edge = minOf(threshold, (end - start) / 3f)
            return when {
                value < start + edge -> -1
                value > end - edge -> 1
                else -> 0
            }
        }
        val beforeX = horizontal.scrollX
        val beforeY = vertical.scrollY
        val step = (3f * density).roundToInt().coerceAtLeast(1)
        horizontal.scrollBy(direction(screenX, viewport.left, viewport.right) * step, 0)
        vertical.scrollBy(0, direction(screenY, viewport.top, viewport.bottom) * step)
        val dx = horizontal.scrollX - beforeX
        val dy = vertical.scrollY - beforeY
        if (dx != 0 || dy != 0) overlay.offsetPendingPointBy(dx.toFloat(), dy.toFloat())
    }

    private fun contentRect(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0] + view.paddingLeft, location[1] + view.paddingTop,
            location[0] + view.width - view.paddingRight, location[1] + view.height - view.paddingBottom)
    }
}
