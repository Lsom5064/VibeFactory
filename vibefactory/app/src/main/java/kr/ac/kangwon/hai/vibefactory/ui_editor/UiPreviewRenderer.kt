package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class UiPreviewRenderResult(
    val rootView: View,
    val nodeViews: Map<String, View>,
    val warnings: List<String>
)

class UiPreviewRenderer(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val nodeViews = linkedMapOf<String, View>()
    private val androidIds = linkedMapOf<String, Int>()
    private val warnings = mutableListOf<String>()
    private var resources = ResolvedUiResources.EMPTY
    private var imageBitmaps: Map<String, Bitmap> = emptyMap()

    fun render(
        document: AndroidXmlDocument,
        resources: ResolvedUiResources,
        canvas: FrameLayout,
        imageBitmaps: Map<String, Bitmap> = emptyMap()
    ): UiPreviewRenderResult {
        this.resources = resources
        this.imageBitmaps = imageBitmaps
        nodeViews.clear()
        androidIds.clear()
        warnings.clear()
        warnings += resources.warnings
        document.root.descendantsAndSelf().forEach { node ->
            val name = resourceIdName(node.androidAttribute("id"))
            if (name != null) androidIds.getOrPut(name) { View.generateViewId() }
        }

        canvas.removeAllViews()
        val rootView = createNodeView(document.root)
        rootView.layoutParams = frameLayoutParams(document.root)
        canvas.addView(rootView)
        return UiPreviewRenderResult(rootView, nodeViews.toMap(), warnings.distinct())
    }

    private fun createNodeView(node: UiNode): View {
        val view = createView(node)
        nodeViews[node.stableId] = view
        resourceIdName(node.androidAttribute("id"))?.let { name -> view.id = androidIds.getValue(name) }
        view.tag = node.stableId
        applyCommonAttributes(view, node)

        if (view is ViewGroup && node.supported && view !is RecyclerView) {
            view.clipChildren = false
            view.clipToPadding = false
            node.children.forEachIndexed { index, child ->
                if ((view is ScrollView || view is NestedScrollView) && index > 0) {
                    warnings += "${node.simpleTag} can display only its first child"
                    return@forEachIndexed
                }
                val childView = createNodeView(child)
                childView.layoutParams = layoutParamsFor(view, child)
                if (
                    node.simpleTag == "TextInputLayout" &&
                    childView is TextView &&
                    childView.hint.isNullOrBlank()
                ) {
                    childView.hint = resources.text(node.androidAttribute("hint"))
                }
                view.addView(childView)
            }
            decorateEmptyContainer(view, node)
        } else if (node.children.isNotEmpty()) {
            warnings += "${node.tagName} child hierarchy is preserved but locked in preview"
        }
        return view
    }

    private fun createView(node: UiNode): View = when (node.simpleTag) {
        "ConstraintLayout" -> ConstraintLayout(context)
        "CoordinatorLayout" -> FrameLayout(context)
        "LinearLayout" -> LinearLayout(context).apply {
            orientation = if (node.androidAttribute("orientation") == "horizontal") {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
        }
        "FrameLayout" -> FrameLayout(context)
        "ScrollView" -> ScrollView(context).apply { isFillViewport = true }
        "NestedScrollView" -> NestedScrollView(context).apply { isFillViewport = true }
        "AppBarLayout" -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        "Toolbar", "MaterialToolbar" -> FrameLayout(context)
        "TextView" -> TextView(context)
        "MaterialTextView" -> TextView(context)
        "Button" -> Button(context)
        "MaterialButton" -> Button(context)
        "EditText" -> EditText(context)
        "TextInputLayout" -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        "TextInputEditText" -> EditText(context)
        "ImageView" -> ImageView(context).apply {
            val bitmap = imageBitmaps[node.stableId]
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_menu_gallery)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.rgb(238, 241, 240))
        }
        "ImageButton" -> ImageButton(context).apply {
            val bitmap = imageBitmaps[node.stableId]
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_menu_gallery)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.rgb(238, 241, 240))
        }
        "CheckBox" -> CheckBox(context)
        "Switch", "SwitchCompat" -> Switch(context)
        "RadioButton" -> RadioButton(context)
        "ProgressBar" -> ProgressBar(context)
        "FloatingActionButton" -> Button(context).apply { text = "+" }
        "View" -> View(context)
        "Space" -> Space(context)
        "CardView", "MaterialCardView" -> FrameLayout(context).apply {
            elevation = dp(1).toFloat()
        }
        "RecyclerView" -> RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = DummyAdapter()
        }
        else -> lockedPlaceholder(node)
    }

    private fun lockedPlaceholder(node: UiNode): TextView = TextView(context).apply {
        text = "잠김 · ${node.simpleTag}"
        setTextColor(Color.rgb(83, 91, 88))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(Color.rgb(232, 235, 234))
        minimumWidth = dp(64)
        minimumHeight = dp(44)
        warnings += "Unsupported tag is locked: ${node.tagName}"
    }

    private fun applyCommonAttributes(view: View, node: UiNode) {
        view.contentDescription = resources.text(node.androidAttribute("contentDescription"))
        view.visibility = when (node.androidAttribute("visibility")) {
            "gone" -> View.GONE
            "invisible" -> View.INVISIBLE
            else -> View.VISIBLE
        }
        node.androidAttribute("alpha")?.toFloatOrNull()?.let { view.alpha = it.coerceIn(0f, 1f) }
        applyPadding(view, node)

        resources.color(node.androidAttribute("background"))?.let(::parseColor)?.let(view::setBackgroundColor)
        if (view is TextView) {
            resources.text(node.androidAttribute("text"))?.let { view.text = it }
            resources.text(node.androidAttribute("hint"))?.let { view.hint = it }
            resources.color(node.androidAttribute("textColor"))?.let(::parseColor)?.let(view::setTextColor)
            parseTextSize(node.androidAttribute("textSize"))?.let { view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
            view.gravity = parseGravity(node.androidAttribute("gravity")) ?: view.gravity
            when (node.androidAttribute("textStyle")) {
                "bold" -> view.setTypeface(view.typeface, Typeface.BOLD)
                "italic" -> view.setTypeface(view.typeface, Typeface.ITALIC)
                "bold|italic", "italic|bold" -> view.setTypeface(view.typeface, Typeface.BOLD_ITALIC)
            }
            node.androidAttribute("maxLines")?.toIntOrNull()?.let { view.maxLines = it.coerceAtLeast(1) }
        }
        if (view is ImageView) {
            view.scaleType = when (node.androidAttribute("scaleType")) {
                "centerCrop" -> ImageView.ScaleType.CENTER_CROP
                "fitCenter" -> ImageView.ScaleType.FIT_CENTER
                "fitXY" -> ImageView.ScaleType.FIT_XY
                "centerInside" -> ImageView.ScaleType.CENTER_INSIDE
                else -> view.scaleType
            }
        }
        if (view is LinearLayout) {
            parseGravity(node.androidAttribute("gravity"))?.let { view.gravity = it }
        }
    }

    private fun decorateEmptyContainer(view: ViewGroup, node: UiNode) {
        if (node.children.isNotEmpty() || node.simpleTag !in EMPTY_CONTAINER_TAGS) return
        view.minimumWidth = dp(96)
        view.minimumHeight = dp(72)
        if (node.androidAttribute("background").isNullOrBlank()) {
            view.background = GradientDrawable().apply {
                setColor(Color.rgb(247, 249, 248))
                setStroke(dp(1).coerceAtLeast(1), Color.rgb(152, 166, 160), dp(6).toFloat(), dp(4).toFloat())
            }
        }
        if (view.contentDescription.isNullOrBlank()) {
            view.contentDescription = "빈 ${containerDisplayName(node.simpleTag)}"
        }
    }

    private fun containerDisplayName(simpleTag: String): String = when (simpleTag) {
        "LinearLayout" -> "리니어 레이아웃"
        "ConstraintLayout" -> "컨스트레인트 레이아웃"
        "CoordinatorLayout" -> "코디네이터 레이아웃"
        "FrameLayout" -> "프레임 레이아웃"
        "ScrollView", "NestedScrollView" -> "스크롤 영역"
        "CardView", "MaterialCardView" -> "카드"
        else -> simpleTag
    }

    private fun applyPadding(view: View, node: UiNode) {
        val all = dimensionPx(node.androidAttribute("padding"))
        val horizontal = dimensionPx(node.androidAttribute("paddingHorizontal"))
        val vertical = dimensionPx(node.androidAttribute("paddingVertical"))
        val start = dimensionPx(node.androidAttribute("paddingStart")) ?: horizontal ?: all ?: 0
        val top = dimensionPx(node.androidAttribute("paddingTop")) ?: vertical ?: all ?: 0
        val end = dimensionPx(node.androidAttribute("paddingEnd")) ?: horizontal ?: all ?: 0
        val bottom = dimensionPx(node.androidAttribute("paddingBottom")) ?: vertical ?: all ?: 0
        view.setPaddingRelative(start, top, end, bottom)
    }

    private fun layoutParamsFor(parent: ViewGroup, node: UiNode): ViewGroup.LayoutParams {
        val width = layoutSize(node.androidAttribute("layout_width"), fallback = ViewGroup.LayoutParams.WRAP_CONTENT)
        val height = layoutSize(node.androidAttribute("layout_height"), fallback = ViewGroup.LayoutParams.WRAP_CONTENT)
        return when (parent) {
            is ConstraintLayout -> ConstraintLayout.LayoutParams(width, height).apply {
                applyMargins(this, node)
                constraint(node, "layout_constraintStart_toStartOf")?.let { startToStart = it }
                constraint(node, "layout_constraintStart_toEndOf")?.let { startToEnd = it }
                constraint(node, "layout_constraintEnd_toStartOf")?.let { endToStart = it }
                constraint(node, "layout_constraintEnd_toEndOf")?.let { endToEnd = it }
                constraint(node, "layout_constraintTop_toTopOf")?.let { topToTop = it }
                constraint(node, "layout_constraintTop_toBottomOf")?.let { topToBottom = it }
                constraint(node, "layout_constraintBottom_toTopOf")?.let { bottomToTop = it }
                constraint(node, "layout_constraintBottom_toBottomOf")?.let { bottomToBottom = it }
                horizontalBias = node.appAttribute("layout_constraintHorizontal_bias")?.toFloatOrNull() ?: 0.5f
                verticalBias = node.appAttribute("layout_constraintVertical_bias")?.toFloatOrNull() ?: 0.5f
            }
            is LinearLayout -> LinearLayout.LayoutParams(width, height).apply {
                applyMargins(this, node)
                weight = node.androidAttribute("layout_weight")?.toFloatOrNull() ?: 0f
                gravity = parseGravity(node.androidAttribute("layout_gravity")) ?: -1
            }
            is FrameLayout -> FrameLayout.LayoutParams(width, height).apply {
                applyMargins(this, node)
                gravity = parseGravity(node.androidAttribute("layout_gravity")) ?: Gravity.NO_GRAVITY
            }
            else -> ViewGroup.MarginLayoutParams(width, height).apply { applyMargins(this, node) }
        }
    }

    private fun frameLayoutParams(node: UiNode): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        layoutSize(node.androidAttribute("layout_width"), ViewGroup.LayoutParams.MATCH_PARENT),
        layoutSize(node.androidAttribute("layout_height"), ViewGroup.LayoutParams.WRAP_CONTENT)
    ).apply { applyMargins(this, node) }

    private fun applyMargins(params: ViewGroup.MarginLayoutParams, node: UiNode) {
        val all = dimensionPx(node.androidAttribute("layout_margin")) ?: 0
        val horizontal = dimensionPx(node.androidAttribute("layout_marginHorizontal"))
        val vertical = dimensionPx(node.androidAttribute("layout_marginVertical"))
        params.marginStart = dimensionPx(node.androidAttribute("layout_marginStart")) ?: horizontal ?: all
        params.topMargin = dimensionPx(node.androidAttribute("layout_marginTop")) ?: vertical ?: all
        params.marginEnd = dimensionPx(node.androidAttribute("layout_marginEnd")) ?: horizontal ?: all
        params.bottomMargin = dimensionPx(node.androidAttribute("layout_marginBottom")) ?: vertical ?: all
    }

    private fun constraint(node: UiNode, name: String): Int? {
        val value = node.appAttribute(name)?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value == "parent") return ConstraintLayout.LayoutParams.PARENT_ID
        val targetName = resourceIdName(value) ?: return null
        return androidIds[targetName]
    }

    private fun layoutSize(value: String?, fallback: Int): Int {
        val resolved = resources.dimen(value)?.trim().orEmpty()
        return when (resolved) {
            "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
            "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
            "0dp", "0dip" -> 0
            else -> dimensionPx(resolved) ?: fallback
        }
    }

    private fun dimensionPx(value: String?): Int? {
        val resolved = resources.dimen(value)?.trim()?.lowercase().orEmpty()
        val number = resolved.removeSuffix("dp").removeSuffix("dip").removeSuffix("sp").removeSuffix("px").toFloatOrNull()
            ?: return null
        return when {
            resolved.endsWith("sp") -> TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                number,
                context.resources.displayMetrics
            ).toInt()
            resolved.endsWith("px") -> number.toInt()
            else -> (number * density).toInt()
        }
    }

    private fun parseTextSize(value: String?): Float? {
        val resolved = resources.dimen(value)?.trim()?.lowercase().orEmpty()
        val number = resolved.removeSuffix("sp").removeSuffix("dp").removeSuffix("dip").removeSuffix("px").toFloatOrNull()
            ?: return null
        return when {
            resolved.endsWith("sp") -> TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                number,
                context.resources.displayMetrics
            )
            resolved.endsWith("px") -> number
            else -> number * density
        }
    }

    private fun parseColor(value: String): Int? = runCatching { Color.parseColor(value) }.getOrNull()

    private fun parseGravity(value: String?): Int? {
        val parts = value?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (parts.isEmpty()) return null
        return parts.fold(0) { gravity, part ->
            gravity or when (part) {
                "center" -> Gravity.CENTER
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "start" -> Gravity.START
                "end" -> Gravity.END
                "left" -> Gravity.LEFT
                "right" -> Gravity.RIGHT
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                else -> 0
            }
        }
    }

    private fun resourceIdName(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith("@id/") && !raw.startsWith("@+id/")) return null
        return raw.substringAfter('/').takeIf { it.isNotBlank() }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private inner class DummyAdapter : RecyclerView.Adapter<DummyViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DummyViewHolder {
            val text = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(Color.rgb(42, 48, 46))
            }
            return DummyViewHolder(text)
        }

        override fun getItemCount(): Int = 3

        override fun onBindViewHolder(holder: DummyViewHolder, position: Int) {
            holder.text.text = "목록 항목 ${position + 1}"
        }
    }

    private class DummyViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private companion object {
        val EMPTY_CONTAINER_TAGS = setOf(
            "ConstraintLayout",
            "CoordinatorLayout",
            "LinearLayout",
            "FrameLayout",
            "ScrollView",
            "NestedScrollView",
            "AppBarLayout",
            "Toolbar",
            "MaterialToolbar",
            "CardView",
            "MaterialCardView"
        )
    }
}
