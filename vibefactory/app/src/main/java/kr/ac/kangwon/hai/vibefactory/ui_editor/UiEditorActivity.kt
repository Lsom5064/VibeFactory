package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kr.ac.kangwon.hai.vibefactory.HostPreferencesStore
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.SelectedAttachmentKind
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kr.ac.kangwon.hai.vibefactory.UiEditorDraftDto
import kr.ac.kangwon.hai.vibefactory.UiEditorDraftRequestDto
import kr.ac.kangwon.hai.vibefactory.UiEditorImageUploadRequestDto
import kr.ac.kangwon.hai.vibefactory.UiEditorSaveRequestDto
import kr.ac.kangwon.hai.vibefactory.buildSelectedAttachment
import kr.ac.kangwon.hai.vibefactory.createVibeApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale

class UiEditorActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()
    private val apiService by lazy { createVibeApiService(gson) }
    private val preferencesStore by lazy { HostPreferencesStore(this, gson, "UiEditorActivity") }
    private val draftStore by lazy { UiEditorDraftStore(this, gson) }
    private val viewModel by lazy { ViewModelProvider(this)[UiEditorViewModel::class.java] }
    private var taskId: String = ""
    private var revisionLabel: String = ""
    private var appName: String = ""
    private var selectedLayout: UiLayoutSummaryDto? = null
    private var desiredRestoredLayout: UiLayoutSummaryDto? = null
    private var renderGeneration = 0
    private var draftSaveJob: Job? = null
    private var pendingImageElementId: String? = null
    private var pendingRevealElementId: String? = null
    private var currentNodeViews: Map<String, View> = emptyMap()
    private var isSubmitting = false
    private var isImeEditingMode = false
    private var isPaletteExpanded = true
    private var propertyPanelHeightPx = 0
    private var canvasInteractionMode = CanvasInteractionMode.SCROLL
    private val density by lazy { resources.displayMetrics.density }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val elementId = pendingImageElementId
        pendingImageElementId = null
        if (uri == null || elementId.isNullOrBlank()) return@registerForActivityResult
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) {
                val attachment = buildSelectedAttachment(
                    contentResolver = contentResolver,
                    uri = uri,
                    requestedKind = SelectedAttachmentKind.IMAGE,
                    maxOriginalImageBytes = 20 * 1024 * 1024,
                    maxImagePayloadBytes = 2 * 1024 * 1024,
                    maxPdfBytes = 10 * 1024 * 1024,
                    maxTextBytes = 2 * 1024 * 1024
                ) ?: return@withContext null
                val session = viewModel.session ?: return@withContext null
                draftStore.persistImage(
                    taskId = session.taskId,
                    revisionLabel = session.revisionLabel,
                    layoutName = session.layout.layout_name,
                    elementStableId = elementId,
                    attachment = attachment
                )
            }
            if (image == null) {
                Toast.makeText(this@UiEditorActivity, R.string.ui_editor_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val session = viewModel.session ?: return@launch
            val before = session.snapshot()
            if (UiDocumentEditor.setImageReference(session.document, elementId, image.resourceName)) {
                session.images.removeAll { it.elementStableId == elementId }
                session.images += image
                recordAndRender(before, elementId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ui_editor)
        propertyPanelHeightPx = ((
            savedInstanceState?.getInt(STATE_PROPERTY_PANEL_HEIGHT_DP, PROPERTY_PANEL_DEFAULT_HEIGHT_DP)
                ?: PROPERTY_PANEL_DEFAULT_HEIGHT_DP
            ) * density).toInt()
        applyEditorWindowInsets()
        bindPropertyFieldFocusHandling()
        bindPropertyPanelResizing()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleEditorBack()
        })

        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        revisionLabel = intent.getStringExtra(EXTRA_REVISION_LABEL).orEmpty().trim()
        appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().trim()
        if (taskId.isBlank() || revisionLabel.isBlank()) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnBackUiEditor).setOnClickListener { handleEditorBack() }
        findViewById<TextView>(R.id.uiEditorTitle).text = appName.ifBlank { getString(R.string.ui_editor_title) }
        findViewById<TextView>(R.id.uiEditorRevision).text = revisionLabel
        findViewById<Button>(R.id.btnUiEditorScreen).setOnClickListener { anchor -> showLayoutMenu(anchor) }
        findViewById<Button>(R.id.btnUiEditorRetry).setOnClickListener { loadLayouts() }
        findViewById<Button>(R.id.btnUiEditorUndo).setOnClickListener { undo() }
        findViewById<Button>(R.id.btnUiEditorRedo).setOnClickListener { redo() }
        findViewById<Button>(R.id.btnUiEditorSave).setOnClickListener { saveDraftForChat() }
        findViewById<Button>(R.id.btnUiEditorApply).setOnClickListener { applySelectedProperties() }
        findViewById<Button>(R.id.btnUiEditorDuplicate).setOnClickListener { duplicateSelected() }
        findViewById<Button>(R.id.btnUiEditorDelete).setOnClickListener { confirmDeleteSelected() }
        findViewById<Button>(R.id.btnUiEditorAttachImage).setOnClickListener { selectImageForCurrentElement() }
        findViewById<Button>(R.id.btnUiEditorChooseIcon).setOnClickListener(::showIconMenu)
        findViewById<Button>(R.id.btnUiEditorLayers).setOnClickListener { showLayerMenu(it) }
        findViewById<Button>(R.id.btnUiEditorLayerBack).setOnClickListener { reorderSelected(false) }
        findViewById<Button>(R.id.btnUiEditorLayerFront).setOnClickListener { reorderSelected(true) }
        findViewById<Button>(R.id.btnUiEditorCloseProperties).setOnClickListener { closePropertyEditor() }
        canvasInteractionMode = savedInstanceState?.getString(STATE_INTERACTION_MODE)
            ?.let { saved -> CanvasInteractionMode.entries.firstOrNull { it.name == saved } }
            ?: CanvasInteractionMode.SCROLL
        isPaletteExpanded = savedInstanceState?.getBoolean(STATE_PALETTE_EXPANDED, true) ?: true
        bindCanvasInteractionMode()
        bindPaletteSection()
        bindPalette()
        bindColorPickers()
        bindCanvasDropTarget()

        val restoredName = savedInstanceState?.getString(STATE_LAYOUT_NAME).orEmpty()
        val restoredConfiguration = savedInstanceState?.getString(STATE_LAYOUT_CONFIGURATION).orEmpty()
        if (restoredName.isNotBlank()) {
            desiredRestoredLayout = UiLayoutSummaryDto(
                layout_name = restoredName,
                configuration = restoredConfiguration.ifBlank { "layout" }
            )
        }
        val retained = viewModel.session
        if (retained != null && retained.taskId == taskId && retained.revisionLabel == revisionLabel) {
            selectedLayout = retained.layout
            findViewById<Button>(R.id.btnUiEditorScreen).text = layoutDisplayName(retained.layout)
            renderCurrentSession()
        } else {
            loadLayouts()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedLayout?.let { layout ->
            outState.putString(STATE_LAYOUT_NAME, layout.layout_name)
            outState.putString(STATE_LAYOUT_CONFIGURATION, layout.configuration)
        }
        outState.putString(STATE_INTERACTION_MODE, canvasInteractionMode.name)
        outState.putBoolean(STATE_PALETTE_EXPANDED, isPaletteExpanded)
        outState.putInt(
            STATE_PROPERTY_PANEL_HEIGHT_DP,
            (propertyPanelHeightPx / density).toInt().coerceAtLeast(PROPERTY_PANEL_MIN_HEIGHT_DP)
        )
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isSubmitting) persistDraft(showConfirmation = false)
        super.onStop()
    }

    private fun applyEditorWindowInsets() {
        val root = findViewById<View>(R.id.uiEditorRoot)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = baseLeft + systemBars.left,
                top = baseTop + systemBars.top,
                right = baseRight + systemBars.right,
                bottom = baseBottom + systemBars.bottom
            )
            setImeEditingMode(imeVisible)
            if (imeVisible) scrollFocusedPropertyIntoView()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setImeEditingMode(enabled: Boolean) {
        if (isImeEditingMode == enabled) return
        isImeEditingMode = enabled
        EDITOR_CONTENT_IDS.forEach { viewId ->
            findViewById<View>(viewId).visibility = if (enabled) View.GONE else View.VISIBLE
        }
        val panel = findViewById<View>(R.id.uiEditorPropertiesPanel)
        val params = panel.layoutParams as LinearLayout.LayoutParams
        params.height = if (enabled) 0 else propertyPanelHeightPx
        params.weight = if (enabled) 1f else 0f
        panel.layoutParams = params
        if (!enabled) updatePaletteSectionVisibility()
    }

    private fun bindPropertyFieldFocusHandling() {
        PROPERTY_FIELD_IDS.forEach { viewId ->
            findViewById<EditText>(viewId).setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) scrollFocusedPropertyIntoView()
            }
        }
    }

    private fun bindPropertyPanelResizing() {
        val handle = findViewById<View>(R.id.uiEditorPropertiesResizeHandle)
        var startRawY = 0f
        var startHeight = 0
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isImeEditingMode) return@setOnTouchListener false
                    startRawY = event.rawY
                    startHeight = propertyPanelHeightPx
                    view.isPressed = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    updatePropertyPanelHeight(startHeight + (startRawY - event.rawY).toInt())
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }

                else -> false
            }
        }
    }

    private fun updatePropertyPanelHeight(requestedHeight: Int) {
        val root = findViewById<View>(R.id.uiEditorRoot)
        val availableHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val minimumHeight = (PROPERTY_PANEL_MIN_HEIGHT_DP * density).toInt()
        val maximumHeight = (availableHeight * PROPERTY_PANEL_MAX_HEIGHT_RATIO).toInt()
            .coerceAtLeast(minimumHeight)
        propertyPanelHeightPx = requestedHeight.coerceIn(minimumHeight, maximumHeight)
        if (isImeEditingMode) return
        findViewById<View>(R.id.uiEditorPropertiesPanel).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also { params ->
                params.height = propertyPanelHeightPx
                params.weight = 0f
            }
        }
    }

    private fun scrollFocusedPropertyIntoView() {
        val focused = currentFocus ?: return
        val panel = findViewById<ScrollView>(R.id.uiEditorPropertiesScroll)
        if (!focused.isDescendantOf(panel)) return
        panel.postDelayed({
            if (!focused.isAttachedToWindow || !focused.hasFocus()) return@postDelayed
            val bounds = Rect(0, 0, focused.width, focused.height)
            panel.offsetDescendantRectToMyCoords(focused, bounds)
            panel.smoothScrollTo(0, (bounds.top - (12 * density).toInt()).coerceAtLeast(0))
        }, IME_SCROLL_DELAY_MILLIS)
    }

    private fun closePropertyEditor() {
        currentFocus?.clearFocus()
        WindowCompat.getInsetsController(window, findViewById(R.id.uiEditorRoot))
            .hide(WindowInsetsCompat.Type.ime())
        viewModel.session?.selectedElementId = null
        highlightSelection(currentNodeViews)
        bindSelectedElement()
    }

    private fun handleEditorBack() {
        val propertyPanel = findViewById<View>(R.id.uiEditorPropertiesPanel)
        if (propertyPanel.visibility == View.VISIBLE) {
            closePropertyEditor()
        } else {
            finish()
        }
    }

    private fun bindPaletteSection() {
        findViewById<View>(R.id.uiEditorPaletteSectionHeader).setOnClickListener {
            isPaletteExpanded = !isPaletteExpanded
            updatePaletteSectionVisibility(animate = true)
        }
        updatePaletteSectionVisibility()
    }

    private fun updatePaletteSectionVisibility(animate: Boolean = false) {
        val content = findViewById<View>(R.id.uiEditorPaletteContent)
        val chevron = findViewById<ImageView>(R.id.uiEditorPaletteChevron)
        val stateLabel = findViewById<TextView>(R.id.uiEditorPaletteSectionSummary)
        content.visibility = if (isPaletteExpanded) View.VISIBLE else View.GONE
        stateLabel.setText(
            if (isPaletteExpanded) R.string.ui_editor_palette_close else R.string.ui_editor_palette_open
        )
        val targetRotation = if (isPaletteExpanded) 90f else 0f
        if (animate) {
            chevron.animate().rotation(targetRotation).setDuration(SECTION_TOGGLE_DURATION_MILLIS).start()
        } else {
            chevron.rotation = targetRotation
        }
        chevron.contentDescription = getString(
            if (isPaletteExpanded) R.string.ui_editor_collapse_palette else R.string.ui_editor_expand_palette
        )
    }

    private fun bindColorPickers() {
        bindColorPickerControl(
            controlId = R.id.uiEditorTextColorControl,
            fieldId = R.id.uiEditorTextColor,
            swatchId = R.id.uiEditorTextColorSwatch,
            titleRes = R.string.ui_editor_color_picker_title_text
        )
        bindColorPickerControl(
            controlId = R.id.uiEditorBackgroundControl,
            fieldId = R.id.uiEditorBackground,
            swatchId = R.id.uiEditorBackgroundSwatch,
            titleRes = R.string.ui_editor_color_picker_title_background
        )
    }

    private fun bindColorPickerControl(controlId: Int, fieldId: Int, swatchId: Int, titleRes: Int) {
        val listener = View.OnClickListener {
            showColorPicker(
                fieldId = fieldId,
                swatchId = swatchId,
                titleRes = titleRes
            )
        }
        listOf(controlId, fieldId, swatchId).forEach { viewId ->
            findViewById<View>(viewId).setOnClickListener(listener)
        }
        findViewById<EditText>(fieldId).apply {
            isClickable = true
            isLongClickable = false
        }
    }

    private fun showColorPicker(fieldId: Int, swatchId: Int, titleRes: Int) {
        val field = findViewById<EditText>(fieldId)
        val currentValue = field.text.toString().trim()
        val customInput = EditText(this).apply {
            hint = getString(R.string.ui_editor_color_custom_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
            setText(currentValue)
            setSelection(text.length)
        }
        val grid = GridLayout(this).apply {
            columnCount = COLOR_PICKER_COLUMN_COUNT
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        var dialog: AlertDialog? = null
        COLOR_PRESETS.forEach { value ->
            val colorButton = View(this).apply {
                background = colorSwatchDrawable(value, selected = value.equals(currentValue, ignoreCase = true))
                contentDescription = value
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setColorFieldValue(fieldId, swatchId, value)
                    dialog?.dismiss()
                }
            }
            grid.addView(
                colorButton,
                GridLayout.LayoutParams().apply {
                    width = (COLOR_SWATCH_SIZE_DP * density).toInt()
                    height = (COLOR_SWATCH_SIZE_DP * density).toInt()
                    val margin = (COLOR_SWATCH_MARGIN_DP * density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (20 * density).toInt()
            val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(grid, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                customInput,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                    topMargin = (10 * density).toInt()
                }
            )
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.ui_editor_color_clear) { _, _ ->
                setColorFieldValue(fieldId, swatchId, "")
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = normalizePickerColor(customInput.text.toString())
                if (normalized == null) {
                    customInput.error = getString(R.string.ui_editor_invalid_color)
                    return@setOnClickListener
                }
                setColorFieldValue(fieldId, swatchId, normalized)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun normalizePickerColor(value: String): String? {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return normalized.takeIf { it.matches(PICKER_COLOR_PATTERN) }
    }

    private fun setColorFieldValue(fieldId: Int, swatchId: Int, value: String) {
        findViewById<EditText>(fieldId).setText(value)
        updateColorSwatch(fieldId, swatchId)
    }

    private fun updateColorSwatch(fieldId: Int, swatchId: Int) {
        val value = findViewById<EditText>(fieldId).text.toString().trim()
        findViewById<View>(swatchId).background = colorSwatchDrawable(value)
    }

    private fun colorSwatchDrawable(value: String, selected: Boolean = false): GradientDrawable {
        val color = runCatching { Color.parseColor(value) }.getOrNull() ?: Color.TRANSPARENT
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * density
            setColor(color)
            setStroke(
                ((if (selected) 2 else 1) * density).toInt().coerceAtLeast(1),
                getColor(if (selected) R.color.accent_primary_dark else R.color.text_secondary)
            )
        }
    }

    private fun bindCanvasInteractionMode() {
        findViewById<Button>(R.id.btnUiEditorScrollMode).setOnClickListener {
            setCanvasInteractionMode(CanvasInteractionMode.SCROLL)
        }
        findViewById<Button>(R.id.btnUiEditorMoveMode).setOnClickListener {
            setCanvasInteractionMode(CanvasInteractionMode.MOVE)
        }
        updateCanvasInteractionModeUi()
    }

    private fun setCanvasInteractionMode(mode: CanvasInteractionMode) {
        if (canvasInteractionMode == mode) return
        canvasInteractionMode = mode
        if (mode == CanvasInteractionMode.MOVE) {
            currentFocus?.clearFocus()
            WindowCompat.getInsetsController(window, findViewById(R.id.uiEditorRoot))
                .hide(WindowInsetsCompat.Type.ime())
        }
        updateCanvasInteractionModeUi()
        attachElementInteractions(currentNodeViews)
        bindSelectedElement()
    }

    private fun updateCanvasInteractionModeUi() {
        updateInteractionModeButton(R.id.btnUiEditorScrollMode, canvasInteractionMode == CanvasInteractionMode.SCROLL)
        updateInteractionModeButton(R.id.btnUiEditorMoveMode, canvasInteractionMode == CanvasInteractionMode.MOVE)
    }

    private fun updateInteractionModeButton(viewId: Int, selected: Boolean) {
        findViewById<Button>(viewId).apply {
            isSelected = selected
            setBackgroundResource(if (selected) R.drawable.bg_send_button else R.drawable.bg_button_secondary)
            setTextColor(getColor(if (selected) R.color.text_inverse else R.color.text_primary))
        }
    }

    private fun View.isDescendantOf(parent: View): Boolean {
        var current = this.parent
        while (current is View) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun loadLayouts() {
        val generation = ++viewModel.loadGeneration
        showLoading(getString(R.string.ui_editor_loading_layouts))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    apiService.getRevisionUiLayouts(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }
            if (generation != viewModel.loadGeneration) return@launch
            result.onSuccess { response ->
                if (!response.source_available) {
                    showError(response.unavailable_reason.ifBlank { getString(R.string.ui_editor_source_unavailable) })
                    return@onSuccess
                }
                viewModel.setLayouts(response.layouts)
                if (viewModel.layouts.isEmpty()) {
                    showError(getString(R.string.ui_editor_no_layouts))
                    return@onSuccess
                }
                val previous = selectedLayout ?: desiredRestoredLayout
                val selected = viewModel.layouts.firstOrNull {
                    it.layout_name == previous?.layout_name && it.configuration == previous.configuration
                } ?: viewModel.layouts.firstOrNull { it.layout_name == "activity_main" && it.configuration == "layout" }
                    ?: viewModel.layouts.first()
                loadLayout(selected)
            }.onFailure { error ->
                showError(error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.ui_editor_load_failed))
            }
        }
    }

    private fun showLayoutMenu(anchor: View) {
        if (viewModel.layouts.isEmpty()) return
        PopupMenu(this, anchor).apply {
            val layoutsByItemId = mutableMapOf<Int, UiLayoutSummaryDto>()
            var itemId = 1
            viewModel.layoutMenuGroups.forEach { group ->
                val subMenu = menu.addSubMenu(group.label)
                group.layouts.forEach { layout ->
                    layoutsByItemId[itemId] = layout
                    subMenu.add(0, itemId, itemId, layoutDisplayName(layout))
                    itemId += 1
                }
            }
            menu.add(0, MENU_NEW_LAYOUT, itemId, getString(R.string.ui_editor_new_layout))
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_NEW_LAYOUT) {
                    showNewLayoutDialog()
                    true
                } else {
                    layoutsByItemId[item.itemId]?.let(::loadLayout) != null
                }
            }
            show()
        }
    }

    private fun loadLayout(layout: UiLayoutSummaryDto) {
        selectedLayout = layout
        val existing = viewModel.session
        if (
            existing?.taskId == taskId &&
            existing.revisionLabel == revisionLabel &&
            existing.layout.layout_name == layout.layout_name &&
            existing.layout.configuration == layout.configuration
        ) {
            renderCurrentSession()
            return
        }
        val generation = ++viewModel.loadGeneration
        findViewById<Button>(R.id.btnUiEditorScreen).text = layoutDisplayName(layout)
        showLoading(getString(R.string.ui_editor_loading_preview))
        lifecycleScope.launch {
            val result = runCatching {
                val response = withContext(Dispatchers.IO) {
                    apiService.getRevisionUiLayout(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        layoutName = layout.layout_name,
                        configuration = layout.configuration,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
                val draft = withContext(Dispatchers.IO) {
                    draftStore.load(taskId, revisionLabel, layout.layout_name, layout.configuration)
                }
                val serverDraft = withContext(Dispatchers.IO) {
                    runCatching {
                        apiService.getUiEditorDraft(
                            taskId = taskId,
                            revisionLabel = revisionLabel,
                            layoutName = layout.layout_name,
                            configuration = layout.configuration,
                            deviceId = preferencesStore.getOrCreateDeviceId(),
                            userId = null,
                            phoneNumber = preferencesStore.loadPhoneNumber()
                        )
                    }.getOrNull()
                }
                val resolvedDraft = withContext(Dispatchers.IO) {
                    reconcileDrafts(layout, draft, serverDraft)
                }
                withContext(Dispatchers.Default) {
                    val document = AndroidXmlDocument.parse(response.xml, response.sha256)
                    val resources = ResolvedUiResources.from(response.resource_files)
                    val session = viewModel.initialize(
                        taskId,
                        revisionLabel,
                        layout,
                        document,
                        resources,
                        resolvedDraft,
                        unresolvedResourceCount = response.unresolved_resources.size
                    )
                    session
                }
            }
            if (generation != viewModel.loadGeneration) return@launch
            result.onSuccess {
                renderCurrentSession()
            }.onFailure { error ->
                showError(error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.ui_editor_load_failed))
            }
        }
    }

    private fun showNewLayoutDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ui_editor_new_layout_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_editor_new_layout)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_editor_create) { _, _ ->
                val name = input.text.toString().trim().lowercase()
                if (!name.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) {
                    Toast.makeText(this, R.string.ui_editor_invalid_layout_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createBlankLayout(name)
            }
            .show()
    }

    private fun createBlankLayout(layoutName: String) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/root_${layoutName}"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />"""
        val layout = UiLayoutSummaryDto(
            layout_name = layoutName,
            configuration = "layout",
            resource_path = "res/layout/$layoutName.xml",
            root_tag = "ConstraintLayout",
            sha256 = AndroidXmlDocument.sha256(xml),
            size_bytes = xml.toByteArray().size.toLong()
        )
        selectedLayout = layout
        viewModel.initialize(
            taskId,
            revisionLabel,
            layout,
            AndroidXmlDocument.parse(xml),
            ResolvedUiResources.EMPTY,
            draftStore.load(taskId, revisionLabel, layoutName, "layout"),
            isNewLayout = true
        )
        findViewById<Button>(R.id.btnUiEditorScreen).text = layoutDisplayName(layout)
        renderCurrentSession()
        scheduleDraftSave()
    }

    private fun bindPalette() {
        findViewById<Button>(R.id.btnUiAddElement).setOnClickListener(::showAddElementMenu)
        val bindings = mapOf(
            R.id.btnUiAddText to UiPaletteElement.TEXT,
            R.id.btnUiAddButton to UiPaletteElement.BUTTON,
            R.id.btnUiAddInput to UiPaletteElement.INPUT,
            R.id.btnUiAddImage to UiPaletteElement.IMAGE,
            R.id.btnUiAddIcon to UiPaletteElement.ICON,
            R.id.btnUiAddCard to UiPaletteElement.CARD,
            R.id.btnUiAddList to UiPaletteElement.LIST
        )
        bindings.forEach { (viewId, type) ->
            findViewById<Button>(viewId).apply {
                setOnClickListener { addPaletteElement(type) }
                setOnLongClickListener {
                    startDragAndDrop(
                        ClipData.newPlainText("ui_element", type.name),
                        View.DragShadowBuilder(this),
                        type,
                        0
                    )
                    true
                }
            }
        }
    }

    private fun showAddElementMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            val basic = menu.addSubMenu(
                0,
                MENU_ADD_BASIC_GROUP,
                0,
                getString(R.string.ui_editor_add_basic_group)
            )
            addPaletteMenuItem(basic, UiPaletteElement.TEXT, R.string.ui_editor_add_text)
            addPaletteMenuItem(basic, UiPaletteElement.BUTTON, R.string.ui_editor_add_button)
            addPaletteMenuItem(basic, UiPaletteElement.INPUT, R.string.ui_editor_add_input)
            addPaletteMenuItem(basic, UiPaletteElement.IMAGE, R.string.ui_editor_add_image)
            addPaletteMenuItem(basic, UiPaletteElement.ICON, R.string.ui_editor_add_icon)
            addPaletteMenuItem(basic, UiPaletteElement.CHECKBOX, R.string.ui_editor_add_checkbox)
            addPaletteMenuItem(basic, UiPaletteElement.SWITCH, R.string.ui_editor_add_switch)

            val containers = menu.addSubMenu(
                0,
                MENU_ADD_CONTAINER_GROUP,
                1,
                getString(R.string.ui_editor_add_container_group)
            )
            addPaletteMenuItem(
                containers,
                UiPaletteElement.VERTICAL_LINEAR_LAYOUT,
                R.string.ui_editor_add_vertical_layout
            )
            addPaletteMenuItem(
                containers,
                UiPaletteElement.HORIZONTAL_LINEAR_LAYOUT,
                R.string.ui_editor_add_horizontal_layout
            )
            addPaletteMenuItem(
                containers,
                UiPaletteElement.CONSTRAINT_LAYOUT,
                R.string.ui_editor_add_constraint_layout
            )
            addPaletteMenuItem(containers, UiPaletteElement.FRAME_LAYOUT, R.string.ui_editor_add_frame_layout)
            addPaletteMenuItem(containers, UiPaletteElement.SCROLL_VIEW, R.string.ui_editor_add_scroll_view)
            addPaletteMenuItem(containers, UiPaletteElement.CARD, R.string.ui_editor_add_card)

            val data = menu.addSubMenu(
                0,
                MENU_ADD_DATA_GROUP,
                2,
                getString(R.string.ui_editor_add_data_group)
            )
            addPaletteMenuItem(data, UiPaletteElement.LIST, R.string.ui_editor_add_list)
            setOnMenuItemClickListener { item ->
                val typeIndex = item.itemId - MENU_ADD_ELEMENT_BASE
                val type = UiPaletteElement.entries.getOrNull(typeIndex)
                    ?: return@setOnMenuItemClickListener false
                addPaletteElement(type)
                true
            }
            show()
        }
    }

    private fun addPaletteMenuItem(menu: android.view.SubMenu, type: UiPaletteElement, label: Int) {
        menu.add(0, MENU_ADD_ELEMENT_BASE + type.ordinal, type.ordinal, label)
    }

    private fun showLayerMenu(anchor: View) {
        val session = viewModel.session ?: return
        val nodes = session.document.root.descendantsAndSelf().toList()
        PopupMenu(this, anchor).apply {
            nodes.forEachIndexed { index, node ->
                val depth = node.elementPath.size.coerceAtLeast(1) - 1
                val label = buildString {
                    repeat(depth) { append("  ") }
                    append(node.simpleTag)
                    node.androidAttribute("id")?.substringAfterLast('/')?.let { append(" · ").append(it) }
                    if (node.locked) append(" · 잠김")
                }
                menu.add(0, index, index, label)
            }
            setOnMenuItemClickListener { item ->
                nodes.getOrNull(item.itemId)?.let { node -> selectElement(node.stableId, reveal = true) } != null
            }
            show()
        }
    }

    private fun reorderSelected(moveForward: Boolean) {
        val session = viewModel.session ?: return
        val stableId = session.selectedElementId ?: return
        val before = session.snapshot()
        if (UiDocumentEditor.reorderSibling(session.document, stableId, moveForward)) {
            recordAndRender(before, stableId)
        }
    }

    private fun bindCanvasDropTarget() {
        findViewById<FrameLayout>(R.id.uiEditorCanvas).setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is UiPaletteElement
                DragEvent.ACTION_DROP -> {
                    val type = event.localState as? UiPaletteElement ?: return@setOnDragListener false
                    addPaletteElement(type, (event.x / density).toInt(), (event.y / density).toInt())
                    true
                }
                else -> true
            }
        }
    }

    private fun addPaletteElement(type: UiPaletteElement, xDp: Int? = null, yDp: Int? = null) {
        val session = viewModel.session ?: return
        val insertionParent = UiDocumentEditor.insertionParent(session.document, session.selectedElementId)
        val before = session.snapshot()
        val selectedParent = session.selectedElementId
        val newId = UiDocumentEditor.addElement(session.document, selectedParent, type, xDp, yDp)
            ?: run {
                Toast.makeText(this, R.string.ui_editor_element_add_failed, Toast.LENGTH_SHORT).show()
                return
            }
        recordAndRender(before, newId, revealSelection = true)
        val parentLabel = insertionParent?.simpleTag?.let(::displayElementType)
            ?: getString(R.string.ui_editor_screen)
        Toast.makeText(
            this,
            getString(R.string.ui_editor_element_added, displayPaletteType(type), parentLabel),
            Toast.LENGTH_SHORT
        ).show()
        if (type == UiPaletteElement.IMAGE) {
            pendingImageElementId = newId
            imagePicker.launch("image/*")
        }
    }

    private fun displayPaletteType(type: UiPaletteElement): String = getString(
        when (type) {
            UiPaletteElement.TEXT -> R.string.ui_editor_add_text
            UiPaletteElement.BUTTON -> R.string.ui_editor_add_button
            UiPaletteElement.INPUT -> R.string.ui_editor_add_input
            UiPaletteElement.IMAGE -> R.string.ui_editor_add_image
            UiPaletteElement.ICON -> R.string.ui_editor_add_icon
            UiPaletteElement.CHECKBOX -> R.string.ui_editor_add_checkbox
            UiPaletteElement.SWITCH -> R.string.ui_editor_add_switch
            UiPaletteElement.CARD -> R.string.ui_editor_add_card
            UiPaletteElement.LIST -> R.string.ui_editor_add_list
            UiPaletteElement.VERTICAL_LINEAR_LAYOUT -> R.string.ui_editor_add_vertical_layout
            UiPaletteElement.HORIZONTAL_LINEAR_LAYOUT -> R.string.ui_editor_add_horizontal_layout
            UiPaletteElement.CONSTRAINT_LAYOUT -> R.string.ui_editor_add_constraint_layout
            UiPaletteElement.FRAME_LAYOUT -> R.string.ui_editor_add_frame_layout
            UiPaletteElement.SCROLL_VIEW -> R.string.ui_editor_add_scroll_view
        }
    )

    private fun displayElementType(simpleTag: String): String = when (simpleTag) {
        "LinearLayout" -> "LinearLayout"
        "ConstraintLayout" -> "ConstraintLayout"
        "FrameLayout" -> "FrameLayout"
        "ScrollView", "NestedScrollView" -> "ScrollView"
        "CardView", "MaterialCardView" -> "Card"
        else -> simpleTag
    }

    private fun renderCurrentSession() {
        val session = viewModel.session ?: return
        val generation = ++renderGeneration
        lifecycleScope.launch {
            val imageBitmaps = withContext(Dispatchers.Default) { decodeEditorImages(session.images) }
            if (generation != renderGeneration || viewModel.session !== session) return@launch
            val canvas = findViewById<FrameLayout>(R.id.uiEditorCanvas)
            val renderResult = UiPreviewRenderer(this@UiEditorActivity).render(
                session.document,
                session.resources,
                canvas,
                imageBitmaps
            )
            currentNodeViews = renderResult.nodeViews
            attachElementInteractions(renderResult)
            val lockedCount = session.document.root.descendantsAndSelf().count { it.locked }
            findViewById<TextView>(R.id.uiEditorCanvasMeta).text = getString(
                R.string.ui_editor_canvas_meta,
                session.document.root.descendantsAndSelf().count(),
                lockedCount,
                renderResult.warnings.size + session.unresolvedResourceCount
            )
            updateHistoryActions()
            bindSelectedElement()
            showCanvas()
            pendingRevealElementId?.let { stableId ->
                pendingRevealElementId = null
                revealElement(stableId)
            }
        }
    }

    private fun attachElementInteractions(result: UiPreviewRenderResult) {
        attachElementInteractions(result.nodeViews)
    }

    private fun attachElementInteractions(nodeViews: Map<String, View>) {
        val session = viewModel.session ?: return
        nodeViews.forEach { (stableId, view) ->
            val node = session.document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId }
                ?: return@forEach
            view.isClickable = true
            view.setOnClickListener { selectElement(stableId) }
            view.setOnTouchListener(
                if (canvasInteractionMode == CanvasInteractionMode.MOVE) {
                    ElementDragTouchListener(stableId, node.locked || node === session.document.root)
                } else {
                    ElementSelectionTouchListener()
                }
            )
        }
        highlightSelection(nodeViews)
    }

    private fun selectElement(stableId: String, reveal: Boolean = false) {
        val session = viewModel.session ?: return
        session.selectedElementId = stableId
        highlightSelection(currentNodeViews)
        bindSelectedElement()
        if (reveal) revealElement(stableId)
        scheduleDraftSave()
    }

    private fun revealElement(stableId: String) {
        val target = currentNodeViews[stableId] ?: return
        val canvas = findViewById<FrameLayout>(R.id.uiEditorCanvas)
        val horizontalViewport = findViewById<HorizontalScrollView>(R.id.uiEditorCanvasViewport)
        val verticalViewport = findViewById<ScrollView>(R.id.uiEditorCanvasVerticalViewport)
        target.post {
            if (!target.isAttachedToWindow || !canvas.isAttachedToWindow) return@post
            val bounds = Rect(0, 0, target.width, target.height)
            canvas.offsetDescendantRectToMyCoords(target, bounds)
            val horizontalTarget = (bounds.centerX() - horizontalViewport.width / 2)
                .coerceIn(0, (canvas.width - horizontalViewport.width).coerceAtLeast(0))
            val verticalTarget = (bounds.centerY() - verticalViewport.height / 2)
                .coerceIn(0, (canvas.height - verticalViewport.height).coerceAtLeast(0))
            horizontalViewport.smoothScrollTo(horizontalTarget, 0)
            verticalViewport.smoothScrollTo(0, verticalTarget)
            pulseElement(target)
        }
    }

    private fun pulseElement(target: View) {
        target.animate().cancel()
        target.scaleX = 1f
        target.scaleY = 1f
        target.animate()
            .scaleX(1.025f)
            .scaleY(1.025f)
            .setDuration(ELEMENT_PULSE_DURATION_MILLIS)
            .withEndAction {
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(ELEMENT_PULSE_DURATION_MILLIS)
                    .start()
            }
            .start()
        target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun highlightSelection(nodeViews: Map<String, View>) {
        nodeViews.values.forEach { it.foreground = null }
        val selected = viewModel.session?.selectedElementId ?: return
        nodeViews[selected]?.foreground = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((2 * density).toInt().coerceAtLeast(2), getColor(R.color.accent_primary_dark))
        }
    }

    private fun bindSelectedElement() {
        val panel = findViewById<View>(R.id.uiEditorPropertiesPanel)
        if (canvasInteractionMode == CanvasInteractionMode.MOVE) {
            panel.visibility = View.GONE
            return
        }
        val session = viewModel.session ?: run {
            panel.visibility = View.GONE
            return
        }
        val node = session.selectedElementId?.let { selected ->
            session.document.root.descendantsAndSelf().firstOrNull { it.stableId == selected }
        }
        if (node == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.uiEditorSelectedElement).text = if (node.locked) {
            getString(R.string.ui_editor_locked_element, node.simpleTag)
        } else {
            getString(
                R.string.ui_editor_selected_element,
                node.simpleTag,
                node.stableId.removePrefix("id:").removePrefix("path:")
            )
        }
        val supportsText = node.simpleTag in TEXT_TAGS
        findViewById<View>(R.id.uiEditorElementTextContainer).visibility =
            if (supportsText) View.VISIBLE else View.GONE
        findViewById<EditText>(R.id.uiEditorElementText)
            .setText(node.androidAttribute("text") ?: node.androidAttribute("hint").orEmpty())
        findViewById<EditText>(R.id.uiEditorWidth).setText(node.androidAttribute("layout_width").orEmpty())
        findViewById<EditText>(R.id.uiEditorHeight).setText(node.androidAttribute("layout_height").orEmpty())
        findViewById<EditText>(R.id.uiEditorMarginStart).setText(dpValue(node.androidAttribute("layout_marginStart")))
        findViewById<EditText>(R.id.uiEditorMarginTop).setText(dpValue(node.androidAttribute("layout_marginTop")))
        findViewById<EditText>(R.id.uiEditorTextColor).apply {
            setText(node.androidAttribute("textColor").orEmpty())
        }
        findViewById<EditText>(R.id.uiEditorBackground).setText(node.androidAttribute("background").orEmpty())
        findViewById<View>(R.id.uiEditorTextColorControl).visibility =
            if (supportsText) View.VISIBLE else View.GONE
        updateColorSwatch(R.id.uiEditorTextColor, R.id.uiEditorTextColorSwatch)
        updateColorSwatch(R.id.uiEditorBackground, R.id.uiEditorBackgroundSwatch)
        findViewById<EditText>(R.id.uiEditorDescription).setText(session.descriptions[node.stableId].orEmpty())
        val editable = !node.locked
        listOf(
            R.id.uiEditorElementText,
            R.id.uiEditorWidth,
            R.id.uiEditorHeight,
            R.id.uiEditorMarginStart,
            R.id.uiEditorMarginTop,
            R.id.uiEditorTextColor,
            R.id.uiEditorBackground,
            R.id.uiEditorDescription,
            R.id.uiEditorTextColorControl,
            R.id.uiEditorBackgroundControl,
            R.id.btnUiEditorApply,
            R.id.btnUiEditorDuplicate,
            R.id.btnUiEditorLayerBack,
            R.id.btnUiEditorLayerFront,
            R.id.btnUiEditorDelete
        ).forEach { findViewById<View>(it).isEnabled = editable }
        findViewById<Button>(R.id.btnUiEditorAttachImage).apply {
            visibility = if (editable && node.simpleTag in IMAGE_TAGS) View.VISIBLE else View.GONE
            isEnabled = editable
        }
        findViewById<Button>(R.id.btnUiEditorChooseIcon).apply {
            visibility = if (editable && node.simpleTag in IMAGE_TAGS) View.VISIBLE else View.GONE
            isEnabled = editable
        }
    }

    private fun applySelectedProperties() {
        val session = viewModel.session ?: return
        val stableId = session.selectedElementId ?: return
        val node = session.document.root.descendantsAndSelf().firstOrNull { it.stableId == stableId } ?: return
        val before = session.snapshot()
        val patch = UiElementPatch(
            text = if (node.simpleTag in TEXT_TAGS) textOf(R.id.uiEditorElementText) else null,
            width = textOf(R.id.uiEditorWidth).takeIf { it.isNotBlank() },
            height = textOf(R.id.uiEditorHeight).takeIf { it.isNotBlank() },
            marginStartDp = textOf(R.id.uiEditorMarginStart).toIntOrNull(),
            marginTopDp = textOf(R.id.uiEditorMarginTop).toIntOrNull(),
            textColor = if (node.simpleTag in TEXT_TAGS) textOf(R.id.uiEditorTextColor) else null,
            background = textOf(R.id.uiEditorBackground)
        )
        val applied = runCatching { UiDocumentEditor.applyPatch(session.document, stableId, patch) }
            .getOrElse {
                Toast.makeText(this, R.string.ui_editor_invalid_property, Toast.LENGTH_SHORT).show()
                false
            }
        if (!applied) return
        val description = textOf(R.id.uiEditorDescription).trim()
        if (description.isBlank()) session.descriptions.remove(stableId)
        else session.descriptions[stableId] = description
        recordAndRender(before, stableId)
    }

    private fun duplicateSelected() {
        val session = viewModel.session ?: return
        val stableId = session.selectedElementId ?: return
        val before = session.snapshot()
        val newId = UiDocumentEditor.duplicate(session.document, stableId) ?: return
        session.descriptions[stableId]?.let { session.descriptions[newId] = it }
        recordAndRender(before, newId, revealSelection = true)
    }

    private fun confirmDeleteSelected() {
        val stableId = viewModel.session?.selectedElementId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_editor_delete_title)
            .setMessage(R.string.ui_editor_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_editor_delete) { _, _ -> deleteSelected(stableId) }
            .show()
    }

    private fun deleteSelected(stableId: String) {
        val session = viewModel.session ?: return
        val before = session.snapshot()
        if (!UiDocumentEditor.delete(session.document, stableId)) return
        session.descriptions.remove(stableId)
        session.images.removeAll { it.elementStableId == stableId }
        recordAndRender(before, null)
    }

    private fun selectImageForCurrentElement() {
        val stableId = viewModel.session?.selectedElementId ?: return
        pendingImageElementId = stableId
        imagePicker.launch("image/*")
    }

    private fun showIconMenu(anchor: View) {
        val session = viewModel.session ?: return
        val stableId = session.selectedElementId ?: return
        PopupMenu(this, anchor).apply {
            PLATFORM_ICONS.forEachIndexed { index, option ->
                menu.add(0, index, index, option.labelRes).apply {
                    icon = AppCompatResources.getDrawable(this@UiEditorActivity, option.drawableRes)
                }
            }
            setOnMenuItemClickListener { item ->
                val option = PLATFORM_ICONS.getOrNull(item.itemId)
                    ?: return@setOnMenuItemClickListener false
                val before = session.snapshot()
                val changed = UiDocumentEditor.setPlatformIconReference(
                    session.document,
                    stableId,
                    option.resourceName,
                    getString(option.labelRes)
                )
                if (!changed) return@setOnMenuItemClickListener false
                session.images.removeAll { it.elementStableId == stableId }
                recordAndRender(before, stableId)
                true
            }
            show()
        }
    }

    private fun recordAndRender(
        before: UiEditorSnapshot,
        selectedId: String?,
        revealSelection: Boolean = false
    ) {
        val session = viewModel.session ?: return
        session.selectedElementId = selectedId
        if (revealSelection) pendingRevealElementId = selectedId
        if (session.snapshot() != before) session.recordChange()
        renderCurrentSession()
        scheduleDraftSave()
    }

    private fun undo() {
        val session = viewModel.session ?: return
        session.history.undo()?.let {
            session.restore(it)
            renderCurrentSession()
            scheduleDraftSave()
        }
    }

    private fun redo() {
        val session = viewModel.session ?: return
        session.history.redo()?.let {
            session.restore(it)
            renderCurrentSession()
            scheduleDraftSave()
        }
    }

    private fun updateHistoryActions() {
        val history = viewModel.session?.history
        findViewById<Button>(R.id.btnUiEditorUndo).isEnabled = history?.canUndo == true
        findViewById<Button>(R.id.btnUiEditorRedo).isEnabled = history?.canRedo == true
        findViewById<Button>(R.id.btnUiEditorSave).isEnabled = viewModel.session != null && !isSubmitting
    }

    private fun scheduleDraftSave() {
        draftSaveJob?.cancel()
        draftSaveJob = lifecycleScope.launch {
            delay(600)
            persistDraft(showConfirmation = false)
        }
    }

    private fun persistDraft(showConfirmation: Boolean) {
        val session = viewModel.session ?: return
        val record = draftStore.recordFor(session)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { persistDraftNow(session, record) }
            if (showConfirmation) {
                val message = if (result.isSuccess) R.string.ui_editor_saved else R.string.ui_editor_saved_locally
                Toast.makeText(this@UiEditorActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun persistDraftNow(
        session: UiEditorSession,
        record: UiEditorDraftRecord
    ): Result<UiEditorDraftDto> = runCatching {
        draftStore.save(record)
        viewModel.serverDraftMutex.withLock {
            val current = viewModel.session
            require(current === session) { "editor session changed" }
            val request = UiEditorDraftRequestDto(
                draft_id = current.serverDraftId,
                configuration = record.configuration,
                base_xml_sha256 = record.baseXmlSha256,
                original_xml = current.baseXml,
                edited_xml = record.editedXml,
                descriptions = record.descriptions,
                expected_version = current.serverDraftVersion,
                is_new_layout = current.isNewLayout
            )
            val response = try {
                apiService.saveUiEditorDraft(
                    taskId = record.taskId,
                    revisionLabel = record.revisionLabel,
                    layoutName = record.layoutName,
                    deviceId = preferencesStore.getOrCreateDeviceId(),
                    userId = null,
                    phoneNumber = preferencesStore.loadPhoneNumber(),
                    request = request
                )
            } catch (error: HttpException) {
                if (error.code() != 409 || current.serverDraftId != null) throw error
                val remote = apiService.getUiEditorDraft(
                    taskId = record.taskId,
                    revisionLabel = record.revisionLabel,
                    layoutName = record.layoutName,
                    configuration = record.configuration,
                    deviceId = preferencesStore.getOrCreateDeviceId(),
                    userId = null,
                    phoneNumber = preferencesStore.loadPhoneNumber()
                )
                if (remote.edited_xml != record.editedXml || remote.descriptions != record.descriptions) throw error
                remote
            }
            current.serverDraftId = response.draft_id
            current.serverDraftVersion = response.version
            draftStore.save(draftStore.recordFor(current))
            response
        }
    }

    private fun saveDraftForChat() {
        val session = viewModel.session ?: return
        if (isSubmitting) return
        applySelectedProperties()
        draftSaveJob?.cancel()
        val preview = captureCanvasPreview()
        isSubmitting = true
        updateHistoryActions()
        showLoading(getString(R.string.ui_editor_saving))
        lifecycleScope.launch {
            val result = runCatching {
                val record = draftStore.recordFor(session)
                val saved = withContext(Dispatchers.IO) {
                    persistDraftNow(session, record).getOrThrow()
                }
                val uploadedVersion = withContext(Dispatchers.IO) {
                    uploadPendingImages(session, saved)
                    session.serverDraftVersion ?: saved.version
                }
                withContext(Dispatchers.IO) {
                    apiService.confirmUiEditorDraft(
                        taskId = session.taskId,
                        revisionLabel = session.revisionLabel,
                        draftId = session.serverDraftId ?: error("server draft ID is missing"),
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber(),
                        request = UiEditorSaveRequestDto(
                            expected_version = uploadedVersion,
                            preview_image_base64 = preview
                        )
                    )
                }
            }
            result.onSuccess {
                withContext(Dispatchers.IO) {
                    draftStore.save(draftStore.recordFor(session, status = "saved"))
                }
                Toast.makeText(this@UiEditorActivity, R.string.ui_editor_saved, Toast.LENGTH_LONG).show()
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_TASK_ID, session.taskId)
                )
                finish()
            }.onFailure { error ->
                isSubmitting = false
                updateHistoryActions()
                renderCurrentSession()
                Toast.makeText(
                    this@UiEditorActivity,
                    error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.ui_editor_save_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun uploadPendingImages(session: UiEditorSession, draft: UiEditorDraftDto) {
        val draftId = draft.draft_id
        session.images.toList().forEach { image ->
            if (!image.serverWorkspacePath.isNullOrBlank()) return@forEach
            val imageBytes = File(image.localPath).readBytes()
            val response = apiService.uploadUiEditorImage(
                taskId = session.taskId,
                revisionLabel = session.revisionLabel,
                draftId = draftId,
                deviceId = preferencesStore.getOrCreateDeviceId(),
                userId = null,
                phoneNumber = preferencesStore.loadPhoneNumber(),
                request = UiEditorImageUploadRequestDto(
                    image_id = image.imageId,
                    element_stable_id = image.elementStableId,
                    original_name = image.displayName,
                    mime_type = image.mimeType,
                    resource_name = image.resourceName,
                    base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                )
            ).image
            val index = session.images.indexOfFirst { it.imageId == image.imageId }
            if (index >= 0) {
                session.images[index] = image.copy(serverWorkspacePath = response.workspace_path)
            }
        }
        draftStore.save(draftStore.recordFor(session))
    }

    private fun captureCanvasPreview(): String? {
        val canvasView = findViewById<FrameLayout>(R.id.uiEditorCanvas)
        if (canvasView.width <= 0 || canvasView.height <= 0) return null
        currentNodeViews.values.forEach { it.foreground = null }
        val bitmap = Bitmap.createBitmap(canvasView.width, canvasView.height, Bitmap.Config.RGB_565)
        canvasView.draw(Canvas(bitmap))
        highlightSelection(currentNodeViews)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun reconcileDrafts(
        layout: UiLayoutSummaryDto,
        local: UiEditorDraftRecord?,
        remote: UiEditorDraftDto?
    ): UiEditorDraftRecord? {
        if (remote == null) return local
        val localImages = local?.images.orEmpty().associateBy { it.imageId }
        val remoteImages = remote.images.mapNotNull { image ->
            localImages[image.image_id]
                ?.takeIf { File(it.localPath).isFile && it.sha256.equals(image.sha256, ignoreCase = true) }
                ?.copy(serverWorkspacePath = image.workspace_path)
                ?: runCatching {
                    val bytes = apiService.getUiEditorImage(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        draftId = remote.draft_id,
                        imageId = image.image_id,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    ).bytes()
                    draftStore.persistDownloadedImage(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        layoutName = layout.layout_name,
                        imageId = image.image_id,
                        elementStableId = image.element_stable_id,
                        displayName = image.original_name,
                        resourceName = image.resource_name,
                        sha256 = image.sha256,
                        serverWorkspacePath = image.workspace_path,
                        bytes = bytes
                    )
                }.getOrNull()
        }
        val remoteRecord = UiEditorDraftRecord(
            taskId = taskId,
            revisionLabel = revisionLabel,
            layoutName = layout.layout_name,
            configuration = layout.configuration,
            baseXmlSha256 = remote.base_xml_sha256,
            editedXml = remote.edited_xml,
            descriptions = remote.descriptions,
            images = remoteImages,
            selectedElementId = local?.selectedElementId,
            status = remote.status,
            updatedAt = remote.updated_at,
            serverDraftId = remote.draft_id,
            serverDraftVersion = remote.version
        )
        val localUpdatedAt = runCatching { Instant.parse(local?.updatedAt.orEmpty()) }.getOrNull()
        val remoteUpdatedAt = runCatching { Instant.parse(remote.updated_at) }.getOrNull()
        val localIsNewer = local != null && localUpdatedAt != null && remoteUpdatedAt != null && localUpdatedAt > remoteUpdatedAt
        val resolved = local?.takeIf { localIsNewer }?.let { newerLocal ->
            newerLocal.copy(
                images = newerLocal.images.map { localImage ->
                    remote.images.firstOrNull { it.image_id == localImage.imageId }
                        ?.let { localImage.copy(serverWorkspacePath = it.workspace_path) }
                        ?: localImage
                },
                serverDraftId = remote.draft_id,
                serverDraftVersion = remote.version
            )
        } ?: remoteRecord
        draftStore.save(resolved)
        return resolved
    }

    private fun decodeEditorImages(images: List<UiEditorImage>): Map<String, Bitmap> = buildMap {
        images.forEach { image ->
            val file = File(image.localPath)
            if (!file.isFile) return@forEach
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1000) sampleSize *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
            )
            if (bitmap != null) put(image.elementStableId, bitmap)
        }
    }

    private fun textOf(viewId: Int): String = findViewById<EditText>(viewId).text.toString()

    private fun dpValue(value: String?): String = value.orEmpty().trim().removeSuffix("dp")

    private fun showLoading(message: String) {
        findViewById<View>(R.id.uiEditorStateOverlay).visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.uiEditorProgress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.uiEditorStateText).apply {
            visibility = View.VISIBLE
            text = message
        }
        findViewById<Button>(R.id.btnUiEditorRetry).visibility = View.GONE
        findViewById<View>(R.id.uiEditorCanvasViewport).visibility = View.INVISIBLE
    }

    private fun showError(message: String) {
        findViewById<View>(R.id.uiEditorStateOverlay).visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.uiEditorProgress).visibility = View.GONE
        findViewById<TextView>(R.id.uiEditorStateText).apply {
            visibility = View.VISIBLE
            text = message
        }
        findViewById<Button>(R.id.btnUiEditorRetry).visibility = View.VISIBLE
        findViewById<View>(R.id.uiEditorCanvasViewport).visibility = View.INVISIBLE
    }

    private fun showCanvas() {
        findViewById<ProgressBar>(R.id.uiEditorProgress).visibility = View.GONE
        findViewById<TextView>(R.id.uiEditorStateText).visibility = View.GONE
        findViewById<Button>(R.id.btnUiEditorRetry).visibility = View.GONE
        findViewById<View>(R.id.uiEditorStateOverlay).visibility = View.GONE
        findViewById<View>(R.id.uiEditorCanvasViewport).visibility = View.VISIBLE
    }

    private fun layoutDisplayName(layout: UiLayoutSummaryDto): String =
        UiLayoutPresentation.displayName(layout)

    private fun resolveDragPlacement(
        stableId: String,
        draggedView: View,
        translatedX: Float,
        translatedY: Float
    ): ResolvedDragPlacement? {
        val session = viewModel.session ?: return null
        val parentView = draggedView.parent as? ViewGroup ?: return null
        val parentNode = findUiNodeParent(session.document.root, stableId) ?: return null
        val draggedNode = parentNode.children.firstOrNull { it.stableId == stableId } ?: return null
        val sourceAvoidsOverlap = avoidsUnnecessaryOverlap(draggedNode, draggedView, parentView)
        val items = parentNode.children.mapNotNull { node ->
            val view = currentNodeViews[node.stableId]
                ?.takeIf { it.parent === parentView && it.visibility != View.GONE }
                ?: return@mapNotNull null
            UiEditorPlacementItem(
                stableId = node.stableId,
                left = view.left,
                top = view.top,
                right = view.right,
                bottom = view.bottom,
                canDisplace = sourceAvoidsOverlap && avoidsUnnecessaryOverlap(node, view, parentView)
            )
        }
        if (items.none { it.stableId == stableId }) return null
        val flow = when (parentView) {
            is LinearLayout -> if (parentView.orientation == LinearLayout.HORIZONTAL) {
                UiEditorParentFlow.HORIZONTAL
            } else {
                UiEditorParentFlow.VERTICAL
            }

            else -> UiEditorParentFlow.FREEFORM
        }
        val minLeft = parentView.paddingLeft
        val maxLeft = (parentView.width - parentView.paddingRight - draggedView.width).coerceAtLeast(minLeft)
        val minTop = parentView.paddingTop
        val maxTop = (parentView.height - parentView.paddingBottom - draggedView.height).coerceAtLeast(minTop)
        val dropLeft = (draggedView.left + translatedX).toInt().coerceIn(minLeft, maxLeft)
        val dropTop = (draggedView.top + translatedY).toInt().coerceIn(minTop, maxTop)
        val directPlacement = ResolvedDragPlacement(
            flow = flow,
            parentView = parentView,
            decision = UiEditorPlacementPolicy.resolve(
                flow = flow,
                draggedStableId = stableId,
                dropLeft = dropLeft,
                dropTop = dropTop,
                dropWidth = draggedView.width,
                dropHeight = draggedView.height,
                items = items
            )
        )
        if (
            flow == UiEditorParentFlow.FREEFORM &&
            directPlacement.decision.targetStableId == null &&
            sourceAvoidsOverlap
        ) {
            return resolveNestedLinearPlacement(
                parentNode = parentNode,
                parentView = parentView,
                draggedView = draggedView,
                dropLeft = dropLeft,
                dropTop = dropTop
            ) ?: directPlacement
        }
        return directPlacement
    }

    private fun commitDragPlacement(
        session: UiEditorSession,
        stableId: String,
        draggedView: View,
        placement: ResolvedDragPlacement
    ): Boolean {
        val decision = placement.decision
        placement.nestedTargetStableId?.let { targetStableId ->
            return UiDocumentEditor.reparentRelative(
                document = session.document,
                stableId = stableId,
                targetStableId = targetStableId,
                insertAfterTarget = false,
                renderedWidthDp = (draggedView.width / density).toInt(),
                renderedHeightDp = (draggedView.height / density).toInt()
            )
        }
        if (placement.flow != UiEditorParentFlow.FREEFORM) {
            val targetStableId = decision.targetStableId ?: return false
            return UiDocumentEditor.reorderSiblingRelative(
                document = session.document,
                stableId = stableId,
                targetStableId = targetStableId,
                insertAfterTarget = decision.insertAfterTarget
            )
        }

        val sourceStartDp = pxPositionToMarginDp(draggedView.left, placement.parentView.paddingLeft)
        val sourceTopDp = pxPositionToMarginDp(draggedView.top, placement.parentView.paddingTop)
        val sourceMoved = UiDocumentEditor.placeAt(
            document = session.document,
            stableId = stableId,
            startDp = pxPositionToMarginDp(decision.snapLeft, placement.parentView.paddingLeft),
            topDp = pxPositionToMarginDp(decision.snapTop, placement.parentView.paddingTop),
            renderedWidthDp = (draggedView.width / density).toInt(),
            renderedHeightDp = (draggedView.height / density).toInt()
        )
        val targetStableId = decision.targetStableId ?: return sourceMoved
        val targetView = currentNodeViews[targetStableId] ?: return sourceMoved
        val targetMoved = UiDocumentEditor.placeAt(
            document = session.document,
            stableId = targetStableId,
            startDp = sourceStartDp,
            topDp = sourceTopDp,
            renderedWidthDp = (targetView.width / density).toInt(),
            renderedHeightDp = (targetView.height / density).toInt()
        )
        return sourceMoved || targetMoved
    }

    private fun resolveNestedLinearPlacement(
        parentNode: UiNode,
        parentView: ViewGroup,
        draggedView: View,
        dropLeft: Int,
        dropTop: Int
    ): ResolvedDragPlacement? {
        val parentLocation = IntArray(2).also(parentView::getLocationInWindow)
        val dropRect = Rect(
            dropLeft,
            dropTop,
            dropLeft + draggedView.width,
            dropTop + draggedView.height
        )
        val collision = parentNode.descendantsAndSelf().asSequence()
            .drop(1)
            .mapNotNull { node ->
                val view = currentNodeViews[node.stableId]
                    ?.takeIf { it !== draggedView && it.visibility == View.VISIBLE }
                    ?: return@mapNotNull null
                val linearParent = view.parent as? LinearLayout ?: return@mapNotNull null
                if (linearParent === parentView || !avoidsUnnecessaryOverlap(node, view, linearParent)) {
                    return@mapNotNull null
                }
                val viewLocation = IntArray(2).also(view::getLocationInWindow)
                val bounds = Rect(
                    viewLocation[0] - parentLocation[0],
                    viewLocation[1] - parentLocation[1],
                    viewLocation[0] - parentLocation[0] + view.width,
                    viewLocation[1] - parentLocation[1] + view.height
                )
                val intersection = Rect(dropRect)
                if (!intersection.intersect(bounds)) return@mapNotNull null
                NestedLinearCollision(node, linearParent, bounds, intersection.width() * intersection.height())
            }
            .maxByOrNull { it.intersectionArea }
            ?: return null
        val targetParentNode = findUiNodeParent(parentNode, collision.node.stableId) ?: return null
        val targetIndex = targetParentNode.children.indexOfFirst { it.stableId == collision.node.stableId }
        if (targetIndex < 0) return null
        val previewGap = (REFLOW_PREVIEW_GAP_DP * density).toInt()
        val shift = if (collision.parentView.orientation == LinearLayout.HORIZONTAL) {
            UiEditorPlacementOffset(draggedView.width + previewGap, 0)
        } else {
            UiEditorPlacementOffset(0, draggedView.height + previewGap)
        }
        val offsets = targetParentNode.children.drop(targetIndex).mapNotNull { sibling ->
            currentNodeViews[sibling.stableId]
                ?.takeIf { it.parent === collision.parentView }
                ?.let { sibling.stableId to shift }
        }.toMap()
        return ResolvedDragPlacement(
            flow = UiEditorParentFlow.FREEFORM,
            parentView = parentView,
            decision = UiEditorPlacementDecision(
                targetStableId = collision.node.stableId,
                snapLeft = collision.bounds.left,
                snapTop = collision.bounds.top,
                insertAfterTarget = false,
                siblingOffsets = offsets
            ),
            nestedTargetStableId = collision.node.stableId
        )
    }

    private fun pxPositionToMarginDp(positionPx: Int, parentPaddingPx: Int): Int =
        ((positionPx - parentPaddingPx).coerceAtLeast(0) / density).toInt()

    private fun avoidsUnnecessaryOverlap(node: UiNode, view: View, parent: ViewGroup): Boolean {
        if (node.locked) return false
        if (node.simpleTag in NON_OVERLAPPING_CONTROL_TAGS) return true
        if (node.simpleTag != "ImageView") return false
        val parentWidth = parent.width.coerceAtLeast(1)
        val parentHeight = parent.height.coerceAtLeast(1)
        return view.width <= parentWidth * COMPACT_IMAGE_MAX_PARENT_RATIO &&
            view.height <= parentHeight * COMPACT_IMAGE_MAX_PARENT_RATIO
    }

    private fun findUiNodeParent(root: UiNode, childStableId: String): UiNode? {
        if (root.children.any { it.stableId == childStableId }) return root
        return root.children.firstNotNullOfOrNull { child -> findUiNodeParent(child, childStableId) }
    }

    companion object {
        const val EXTRA_TASK_ID = "ui_editor_task_id"
        const val EXTRA_REVISION_LABEL = "ui_editor_revision_label"
        const val EXTRA_APP_NAME = "ui_editor_app_name"
        private const val STATE_LAYOUT_NAME = "ui_editor_layout_name"
        private const val STATE_LAYOUT_CONFIGURATION = "ui_editor_layout_configuration"
        private const val STATE_INTERACTION_MODE = "ui_editor_interaction_mode"
        private const val STATE_PALETTE_EXPANDED = "ui_editor_palette_expanded"
        private const val STATE_PROPERTY_PANEL_HEIGHT_DP = "ui_editor_property_panel_height_dp"
        private const val MENU_NEW_LAYOUT = 10_000
        private const val MENU_ADD_ELEMENT_BASE = 20_000
        private const val MENU_ADD_BASIC_GROUP = 30_001
        private const val MENU_ADD_CONTAINER_GROUP = 30_002
        private const val MENU_ADD_DATA_GROUP = 30_003
        private const val IME_SCROLL_DELAY_MILLIS = 120L
        private const val ELEMENT_PULSE_DURATION_MILLIS = 140L
        private const val SECTION_TOGGLE_DURATION_MILLIS = 160L
        private const val PROPERTY_PANEL_DEFAULT_HEIGHT_DP = 250
        private const val PROPERTY_PANEL_MIN_HEIGHT_DP = 160
        private const val PROPERTY_PANEL_MAX_HEIGHT_RATIO = 0.65f
        private const val COLOR_PICKER_COLUMN_COUNT = 5
        private const val COLOR_SWATCH_SIZE_DP = 44
        private const val COLOR_SWATCH_MARGIN_DP = 4
        private const val DRAG_FEEDBACK_ALPHA = 235
        private const val DRAG_SOURCE_ALPHA = 0.22f
        private const val MAX_DRAG_BITMAP_PIXELS = 1_500_000L
        private const val EDGE_SCROLL_ZONE_DP = 56
        private const val EDGE_SCROLL_MAX_STEP_DP = 8
        private const val REORDER_PREVIEW_DURATION_MILLIS = 110L
        private const val COMPACT_IMAGE_MAX_PARENT_RATIO = 0.5f
        private const val REFLOW_PREVIEW_GAP_DP = 8
        private val EDITOR_CONTENT_IDS = intArrayOf(
            R.id.uiEditorPaletteSection,
            R.id.uiEditorCanvasSection
        )
        private val PROPERTY_FIELD_IDS = intArrayOf(
            R.id.uiEditorElementText,
            R.id.uiEditorWidth,
            R.id.uiEditorHeight,
            R.id.uiEditorMarginStart,
            R.id.uiEditorMarginTop,
            R.id.uiEditorDescription
        )
        private val PICKER_COLOR_PATTERN = Regex("^#[0-9A-F]{6}(?:[0-9A-F]{2})?$")
        private val COLOR_PRESETS = listOf(
            "#17211B",
            "#FFFFFF",
            "#667085",
            "#D92D20",
            "#F79009",
            "#F8D648",
            "#1F6B4F",
            "#0E9384",
            "#1570EF",
            "#3538CD",
            "#7A5AF8",
            "#C11574",
            "#344054",
            "#EAECF0",
            "#00000000"
        )
        private val PLATFORM_ICONS = listOf(
            PlatformIconOption(
                R.string.ui_editor_icon_information,
                "ic_menu_info_details",
                android.R.drawable.ic_menu_info_details
            ),
            PlatformIconOption(R.string.ui_editor_icon_edit, "ic_menu_edit", android.R.drawable.ic_menu_edit),
            PlatformIconOption(R.string.ui_editor_icon_delete, "ic_menu_delete", android.R.drawable.ic_menu_delete),
            PlatformIconOption(R.string.ui_editor_icon_search, "ic_menu_search", android.R.drawable.ic_menu_search),
            PlatformIconOption(R.string.ui_editor_icon_share, "ic_menu_share", android.R.drawable.ic_menu_share),
            PlatformIconOption(R.string.ui_editor_icon_camera, "ic_menu_camera", android.R.drawable.ic_menu_camera),
            PlatformIconOption(R.string.ui_editor_icon_location, "ic_menu_mylocation", android.R.drawable.ic_menu_mylocation),
            PlatformIconOption(R.string.ui_editor_icon_save, "ic_menu_save", android.R.drawable.ic_menu_save),
            PlatformIconOption(R.string.ui_editor_icon_refresh, "ic_popup_sync", android.R.drawable.ic_popup_sync),
            PlatformIconOption(R.string.ui_editor_icon_help, "ic_menu_help", android.R.drawable.ic_menu_help)
        )
        private val TEXT_TAGS = setOf(
            "TextView",
            "MaterialTextView",
            "Button",
            "MaterialButton",
            "EditText",
            "TextInputEditText",
            "CheckBox",
            "Switch",
            "SwitchCompat",
            "RadioButton"
        )
        private val IMAGE_TAGS = setOf("ImageView", "ImageButton")
        private val NON_OVERLAPPING_CONTROL_TAGS = setOf(
            "TextView",
            "MaterialTextView",
            "Button",
            "MaterialButton",
            "EditText",
            "TextInputEditText",
            "CheckBox",
            "Switch",
            "SwitchCompat",
            "RadioButton",
            "ImageButton",
            "FloatingActionButton",
            "ProgressBar"
        )
    }

    private data class ResolvedDragPlacement(
        val flow: UiEditorParentFlow,
        val parentView: ViewGroup,
        val decision: UiEditorPlacementDecision,
        val nestedTargetStableId: String? = null
    )

    private data class NestedLinearCollision(
        val node: UiNode,
        val parentView: LinearLayout,
        val bounds: Rect,
        val intersectionArea: Int
    )

    private data class PlatformIconOption(
        val labelRes: Int,
        val resourceName: String,
        val drawableRes: Int
    )

    private enum class CanvasInteractionMode {
        SCROLL,
        MOVE
    }

    private inner class ElementDragFeedback(
        val canvas: FrameLayout,
        val initialBounds: Rect,
        private val source: View,
        private val sourceAlpha: Float,
        private val bitmap: Bitmap,
        private val drawable: BitmapDrawable
    ) {
        fun moveTo(deltaX: Float, deltaY: Float) {
            drawable.bounds = Rect(initialBounds).apply {
                offset(deltaX.toInt(), deltaY.toInt())
            }
            canvas.invalidate()
        }

        fun dispose() {
            canvas.overlay.remove(drawable)
            source.alpha = sourceAlpha
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun createElementDragFeedback(view: View): ElementDragFeedback? {
        if (view.width <= 0 || view.height <= 0) return null
        val canvas = findViewById<FrameLayout>(R.id.uiEditorCanvas)
        val sourceLocation = IntArray(2).also(view::getLocationInWindow)
        val canvasLocation = IntArray(2).also(canvas::getLocationInWindow)
        val bounds = Rect(
            sourceLocation[0] - canvasLocation[0],
            sourceLocation[1] - canvasLocation[1],
            sourceLocation[0] - canvasLocation[0] + view.width,
            sourceLocation[1] - canvasLocation[1] + view.height
        )
        return runCatching {
            val sourcePixels = view.width.toLong() * view.height.toLong()
            val bitmapScale = if (sourcePixels > MAX_DRAG_BITMAP_PIXELS) {
                kotlin.math.sqrt(MAX_DRAG_BITMAP_PIXELS.toDouble() / sourcePixels.toDouble()).toFloat()
            } else {
                1f
            }
            val bitmap = Bitmap.createBitmap(
                (view.width * bitmapScale).toInt().coerceAtLeast(1),
                (view.height * bitmapScale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            Canvas(bitmap).apply {
                scale(bitmapScale, bitmapScale)
                view.draw(this)
            }
            val drawable = BitmapDrawable(resources, bitmap).apply {
                alpha = DRAG_FEEDBACK_ALPHA
                this.bounds = Rect(bounds)
            }
            val sourceAlpha = view.alpha
            view.alpha = sourceAlpha * DRAG_SOURCE_ALPHA
            canvas.overlay.add(drawable)
            ElementDragFeedback(canvas, bounds, view, sourceAlpha, bitmap, drawable)
        }.getOrNull()
    }

    private inner class ElementSelectionTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false
        private val touchSlop = ViewConfiguration.get(this@UiEditorActivity).scaledTouchSlop

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (
                        kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                        kotlin.math.abs(event.rawY - downRawY) > touchSlop
                    ) {
                        moved = true
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private inner class ElementDragTouchListener(
        private val stableId: String,
        private val movementLocked: Boolean
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var translatedX = 0f
        private var translatedY = 0f
        private var dragging = false
        private var feedback: ElementDragFeedback? = null
        private var draggedView: View? = null
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var initialHorizontalScroll = 0
        private var initialVerticalScroll = 0
        private var edgeScrollPosted = false
        private var currentPlacement: ResolvedDragPlacement? = null
        private var previewTargetStableId: String? = null
        private val previewOffsets = mutableMapOf<View, UiEditorPlacementOffset>()
        private val touchSlop = ViewConfiguration.get(this@UiEditorActivity).scaledTouchSlop
        private val edgeScrollRunnable = object : Runnable {
            override fun run() {
                edgeScrollPosted = false
                val view = draggedView ?: return
                if (!dragging) return
                val container = findViewById<FrameLayout>(R.id.uiEditorCanvasContainer)
                val visibleBounds = Rect()
                if (!container.getGlobalVisibleRect(visibleBounds)) return
                val horizontalViewport = findViewById<HorizontalScrollView>(R.id.uiEditorCanvasViewport)
                val verticalViewport = findViewById<ScrollView>(R.id.uiEditorCanvasVerticalViewport)
                val edgeSize = (EDGE_SCROLL_ZONE_DP * density).toInt().coerceAtLeast(1)
                val maxStep = (EDGE_SCROLL_MAX_STEP_DP * density).toInt().coerceAtLeast(1)
                val horizontalStep = UiEditorEdgeScrollPolicy.step(
                    pointer = lastRawX,
                    viewportStart = visibleBounds.left,
                    viewportEnd = visibleBounds.right,
                    edgeSize = edgeSize,
                    maxStep = maxStep
                )
                val verticalStep = UiEditorEdgeScrollPolicy.step(
                    pointer = lastRawY,
                    viewportStart = visibleBounds.top,
                    viewportEnd = visibleBounds.bottom,
                    edgeSize = edgeSize,
                    maxStep = maxStep
                )
                if (horizontalStep != 0) horizontalViewport.scrollBy(horizontalStep, 0)
                if (verticalStep != 0) verticalViewport.scrollBy(0, verticalStep)
                updateDragTranslation(view)

                val keepHorizontal = horizontalStep != 0 &&
                    horizontalViewport.canScrollHorizontally(horizontalStep.sign())
                val keepVertical = verticalStep != 0 &&
                    verticalViewport.canScrollVertically(verticalStep.sign())
                if (keepHorizontal || keepVertical) postEdgeScrollFrame(container)
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    translatedX = 0f
                    translatedY = 0f
                    dragging = false
                    currentPlacement = null
                    previewTargetStableId = null
                    clearPlacementPreview()
                    draggedView = view
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    initialHorizontalScroll = findViewById<HorizontalScrollView>(R.id.uiEditorCanvasViewport).scrollX
                    initialVerticalScroll = findViewById<ScrollView>(R.id.uiEditorCanvasVerticalViewport).scrollY
                    if (!movementLocked) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    viewModel.session?.selectedElementId = stableId
                    highlightSelection(currentNodeViews)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (movementLocked) return true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    val deltaX = lastRawX - downRawX
                    val deltaY = lastRawY - downRawY
                    if (!dragging && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                        dragging = true
                        feedback = createElementDragFeedback(view)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (dragging) {
                        updateDragTranslation(view)
                        postEdgeScrollFrame(findViewById(R.id.uiEditorCanvasContainer))
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    stopEdgeScroll()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging && !movementLocked) {
                        val placement = currentPlacement
                            ?: resolveDragPlacement(stableId, view, translatedX, translatedY)
                        clearPlacementPreview()
                        feedback?.dispose()
                        feedback = null
                        val session = viewModel.session ?: return true
                        val before = session.snapshot()
                        resetDraggedView(view)
                        val changed = placement?.let {
                            commitDragPlacement(session, stableId, view, it)
                        } ?: UiDocumentEditor.moveBy(
                            session.document,
                            stableId,
                            (translatedX / density).toInt(),
                            (translatedY / density).toInt(),
                            renderedWidthDp = (view.width / density).toInt(),
                            renderedHeightDp = (view.height / density).toInt()
                        )
                        if (changed) {
                            recordAndRender(before, stableId)
                        } else {
                            resetDraggedView(view)
                        }
                    } else {
                        feedback?.dispose()
                        feedback = null
                        resetDraggedView(view)
                        view.performClick()
                    }
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopEdgeScroll()
                    clearPlacementPreview()
                    feedback?.dispose()
                    feedback = null
                    resetDraggedView(view)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    return true
                }
            }
            return false
        }

        private fun updateDragTranslation(view: View) {
            val horizontalViewport = findViewById<HorizontalScrollView>(R.id.uiEditorCanvasViewport)
            val verticalViewport = findViewById<ScrollView>(R.id.uiEditorCanvasVerticalViewport)
            val deltaX = lastRawX - downRawX + horizontalViewport.scrollX - initialHorizontalScroll
            val deltaY = lastRawY - downRawY + verticalViewport.scrollY - initialVerticalScroll
            val bounds = feedback?.initialBounds
            val canvas = feedback?.canvas
            translatedX = clampedTranslation(
                delta = deltaX,
                minimum = -(bounds?.left ?: view.left).toFloat(),
                maximum = ((canvas?.width ?: Int.MAX_VALUE) - (bounds?.right ?: view.right)).toFloat()
            )
            translatedY = clampedTranslation(
                delta = deltaY,
                minimum = -(bounds?.top ?: view.top).toFloat(),
                maximum = ((canvas?.height ?: Int.MAX_VALUE) - (bounds?.bottom ?: view.bottom)).toFloat()
            )
            feedback?.moveTo(translatedX, translatedY) ?: run {
                view.translationX = translatedX
                view.translationY = translatedY
                view.translationZ = 8 * density
            }
            updatePlacementPreview(view)
        }

        private fun updatePlacementPreview(view: View) {
            val placement = resolveDragPlacement(stableId, view, translatedX, translatedY) ?: return
            val targetStableId = placement.decision.targetStableId
            if (targetStableId != previewTargetStableId && targetStableId != null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            previewTargetStableId = targetStableId
            currentPlacement = placement

            val desiredOffsets = placement.decision.siblingOffsets.mapNotNull { (siblingId, offset) ->
                currentNodeViews[siblingId]?.let { sibling -> sibling to offset }
            }.toMap()
            previewOffsets.keys.minus(desiredOffsets.keys).forEach { sibling ->
                sibling.animate().cancel()
                sibling.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(REORDER_PREVIEW_DURATION_MILLIS)
                    .start()
                previewOffsets.remove(sibling)
            }
            desiredOffsets.forEach { (sibling, offset) ->
                if (previewOffsets[sibling] == offset) return@forEach
                sibling.animate().cancel()
                sibling.animate()
                    .translationX(offset.deltaX.toFloat())
                    .translationY(offset.deltaY.toFloat())
                    .setDuration(REORDER_PREVIEW_DURATION_MILLIS)
                    .start()
                previewOffsets[sibling] = offset
            }
        }

        private fun clearPlacementPreview() {
            previewOffsets.keys.toList().forEach { sibling ->
                sibling.animate().cancel()
                sibling.translationX = 0f
                sibling.translationY = 0f
            }
            previewOffsets.clear()
            currentPlacement = null
            previewTargetStableId = null
        }

        private fun postEdgeScrollFrame(host: View) {
            if (edgeScrollPosted || !dragging) return
            edgeScrollPosted = true
            ViewCompat.postOnAnimation(host, edgeScrollRunnable)
        }

        private fun stopEdgeScroll() {
            findViewById<View>(R.id.uiEditorCanvasContainer).removeCallbacks(edgeScrollRunnable)
            edgeScrollPosted = false
            draggedView = null
        }

        private fun resetDraggedView(view: View) {
            view.translationX = 0f
            view.translationY = 0f
            view.translationZ = 0f
        }

        private fun clampedTranslation(delta: Float, minimum: Float, maximum: Float): Float {
            return if (minimum <= maximum) delta.coerceIn(minimum, maximum) else 0f
        }

        private fun Int.sign(): Int = if (this < 0) -1 else 1
    }
}
