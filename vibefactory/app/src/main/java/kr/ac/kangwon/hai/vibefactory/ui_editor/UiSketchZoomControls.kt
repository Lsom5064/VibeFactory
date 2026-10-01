package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.ac.kangwon.hai.vibefactory.R
import kotlin.math.roundToInt

/** View-only zoom controls; drawing and image coordinates stay in the original region. */
@SuppressLint("ViewConstructor") // Created in code with its canvas; never inflated from XML.
internal class UiSketchZoomControls(context: Context, private val drawing: UiSketchCanvasView) :
    LinearLayout(context) {
    init {
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = context.getString(R.string.ui_sketch_gesture_hint)
        val size = (48 * resources.displayMetrics.density).roundToInt()
        fun button(id: Int, label: String, description: Int, action: () -> Unit) = Button(context).apply {
            this.id = id
            text = label
            textSize = 16f
            contentDescription = context.getString(description)
            tooltipText = contentDescription
            minWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (drawing.isEnabled) action() }
        }
        val out = button(R.id.uiSketchZoomOut, "−", R.string.ui_sketch_zoom_out) { drawing.zoomBy(1f / 1.25f) }
        val level = TextView(context).apply {
            id = R.id.uiSketchZoomLevel
            gravity = Gravity.CENTER
            textSize = 13f
        }
        val zoomIn = button(R.id.uiSketchZoomIn, "+", R.string.ui_sketch_zoom_in) { drawing.zoomBy(1.25f) }
        val reset = button(R.id.uiSketchZoomReset, context.getString(R.string.ui_sketch_zoom_reset),
            R.string.ui_sketch_zoom_reset) { drawing.resetViewport() }.apply { textSize = 12f }
        addView(out, LayoutParams(size, size))
        addView(level, LayoutParams(0, size, 1f))
        addView(zoomIn, LayoutParams(size, size))
        addView(reset, LayoutParams(size * 2, size))
        fun refresh(scale: Float) {
            level.text = context.getString(R.string.ui_sketch_zoom_level, (scale * 100).roundToInt())
            out.isEnabled = scale > 1f
            zoomIn.isEnabled = scale < 4f
        }
        drawing.viewportChanged = ::refresh
        refresh(drawing.zoomScale)
    }
}
