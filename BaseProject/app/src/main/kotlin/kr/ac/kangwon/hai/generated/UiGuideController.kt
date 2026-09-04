package kr.ac.kangwon.hai.generated

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser

object UiGuideController {
    private const val TAG = "VibeUiGuide"
    private const val PREFERENCES = "vibe_ui_guide"
    private const val CATALOG_RESOURCE = "vf_ui_catalog"
    private const val CHECK_INTERVAL_MS = 500L
    private val smallestWidthQualifier = Regex("sw(\\d+)dp")
    private val widthQualifier = Regex("w(\\d+)dp")
    private val heightQualifier = Regex("h(\\d+)dp")
    private val apiQualifier = Regex("v(\\d+)")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VibeUiGuideCatalog").apply { isDaemon = true }
    }
    private val initialized = AtomicBoolean(false)
    private val activityStates = WeakHashMap<Activity, ActivityGuideState>()

    @Volatile
    private var catalog: GuideCatalog? = null

    fun initialize(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                attach(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                activityStates[activity]?.detachLayoutObserver(activity)
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                activityStates.remove(activity)?.dispose()
            }
        })
        loader.execute {
            val loaded = runCatching { readCatalog(application) }
                .onFailure { Log.w(TAG, "UI guide catalog could not be loaded", it) }
                .getOrNull()
            catalog = loaded
            mainHandler.post {
                activityStates.keys.toList().forEach(::attach)
            }
        }
    }

    fun replay(activity: Activity) {
        val state = stateFor(activity)
        val selected = bestLayout(activity, includeSeen = true) ?: return
        clearProgress(activity, selected)
        show(activity, selected, force = true)
        state.helpButton?.visibility = View.GONE
    }

    fun show(activity: Activity, layoutName: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        show(activity, layoutName, content)
    }

    fun show(activity: Activity, layoutName: String, targetRoot: View) {
        val selected = selectExplicitLayout(layoutName, targetRoot) ?: return
        showInHost(
            activity = activity,
            layout = selected,
            targetRoot = targetRoot,
            overlayHost = activity.findViewById(android.R.id.content),
            force = false,
            state = stateFor(activity),
        )
    }

    fun show(dialog: Dialog, layoutName: String) {
        val activity = dialog.ownerActivity ?: dialog.context.findActivity() ?: return
        val content = dialog.window?.findViewById<ViewGroup>(android.R.id.content) ?: return
        val selected = selectExplicitLayout(layoutName, content) ?: return
        showInHost(activity, selected, content, content, force = false, state = null)
    }

    private fun attach(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val state = stateFor(activity)
        if (catalog == null) return
        installHelpButton(activity, state)
        state.attachLayoutObserver(activity) {
            val now = SystemClock.uptimeMillis()
            if (now - state.lastLayoutCheckAt < CHECK_INTERVAL_MS || state.overlay != null) return@attachLayoutObserver
            state.lastLayoutCheckAt = now
            bestLayout(activity, includeSeen = false)?.let { show(activity, it, force = false) }
        }
        activity.window.decorView.post {
            bestLayout(activity, includeSeen = false)?.let { show(activity, it, force = false) }
        }
    }

    private fun stateFor(activity: Activity): ActivityGuideState =
        activityStates.getOrPut(activity) { ActivityGuideState() }

    private fun installHelpButton(activity: Activity, state: ActivityGuideState) {
        if (state.helpButton != null) return
        if (catalog?.layouts.orEmpty().none { it.isAutomaticFor(activity) }) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        val button = TextView(activity).apply {
            text = "?"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            contentDescription = "사용법 다시 보기"
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(24, 107, 77))
            }
            setOnClickListener { replay(activity) }
        }
        val size = dp(activity, 48)
        content.addView(
            button,
            FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(activity, 16)
                bottomMargin = dp(activity, 16)
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(button) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val marginEnd = dp(activity, 16) + safe.right
                val bottomMargin = dp(activity, 16) + safe.bottom
                if (params.marginEnd != marginEnd || params.bottomMargin != bottomMargin) {
                    params.marginEnd = marginEnd
                    params.bottomMargin = bottomMargin
                    view.layoutParams = params
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(button)
        state.helpButton = button
    }

    private fun bestLayout(activity: Activity, includeSeen: Boolean): GuideLayout? {
        val candidates = catalog?.layouts.orEmpty()
            .asSequence()
            .filter { it.isAutomaticFor(activity) && it.elements.isNotEmpty() }
            .filter { includeSeen || !isSeen(activity, it) }
            .map { layout ->
                LayoutCandidate(
                    layout = layout,
                    visibleTargetCount = visibleTargets(activity.window.decorView, layout).size,
                    configurationScore = layout.configurationScore(activity.resources.configuration),
                )
            }
            .filter { it.visibleTargetCount > 0 && it.configurationScore >= 0 }
            .sortedWith(
                compareByDescending<LayoutCandidate> { it.configurationScore }
                    .thenByDescending { it.visibleTargetCount }
                    .thenByDescending { it.layout.configuration == "layout" }
                    .thenBy { it.layout.layoutName },
            )
            .toList()
        return candidates.firstOrNull()?.layout
    }

    private fun selectExplicitLayout(layoutName: String, targetRoot: View): GuideLayout? =
        catalog?.layouts.orEmpty()
            .asSequence()
            .filter { it.layoutName == layoutName && it.elements.isNotEmpty() }
            .map { layout ->
                LayoutCandidate(
                    layout = layout,
                    visibleTargetCount = visibleTargets(targetRoot, layout).size,
                    configurationScore = layout.configurationScore(targetRoot.resources.configuration),
                )
            }
            .filter { it.visibleTargetCount > 0 && it.configurationScore >= 0 }
            .maxWithOrNull(
                compareBy<LayoutCandidate> { it.configurationScore }
                    .thenBy { it.visibleTargetCount }
                    .thenBy { it.layout.configuration == "layout" },
            )
            ?.layout

    private fun visibleTargets(targetRoot: View, layout: GuideLayout): List<GuideTarget> =
        layout.elements.mapNotNull { element ->
            val id = targetRoot.resources.getIdentifier(element.viewId, "id", targetRoot.context.packageName)
            if (id == 0) return@mapNotNull null
            val view = targetRoot.findViewById<View>(id) ?: return@mapNotNull null
            if (!view.isShown || view.width <= 0 || view.height <= 0) return@mapNotNull null
            GuideTarget(element, view)
        }

    private fun show(activity: Activity, layout: GuideLayout, force: Boolean) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        showInHost(activity, layout, activity.window.decorView, content, force, stateFor(activity))
    }

    private fun showInHost(
        activity: Activity,
        layout: GuideLayout,
        targetRoot: View,
        overlayHost: ViewGroup?,
        force: Boolean,
        state: ActivityGuideState?,
    ) {
        if (!force && isSeen(activity, layout)) return
        if (state?.overlay != null || overlayHost == null || overlayHost.containsGuideOverlay()) return
        val targets = visibleTargets(targetRoot, layout)
        if (targets.isEmpty()) return
        val startIndex = if (force) 0 else progress(activity, layout).coerceIn(0, targets.lastIndex)
        state?.helpButton?.visibility = View.GONE
        val overlay = GuideOverlay(
            activity = activity,
            layout = layout,
            targets = targets,
            startIndex = startIndex,
            onStepChanged = { saveProgress(activity, layout, it) },
            onDismiss = {
                markSeen(activity, layout)
                state?.overlay = null
                state?.helpButton?.visibility = View.VISIBLE
            },
        )
        state?.overlay = overlay
        overlayHost.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.start()
    }

    private fun ViewGroup.containsGuideOverlay(): Boolean =
        (0 until childCount).any { getChildAt(it) is GuideOverlay }

    private fun preferenceKey(activity: Activity, layout: GuideLayout, suffix: String): String {
        val guideVersion = catalog?.guideVersion.orEmpty()
        return "${activity.packageName}:${BuildConfig.VERSION_CODE}:$guideVersion:${layout.layoutName}:$suffix"
    }

    private fun preferences(activity: Activity) =
        activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)

    private fun isSeen(activity: Activity, layout: GuideLayout): Boolean =
        preferences(activity).getBoolean(preferenceKey(activity, layout, "seen"), false)

    private fun progress(activity: Activity, layout: GuideLayout): Int =
        preferences(activity).getInt(preferenceKey(activity, layout, "progress"), 0)

    private fun saveProgress(activity: Activity, layout: GuideLayout, index: Int) {
        preferences(activity).edit().putInt(preferenceKey(activity, layout, "progress"), index).apply()
    }

    private fun markSeen(activity: Activity, layout: GuideLayout) {
        preferences(activity).edit()
            .putBoolean(preferenceKey(activity, layout, "seen"), true)
            .remove(preferenceKey(activity, layout, "progress"))
            .apply()
    }

    private fun clearProgress(activity: Activity, layout: GuideLayout) {
        preferences(activity).edit()
            .remove(preferenceKey(activity, layout, "seen"))
            .remove(preferenceKey(activity, layout, "progress"))
            .apply()
    }

    private fun readCatalog(application: Application): GuideCatalog? {
        val resourceId = application.resources.getIdentifier(CATALOG_RESOURCE, "xml", application.packageName)
        if (resourceId == 0) return null
        val parser = application.resources.getXml(resourceId)
        var guideVersion = "1"
        val layouts = mutableListOf<GuideLayout>()
        var current: MutableGuideLayout? = null
        parser.use {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "ui-catalog" -> guideVersion = parser.getAttributeValue(null, "guideVersion").orEmpty().ifBlank { "1" }
                        "layout" -> current = MutableGuideLayout(
                            layoutName = parser.getAttributeValue(null, "layoutName").orEmpty(),
                            configuration = parser.getAttributeValue(null, "configuration").orEmpty().ifBlank { "layout" },
                            displayName = parser.getAttributeValue(null, "displayName").orEmpty(),
                            kind = parser.getAttributeValue(null, "kind").orEmpty(),
                            activityClass = parser.getAttributeValue(null, "activityClass").orEmpty(),
                        )
                        "element" -> current?.elements?.add(
                            GuideElement(
                                viewId = parser.getAttributeValue(null, "viewId").orEmpty(),
                                title = parser.getAttributeValue(null, "title").orEmpty(),
                                description = parser.getAttributeValue(null, "description").orEmpty(),
                                order = parser.getAttributeValue(null, "order")?.toIntOrNull() ?: Int.MAX_VALUE,
                            ),
                        )
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "layout") {
                    current?.toImmutable()?.let(layouts::add)
                    current = null
                }
                parser.next()
            }
        }
        return GuideCatalog(guideVersion, layouts)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private class ActivityGuideState {
        var helpButton: View? = null
        var overlay: GuideOverlay? = null
        var lastLayoutCheckAt: Long = 0
        private var layoutObserver: ViewTreeObserver.OnGlobalLayoutListener? = null

        fun attachLayoutObserver(activity: Activity, onLayout: () -> Unit) {
            if (layoutObserver != null) return
            val observer = ViewTreeObserver.OnGlobalLayoutListener(onLayout)
            activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(observer)
            layoutObserver = observer
        }

        fun detachLayoutObserver(activity: Activity) {
            val observer = layoutObserver ?: return
            if (activity.window.decorView.viewTreeObserver.isAlive) {
                activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(observer)
            }
            layoutObserver = null
        }

        fun dispose() {
            overlay?.dismiss(markCompleted = false)
            overlay = null
            helpButton = null
        }
    }

    private data class GuideCatalog(
        val guideVersion: String,
        val layouts: List<GuideLayout>,
    )

    private data class LayoutCandidate(
        val layout: GuideLayout,
        val visibleTargetCount: Int,
        val configurationScore: Int,
    )

    private data class GuideLayout(
        val layoutName: String,
        val configuration: String,
        val displayName: String,
        val kind: String,
        val activityClass: String,
        val elements: List<GuideElement>,
    ) {
        fun isAutomaticFor(activity: Activity): Boolean =
            kind == "screen" &&
            activityClass.isNotBlank() &&
                (activityClass == activity.javaClass.name || activityClass == activity.javaClass.simpleName)

        fun configurationScore(current: Configuration): Int {
            if (configuration == "layout") return 0
            val qualifiers = configuration.removePrefix("layout-").split('-').filter(String::isNotBlank)
            var matchedQualifierCount = 0
            for (qualifier in qualifiers) {
                val smallestWidth = smallestWidthQualifier.matchEntire(qualifier)
                val width = widthQualifier.matchEntire(qualifier)
                val height = heightQualifier.matchEntire(qualifier)
                val api = apiQualifier.matchEntire(qualifier)
                val matches: Boolean? = when {
                    qualifier == "land" -> current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    qualifier == "port" -> current.orientation == Configuration.ORIENTATION_PORTRAIT
                    qualifier == "night" ->
                        current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    qualifier == "notnight" ->
                        current.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
                    smallestWidth != null ->
                        current.smallestScreenWidthDp >= smallestWidth.groupValues[1].toInt()
                    width != null -> current.screenWidthDp >= width.groupValues[1].toInt()
                    height != null -> current.screenHeightDp >= height.groupValues[1].toInt()
                    api != null -> Build.VERSION.SDK_INT >= api.groupValues[1].toInt()
                    else -> null
                }
                if (matches == false) return -1
                if (matches == true) matchedQualifierCount += 1
            }
            return matchedQualifierCount
        }
    }

    private data class GuideElement(
        val viewId: String,
        val title: String,
        val description: String,
        val order: Int,
    )

    private class MutableGuideLayout(
        val layoutName: String,
        val configuration: String,
        val displayName: String,
        val kind: String,
        val activityClass: String,
        val elements: MutableList<GuideElement> = mutableListOf(),
    ) {
        fun toImmutable(): GuideLayout? {
            if (layoutName.isBlank() || displayName.isBlank()) return null
            val validElements = elements
                .filter { it.viewId.isNotBlank() && it.title.isNotBlank() && it.description.isNotBlank() }
                .sortedBy { it.order }
            if (validElements.isEmpty()) return null
            return GuideLayout(layoutName, configuration, displayName, kind, activityClass, validElements)
        }
    }

    private data class GuideTarget(val element: GuideElement, val view: View)

    private class GuideOverlay(
        private val activity: Activity,
        private val layout: GuideLayout,
        private val targets: List<GuideTarget>,
        startIndex: Int,
        private val onStepChanged: (Int) -> Unit,
        private val onDismiss: () -> Unit,
    ) : FrameLayout(activity) {
        private val scrim = GuideScrimView(activity)
        private val card = ScrollView(activity)
        private val cardContent = LinearLayout(activity)
        private val stepText = TextView(activity)
        private val titleText = TextView(activity)
        private val descriptionText = TextView(activity)
        private val previousButton = Button(activity)
        private val nextButton = Button(activity)
        private val safeInsets = Rect()
        private var index = startIndex
        private var closed = false
        private var scrollPaddingAdjustment: ScrollPaddingAdjustment? = null

        init {
            isClickable = true
            isFocusableInTouchMode = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
                )
                safeInsets.set(safe.left, safe.top, safe.right, safe.bottom)
                post {
                    ensureTargetVisible(targets[index.coerceIn(0, targets.lastIndex)].view)
                    refreshTargetPosition()
                }
                insets
            }
            addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            configureCard()
            addView(card)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
            ViewCompat.requestApplyInsets(this)
        }

        fun start() {
            requestFocus()
            post { showStep(index) }
        }

        fun dismiss(markCompleted: Boolean = true) {
            if (closed) return
            closed = true
            restoreScrollPadding()
            (parent as? ViewGroup)?.removeView(this)
            if (markCompleted) onDismiss()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (width > 0 && height > 0 && (width != oldWidth || height != oldHeight)) {
                post { refreshTargetPosition() }
            }
        }

        private fun configureCard() {
            val dark = (resources.configuration.uiMode and 0x30) == 0x20
            val backgroundColor = if (dark) Color.rgb(38, 42, 40) else Color.WHITE
            val primaryText = if (dark) Color.WHITE else Color.rgb(24, 31, 28)
            val secondaryText = if (dark) Color.rgb(210, 218, 214) else Color.rgb(82, 94, 88)
            card.isFillViewport = true
            card.elevation = dp(10).toFloat()
            card.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(backgroundColor)
            }
            cardContent.orientation = LinearLayout.VERTICAL
            cardContent.setPadding(dp(18), dp(16), dp(18), dp(12))
            card.addView(
                cardContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            stepText.setTextColor(secondaryText)
            stepText.textSize = 12f
            titleText.setTextColor(primaryText)
            titleText.textSize = 18f
            titleText.setTypeface(titleText.typeface, android.graphics.Typeface.BOLD)
            descriptionText.setTextColor(secondaryText)
            descriptionText.textSize = 15f
            descriptionText.setLineSpacing(0f, 1.18f)
            cardContent.addView(stepText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            cardContent.addView(titleText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            cardContent.addView(descriptionText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })

            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val skip = Button(activity).apply {
                text = "건너뛰기"
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { dismiss() }
            }
            previousButton.apply {
                text = "이전"
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { showStep(index - 1) }
            }
            nextButton.apply {
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener {
                    if (index == targets.lastIndex) dismiss() else showStep(index + 1)
                }
            }
            actions.addView(skip, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(previousButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(nextButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            cardContent.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }

        private fun showStep(requestedIndex: Int) {
            index = requestedIndex.coerceIn(0, targets.lastIndex)
            val target = targets[index]
            ensureTargetVisible(target.view)
            postDelayed({
                if (closed) return@postDelayed
                stepText.text = "${layout.displayName} · ${index + 1}/${targets.size}"
                titleText.text = target.element.title
                descriptionText.text = target.element.description
                previousButton.isEnabled = index > 0
                nextButton.text = if (index == targets.lastIndex) "닫기" else "다음"
                refreshTargetPosition()
                card.scrollTo(0, 0)
                onStepChanged(index)
                announceStep(target)
            }, 32L)
        }

        private fun ensureTargetVisible(target: View) {
            val scrollContainer = target.findVerticalScrollContainer()
            val bottomInset = effectiveBottomInset()
            if (scrollContainer !== scrollPaddingAdjustment?.view) {
                restoreScrollPadding()
                if (scrollContainer != null) {
                    scrollPaddingAdjustment = ScrollPaddingAdjustment.capture(scrollContainer)
                }
            }
            scrollPaddingAdjustment?.applyBottomInset(bottomInset + dp(12))
            target.requestRectangleOnScreen(
                Rect(
                    0,
                    -effectiveTopInset() - dp(8),
                    target.width,
                    target.height + bottomInset + dp(12),
                ),
                true,
            )
        }

        private fun effectiveTopInset(): Int = effectiveSystemInsets().top

        private fun effectiveBottomInset(): Int = effectiveSystemInsets().bottom

        private fun effectiveSystemInsets(): Rect {
            val rootInsets = ViewCompat.getRootWindowInsets(this)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val result = Rect(
                maxOf(safeInsets.left, rootInsets?.left ?: 0),
                maxOf(safeInsets.top, rootInsets?.top ?: 0, systemBarDimension("status_bar_height")),
                maxOf(safeInsets.right, rootInsets?.right ?: 0),
                maxOf(safeInsets.bottom, rootInsets?.bottom ?: 0, systemBarDimension("navigation_bar_height")),
            )
            includeSystemBarView("statusBarBackground", result)
            includeSystemBarView("navigationBarBackground", result)
            return result
        }

        private fun includeSystemBarView(resourceName: String, result: Rect) {
            val resourceId = resources.getIdentifier(resourceName, "id", "android")
            val bar = if (resourceId == 0) null else activity.window.decorView.findViewById<View>(resourceId)
            if (bar == null || !bar.isShown || bar.width <= 0 || bar.height <= 0) return
            val barLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            bar.getLocationOnScreen(barLocation)
            getLocationOnScreen(overlayLocation)
            val left = barLocation[0] - overlayLocation[0]
            val top = barLocation[1] - overlayLocation[1]
            val right = left + bar.width
            val bottom = top + bar.height
            if (bar.width >= width / 2 && top <= 1) result.top = maxOf(result.top, bottom)
            if (bar.width >= width / 2 && bottom >= height - 1) result.bottom = maxOf(result.bottom, height - top)
            if (bar.height >= height / 2 && left <= 1) result.left = maxOf(result.left, right)
            if (bar.height >= height / 2 && right >= width - 1) result.right = maxOf(result.right, width - left)
        }

        private fun systemBarDimension(name: String): Int {
            val resourceId = resources.getIdentifier(name, "dimen", "android")
            return if (resourceId == 0) 0 else resources.getDimensionPixelSize(resourceId)
        }

        private fun restoreScrollPadding() {
            scrollPaddingAdjustment?.restore()
            scrollPaddingAdjustment = null
        }

        private fun View.findVerticalScrollContainer(): ViewGroup? {
            var ancestor = parent
            while (ancestor is View) {
                if (ancestor is ViewGroup && ancestor.isVerticalScrollContainer()) return ancestor
                ancestor = ancestor.parent
            }
            return null
        }

        private fun ViewGroup.isVerticalScrollContainer(): Boolean {
            val name = javaClass.name
            return this is ScrollView ||
                name == "androidx.core.widget.NestedScrollView" ||
                name == "androidx.recyclerview.widget.RecyclerView"
        }

        private fun announceStep(target: GuideTarget) {
            val announcement = "${target.element.title}. ${target.element.description}"
            titleText.contentDescription = announcement
            titleText.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }

        private fun refreshTargetPosition() {
            if (closed || width <= 0 || height <= 0 || targets.isEmpty()) return
            val target = targets[index.coerceIn(0, targets.lastIndex)]
            if (!target.view.isShown || target.view.width <= 0 || target.view.height <= 0) return
            correctTargetForSystemBars(target.view)
            val targetRect = targetRect(target.view)
            scrim.target = targetRect
            positionCard(targetRect)
        }

        private fun correctTargetForSystemBars(target: View) {
            val scrollContainer = target.findVerticalScrollContainer() ?: return
            val raw = rawTargetRect(target)
            val safe = effectiveSystemInsets()
            val margin = dp(8).toFloat()
            val safeTop = safe.top + margin
            val safeBottom = height - safe.bottom - margin
            val delta = when {
                raw.bottom > safeBottom -> kotlin.math.ceil(raw.bottom - safeBottom).toInt()
                raw.top < safeTop -> -kotlin.math.ceil(safeTop - raw.top).toInt()
                else -> 0
            }
            if (delta != 0) scrollContainer.scrollBy(0, delta)
        }

        private fun targetRect(view: View): RectF {
            val target = rawTargetRect(view)
            val safe = effectiveSystemInsets()
            target.left = maxOf(target.left, safe.left.toFloat())
            target.top = maxOf(target.top, safe.top.toFloat())
            target.right = minOf(target.right, (width - safe.right).toFloat())
            target.bottom = minOf(target.bottom, (height - safe.bottom).toFloat())
            return target
        }

        private fun rawTargetRect(view: View): RectF {
            val targetLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            view.getLocationOnScreen(targetLocation)
            getLocationOnScreen(overlayLocation)
            val padding = dp(6).toFloat()
            return RectF(
                targetLocation[0] - overlayLocation[0] - padding,
                targetLocation[1] - overlayLocation[1] - padding,
                targetLocation[0] - overlayLocation[0] + view.width + padding,
                targetLocation[1] - overlayLocation[1] + view.height + padding,
            )
        }

        private fun positionCard(target: RectF) {
            val margin = dp(16)
            val gap = dp(12)
            val insets = effectiveSystemInsets()
            val safeLeft = insets.left + margin
            val safeTop = insets.top + margin
            val safeRight = width - insets.right - margin
            val safeBottom = height - insets.bottom - margin
            val availableWidth = (safeRight - safeLeft).coerceAtLeast(1)
            val availableHeight = (safeBottom - safeTop).coerceAtLeast(dp(120))
            val cardWidth = minOf(dp(360), availableWidth)
            card.measure(
                MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((availableHeight * 0.55f).toInt(), MeasureSpec.AT_MOST),
            )
            val cardHeight = card.measuredHeight
            val below = target.bottom.toInt() + gap
            val above = target.top.toInt() - gap - cardHeight
            val top = when {
                below + cardHeight <= safeBottom -> below
                above >= safeTop -> above
                else -> (safeBottom - cardHeight).coerceAtLeast(safeTop)
            }
            val preferredLeft = (target.centerX() - cardWidth / 2f).toInt()
            val left = preferredLeft.coerceIn(safeLeft, (safeRight - cardWidth).coerceAtLeast(safeLeft))
            card.layoutParams = LayoutParams(cardWidth, cardHeight).apply {
                leftMargin = left
                topMargin = top
            }
            card.requestLayout()
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        private data class ScrollPaddingAdjustment(
            val view: ViewGroup,
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
        ) {
            fun applyBottomInset(extraBottom: Int) {
                view.setPadding(left, top, right, bottom + extraBottom)
            }

            fun restore() {
                view.setPadding(left, top, right, bottom)
            }

            companion object {
                fun capture(view: ViewGroup) = ScrollPaddingAdjustment(
                    view = view,
                    left = view.paddingLeft,
                    top = view.paddingTop,
                    right = view.paddingRight,
                    bottom = view.paddingBottom,
                )
            }
        }
    }

    private class GuideScrimView(activity: Activity) : View(activity) {
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 0, 0, 0) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 164, 116)
            style = Paint.Style.STROKE
            strokeWidth = activity.resources.displayMetrics.density * 3f
        }
        var target: RectF = RectF()
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = target
            if (rect.isEmpty) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                return
            }
            canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dim)
            canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dim)
            canvas.drawRoundRect(rect, 12f, 12f, border)
        }
    }
}
