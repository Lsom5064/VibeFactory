package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.GsonBuilder
import kr.ac.kangwon.hai.vibefactory.HostPreferencesStore
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.SelectedAttachmentKind
import kr.ac.kangwon.hai.vibefactory.UiEditorDraftDto
import kr.ac.kangwon.hai.vibefactory.UiEditorDraftRequestDto
import kr.ac.kangwon.hai.vibefactory.UiEditorImageDto
import kr.ac.kangwon.hai.vibefactory.UiEditorImageUploadRequestDto
import kr.ac.kangwon.hai.vibefactory.UiEditorSaveRequestDto
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kr.ac.kangwon.hai.vibefactory.buildSelectedAttachment
import kr.ac.kangwon.hai.vibefactory.createVibeApiService
import kr.ac.kangwon.hai.vibefactory.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

class UiAnnotationEditorActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()
    private val apiService by lazy { createVibeApiService(gson) }
    private val preferencesStore by lazy { HostPreferencesStore(this, gson, "UiAnnotationEditorActivity") }
    private val draftStore by lazy { UiAnnotationDraftStore(this, gson) }
    private val viewModel by lazy { ViewModelProvider(this)[UiAnnotationViewModel::class.java] }
    private val density by lazy { resources.displayMetrics.density }

    private var taskId = ""
    private var revisionLabel = ""
    private var appName = ""
    private var selectedLayout: UiLayoutSummaryDto? = null
    private var restoredLayoutName = ""
    private var restoredConfiguration = "layout"
    private var currentNodeViews: Map<String, View> = emptyMap()
    private var currentNodes: Map<String, UiNode> = emptyMap()
    private var currentTargetHits: List<UiAnnotationTargetHit> = emptyList()
    private var overlay: UiAnnotationOverlayView? = null
    private var movePreview: UiMovePreviewLayout? = null
    private var previewDestination: Pair<Float, Float>? = null
    private var previewFramePending = false
    private var restoredArmedAction: UiAnnotationAction? = null
    private var restoredDeleteSelection: List<UiAnnotationTarget>? = null
    private var restoredMove: UiAnnotation? = null
    private var remoteSaveJob: Job? = null
    private var isSaving = false
    private val canvasPreviewBitmaps = mutableMapOf<String, Bitmap>()
    private var armedAction: UiAnnotationAction? = null
    private var pendingMoveSource: UiAnnotationTarget? = null
    private var moveEdgeScroller: UiMoveEdgeScroller? = null
    private var loadGeneration = 0
    private var toolbarCollapsed = false
    private var restoredToolbarX = 0f
    private var restoredToolbarY = 0f
    private var instructionEditorState: InstructionEditorState? = null
    private var instructionDialog: BottomSheetDialog? = null
    private var instructionInput: EditText? = null
    private var instructionImagesCommitted = false
    private var additionDialog: UiVisualChangeDialog? = null

    private val instructionImagePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) addInstructionImages(uris)
        }

    private val visualImagePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) addVisualImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ui_annotation_editor)
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        revisionLabel = intent.getStringExtra(EXTRA_REVISION_LABEL).orEmpty().trim()
        appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().trim()
        if (taskId.isBlank() || revisionLabel.isBlank()) {
            finish()
            return
        }
        restoredLayoutName = savedInstanceState?.getString(STATE_LAYOUT_NAME).orEmpty()
        restoredConfiguration = savedInstanceState?.getString(STATE_CONFIGURATION).orEmpty().ifBlank { "layout" }
        armedAction = savedInstanceState?.getString(STATE_ARMED_ACTION)
            ?.let(UiAnnotationAction::fromWireName)
        restoredArmedAction = armedAction
        restoredDeleteSelection = savedInstanceState?.getString("delete_selection")?.let {
            runCatching { gson.fromJson(it, Array<UiAnnotationTarget>::class.java).toList() }.getOrNull()
        }
        restoredMove = savedInstanceState?.getString("pending_move")?.let {
            runCatching { gson.fromJson(it, UiAnnotation::class.java) }.getOrNull()
        }
        toolbarCollapsed = savedInstanceState?.getBoolean(STATE_TOOLBAR_COLLAPSED, false) ?: false
        restoredToolbarX = savedInstanceState?.getFloat(STATE_TOOLBAR_X, 0f) ?: 0f
        restoredToolbarY = savedInstanceState?.getFloat(STATE_TOOLBAR_Y, 0f) ?: 0f

        applyWindowInsets()
        bindActions()
        bindFloatingToolbar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        findViewById<TextView>(R.id.uiAnnotationTitle).text = appName.ifBlank {
            getString(R.string.ui_editor_title)
        }
        findViewById<TextView>(R.id.uiAnnotationRevision).text = revisionLabel

        val retained = viewModel.session
        if (retained != null && retained.taskId == taskId && retained.revisionLabel == revisionLabel) {
            selectedLayout = retained.layout
            showLayoutName(retained.layout)
            renderSession()
        } else {
            loadLayouts()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedLayout?.let {
            outState.putString(STATE_LAYOUT_NAME, it.layout_name)
            outState.putString(STATE_CONFIGURATION, it.configuration)
        }
        outState.putString(STATE_ARMED_ACTION, armedAction?.wireName)
        outState.putString("delete_selection", gson.toJson(viewModel.session?.selectedDeleteTargets.orEmpty()))
        pendingMoveSource?.let { source ->
            outState.putString("pending_move", gson.toJson(UiAnnotation(action = UiAnnotationAction.MOVE,
                target = source, destinationX = previewDestination?.first, destinationY = previewDestination?.second)))
        }
        val toolbar = findViewById<View>(R.id.uiAnnotationFloatingToolbar)
        outState.putBoolean(STATE_TOOLBAR_COLLAPSED, toolbarCollapsed)
        outState.putFloat(STATE_TOOLBAR_X, toolbar.translationX)
        outState.putFloat(STATE_TOOLBAR_Y, toolbar.translationY)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopEdgeAutoScroll()
        persistLocal()
        super.onStop()
    }

    override fun onDestroy() {
        additionDialog?.dismiss()
        additionDialog = null
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.uiAnnotationRoot)
        val initial = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initial.left + bars.left,
                top = initial.top + bars.top,
                right = initial.right + bars.right,
                bottom = initial.bottom + bars.bottom
            )
            insets
        }
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.btnBackUiAnnotation).setOnClickListener { handleBack() }
        findViewById<Button>(R.id.btnUiAnnotationScreen).setOnClickListener(::showLayoutMenu)
        findViewById<Button>(R.id.btnUiAnnotationRetry).setOnClickListener { loadLayouts() }
        findViewById<ImageButton>(R.id.btnUiAnnotationUndo).setOnClickListener { undo() }
        findViewById<ImageButton>(R.id.btnUiAnnotationRedo).setOnClickListener { redo() }
        findViewById<Button>(R.id.btnUiAnnotationSave).setOnClickListener { saveForChat() }
        findViewById<ImageButton>(R.id.btnUiAnnotationList).setOnClickListener { showAnnotationList() }
        findViewById<ImageButton>(R.id.btnUiAnnotationClear).setOnClickListener { confirmClear() }
        findViewById<ImageButton>(R.id.btnCancelUiAnnotationAction).setOnClickListener { cancelCurrentAction() }
        bindTool(R.id.btnUiAnnotationDeleteTool, UiAnnotationAction.DELETE)
        bindTool(R.id.btnUiAnnotationMoveTool, UiAnnotationAction.MOVE)
        bindTool(R.id.btnUiAnnotationBehaviorTool, UiAnnotationAction.BEHAVIOR)
        bindTool(R.id.btnUiAnnotationAddTool, UiAnnotationAction.ADD)
        findViewById<Button>(R.id.btnUiAnnotationDraw).setOnClickListener { openAdditionEditor() }
        findViewById<Button>(R.id.btnUiAnnotationConfirmMove).setOnClickListener { openMoveInstructionDialog() }
        findViewById<Button>(R.id.btnUiAnnotationDeleteSelected).setOnClickListener { applyDeleteSelection() }
        updateActions()
    }

    private fun bindFloatingToolbar() {
        val toolbar = findViewById<View>(R.id.uiAnnotationFloatingToolbar)
        val workspace = findViewById<ViewGroup>(R.id.uiAnnotationWorkspace)
        val handle = findViewById<ImageButton>(R.id.btnUiAnnotationToolbarHandle)
        var downRawX = 0f
        var downRawY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTranslationX = toolbar.translationX
                    startTranslationY = toolbar.translationY
                    handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    toolbar.translationX = startTranslationX + event.rawX - downRawX
                    toolbar.translationY = startTranslationY + event.rawY - downRawY
                    clampToolbar(toolbar, workspace)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampToolbar(toolbar, workspace)
                    true
                }
                else -> false
            }
        }
        findViewById<ImageButton>(R.id.btnUiAnnotationCollapse).setOnClickListener {
            toolbarCollapsed = !toolbarCollapsed
            updateToolbarCollapsedState()
        }
        workspace.post {
            toolbar.translationX = restoredToolbarX
            toolbar.translationY = restoredToolbarY
            updateToolbarCollapsedState()
            clampToolbar(toolbar, workspace)
        }
    }

    private fun updateToolbarCollapsedState() {
        val group = findViewById<View>(R.id.uiAnnotationToolGroup)
        val collapse = findViewById<ImageButton>(R.id.btnUiAnnotationCollapse)
        group.visibility = if (toolbarCollapsed) View.GONE else View.VISIBLE
        collapse.rotation = if (toolbarCollapsed) 90f else -90f
        collapse.contentDescription = getString(R.string.ui_annotation_collapse_toolbar)
        findViewById<ViewGroup>(R.id.uiAnnotationWorkspace).post {
            clampToolbar(
                findViewById(R.id.uiAnnotationFloatingToolbar),
                findViewById(R.id.uiAnnotationWorkspace)
            )
        }
    }

    private fun clampToolbar(toolbar: View, workspace: ViewGroup) {
        if (toolbar.width <= 0 || workspace.width <= 0) return
        val minimumX = -toolbar.left.toFloat()
        val maximumX = (workspace.width - toolbar.width - toolbar.left).toFloat().coerceAtLeast(minimumX)
        val minimumY = -toolbar.top.toFloat()
        val maximumY = (workspace.height - toolbar.height - toolbar.top).toFloat().coerceAtLeast(minimumY)
        toolbar.translationX = toolbar.translationX.coerceIn(minimumX, maximumX)
        toolbar.translationY = toolbar.translationY.coerceIn(minimumY, maximumY)
    }

    private fun bindTool(viewId: Int, action: UiAnnotationAction) {
        findViewById<ImageButton>(viewId).setOnClickListener { armTool(action) }
    }

    private fun armTool(action: UiAnnotationAction) {
        cancelCurrentAction(clearBanner = false)
        armedAction = action
        overlay?.enableTargetSelection(true)
        updateInstruction(actionHint(action), true)
        updateDeleteSelection()
        updateActions()
    }

    private fun actionHint(action: UiAnnotationAction): String = getString(
        when (action) {
            UiAnnotationAction.DELETE -> R.string.ui_annotation_delete_hint
            UiAnnotationAction.MOVE -> R.string.ui_annotation_move_hint
            UiAnnotationAction.BEHAVIOR -> R.string.ui_annotation_behavior_hint
            UiAnnotationAction.ADD -> R.string.ui_annotation_add_hint
        }
    )

    private fun loadLayouts() {
        val generation = ++loadGeneration
        showLoading(getString(R.string.ui_editor_loading_layouts))
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    apiService.getRevisionUiLayouts(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }.onSuccess { response ->
                if (generation != loadGeneration) return@onSuccess
                if (!response.source_available) {
                    showError(response.unavailable_reason.ifBlank { getString(R.string.ui_editor_source_unavailable) })
                    return@onSuccess
                }
                viewModel.setLayouts(response.layouts)
                val layout = response.layouts.firstOrNull {
                    it.layout_name == restoredLayoutName && it.configuration == restoredConfiguration
                } ?: response.layouts.firstOrNull {
                    it.layout_name == "activity_main" && it.configuration == "layout"
                } ?: response.layouts.firstOrNull()
                if (layout == null) showError(getString(R.string.ui_editor_no_layouts)) else loadLayout(layout)
            }.onFailure {
                if (generation == loadGeneration) showError(it.message ?: getString(R.string.ui_editor_load_failed))
            }
        }
    }

    private fun showLayoutMenu(anchor: View) {
        val layouts = viewModel.layouts
        if (layouts.isEmpty()) return
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
            setOnMenuItemClickListener { item -> layoutsByItemId[item.itemId]?.let(::loadLayout) != null }
            show()
        }
    }

    private fun loadLayout(layout: UiLayoutSummaryDto) {
        if (isSaving) return
        persistLocal()
        remoteSaveJob?.cancel()
        selectedLayout = layout
        showLayoutName(layout)
        viewModel.restoreCachedSession(taskId, revisionLabel, layout)?.let {
            loadGeneration += 1
            cancelCurrentAction(clearPendingAddition = false)
            renderSession()
            return
        }
        val generation = ++loadGeneration
        showLoading(getString(R.string.ui_editor_loading_preview))
        lifecycleScope.launch {
            val result = runSuspendCatching {
                coroutineScope {
                    val deviceId = preferencesStore.getOrCreateDeviceId()
                    val phoneNumber = preferencesStore.loadPhoneNumber()
                    val documentRequest = async(Dispatchers.IO) {
                        apiService.getRevisionUiLayout(
                            taskId = taskId,
                            revisionLabel = revisionLabel,
                            layoutName = layout.layout_name,
                            configuration = layout.configuration,
                            deviceId = deviceId,
                            userId = null,
                            phoneNumber = phoneNumber
                        )
                    }
                    val localRequest = async(Dispatchers.IO) {
                        draftStore.load(taskId, revisionLabel, layout.layout_name, layout.configuration)
                    }
                    val remoteRequest = async(Dispatchers.IO) {
                        runSuspendCatching {
                            apiService.getUiEditorDraft(
                                taskId = taskId,
                                revisionLabel = revisionLabel,
                                layoutName = layout.layout_name,
                                configuration = layout.configuration,
                                deviceId = deviceId,
                                userId = null,
                                phoneNumber = phoneNumber
                            )
                        }.getOrNull()
                    }
                    val documentResponse = documentRequest.await()
                    val (document, resolvedResources) = withContext(Dispatchers.Default) {
                        AndroidXmlDocument.parse(documentResponse.xml, documentResponse.sha256) to
                            ResolvedUiResources.from(documentResponse.resource_files)
                    }
                    val local = localRequest.await()
                    val remote = remoteRequest.await()
                    val resolvedDraft = withContext(Dispatchers.IO) { reconcileDrafts(document, local, remote) }
                    if (generation != loadGeneration) return@coroutineScope
                    viewModel.initialize(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        layout = layout,
                        document = document,
                        resources = resolvedResources,
                        unresolvedResourceCount = documentResponse.unresolved_resources.size,
                        previewChildren = documentResponse.preview_children,
                        previewDynamicTextViewIds = documentResponse.preview_dynamic_text_view_ids.toSet(),
                        previewHiddenViewIds = documentResponse.preview_hidden_view_ids.toSet(),
                        draft = resolvedDraft
                    )
                }
            }
            if (generation != loadGeneration) return@launch
            result.onSuccess {
                cancelCurrentAction(clearPendingAddition = false)
                renderSession()
            }.onFailure {
                showError(it.message ?: getString(R.string.ui_editor_load_failed))
            }
        }
    }

    private suspend fun reconcileDrafts(
        document: AndroidXmlDocument,
        local: UiAnnotationDraftRecord?,
        remote: UiEditorDraftDto?
    ): UiAnnotationDraftRecord? {
        val validLocal = local?.takeIf {
            it.baseXmlSha256.equals(document.originalSha256, ignoreCase = true) && it.originalXml == document.originalXml
        }
        val remoteAnnotations = remote?.annotation_xml?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UiAnnotationXmlCodec.decode(it) }.getOrNull() }
        val remoteImages = if (remote != null) restoreRemoteImages(remote, validLocal?.images.orEmpty()) else emptyList()
        val remoteRecord = if (remote != null && remoteAnnotations != null &&
            remote.base_xml_sha256.equals(document.originalSha256, ignoreCase = true) &&
            remote.original_xml == document.originalXml
        ) {
            UiAnnotationDraftRecord(
                taskId = taskId,
                revisionLabel = revisionLabel,
                layoutName = remote.layout_name,
                configuration = remote.configuration,
                baseXmlSha256 = remote.base_xml_sha256,
                originalXml = remote.original_xml,
                annotationXml = remote.annotation_xml,
                annotations = remoteAnnotations,
                images = remoteImages,
                updatedAt = remote.updated_at,
                serverDraftId = remote.draft_id,
                serverDraftVersion = remote.version,
                confirmed = !remote.confirmed_at.isNullOrBlank()
            )
        } else null
        val localTime = runCatching { Instant.parse(validLocal?.updatedAt.orEmpty()) }.getOrNull()
        val remoteTime = runCatching { Instant.parse(remoteRecord?.updatedAt.orEmpty()) }.getOrNull()
        return when {
            validLocal != null && remoteRecord != null && localTime != null && remoteTime != null && localTime > remoteTime ->
                validLocal.copy(
                    serverDraftId = remoteRecord.serverDraftId,
                    serverDraftVersion = remoteRecord.serverDraftVersion,
                    images = validLocal.images.map { localImage ->
                        val remoteImage = remoteImages.firstOrNull { it.imageId == localImage.imageId }
                        localImage.copy(serverWorkspacePath = remoteImage?.serverWorkspacePath)
                    }
                )
            remoteRecord != null -> remoteRecord.copy(
                pendingAddition = validLocal?.pendingAddition,
                pendingAdditionEditing = validLocal?.pendingAdditionEditing == true,
                pendingModification = validLocal?.pendingModification
            )
            else -> validLocal
        }
    }

    private suspend fun restoreRemoteImages(
        remote: UiEditorDraftDto,
        localImages: List<UiEditorImage>
    ): List<UiEditorImage> {
        val localById = localImages.associateBy(UiEditorImage::imageId)
        return remote.images.mapNotNull { image ->
            localById[image.image_id]
                ?.takeIf { File(it.localPath).isFile && it.sha256.equals(image.sha256, ignoreCase = true) }
                ?.copy(serverWorkspacePath = image.workspace_path)
                ?: downloadRemoteImage(remote, image)
        }
    }

    private suspend fun downloadRemoteImage(
        remote: UiEditorDraftDto,
        image: UiEditorImageDto
    ): UiEditorImage? = runCatching {
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
            layoutName = remote.layout_name,
            imageId = image.image_id,
            annotationId = image.element_stable_id,
            displayName = image.original_name,
            resourceName = image.resource_name,
            expectedSha256 = image.sha256,
            serverWorkspacePath = image.workspace_path,
            bytes = bytes
        )
    }.getOrNull()

    private fun renderSession() {
        val session = viewModel.session ?: return
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        stopEdgeAutoScroll()
        movePreview = null
        previewDestination = null
        canvas.minimumHeight = (640f * density).roundToInt()
        val result = UiPreviewRenderer(this).render(
            document = session.document,
            resources = session.resources,
            canvas = canvas,
            previewChildren = session.previewChildren,
            dynamicTextViewIds = session.previewDynamicTextViewIds,
            hiddenViewIds = session.previewHiddenViewIds
        )
        currentNodeViews = result.nodeViews
        currentNodes = session.document.root.descendantsAndSelf().associateBy(UiNode::stableId)
        currentNodeViews.values.forEach(::makePreviewReadOnly)
        val annotationOverlay = UiAnnotationOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            destinationTapListener = this@UiAnnotationEditorActivity::finishMoveDestinationGesture
            destinationDragListener = this@UiAnnotationEditorActivity::handleMoveDestinationDrag
            movePreviewListener = { x, y ->
                previewDestination = if (x != null && y != null) x to y else null
                this@UiAnnotationEditorActivity.findViewById<Button>(R.id.btnUiAnnotationConfirmMove).isEnabled =
                    pendingMoveSource != null && previewDestination != null && !isSaving
                scheduleMovePreview()
            }
            targetTapListener = this@UiAnnotationEditorActivity::completeArmedTarget
            moveSourceTouchListener = this@UiAnnotationEditorActivity::selectMoveSourceAt
            annotationTapListener = { displayed ->
                confirmRemoveAnnotations(displayed.mapNotNull { marked ->
                    session.annotations.firstOrNull { it.annotationId == marked.annotationId }
                })
            }
            additionBoundsChanged = { bounds, finished ->
                session.pendingAddition = session.pendingAddition?.let {
                    it.copy(addition = requireNotNull(it.addition).copy(bounds = bounds, replaceTargets = emptyList()))
                }
                if (finished) { refreshMovePreview(); persistLocal() } else scheduleMovePreview()
            }
            enableTargetSelection(armedAction != null)
            showAnnotations(session.annotations)
        }
        canvas.addView(annotationOverlay)
        overlay = annotationOverlay
        moveEdgeScroller = UiMoveEdgeScroller(
            findViewById(R.id.uiAnnotationHorizontalViewport),
            findViewById(R.id.uiAnnotationVerticalViewport), annotationOverlay)
        canvas.setOnDragListener(null)
        currentTargetHits = emptyList()
        canvas.post {
            if (viewModel.session !== session || overlay !== annotationOverlay) return@post
            rebuildTargetIndex()
            movePreview = UiMovePreviewLayout(canvas, session.document, currentNodeViews)
            session.referenceCanvasWidthDp = canvas.width / density
            session.referenceCanvasHeightDp = canvas.height / density
            annotationOverlay.setReferenceSize(canvas.width, canvas.height)
            restoredArmedAction?.let { armedAction = it }
            restoredDeleteSelection?.let { session.selectedDeleteTargets = it }
            restoredMove?.let { move ->
                pendingMoveSource = move.target
                previewDestination = if (move.destinationX != null && move.destinationY != null)
                    move.destinationX to move.destinationY else null
                armedAction = null
            }
            restoredArmedAction = null
            restoredDeleteSelection = null
            restoredMove = null
            annotationOverlay.enableTargetSelection(armedAction != null)
            annotationOverlay.showPendingMove(pendingMoveSource, previewDestination?.first, previewDestination?.second)
            if (pendingMoveSource != null) updateInstruction(getString(R.string.ui_annotation_destination_hint), true)
            else armedAction?.let { updateInstruction(actionHint(it), true) }
            refreshMovePreview()
            updateActions()
            if (session.pendingAddition != null) {
                showAdditionPlacement()
                if (session.pendingAdditionEditing) openAdditionEditor()
            } else session.pendingModification?.let(::openModificationEditor)
        }
        showCanvas()
        updateActions()
        if (armedAction != null) updateInstruction(actionHint(armedAction!!), true)
    }

    private fun makePreviewReadOnly(view: View) {
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) makePreviewReadOnly(view.getChildAt(index))
        }
    }

    private fun stopEdgeAutoScroll() {
        moveEdgeScroller?.stop()
    }

    private fun handleMoveDestinationDrag(x: Float, y: Float, active: Boolean) {
        if (active) moveEdgeScroller?.update(x, y) else stopEdgeAutoScroll()
    }

    private fun selectTargetForAction(action: UiAnnotationAction, target: UiAnnotationTarget) {
        armedAction = null
        overlay?.enableTargetSelection(false)
        if (action == UiAnnotationAction.MOVE) {
            pendingMoveSource = target
            overlay?.showPendingMove(target)
            updateInstruction(getString(R.string.ui_annotation_destination_hint), true)
            updateActions()
            persistLocal()
        } else if (action == UiAnnotationAction.BEHAVIOR) {
            openModificationEditor(UiAnnotation(action = action, target = target))
        } else {
            showInstructionDialog(action, target, null, null, null)
        }
    }

    private fun selectMoveSourceAt(x: Float, y: Float): Boolean {
        if (armedAction != UiAnnotationAction.MOVE) return false
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val target = findTargetAt(x * (movePreview?.width ?: canvas.width.toFloat()),
            y * (movePreview?.height ?: canvas.height.toFloat())) ?: return false
        selectTargetForAction(UiAnnotationAction.MOVE, target)
        return true
    }

    private fun finishMoveDestinationGesture(x: Float, y: Float) {
        val source = pendingMoveSource ?: return
        stopEdgeAutoScroll()
        previewDestination = x to y
        overlay?.showPendingMove(source, x, y)
        refreshMovePreview()
        updateActions()
    }

    private fun openMoveInstructionDialog() {
        val source = pendingMoveSource ?: return
        val (x, y) = previewDestination ?: return
        if (instructionDialog?.isShowing == true) return
        stopEdgeAutoScroll()
        refreshMovePreview()
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val destinationPixelX = x * (movePreview?.width ?: canvas.width.toFloat())
        val destinationPixelY = y * (movePreview?.height ?: canvas.height.toFloat())
        // Keep the original neighbour as context after the preview has pushed it out of this slot.
        val destination = currentTargetHits.firstOrNull {
            it.bounds.contains(destinationPixelX.roundToInt(), destinationPixelY.roundToInt())
        }?.target?.takeUnless { it.stableId == source.stableId }
        showInstructionDialog(UiAnnotationAction.MOVE, source, destination, x, y)
    }

    private fun completeArmedTarget(x: Float, y: Float) {
        val action = armedAction ?: return
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        if (action == UiAnnotationAction.ADD) {
            startAddition(x * (movePreview?.width ?: canvas.width.toFloat()), y * (movePreview?.height ?: canvas.height.toFloat()))
            return
        }
        val target = findTargetAt(x * (movePreview?.width ?: canvas.width.toFloat()), y * (movePreview?.height ?: canvas.height.toFloat()))
        if (target == null) {
            Toast.makeText(this, R.string.ui_annotation_no_target, Toast.LENGTH_SHORT).show()
            return
        }
        if (action == UiAnnotationAction.DELETE) {
            val session = viewModel.session ?: return
            session.selectedDeleteTargets = UiDeleteSelection.toggle(session.selectedDeleteTargets, target)
            updateDeleteSelection()
        } else selectTargetForAction(action, target)
    }

    private fun updateDeleteSelection() {
        val selected = viewModel.session?.selectedDeleteTargets.orEmpty()
        overlay?.showDeleteSelection(selected.map { it.copy(bounds = movePreview?.displayBounds(it) ?: it.bounds) })
        findViewById<Button>(R.id.btnUiAnnotationDeleteSelected).apply {
            visibility = if (armedAction == UiAnnotationAction.DELETE) View.VISIBLE else View.GONE
            text = getString(R.string.ui_annotation_delete_selected, selected.size)
            isEnabled = selected.isNotEmpty()
        }
    }

    private fun applyDeleteSelection() {
        val session = viewModel.session ?: return
        if (session.selectedDeleteTargets.isEmpty()) return
        val updated = UiDeleteSelection.apply(session.annotations, session.selectedDeleteTargets)
        if (updated.size > MAX_ANNOTATIONS) {
            Toast.makeText(this, R.string.ui_annotation_limit_reached, Toast.LENGTH_LONG).show()
            return
        }
        session.replaceAnnotations(updated)
        cancelCurrentAction()
        recordChange()
    }

    private fun newAdditionBounds(x: Float, y: Float): UiNormalizedRect {
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val width = movePreview?.width ?: canvas.width.coerceAtLeast(1).toFloat()
        val height = movePreview?.height ?: canvas.height.coerceAtLeast(1).toFloat()
        return UiAdditionGeometry.at(x / width, y / height,
            0.65f, (160f * density / height).coerceIn(0.05f, 0.5f))
    }

    private fun startAddition(x: Float, y: Float) {
        val session = viewModel.session ?: return
        if (currentTargetHits.isEmpty()) rebuildTargetIndex()
        val parent = currentTargetHits.firstOrNull {
            it.bounds.contains(x.toInt(), y.toInt()) && currentNodes[it.target.stableId]?.children?.isNotEmpty() == true
        } ?: currentTargetHits.firstOrNull { it.target.stableId == session.document.root.stableId }
            ?: return
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        val background = generateSequence(currentNodeViews[parent.target.stableId]) { it.parent as? View }
            .mapNotNull { (it.background as? ColorDrawable)?.color }.firstOrNull() ?: value.data
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        armedAction = null
        overlay?.enableTargetSelection(false)
        session.pendingAddition = UiAnnotation(action = UiAnnotationAction.ADD, target = parent.target,
            addition = UiAdditionSpec(newAdditionBounds(x, y), background,
                referenceCanvasWidthDp = (movePreview?.width ?: canvas.width.toFloat()) / density,
                referenceCanvasHeightDp = (movePreview?.height ?: canvas.height.toFloat()) / density))
        session.pendingAdditionEditing = false
        showAdditionPlacement()
        persistLocal()
    }

    private fun showAdditionPlacement() {
        val pending = viewModel.session?.pendingAddition ?: return
        overlay?.enableTargetSelection(false)
        overlay?.showAdditionPlacement(pending)
        refreshMovePreview()
        updateInstruction(getString(R.string.ui_annotation_add_region_hint), true)
        findViewById<Button>(R.id.btnUiAnnotationDraw).visibility = View.VISIBLE
        updateActions()
    }

    private fun replacementCandidates(bounds: UiNormalizedRect): List<UiAnnotationTarget> = currentTargetHits
        .map { it.target }.filter { target ->
            val b = target.bounds
            currentNodes[target.stableId]?.children?.isEmpty() == true &&
                b.left >= bounds.left && b.top >= bounds.top && b.right <= bounds.right && b.bottom <= bounds.bottom
        }.distinctBy { it.stableId }

    private fun captureAdditionOriginal(bounds: UiNormalizedRect): Bitmap? {
        val canvasView = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val referenceWidth = movePreview?.width ?: canvasView.width.toFloat()
        val referenceHeight = movePreview?.height ?: canvasView.height.toFloat()
        val width = referenceWidth * (bounds.right - bounds.left)
        val height = referenceHeight * (bounds.bottom - bounds.top)
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, 1024f / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
        val previous = overlay?.visibility
        try {
            overlay?.visibility = View.INVISIBLE
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate(-bounds.left * referenceWidth, -bounds.top * referenceHeight)
                canvasView.draw(this)
            }
        } finally { overlay?.visibility = previous ?: View.VISIBLE }
        return bitmap
    }

    private fun openAdditionEditor() {
        val session = viewModel.session ?: return
        val annotation = session.pendingAddition ?: return
        session.pendingAdditionEditing = true
        openVisualEditor(annotation)
    }

    private fun openModificationEditor(annotation: UiAnnotation) {
        val session = viewModel.session ?: return
        val prepared = annotation.copy(sketch = annotation.sketch ?: UiModificationSketch(android.graphics.Color.WHITE))
        session.pendingModification = prepared
        openVisualEditor(prepared)
    }

    private fun pendingVisual(session: UiAnnotationSession): UiAnnotation? =
        session.pendingModification ?: session.pendingAddition

    private fun updatePendingVisual(session: UiAnnotationSession, value: UiAnnotation) {
        if (value.action == UiAnnotationAction.ADD) session.pendingAddition = value
        else session.pendingModification = value
    }

    private fun openVisualEditor(annotation: UiAnnotation) {
        val session = viewModel.session ?: return
        if (additionDialog?.isShowing == true) return
        if (annotation.imageIds.any { id -> session.images.none { it.imageId == id } }) {
            Toast.makeText(this, "첨부 이미지를 불러오지 못했어요. 편집 화면을 다시 열어 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val adding = annotation.action == UiAnnotationAction.ADD
        val bounds = annotation.addition?.bounds ?: (movePreview?.displayBounds(annotation.target) ?: annotation.target.bounds)
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val aspect = (bounds.right - bounds.left) * (movePreview?.width ?: canvas.width.toFloat()) /
            ((bounds.bottom - bounds.top) * (movePreview?.height ?: canvas.height.toFloat())).coerceAtLeast(1f)
        additionDialog = UiVisualChangeDialog(
            this, annotation, captureAdditionOriginal(bounds), aspect,
            if (adding) replacementCandidates(bounds) else emptyList(),
            onDraftChanged = { draft ->
                updatePendingVisual(session, draft)
                if (adding) scheduleMovePreview()
                persistLocal()
            },
            onBack = {
                additionDialog = null
                if (adding) { session.pendingAdditionEditing = false; showAdditionPlacement() }
                else { session.pendingModification = null; cancelCurrentAction() }
                persistLocal()
            },
            onPickImages = {
                if (pendingVisual(session)?.referenceImages(session.images).orEmpty().size >= MAX_IMAGES_PER_ANNOTATION) {
                    Toast.makeText(this, R.string.ui_annotation_image_limit, Toast.LENGTH_SHORT).show()
                } else visualImagePicker.launch(arrayOf("image/*"))
            },
            onSave = { draft, dialog ->
                val original = dialog.copyOriginal()
                lifecycleScope.launch {
                    try {
                        val strokes = draft.addition?.strokes ?: draft.sketch?.strokes.orEmpty()
                        val image = if (strokes.isNotEmpty() || draft.canvasImages.isNotEmpty()) withContext(Dispatchers.IO) {
                            draftStore.persistSketch(session, draft, aspect, original)
                        } else null
                        check(viewModel.session === session && pendingVisual(session)?.annotationId == draft.annotationId)
                        val saved = draft.copy(imageIds = draft.referenceImages(session.images).map { it.imageId } +
                            listOfNotNull(image?.imageId), sketchImageId = image?.imageId)
                        check(upsertAnnotation(saved))
                        image?.let { session.images += it }
                        session.pendingAddition = null
                        session.pendingAdditionEditing = false
                        session.pendingModification = null
                        dialog.dismiss(); additionDialog = null
                        cancelCurrentAction(); recordChange()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        dialog.allowRetry()
                        Toast.makeText(this@UiAnnotationEditorActivity, "표시를 저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                    } finally { original?.recycle() }
                }
            }
        ).also { it.show(); it.showReferenceImages(annotation.referenceImages(session.images)) }
        persistLocal()
    }

    private fun refreshVisualImages(session: UiAnnotationSession) {
        val draft = pendingVisual(session) ?: return
        additionDialog?.replaceDraft(draft)
        additionDialog?.showReferenceImages(draft.referenceImages(session.images))
    }

    private fun addVisualImages(uris: List<Uri>) {
        val session = viewModel.session ?: return
        val pending = pendingVisual(session) ?: return
        val available = (MAX_IMAGES_PER_ANNOTATION - pending.referenceImages(session.images).size).coerceAtLeast(0)
        if (available == 0) return
        additionDialog?.setBusy(true)
        lifecycleScope.launch {
            try {
                val images = withContext(Dispatchers.IO) {
                    uris.distinct().take(available).mapNotNull { uri ->
                        val attachment = buildSelectedAttachment(contentResolver, uri, SelectedAttachmentKind.IMAGE,
                            maxOriginalImageBytes = MAX_ANNOTATION_IMAGE_SOURCE_BYTES,
                            maxImagePayloadBytes = MAX_ANNOTATION_IMAGE_BYTES, maxPdfBytes = 0, maxTextBytes = 0)
                            ?: return@mapNotNull null
                        draftStore.persistImage(session.taskId, session.revisionLabel, session.layout.layout_name,
                            pending.annotationId, attachment)
                    }
                }
                if (viewModel.session !== session || pendingVisual(session)?.annotationId != pending.annotationId) return@launch
                val current = requireNotNull(pendingVisual(session))
                val existing = current.referenceImages(session.images)
                val accepted = images.distinctBy { it.sha256 }.filter { image -> existing.none { it.sha256 == image.sha256 } }
                    .take((MAX_IMAGES_PER_ANNOTATION - existing.size).coerceAtLeast(0))
                session.images += accepted
                updatePendingVisual(session, current.copy(imageIds = current.imageIds + accepted.map { it.imageId }))
                refreshVisualImages(session); persistLocal()
                if (images.isEmpty()) Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_image_failed, Toast.LENGTH_SHORT).show()
                if (uris.size > available) Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_image_limit, Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_image_failed, Toast.LENGTH_SHORT).show()
            } finally { additionDialog?.allowRetry() }
        }
    }

    private fun editAnnotation(annotation: UiAnnotation) {
        if (annotation.action == UiAnnotationAction.ADD) {
            cancelCurrentAction()
            viewModel.session?.pendingAddition = annotation
            showAdditionPlacement()
            openAdditionEditor()
        } else if (annotation.action == UiAnnotationAction.BEHAVIOR) openModificationEditor(annotation)
        else showInstructionDialog(annotation.action, annotation.target, annotation.destination,
            annotation.moveAnchorX ?: annotation.destinationX, annotation.moveAnchorY ?: annotation.destinationY, annotation)
    }

    private fun confirmRemoveAnnotations(annotations: List<UiAnnotation>) {
        if (annotations.size > 1) {
            AlertDialog.Builder(this).setTitle("어떤 표시를 선택할까요?")
                .setItems(annotations.map { annotation ->
                    val number = viewModel.session?.annotations?.indexOfFirst { it.annotationId == annotation.annotationId }?.plus(1)
                    "$number. ${actionLabel(annotation.action)} · ${annotation.instruction.ifBlank { targetDisplayName(annotation.target) }.take(32)}"
                }.toTypedArray()) { _, i ->
                    confirmRemoveAnnotations(listOf(annotations[i]))
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val annotation = annotations.firstOrNull() ?: return
        val session = viewModel.session ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_annotation_remove_title)
            .setMessage(R.string.ui_annotation_remove_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.ui_annotation_update) { _, _ -> editAnnotation(annotation) }
            .setPositiveButton(R.string.ui_annotation_remove) { _, _ ->
                if (viewModel.session === session) {
                    session.annotations.removeAll { it.annotationId == annotation.annotationId }
                    recordChange()
                }
            }.show()
    }

    private fun showInstructionDialog(
        action: UiAnnotationAction,
        target: UiAnnotationTarget,
        destination: UiAnnotationTarget?,
        destinationX: Float?,
        destinationY: Float?,
        existing: UiAnnotation? = null
    ) {
        instructionDialog?.dismiss()
        val session = viewModel.session ?: return
        val annotationId = existing?.annotationId
            ?: UiAnnotation(action = action, target = target).annotationId
        val existingImageIds = existing?.imageIds.orEmpty().toSet()
        val images = session.images
            .filter { it.imageId in existingImageIds }
            .toMutableList()
        instructionEditorState = InstructionEditorState(
            annotationId = annotationId,
            action = action,
            target = target,
            destination = destination ?: existing?.destination,
            destinationX = destinationX ?: existing?.destinationX,
            destinationY = destinationY ?: existing?.destinationY,
            createdAt = existing?.createdAt ?: Instant.now().toString(),
            originalImageIds = existingImageIds,
            images = images
        )
        instructionImagesCommitted = false
        val title = when (action) {
            UiAnnotationAction.DELETE -> R.string.ui_annotation_instruction_title_delete
            UiAnnotationAction.MOVE -> R.string.ui_annotation_instruction_title_move
            UiAnnotationAction.BEHAVIOR -> R.string.ui_annotation_instruction_title_behavior
            UiAnnotationAction.ADD -> R.string.ui_annotation_add_label
        }
        val content = layoutInflater.inflate(R.layout.bottom_sheet_ui_annotation_instruction, null)
        val input = content.findViewById<EditText>(R.id.uiAnnotationInstructionInput).apply {
            hint = getString(
                if (action == UiAnnotationAction.BEHAVIOR) {
                    R.string.ui_annotation_behavior_instruction_hint
                } else {
                    R.string.ui_annotation_instruction_hint
                }
            )
            setText(existing?.instruction.orEmpty())
            setSelection(text?.length ?: 0)
        }
        instructionInput = input
        content.findViewById<TextView>(R.id.uiAnnotationInstructionTitle).setText(title)
        content.findViewById<TextView>(R.id.uiAnnotationInstructionTarget).text = getString(
            R.string.ui_annotation_target_format,
            targetDisplayName(target)
        )
        content.findViewById<Button>(R.id.btnSaveUiAnnotationInstruction).setText(
            if (existing == null) R.string.ui_annotation_add else R.string.ui_annotation_update
        )
        val dialog = BottomSheetDialog(this).apply {
            setContentView(content)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        instructionDialog = dialog
        content.findViewById<View>(R.id.btnCloseUiAnnotationInstruction).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btnCancelUiAnnotationInstruction).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btnAddUiAnnotationImages).setOnClickListener {
            val state = instructionEditorState ?: return@setOnClickListener
            state.instruction = input.text?.toString().orEmpty()
            if (state.images.size >= MAX_IMAGES_PER_ANNOTATION) {
                Toast.makeText(this, R.string.ui_annotation_image_limit, Toast.LENGTH_SHORT).show()
            } else {
                instructionImagePicker.launch(arrayOf("image/*"))
            }
        }
        content.findViewById<View>(R.id.btnSaveUiAnnotationInstruction).setOnClickListener saveClick@{
            val state = instructionEditorState ?: return@saveClick
            val instruction = input.text?.toString().orEmpty().trim()
            if (state.action == UiAnnotationAction.BEHAVIOR && instruction.isBlank()) {
                input.error = getString(R.string.ui_annotation_behavior_instruction_hint)
                return@saveClick
            }
            val annotation = UiAnnotation(
                annotationId = state.annotationId,
                action = state.action,
                target = state.target,
                destination = state.destination,
                destinationX = state.destinationX,
                destinationY = state.destinationY,
                instruction = instruction,
                imageIds = state.images.map(UiEditorImage::imageId).distinct(),
                createdAt = state.createdAt
            )
            if (!upsertAnnotation(annotation)) return@saveClick
            if (state.action == UiAnnotationAction.MOVE) {
                pendingMoveSource = null
                previewDestination = null
                overlay?.showPendingMove(null)
            }
            state.images.forEach { image ->
                if (session.images.none { it.imageId == image.imageId }) session.images += image
            }
            recordChange()
            instructionImagesCommitted = true
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.setOnDismissListener {
            if (!instructionImagesCommitted) {
                val state = instructionEditorState
                state?.images
                    ?.filter { it.imageId !in state.originalImageIds && session.images.none { saved -> saved.imageId == it.imageId } }
                    ?.forEach(draftStore::deleteLocalImage)
            }
            instructionDialog = null
            instructionInput = null
            instructionEditorState = null
            cancelCurrentAction()
        }
        renderInstructionImages(content)
        dialog.show()
    }

    private fun addInstructionImages(uris: List<Uri>) {
        val state = instructionEditorState ?: return
        state.instruction = instructionInput?.text?.toString().orEmpty()
        val available = (MAX_IMAGES_PER_ANNOTATION - state.images.size).coerceAtLeast(0)
        if (available == 0) {
            Toast.makeText(this, R.string.ui_annotation_image_limit, Toast.LENGTH_SHORT).show()
            return
        }
        val selectedUris = uris.distinct().take(available)
        val annotationId = state.annotationId
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                selectedUris.mapNotNull { uri ->
                    val attachment = buildSelectedAttachment(
                        contentResolver = contentResolver,
                        uri = uri,
                        requestedKind = SelectedAttachmentKind.IMAGE,
                        maxOriginalImageBytes = MAX_ANNOTATION_IMAGE_SOURCE_BYTES,
                        maxImagePayloadBytes = MAX_ANNOTATION_IMAGE_BYTES,
                        maxPdfBytes = 0,
                        maxTextBytes = 0
                    ) ?: return@mapNotNull null
                    val session = viewModel.session ?: return@mapNotNull null
                    draftStore.persistImage(
                        taskId = session.taskId,
                        revisionLabel = session.revisionLabel,
                        layoutName = session.layout.layout_name,
                        annotationId = annotationId,
                        attachment = attachment
                    )
                }
            }
            val current = instructionEditorState
            if (current == null || current.annotationId != annotationId) {
                images.forEach(draftStore::deleteLocalImage)
                return@launch
            }
            images.forEach { image ->
                if (current.images.none { it.sha256 == image.sha256 }) {
                    current.images += image
                } else {
                    draftStore.deleteLocalImage(image)
                }
            }
            instructionDialog?.findViewById<View>(R.id.uiAnnotationInstructionSheetRoot)
                ?.let(::renderInstructionImages)
            if (images.isEmpty()) {
                Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_image_failed, Toast.LENGTH_SHORT).show()
            }
            if (uris.size > selectedUris.size) {
                Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_image_limit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderInstructionImages(content: View) {
        val state = instructionEditorState ?: return
        val list = content.findViewById<LinearLayout>(R.id.uiAnnotationImageList)
        val scroller = content.findViewById<HorizontalScrollView>(R.id.uiAnnotationImageScroller)
        val count = content.findViewById<TextView>(R.id.uiAnnotationImageCount)
        list.removeAllViews()
        count.text = if (state.images.isEmpty()) {
            getString(R.string.ui_annotation_no_images)
        } else {
            getString(R.string.ui_annotation_image_count, state.images.size)
        }
        scroller.visibility = if (state.images.isEmpty()) View.GONE else View.VISIBLE
        state.images.toList().forEach { image ->
            val tile = FrameLayout(this).apply {
                background = getDrawable(R.drawable.bg_ui_annotation_image)
                clipToOutline = true
            }
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = image.displayName
            }
            tile.addView(preview, FrameLayout.LayoutParams(dp(88), dp(88)))
            val targetSize = dp(88)
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeImagePreview(File(image.localPath), targetSize)
                }
                if (preview.parent === tile && tile.parent === list) {
                    bitmap?.let(preview::setImageBitmap)
                } else {
                    bitmap?.recycle()
                }
            }
            val remove = ImageButton(this).apply {
                setImageResource(R.drawable.ic_annotation_delete)
                background = getDrawable(R.drawable.bg_ui_annotation_toolbar)
                contentDescription = getString(R.string.ui_annotation_remove_image)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener {
                    state.images.removeAll { it.imageId == image.imageId }
                    renderInstructionImages(content)
                }
            }
            tile.addView(
                remove,
                FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    marginEnd = dp(4)
                }
            )
            list.addView(
                tile,
                LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(10) }
            )
        }
    }

    private fun decodeImagePreview(file: File, targetSize: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) sampleSize *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    private fun targetDisplayName(target: UiAnnotationTarget): String = target.text.trim()
        .ifBlank { target.contentDescription.trim() }
        .ifBlank { target.resourceId.substringAfterLast('/').replace('_', ' ') }
        .ifBlank { target.className.substringAfterLast('.') }

    private fun upsertAnnotation(annotation: UiAnnotation): Boolean {
        val session = viewModel.session ?: return false
        val duplicate = session.annotations.any {
            it.annotationId != annotation.annotationId &&
                it.action == annotation.action &&
                it.target.stableId == annotation.target.stableId &&
                it.destination?.stableId == annotation.destination?.stableId &&
                it.destinationX == annotation.destinationX &&
                it.destinationY == annotation.destinationY &&
                it.instruction == annotation.instruction &&
                it.addition == annotation.addition && it.sketch == annotation.sketch &&
                it.imageIds == annotation.imageIds && it.canvasImages == annotation.canvasImages
        }
        if (duplicate) {
            Toast.makeText(this, R.string.ui_annotation_duplicate, Toast.LENGTH_LONG).show()
            return false
        }
        val index = session.annotations.indexOfFirst { it.annotationId == annotation.annotationId }
        if (index < 0 && session.annotations.size >= MAX_ANNOTATIONS) {
            Toast.makeText(this, R.string.ui_annotation_limit_reached, Toast.LENGTH_LONG).show()
            return false
        }
        if (index >= 0) session.annotations[index] = annotation else session.annotations += annotation
        return true
    }

    private fun findTargetAt(x: Float, y: Float): UiAnnotationTarget? {
        if (currentTargetHits.isEmpty()) rebuildTargetIndex()
        val preview = movePreview
        val canvas = findViewById<View>(R.id.uiAnnotationCanvas)
        val width = preview?.width ?: canvas.width.toFloat()
        val height = preview?.height ?: canvas.height.toFloat()
        return currentTargetHits.firstOrNull { hit ->
            val bounds = preview?.displayBounds(hit.target) ?: hit.target.bounds
            x >= bounds.left * width && x < bounds.right * width && y >= bounds.top * height && y < bounds.bottom * height
        }?.target
    }

    private fun rebuildTargetIndex() {
        val session = viewModel.session ?: return
        val canvas = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        val stableIdByView = currentNodeViews.entries.associate { (stableId, view) -> view to stableId }
        val renderedViews = buildList {
            fun visit(view: View, path: List<Int>) {
                if (view === overlay) return
                add(RenderedTargetCandidate(view, path))
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index), path + index)
                    }
                }
            }
            for (index in 0 until canvas.childCount) visit(canvas.getChildAt(index), listOf(index))
        }
        val width = canvas.width.coerceAtLeast(1).toFloat()
        val height = canvas.height.coerceAtLeast(1).toFloat()
        currentTargetHits = renderedViews.mapNotNull { candidate ->
            val view = candidate.view
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@mapNotNull null
            val bounds = Rect(0, 0, view.width, view.height)
            runCatching { canvas.offsetDescendantRectToMyCoords(view, bounds) }.getOrNull() ?: return@mapNotNull null
            val xmlStableId = stableIdByView[view]
            val node = xmlStableId?.let(currentNodes::get)
            val stableId = node?.stableId ?: runtimeStableId(view, candidate.path)
            val parent = node?.let { findNodeParent(session.document.root, it.stableId) }
            val index = node?.let { current -> parent?.children?.indexOfFirst { it.stableId == current.stableId } } ?: -1
            UiAnnotationTargetHit(
                bounds = bounds,
                depth = candidate.path.size,
                target = UiAnnotationTarget(
                    stableId = stableId,
                    resourceId = node?.androidAttribute("id").orEmpty().ifBlank { viewResourceName(view) },
                    hierarchyPath = node?.elementPath?.joinToString(".")
                        ?: "rendered.${candidate.path.joinToString(".")}",
                    className = node?.tagName ?: view.javaClass.name,
                    text = (view as? TextView)?.text?.toString().orEmpty().take(240),
                    contentDescription = view.contentDescription?.toString().orEmpty().take(240),
                    bounds = UiNormalizedRect(
                        bounds.left / width,
                        bounds.top / height,
                        bounds.right / width,
                        bounds.bottom / height
                    ).normalized(),
                    previousSibling = if (node != null) {
                        parent?.children?.getOrNull(index - 1)?.stableId.orEmpty()
                    } else {
                        renderedSiblingStableId(view, candidate.path, -1)
                    },
                    nextSibling = if (node != null) {
                        parent?.children?.getOrNull(index + 1)?.stableId.orEmpty()
                    } else {
                        renderedSiblingStableId(view, candidate.path, 1)
                    }
                )
            )
        }.sortedWith(
            compareBy<UiAnnotationTargetHit> { it.bounds.width().toLong() * it.bounds.height().toLong() }
                .thenByDescending { it.depth }
        )
    }

    private fun runtimeStableId(view: View, path: List<Int>): String {
        val signature = buildString {
            append(view.javaClass.name)
            append('|').append((view as? TextView)?.text?.toString().orEmpty())
            append('|').append(view.contentDescription?.toString().orEmpty())
        }
        return "runtime:${path.joinToString(".")}:${Integer.toUnsignedString(signature.hashCode(), 16)}"
    }

    private fun renderedSiblingStableId(view: View, path: List<Int>, offset: Int): String {
        val parent = view.parent as? ViewGroup ?: return ""
        val siblingIndex = parent.indexOfChild(view) + offset
        val sibling = parent.getChildAt(siblingIndex) ?: return ""
        val tagged = sibling.tag as? String
        if (!tagged.isNullOrBlank() && currentNodeViews[tagged] === sibling) return tagged
        return runtimeStableId(sibling, path.dropLast(1) + siblingIndex)
    }

    private fun viewResourceName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceName(view.id) }.getOrDefault("")
    }

    private fun findNodeParent(root: UiNode, stableId: String): UiNode? {
        if (root.children.any { it.stableId == stableId }) return root
        return root.children.firstNotNullOfOrNull { findNodeParent(it, stableId) }
    }

    private data class RenderedTargetCandidate(val view: View, val path: List<Int>)

    private data class UiAnnotationTargetHit(
        val bounds: Rect,
        val depth: Int,
        val target: UiAnnotationTarget
    )

    private fun scheduleMovePreview() {
        if (previewFramePending) return
        previewFramePending = true
        findViewById<View>(R.id.uiAnnotationCanvas).postOnAnimation {
            previewFramePending = false
            refreshMovePreview(updateXml = false)
        }
    }

    private fun refreshMovePreview(updateXml: Boolean = true) {
        val session = viewModel.session ?: return
        val moves = session.annotations.filter { it.action == UiAnnotationAction.MOVE }.toMutableList()
        val source = pendingMoveSource
        val point = previewDestination
        val pendingMove = if (source != null && point != null) UiAnnotation(action = UiAnnotationAction.MOVE,
            target = source, destinationX = point.first, destinationY = point.second) else null
        pendingMove?.let(moves::add)
        val additions = session.annotations.filter { it.action == UiAnnotationAction.ADD &&
            it.annotationId != session.pendingAddition?.annotationId } + listOfNotNull(session.pendingAddition)
        movePreview?.apply(moves, updateXml, additions)
        if (pendingMove == null && session.pendingAddition == null) session.replaceAnnotations(session.annotations.map { annotation ->
            if (annotation.action == UiAnnotationAction.MOVE) movePreview?.resolvedMove(annotation) ?: annotation else annotation
        })
        val destinations = movePreview?.destinationBounds.orEmpty()
        overlay?.showMoveDestinationBounds(destinations, pendingMove?.let { destinations[it.annotationId] })
        session.previewCanvasHeightDp = findViewById<View>(R.id.uiAnnotationCanvas).minimumHeight / density
        overlay?.showAnnotations(session.annotations.filter { it.annotationId != session.pendingAddition?.annotationId }.map { annotation ->
            annotation.copy(target = annotation.target.copy(bounds = movePreview?.displayBounds(annotation.target) ?: annotation.target.bounds))
        })
        lifecycleScope.launch { loadCanvasPreviews(session, strict = false) }
        updateDeleteSelection()
    }

    private suspend fun loadCanvasPreviews(session: UiAnnotationSession, strict: Boolean) {
        val ids = session.annotations.filter { it.canvasImages.isNotEmpty() }.mapNotNull { it.sketchImageId }.toSet()
        val missing = ids.filterNot { it in canvasPreviewBitmaps }
        if (missing.isNotEmpty()) {
            val files = session.images.filter { it.imageId in missing }
            val decoded = withContext(Dispatchers.IO) {
                files.mapNotNull { image -> UiSketchImages.decode(image.localPath, 512)?.let { image.imageId to it } }.toMap()
            }
            if (viewModel.session !== session) return
            canvasPreviewBitmaps.putAll(decoded)
        }
        if (strict) check(ids.all { it in canvasPreviewBitmaps }) { "이미지 미리보기를 불러오지 못했어요." }
        overlay?.sketchPreviews = canvasPreviewBitmaps.filterKeys { it in ids }
    }

    private fun recordChange() {
        val session = viewModel.session ?: return
        refreshMovePreview()
        session.history.record(session.annotations)
        updateActions()
        persistLocal()
        scheduleRemoteSave()
    }

    private fun undo() {
        val session = viewModel.session ?: return
        session.history.undo()?.let {
            session.replaceAnnotations(it)
            refreshMovePreview()
            updateActions()
            persistLocal()
            scheduleRemoteSave()
        }
    }

    private fun redo() {
        val session = viewModel.session ?: return
        session.history.redo()?.let {
            session.replaceAnnotations(it)
            refreshMovePreview()
            updateActions()
            persistLocal()
            scheduleRemoteSave()
        }
    }

    private fun showAnnotationList() {
        val session = viewModel.session ?: return
        if (session.annotations.isEmpty()) {
            Toast.makeText(this, R.string.ui_annotation_list_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = session.annotations.mapIndexed { index, annotation ->
            val target = annotation.target.text.ifBlank {
                annotation.target.resourceId.substringAfterLast('/').ifBlank {
                    annotation.target.className.substringAfterLast('.')
                }
            }.take(28)
            "${index + 1}. ${actionLabel(annotation.action)} · $target"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_annotation_list_title)
            .setItems(labels) { _, index -> showAnnotationActions(session.annotations[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAnnotationActions(annotation: UiAnnotation) {
        AlertDialog.Builder(this)
            .setTitle(actionLabel(annotation.action))
            .setItems(arrayOf(getString(R.string.ui_annotation_update), getString(R.string.ui_annotation_remove))) { _, which ->
                if (which == 0) {
                    editAnnotation(annotation)
                } else {
                    confirmRemoveAnnotations(listOf(annotation))
                }
            }
            .show()
    }

    private fun confirmClear() {
        val session = viewModel.session ?: return
        if (session.annotations.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_annotation_clear_title)
            .setMessage(R.string.ui_annotation_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ui_annotation_clear) { _, _ ->
                session.annotations.clear()
                recordChange()
            }
            .show()
    }

    private fun scheduleRemoteSave() {
        remoteSaveJob?.cancel()
        val session = viewModel.session ?: return
        val sequence = ++viewModel.remoteSaveSequence
        remoteSaveJob = lifecycleScope.launch {
            delay(700)
            if (sequence != viewModel.remoteSaveSequence) return@launch
            val snapshot = snapshotForPersistence(session)
            withContext(Dispatchers.IO) { persistRemote(session, draftStore.recordFor(snapshot), sequence) }
        }
    }

    private fun persistLocal() {
        val session = viewModel.session ?: return
        val snapshot = snapshotForPersistence(session)
        val sequence = ++viewModel.nextLocalSaveSequence
        lifecycleScope.launch(Dispatchers.IO) { persistLocalRecord(draftStore.recordFor(snapshot), sequence) }
    }

    private fun snapshotForPersistence(session: UiAnnotationSession) = session.copy(
        annotations = session.annotations.toMutableList(), images = session.images.toMutableList()
    )

    private suspend fun persistLocalRecord(record: UiAnnotationDraftRecord, sequence: Long) {
        viewModel.localDraftMutex.withLock {
            if (sequence <= viewModel.savedLocalSequence) return
            draftStore.save(record)
            viewModel.savedLocalSequence = sequence
        }
    }

    private suspend fun persistRemote(
        session: UiAnnotationSession,
        record: UiAnnotationDraftRecord,
        expectedSequence: Long? = null
    ): Result<UiEditorDraftDto> = runCatching {
        viewModel.serverDraftMutex.withLock {
            require(viewModel.session === session) { "annotation session changed" }
            if (expectedSequence != null) {
                require(expectedSequence == viewModel.remoteSaveSequence) { "annotation save superseded" }
            }
            val response = try {
                saveRemoteRecord(session, record)
            } catch (error: HttpException) {
                if (error.code() != 409 || session.serverDraftId != null) throw error
                val remote = apiService.getUiEditorDraft(
                    taskId = record.taskId,
                    revisionLabel = record.revisionLabel,
                    layoutName = record.layoutName,
                    configuration = record.configuration,
                    deviceId = preferencesStore.getOrCreateDeviceId(),
                    userId = null,
                    phoneNumber = preferencesStore.loadPhoneNumber()
                )
                if (remote.annotation_xml != record.annotationXml) throw error
                remote
            }
            session.serverDraftId = response.draft_id
            session.serverDraftVersion = response.version
            uploadReferencedImages(session, response)
            val localSequence = ++viewModel.nextLocalSaveSequence
            persistLocalRecord(draftStore.recordFor(session), localSequence)
            response
        }
    }

    private suspend fun saveRemoteRecord(
        session: UiAnnotationSession,
        record: UiAnnotationDraftRecord
    ): UiEditorDraftDto = apiService.saveUiEditorDraft(
        taskId = record.taskId,
        revisionLabel = record.revisionLabel,
        layoutName = record.layoutName,
        deviceId = preferencesStore.getOrCreateDeviceId(),
        userId = null,
        phoneNumber = preferencesStore.loadPhoneNumber(),
        request = UiEditorDraftRequestDto(
            draft_id = session.serverDraftId,
            configuration = record.configuration,
            base_xml_sha256 = record.baseXmlSha256,
            original_xml = record.originalXml,
            edited_xml = record.originalXml,
            annotation_xml = record.annotationXml,
            descriptions = session.annotations.associate { it.annotationId to it.instruction },
            expected_version = session.serverDraftVersion,
            is_new_layout = false
        )
    )

    private suspend fun uploadReferencedImages(session: UiAnnotationSession, draft: UiEditorDraftDto) {
        val referencedIds = session.annotations.flatMap(UiAnnotation::imageIds).toSet()
        val remoteById = draft.images.associateBy(UiEditorImageDto::image_id)
        session.images.toList().forEach { image ->
            if (image.imageId !in referencedIds) return@forEach
            val existing = remoteById[image.imageId]
            if (existing != null) {
                updateImageWorkspacePath(session, image.imageId, existing.workspace_path)
                return@forEach
            }
            val imageFile = File(image.localPath)
            require(imageFile.isFile) { "attached UI reference image is missing" }
            val uploaded = apiService.uploadUiEditorImage(
                taskId = session.taskId,
                revisionLabel = session.revisionLabel,
                draftId = draft.draft_id,
                deviceId = preferencesStore.getOrCreateDeviceId(),
                userId = null,
                phoneNumber = preferencesStore.loadPhoneNumber(),
                request = UiEditorImageUploadRequestDto(
                    image_id = image.imageId,
                    element_stable_id = image.elementStableId,
                    original_name = image.displayName,
                    mime_type = image.mimeType,
                    resource_name = image.resourceName,
                    base64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
                )
            ).image
            updateImageWorkspacePath(session, image.imageId, uploaded.workspace_path)
        }
    }

    private fun updateImageWorkspacePath(session: UiAnnotationSession, imageId: String, path: String) {
        val index = session.images.indexOfFirst { it.imageId == imageId }
        if (index >= 0) session.images[index] = session.images[index].copy(serverWorkspacePath = path)
    }

    private fun saveForChat() {
        val session = viewModel.session ?: return
        if (isSaving || session.annotations.isEmpty()) return
        val validationError = validateAnnotationsForSubmit(session.annotations)
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
            return
        }
        cancelCurrentAction()
        remoteSaveJob?.cancel()
        isSaving = true
        updateActions()
        showLoading(getString(R.string.ui_annotation_saving))
        lifecycleScope.launch {
            val result = runCatching {
                val snapshot = snapshotForPersistence(session)
                val saved = withContext(Dispatchers.IO) {
                    persistRemote(session, draftStore.recordFor(snapshot)).getOrThrow()
                }
                val preview = requireNotNull(captureAnnotatedPreview()) {
                    getString(R.string.ui_annotation_preview_failed)
                }
                withContext(Dispatchers.IO) {
                    apiService.confirmUiEditorDraft(
                        taskId = session.taskId,
                        revisionLabel = session.revisionLabel,
                        draftId = saved.draft_id,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber(),
                        request = UiEditorSaveRequestDto(
                            expected_version = session.serverDraftVersion ?: saved.version,
                            preview_image_base64 = preview
                        )
                    )
                }
            }
            result.onSuccess {
                val localSequence = ++viewModel.nextLocalSaveSequence
                withContext(Dispatchers.IO) {
                    persistLocalRecord(
                        draftStore.recordFor(session, confirmed = true),
                        localSequence
                    )
                }
                Toast.makeText(this@UiAnnotationEditorActivity, R.string.ui_annotation_saved, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TASK_ID, session.taskId))
                finish()
            }.onFailure {
                isSaving = false
                showCanvas()
                updateActions()
                Toast.makeText(
                    this@UiAnnotationEditorActivity,
                    it.message?.takeIf(String::isNotBlank) ?: getString(R.string.ui_editor_save_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun validateAnnotationsForSubmit(annotations: List<UiAnnotation>): String? {
        if (annotations.any { it.action == UiAnnotationAction.ADD &&
                (it.addition == null || it.instruction.isBlank()) }) {
            return "추가할 UI의 설명을 입력해 주세요."
        }
        if (annotations.any { it.action == UiAnnotationAction.BEHAVIOR && it.instruction.isBlank() }) {
            return getString(R.string.ui_annotation_behavior_missing)
        }
        if (annotations.any {
                it.action == UiAnnotationAction.MOVE &&
                    it.destination == null && (it.destinationX == null || it.destinationY == null)
            }
        ) {
            return getString(R.string.ui_annotation_destination_missing)
        }
        val signatures = annotations.map {
            listOf(
                it.action.wireName,
                it.target.stableId,
                it.destination?.stableId.orEmpty(),
                it.destinationX?.toString().orEmpty(),
                it.destinationY?.toString().orEmpty(),
                it.instruction,
                it.addition?.toString().orEmpty(), it.sketch?.toString().orEmpty(), it.imageIds.joinToString(",")
            ).joinToString("|")
        }
        return if (signatures.distinct().size != signatures.size) {
            getString(R.string.ui_annotation_duplicate)
        } else null
    }

    private suspend fun captureAnnotatedPreview(): String? {
        viewModel.session?.let { loadCanvasPreviews(it, strict = true) }
        val canvasView = findViewById<FrameLayout>(R.id.uiAnnotationCanvas)
        if (canvasView.width <= 0 || canvasView.height <= 0) return null
        val maxDimension = 1600f
        val scale = minOf(1f, maxDimension / maxOf(canvasView.width, canvasView.height))
        val bitmap = Bitmap.createBitmap(
            (canvasView.width * scale).roundToInt().coerceAtLeast(1),
            (canvasView.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.RGB_565
        )
        Canvas(bitmap).apply {
            scale(scale, scale)
            canvasView.draw(this)
        }
        return withContext(Dispatchers.IO) {
            try {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output))
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally { bitmap.recycle() }
        }
    }

    private fun updateActions() {
        listOf(R.id.btnUiAnnotationDeleteTool to UiAnnotationAction.DELETE,
            R.id.btnUiAnnotationMoveTool to UiAnnotationAction.MOVE,
            R.id.btnUiAnnotationBehaviorTool to UiAnnotationAction.BEHAVIOR,
            R.id.btnUiAnnotationAddTool to UiAnnotationAction.ADD).forEach { (id, action) ->
            findViewById<View>(id).apply {
                isSelected = armedAction == action || (action == UiAnnotationAction.MOVE && pendingMoveSource != null)
                alpha = if (armedAction == null || isSelected) 1f else .45f
            }
        }
        updateDeleteSelection()
        val session = viewModel.session
        findViewById<Button>(R.id.btnUiAnnotationConfirmMove).apply {
            visibility = if (pendingMoveSource != null) View.VISIBLE else View.GONE
            isEnabled = pendingMoveSource != null && previewDestination != null && !isSaving
        }
        findViewById<ImageButton>(R.id.btnUiAnnotationUndo).isEnabled = session?.history?.canUndo == true
        findViewById<ImageButton>(R.id.btnUiAnnotationRedo).isEnabled = session?.history?.canRedo == true
        findViewById<ImageButton>(R.id.btnUiAnnotationList).isEnabled = session?.annotations?.isNotEmpty() == true
        findViewById<ImageButton>(R.id.btnUiAnnotationClear).isEnabled = session?.annotations?.isNotEmpty() == true
        findViewById<Button>(R.id.btnUiAnnotationSave).isEnabled =
            session?.annotations?.isNotEmpty() == true && session.pendingAddition == null &&
                session.pendingModification == null && pendingMoveSource == null && !isSaving
        findViewById<TextView>(R.id.uiAnnotationCount).text = if (session?.annotations.isNullOrEmpty()) {
            getString(R.string.ui_annotation_empty)
        } else {
            getString(R.string.ui_annotation_count, session?.annotations?.size ?: 0)
        }
    }

    private fun cancelCurrentAction(clearBanner: Boolean = true, clearPendingAddition: Boolean = true) {
        stopEdgeAutoScroll()
        armedAction = null
        pendingMoveSource = null
        previewDestination = null
        viewModel.session?.selectedDeleteTargets = emptyList()
        overlay?.showHover(null, null)
        overlay?.showPendingMove(null)
        overlay?.enableTargetSelection(false)
        overlay?.showAdditionPlacement(null)
        if (clearPendingAddition) {
            viewModel.session?.pendingAddition = null
            viewModel.session?.pendingAdditionEditing = false
        }
        refreshMovePreview()
        findViewById<Button>(R.id.btnUiAnnotationDraw).visibility = View.GONE
        updateActions()
        if (clearBanner) updateInstruction("", false)
    }

    private fun handleBack() {
        if (armedAction != null || pendingMoveSource != null || viewModel.session?.pendingAddition != null) {
            cancelCurrentAction()
        } else {
            persistLocal()
            finish()
        }
    }

    private fun updateInstruction(message: String, visible: Boolean) {
        findViewById<View>(R.id.uiAnnotationInstructionBar).visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.uiAnnotationInstruction).text = message
    }

    private fun showLoading(message: String) {
        findViewById<View>(R.id.uiAnnotationStateOverlay).visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.uiAnnotationProgress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.uiAnnotationStateText).apply {
            visibility = View.VISIBLE
            text = message
        }
        findViewById<Button>(R.id.btnUiAnnotationRetry).visibility = View.GONE
        findViewById<View>(R.id.uiAnnotationFloatingToolbar).visibility = View.GONE
    }

    private fun showError(message: String) {
        findViewById<View>(R.id.uiAnnotationStateOverlay).visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.uiAnnotationProgress).visibility = View.GONE
        findViewById<TextView>(R.id.uiAnnotationStateText).apply {
            visibility = View.VISIBLE
            text = message
        }
        findViewById<Button>(R.id.btnUiAnnotationRetry).visibility = View.VISIBLE
        findViewById<View>(R.id.uiAnnotationFloatingToolbar).visibility = View.GONE
    }

    private fun showCanvas() {
        findViewById<View>(R.id.uiAnnotationStateOverlay).visibility = View.GONE
        findViewById<View>(R.id.uiAnnotationFloatingToolbar).visibility = View.VISIBLE
    }

    private fun showLayoutName(layout: UiLayoutSummaryDto) {
        findViewById<Button>(R.id.btnUiAnnotationScreen).text = layoutDisplayName(layout)
    }

    private fun layoutDisplayName(layout: UiLayoutSummaryDto): String =
        UiLayoutPresentation.displayName(layout)

    private fun actionLabel(action: UiAnnotationAction): String = getString(
        when (action) {
            UiAnnotationAction.DELETE -> R.string.ui_annotation_delete_label
            UiAnnotationAction.MOVE -> R.string.ui_annotation_move_label
            UiAnnotationAction.BEHAVIOR -> R.string.ui_annotation_behavior_label
            UiAnnotationAction.ADD -> R.string.ui_annotation_add_label
        }
    )

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private data class InstructionEditorState(
        val annotationId: String,
        val action: UiAnnotationAction,
        val target: UiAnnotationTarget,
        val destination: UiAnnotationTarget?,
        val destinationX: Float?,
        val destinationY: Float?,
        val createdAt: String,
        val originalImageIds: Set<String>,
        val images: MutableList<UiEditorImage>,
        var instruction: String = ""
    )

    companion object {
        const val EXTRA_TASK_ID = "ui_editor_task_id"
        const val EXTRA_REVISION_LABEL = "ui_editor_revision_label"
        const val EXTRA_APP_NAME = "ui_editor_app_name"
        private const val STATE_LAYOUT_NAME = "ui_annotation_layout_name"
        private const val STATE_CONFIGURATION = "ui_annotation_configuration"
        private const val STATE_ARMED_ACTION = "ui_annotation_armed_action"
        private const val STATE_TOOLBAR_COLLAPSED = "ui_annotation_toolbar_collapsed"
        private const val STATE_TOOLBAR_X = "ui_annotation_toolbar_x"
        private const val STATE_TOOLBAR_Y = "ui_annotation_toolbar_y"
        private const val MAX_ANNOTATIONS = 500
        private const val MAX_IMAGES_PER_ANNOTATION = 5
        private const val MAX_ANNOTATION_IMAGE_SOURCE_BYTES = 15 * 1024 * 1024
        private const val MAX_ANNOTATION_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
