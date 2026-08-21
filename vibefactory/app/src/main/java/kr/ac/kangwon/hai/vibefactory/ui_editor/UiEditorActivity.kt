package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import kr.ac.kangwon.hai.vibefactory.UiEditorSubmitRequestDto
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
        applyEditorWindowInsets()
        bindPropertyFieldFocusHandling()

        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        revisionLabel = intent.getStringExtra(EXTRA_REVISION_LABEL).orEmpty().trim()
        appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().trim()
        if (taskId.isBlank() || revisionLabel.isBlank()) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnBackUiEditor).setOnClickListener { finish() }
        findViewById<TextView>(R.id.uiEditorTitle).text = appName.ifBlank { getString(R.string.ui_editor_title) }
        findViewById<TextView>(R.id.uiEditorRevision).text = revisionLabel
        findViewById<Button>(R.id.btnUiEditorScreen).setOnClickListener { anchor -> showLayoutMenu(anchor) }
        findViewById<Button>(R.id.btnUiEditorRetry).setOnClickListener { loadLayouts() }
        findViewById<Button>(R.id.btnUiEditorUndo).setOnClickListener { undo() }
        findViewById<Button>(R.id.btnUiEditorRedo).setOnClickListener { redo() }
        findViewById<Button>(R.id.btnUiEditorSave).setOnClickListener { persistDraft(showConfirmation = true) }
        findViewById<Button>(R.id.btnUiEditorSubmit).setOnClickListener { confirmSubmitDraft() }
        findViewById<Button>(R.id.btnUiEditorApply).setOnClickListener { applySelectedProperties() }
        findViewById<Button>(R.id.btnUiEditorDuplicate).setOnClickListener { duplicateSelected() }
        findViewById<Button>(R.id.btnUiEditorDelete).setOnClickListener { confirmDeleteSelected() }
        findViewById<Button>(R.id.btnUiEditorAttachImage).setOnClickListener { selectImageForCurrentElement() }
        findViewById<Button>(R.id.btnUiEditorLayers).setOnClickListener { showLayerMenu(it) }
        findViewById<Button>(R.id.btnUiEditorLayerBack).setOnClickListener { reorderSelected(false) }
        findViewById<Button>(R.id.btnUiEditorLayerFront).setOnClickListener { reorderSelected(true) }
        findViewById<Button>(R.id.btnUiEditorDoneEditing).setOnClickListener { finishPropertyEditing() }
        canvasInteractionMode = savedInstanceState?.getString(STATE_INTERACTION_MODE)
            ?.let { saved -> CanvasInteractionMode.entries.firstOrNull { it.name == saved } }
            ?: CanvasInteractionMode.SCROLL
        bindCanvasInteractionMode()
        bindPalette()
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
        val panel = findViewById<ScrollView>(R.id.uiEditorPropertiesPanel)
        val params = panel.layoutParams as LinearLayout.LayoutParams
        params.height = if (enabled) 0 else (PROPERTY_PANEL_HEIGHT_DP * density).toInt()
        params.weight = if (enabled) 1f else 0f
        panel.layoutParams = params
        if (!enabled) renderCurrentSession()
    }

    private fun bindPropertyFieldFocusHandling() {
        PROPERTY_FIELD_IDS.forEach { viewId ->
            findViewById<EditText>(viewId).setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) scrollFocusedPropertyIntoView()
            }
        }
    }

    private fun scrollFocusedPropertyIntoView() {
        val focused = currentFocus ?: return
        val panel = findViewById<ScrollView>(R.id.uiEditorPropertiesPanel)
        if (!focused.isDescendantOf(panel)) return
        panel.postDelayed({
            if (!focused.isAttachedToWindow || !focused.hasFocus()) return@postDelayed
            val bounds = Rect(0, 0, focused.width, focused.height)
            panel.offsetDescendantRectToMyCoords(focused, bounds)
            panel.smoothScrollTo(0, (bounds.top - (12 * density).toInt()).coerceAtLeast(0))
        }, IME_SCROLL_DELAY_MILLIS)
    }

    private fun finishPropertyEditing() {
        applySelectedProperties()
        currentFocus?.clearFocus()
        WindowCompat.getInsetsController(window, findViewById(R.id.uiEditorRoot))
            .hide(WindowInsetsCompat.Type.ime())
        viewModel.session?.selectedElementId = null
        highlightSelection(currentNodeViews)
        bindSelectedElement()
        scheduleDraftSave()
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
        updateCanvasInteractionModeUi()
        attachElementInteractions(currentNodeViews)
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
                viewModel.layouts = response.layouts
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
            viewModel.layouts.forEachIndexed { index, layout ->
                menu.add(0, index, index, layoutDisplayName(layout))
            }
            menu.add(0, MENU_NEW_LAYOUT, viewModel.layouts.size, getString(R.string.ui_editor_new_layout))
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_NEW_LAYOUT) {
                    showNewLayoutDialog()
                    true
                } else {
                    viewModel.layouts.getOrNull(item.itemId)?.let(::loadLayout) != null
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
                    null
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
        val session = viewModel.session ?: return
        val node = session.selectedElementId?.let { selected ->
            session.document.root.descendantsAndSelf().firstOrNull { it.stableId == selected }
        }
        val panel = findViewById<View>(R.id.uiEditorPropertiesPanel)
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
        findViewById<EditText>(R.id.uiEditorElementText).apply {
            visibility = if (supportsText) View.VISIBLE else View.GONE
            setText(node.androidAttribute("text") ?: node.androidAttribute("hint").orEmpty())
        }
        findViewById<EditText>(R.id.uiEditorWidth).setText(node.androidAttribute("layout_width").orEmpty())
        findViewById<EditText>(R.id.uiEditorHeight).setText(node.androidAttribute("layout_height").orEmpty())
        findViewById<EditText>(R.id.uiEditorMarginStart).setText(dpValue(node.androidAttribute("layout_marginStart")))
        findViewById<EditText>(R.id.uiEditorMarginTop).setText(dpValue(node.androidAttribute("layout_marginTop")))
        findViewById<EditText>(R.id.uiEditorTextColor).apply {
            visibility = if (supportsText) View.VISIBLE else View.GONE
            setText(node.androidAttribute("textColor").orEmpty())
        }
        findViewById<EditText>(R.id.uiEditorBackground).setText(node.androidAttribute("background").orEmpty())
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
        findViewById<Button>(R.id.btnUiEditorSave).isEnabled = viewModel.session != null
        findViewById<Button>(R.id.btnUiEditorSubmit).isEnabled = viewModel.session != null && !isSubmitting
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

    private fun confirmSubmitDraft() {
        if (isSubmitting || viewModel.session == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_editor_submit_title)
            .setMessage(R.string.ui_editor_submit_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_editor_submit) { _, _ -> submitDraft() }
            .show()
    }

    private fun submitDraft() {
        val session = viewModel.session ?: return
        draftSaveJob?.cancel()
        val preview = captureCanvasPreview()
        isSubmitting = true
        updateHistoryActions()
        showLoading(getString(R.string.ui_editor_submitting))
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
                    apiService.submitUiEditorDraft(
                        taskId = session.taskId,
                        revisionLabel = session.revisionLabel,
                        draftId = session.serverDraftId ?: error("server draft ID is missing"),
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber(),
                        request = UiEditorSubmitRequestDto(
                            expected_version = uploadedVersion,
                            preview_image_base64 = preview
                        )
                    )
                }
            }
            result.onSuccess {
                withContext(Dispatchers.IO) {
                    draftStore.save(draftStore.recordFor(session, status = "submitting"))
                }
                Toast.makeText(this@UiEditorActivity, R.string.ui_editor_submitted, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                isSubmitting = false
                updateHistoryActions()
                renderCurrentSession()
                Toast.makeText(
                    this@UiEditorActivity,
                    error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.ui_editor_submit_failed),
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
        val resolved = if (localIsNewer) {
            local!!.copy(
                images = local.images.map { localImage ->
                    remote.images.firstOrNull { it.image_id == localImage.imageId }
                        ?.let { localImage.copy(serverWorkspacePath = it.workspace_path) }
                        ?: localImage
                },
                serverDraftId = remote.draft_id,
                serverDraftVersion = remote.version
            )
        } else {
            remoteRecord
        }
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
        findViewById<ProgressBar>(R.id.uiEditorProgress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.uiEditorStateText).apply {
            visibility = View.VISIBLE
            text = message
        }
        findViewById<Button>(R.id.btnUiEditorRetry).visibility = View.GONE
        findViewById<View>(R.id.uiEditorCanvasViewport).visibility = View.INVISIBLE
    }

    private fun showError(message: String) {
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
        findViewById<View>(R.id.uiEditorCanvasViewport).visibility = View.VISIBLE
    }

    private fun layoutDisplayName(layout: UiLayoutSummaryDto): String =
        if (layout.configuration == "layout") layout.layout_name
        else "${layout.layout_name} · ${layout.configuration.removePrefix("layout-")}"

    companion object {
        const val EXTRA_TASK_ID = "ui_editor_task_id"
        const val EXTRA_REVISION_LABEL = "ui_editor_revision_label"
        const val EXTRA_APP_NAME = "ui_editor_app_name"
        private const val STATE_LAYOUT_NAME = "ui_editor_layout_name"
        private const val STATE_LAYOUT_CONFIGURATION = "ui_editor_layout_configuration"
        private const val STATE_INTERACTION_MODE = "ui_editor_interaction_mode"
        private const val MENU_NEW_LAYOUT = 10_000
        private const val MENU_ADD_ELEMENT_BASE = 20_000
        private const val MENU_ADD_BASIC_GROUP = 30_001
        private const val MENU_ADD_CONTAINER_GROUP = 30_002
        private const val MENU_ADD_DATA_GROUP = 30_003
        private const val IME_SCROLL_DELAY_MILLIS = 120L
        private const val ELEMENT_PULSE_DURATION_MILLIS = 140L
        private const val PROPERTY_PANEL_HEIGHT_DP = 250
        private const val DRAG_FEEDBACK_ALPHA = 235
        private const val DRAG_SOURCE_ALPHA = 0.22f
        private const val MAX_DRAG_BITMAP_PIXELS = 1_500_000L
        private val EDITOR_CONTENT_IDS = intArrayOf(
            R.id.uiEditorActionToolbar,
            R.id.btnUiEditorSubmit,
            R.id.uiEditorCanvasMeta,
            R.id.uiEditorInteractionMode,
            R.id.uiEditorPaletteToolbar,
            R.id.uiEditorCanvasContainer
        )
        private val PROPERTY_FIELD_IDS = intArrayOf(
            R.id.uiEditorElementText,
            R.id.uiEditorWidth,
            R.id.uiEditorHeight,
            R.id.uiEditorMarginStart,
            R.id.uiEditorMarginTop,
            R.id.uiEditorTextColor,
            R.id.uiEditorBackground,
            R.id.uiEditorDescription
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
    }

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
        private val touchSlop = ViewConfiguration.get(this@UiEditorActivity).scaledTouchSlop

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    translatedX = 0f
                    translatedY = 0f
                    dragging = false
                    if (!movementLocked) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    viewModel.session?.selectedElementId = stableId
                    highlightSelection(currentNodeViews)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (movementLocked) return true
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                        dragging = true
                        feedback = createElementDragFeedback(view)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (dragging) {
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
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging && !movementLocked) {
                        feedback?.dispose()
                        feedback = null
                        val session = viewModel.session ?: return true
                        val before = session.snapshot()
                        if (
                            UiDocumentEditor.moveBy(
                                session.document,
                                stableId,
                                (translatedX / density).toInt(),
                                (translatedY / density).toInt(),
                                renderedWidthDp = (view.width / density).toInt(),
                                renderedHeightDp = (view.height / density).toInt()
                            )
                        ) {
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

        private fun resetDraggedView(view: View) {
            view.translationX = 0f
            view.translationY = 0f
            view.translationZ = 0f
        }

        private fun clampedTranslation(delta: Float, minimum: Float, maximum: Float): Float {
            return if (minimum <= maximum) delta.coerceIn(minimum, maximum) else 0f
        }
    }
}
