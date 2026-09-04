package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
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
import kr.ac.kangwon.hai.vibefactory.UiPreviewChildDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    private var previewChildrenByContainer: Map<String, List<UiPreviewChildDto>> = emptyMap()
    private var dynamicTextViewIds: Set<String> = emptySet()
    private var hiddenViewIds: Set<String> = emptySet()
    private var representativeContent = false

    fun render(
        document: AndroidXmlDocument,
        resources: ResolvedUiResources,
        canvas: FrameLayout,
        imageBitmaps: Map<String, Bitmap> = emptyMap(),
        previewChildren: List<UiPreviewChildDto> = emptyList(),
        dynamicTextViewIds: Set<String> = emptySet(),
        hiddenViewIds: Set<String> = emptySet(),
        representativeContent: Boolean = false
    ): UiPreviewRenderResult {
        this.resources = resources
        this.imageBitmaps = imageBitmaps
        previewChildrenByContainer = previewChildren.groupBy(UiPreviewChildDto::container_id)
        this.dynamicTextViewIds = dynamicTextViewIds
        this.hiddenViewIds = hiddenViewIds
        this.representativeContent = representativeContent
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
                    childView.hint = resources.text(previewAndroidValue(node, "hint"))
                }
                view.addView(childView)
            }
            addPreviewChildren(view, node)
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
            // Android LinearLayout defaults to horizontal when orientation is omitted.
            orientation = if (previewAndroidValue(node, "orientation") == "vertical") {
                LinearLayout.VERTICAL
            } else {
                LinearLayout.HORIZONTAL
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
        "MaterialButtonToggleGroup" -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        "EditText" -> EditText(context)
        "TextInputLayout" -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        "TextInputEditText" -> EditText(context)
        "ImageView" -> ImageView(context).apply {
            val sourceApplied = applyImageSource(this, node)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (!sourceApplied) {
                setBackgroundColor(Color.rgb(238, 241, 240))
            }
        }
        "ImageButton" -> ImageButton(context).apply {
            val sourceApplied = applyImageSource(this, node)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (!sourceApplied) {
                setBackgroundColor(Color.rgb(238, 241, 240))
            }
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
            adapter = DummyAdapter(node)
        }
        else -> lockedPlaceholder(node)
    }

    private fun applyImageSource(view: ImageView, node: UiNode): Boolean {
        val bitmap = imageBitmaps[node.stableId]
        if (bitmap != null) {
            view.setImageBitmap(bitmap)
            return true
        }
        val reference = imageSource(node)
        platformDrawableId(reference)?.let {
            view.setImageResource(it)
            return true
        }
        drawable(reference)?.let {
            view.setImageDrawable(it)
            return true
        }
        view.setImageResource(android.R.drawable.ic_menu_gallery)
        return false
    }

    private fun platformDrawableId(reference: String?): Int? {
        if (!isPlatformDrawable(reference)) return null
        val resourceName = reference.orEmpty().removePrefix("@android:drawable/")
        return context.resources.getIdentifier(resourceName, "drawable", "android").takeIf { it != 0 }
    }

    private fun isPlatformDrawable(reference: String?): Boolean =
        reference?.startsWith("@android:drawable/") == true

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
        view.contentDescription = resources.text(previewAndroidValue(node, "contentDescription"))
        view.visibility = when (previewAndroidValue(node, "visibility")) {
            "gone" -> View.GONE
            "invisible" -> View.INVISIBLE
            else -> View.VISIBLE
        }
        previewAndroidValue(node, "alpha")?.toFloatOrNull()?.let { view.alpha = it.coerceIn(0f, 1f) }
        previewAndroidValue(node, "enabled")?.toBooleanStrictOrNull()?.let { view.isEnabled = it }
        previewAndroidValue(node, "selected")?.toBooleanStrictOrNull()?.let { view.isSelected = it }
        applyPadding(view, node)

        drawable(previewAndroidValue(node, "background"))?.let { view.background = it }
        if (view is TextView) {
            resources.text(previewAndroidValue(node, "text"))?.let { view.text = it }
            resources.text(previewAndroidValue(node, "hint"))?.let { view.hint = it }
            resources.color(previewAndroidValue(node, "textColor"))?.let(::parseColor)?.let(view::setTextColor)
            parseTextSize(previewAndroidValue(node, "textSize"))?.let {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it)
            }
            view.gravity = parseGravity(previewAndroidValue(node, "gravity")) ?: view.gravity
            when (previewAndroidValue(node, "textStyle")) {
                "bold" -> view.setTypeface(view.typeface, Typeface.BOLD)
                "italic" -> view.setTypeface(view.typeface, Typeface.ITALIC)
                "bold|italic", "italic|bold" -> view.setTypeface(view.typeface, Typeface.BOLD_ITALIC)
            }
            previewAndroidValue(node, "maxLines")?.toIntOrNull()?.let { view.maxLines = it.coerceAtLeast(1) }
            val resourceName = resourceIdName(node.androidAttribute("id"))
            if (view.text.isNullOrBlank() && resourceName in dynamicTextViewIds) {
                view.text = dynamicPreviewText(resourceName.orEmpty())
            } else if (view.text.isNullOrBlank() && representativeContent && view is CompoundButton) {
                view.text = "예시 항목"
            }
        }
        if (view is CompoundButton) {
            previewAndroidValue(node, "checked")?.toBooleanStrictOrNull()?.let { view.isChecked = it }
        }
        if (view is ImageView) {
            view.scaleType = when (previewAndroidValue(node, "scaleType")) {
                "centerCrop" -> ImageView.ScaleType.CENTER_CROP
                "fitCenter" -> ImageView.ScaleType.FIT_CENTER
                "fitXY" -> ImageView.ScaleType.FIT_XY
                "centerInside" -> ImageView.ScaleType.CENTER_INSIDE
                else -> view.scaleType
            }
        }
        if (view is LinearLayout) {
            parseGravity(previewAndroidValue(node, "gravity"))?.let { view.gravity = it }
        }
        val resourceName = resourceIdName(node.androidAttribute("id"))
        if (resourceName in hiddenViewIds) view.visibility = View.GONE
    }

    private fun addPreviewChildren(parent: ViewGroup, node: UiNode) {
        val containerId = resourceIdName(node.androidAttribute("id")) ?: return
        previewChildrenByContainer[containerId].orEmpty().forEach { hint ->
            val sampleXml = resources.layout("@layout/${hint.layout_name}") ?: return@forEach
            val sampleDocument = runCatching { AndroidXmlDocument.parse(sampleXml) }.getOrNull()
                ?: return@forEach
            repeat(hint.sample_count.coerceIn(1, MAX_PREVIEW_LIST_ITEMS)) {
                val holder = FrameLayout(context)
                UiPreviewRenderer(context).render(
                    document = sampleDocument,
                    resources = resources,
                    canvas = holder,
                    imageBitmaps = imageBitmaps,
                    representativeContent = true
                )
                val sample = holder.getChildAt(0) ?: return@repeat
                holder.removeView(sample)
                sample.layoutParams = layoutParamsFor(parent, sampleDocument.root)
                parent.addView(sample)
            }
        }
    }

    private fun dynamicPreviewText(resourceName: String): String {
        val normalized = resourceName.lowercase(Locale.ROOT)
        return when {
            "date" in normalized || "day" in normalized -> LocalDate.now().format(
                DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
            )
            "count" in normalized -> "0개"
            "progress" in normalized || "summary" in normalized -> "진행 상황 미리보기"
            else -> "동적으로 표시되는 내용"
        }
    }

    private fun decorateEmptyContainer(view: ViewGroup, node: UiNode) {
        if (node.children.isNotEmpty() || node.simpleTag !in EMPTY_CONTAINER_TAGS) return
        view.minimumWidth = dp(96)
        view.minimumHeight = dp(72)
        if (previewAndroidValue(node, "background").isNullOrBlank()) {
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
        val all = dimensionPx(previewAndroidValue(node, "padding"))
        val horizontal = dimensionPx(previewAndroidValue(node, "paddingHorizontal"))
        val vertical = dimensionPx(previewAndroidValue(node, "paddingVertical"))
        val start = dimensionPx(previewAndroidValue(node, "paddingStart")) ?: horizontal ?: all ?: 0
        val top = dimensionPx(previewAndroidValue(node, "paddingTop")) ?: vertical ?: all ?: 0
        val end = dimensionPx(previewAndroidValue(node, "paddingEnd")) ?: horizontal ?: all ?: 0
        val bottom = dimensionPx(previewAndroidValue(node, "paddingBottom")) ?: vertical ?: all ?: 0
        view.setPaddingRelative(start, top, end, bottom)
    }

    private fun layoutParamsFor(parent: ViewGroup, node: UiNode): ViewGroup.LayoutParams {
        val width = layoutSize(
            previewAndroidValue(node, "layout_width"),
            fallback = ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val height = layoutSize(
            previewAndroidValue(node, "layout_height"),
            fallback = ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
                horizontalBias = previewAppValue(node, "layout_constraintHorizontal_bias")?.toFloatOrNull() ?: 0.5f
                verticalBias = previewAppValue(node, "layout_constraintVertical_bias")?.toFloatOrNull() ?: 0.5f
                editorAbsoluteX = dimensionPx(node.toolsAttribute("layout_editor_absoluteX")) ?: -1
                editorAbsoluteY = dimensionPx(node.toolsAttribute("layout_editor_absoluteY")) ?: -1
            }
            is LinearLayout -> LinearLayout.LayoutParams(width, height).apply {
                applyMargins(this, node)
                weight = previewAndroidValue(node, "layout_weight")?.toFloatOrNull() ?: 0f
                gravity = parseGravity(previewAndroidValue(node, "layout_gravity")) ?: -1
            }
            is FrameLayout -> FrameLayout.LayoutParams(width, height).apply {
                applyMargins(this, node)
                gravity = parseGravity(previewAndroidValue(node, "layout_gravity")) ?: Gravity.NO_GRAVITY
            }
            else -> ViewGroup.MarginLayoutParams(width, height).apply { applyMargins(this, node) }
        }
    }

    private fun frameLayoutParams(node: UiNode): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        layoutSize(previewAndroidValue(node, "layout_width"), ViewGroup.LayoutParams.MATCH_PARENT),
        layoutSize(previewAndroidValue(node, "layout_height"), ViewGroup.LayoutParams.WRAP_CONTENT)
    ).apply { applyMargins(this, node) }

    private fun applyMargins(params: ViewGroup.MarginLayoutParams, node: UiNode) {
        val all = dimensionPx(previewAndroidValue(node, "layout_margin")) ?: 0
        val horizontal = dimensionPx(previewAndroidValue(node, "layout_marginHorizontal"))
        val vertical = dimensionPx(previewAndroidValue(node, "layout_marginVertical"))
        params.marginStart = dimensionPx(previewAndroidValue(node, "layout_marginStart")) ?: horizontal ?: all
        params.topMargin = dimensionPx(previewAndroidValue(node, "layout_marginTop")) ?: vertical ?: all
        params.marginEnd = dimensionPx(previewAndroidValue(node, "layout_marginEnd")) ?: horizontal ?: all
        params.bottomMargin = dimensionPx(previewAndroidValue(node, "layout_marginBottom")) ?: vertical ?: all
    }

    private fun constraint(node: UiNode, name: String): Int? {
        val value = previewAppValue(node, name)?.trim().orEmpty()
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

    private fun previewAndroidValue(node: UiNode, localName: String): String? =
        node.toolsAttribute(localName)
            ?: node.androidAttribute(localName)
            ?: resources.styleValue(node.unqualifiedAttribute("style"), ANDROID_NAMESPACE_URI, localName)

    private fun previewAppValue(node: UiNode, localName: String): String? =
        node.appAttribute(localName)
            ?: resources.styleValue(node.unqualifiedAttribute("style"), APP_NAMESPACE_URI, localName)

    private fun imageSource(node: UiNode): String? =
        node.toolsAttribute("src")
            ?: node.toolsAttribute("srcCompat")
            ?: node.androidAttribute("src")
            ?: node.appAttribute("srcCompat")
            ?: resources.styleValue(node.unqualifiedAttribute("style"), ANDROID_NAMESPACE_URI, "src")
            ?: resources.styleValue(node.unqualifiedAttribute("style"), APP_NAMESPACE_URI, "srcCompat")

    private fun drawable(value: String?): Drawable? {
        resources.color(value)?.let(::parseColor)?.let { return ColorDrawable(it) }
        val xml = resources.drawableXml(value) ?: return null
        return parseXmlDrawable(xml)
    }

    private fun parseXmlDrawable(xml: String): Drawable? = runCatching {
        val root = SecureAndroidXml.parse(xml).documentElement
        when (root.tagName.substringAfterLast('.')) {
            "shape" -> parseShapeDrawable(root)
            "selector" -> root.childElements().firstNotNullOfOrNull { item ->
                if (item.tagName.substringAfterLast('.') != "item") return@firstNotNullOfOrNull null
                drawable(item.getAttributeNS(ANDROID_NAMESPACE_URI, "drawable"))
                    ?: resources.color(item.getAttributeNS(ANDROID_NAMESPACE_URI, "color"))
                        ?.let(::parseColor)
                        ?.let(::ColorDrawable)
            }
            else -> null
        }
    }.getOrNull()

    private fun parseShapeDrawable(root: org.w3c.dom.Element): GradientDrawable {
        val result = GradientDrawable()
        result.shape = when (root.getAttributeNS(ANDROID_NAMESPACE_URI, "shape")) {
            "oval" -> GradientDrawable.OVAL
            "line" -> GradientDrawable.LINE
            "ring" -> GradientDrawable.RING
            else -> GradientDrawable.RECTANGLE
        }
        root.childElements().forEach { child ->
            when (child.tagName.substringAfterLast('.')) {
                "solid" -> resources.color(child.getAttributeNS(ANDROID_NAMESPACE_URI, "color"))
                    ?.let(::parseColor)
                    ?.let(result::setColor)
                "stroke" -> {
                    val width = dimensionPx(child.getAttributeNS(ANDROID_NAMESPACE_URI, "width")) ?: 0
                    resources.color(child.getAttributeNS(ANDROID_NAMESPACE_URI, "color"))
                        ?.let(::parseColor)
                        ?.let { result.setStroke(width, it) }
                }
                "corners" -> dimensionPx(child.getAttributeNS(ANDROID_NAMESPACE_URI, "radius"))
                    ?.let { result.cornerRadius = it.toFloat() }
            }
        }
        return result
    }

    private fun org.w3c.dom.Element.childElements(): List<org.w3c.dom.Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) add(child as org.w3c.dom.Element)
            child = child.nextSibling
        }
    }

    private inner class DummyAdapter(private val sourceNode: UiNode) : RecyclerView.Adapter<DummyViewHolder>() {
        private val count = sourceNode.toolsAttribute("itemCount")
            ?.toIntOrNull()
            ?.coerceIn(0, MAX_PREVIEW_LIST_ITEMS)
            ?: DEFAULT_PREVIEW_LIST_ITEMS
        private val itemLayoutReference = sourceNode.toolsAttribute("listitem")
        private val itemLayoutXml = resources.layout(itemLayoutReference)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DummyViewHolder {
            val sampleXml = itemLayoutXml
            if (sampleXml != null) {
                val container = FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val sampleDocument = runCatching { AndroidXmlDocument.parse(sampleXml) }.getOrNull()
                if (sampleDocument != null) {
                    UiPreviewRenderer(parent.context).render(
                        document = sampleDocument,
                        resources = resources,
                        canvas = container,
                        imageBitmaps = imageBitmaps
                    )
                    return DummyViewHolder(container)
                }
            }
            val text = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(Color.rgb(42, 48, 46))
            }
            return DummyViewHolder(text)
        }

        override fun getItemCount(): Int = count

        override fun onBindViewHolder(holder: DummyViewHolder, position: Int) {
            (holder.itemView as? TextView)?.text = buildString {
                append("목록 항목 ${position + 1}")
                itemLayoutReference?.substringAfterLast('/')?.let { append(" · ").append(it) }
            }
        }
    }

    private class DummyViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val DEFAULT_PREVIEW_LIST_ITEMS = 3
        const val MAX_PREVIEW_LIST_ITEMS = 20
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
