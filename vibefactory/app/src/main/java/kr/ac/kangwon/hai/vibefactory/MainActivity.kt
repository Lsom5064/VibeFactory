package kr.ac.kangwon.hai.vibefactory

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Paint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.android.material.bottomsheet.BottomSheetDialog
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiEditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_TASK_ID = "selected_task_id"
        const val EXTRA_BRANCHED_TASK_CREATED = "branched_task_created"
        const val EXTRA_BRANCHED_TASK_APP_NAME = "branched_task_app_name"
        const val EXTRA_BRANCHED_TASK_PACKAGE_NAME = "branched_task_package_name"
        const val EXTRA_BRANCHED_TASK_STATUS = "branched_task_status"
        const val EXTRA_BRANCHED_TASK_MESSAGE = "branched_task_message"
        const val EXTRA_BRANCHED_TASK_VERSION = "branched_task_version"
        const val EXTRA_BRANCHED_TASK_CREATED_AT = "branched_task_created_at"
        private const val POLL_INTERVAL_MS = 3000L
        private const val TAG = "VibeFactoryHost"
        private const val STATE_SELECTED_TASK_ID = EXTRA_SELECTED_TASK_ID
        private const val STATE_INPUT_PROMPT = "input_prompt"
        private const val STATE_COMPOSER_ATTACHMENTS = "composer_attachments"
        private const val STATE_CHAT_SCROLL_MESSAGE_ID = "chat_scroll_message_id"
        private const val STATE_CHAT_SCROLL_TOP_OFFSET = "chat_scroll_top_offset"
        private const val PREF_PENDING_INSTALLER_LAUNCHED = "pending_installer_launched"
        private const val PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE =
            "pending_install_previous_version_code"
        private const val PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME =
            "pending_install_previous_update_time"
        private const val PREF_PENDING_INSTALL_ARTIFACT_IDENTITY =
            "pending_install_artifact_identity"
        private const val INSTALL_RESOLUTION_ATTEMPTS = 10
        private const val INSTALL_RESOLUTION_DELAY_MS = 500L
        private const val REQUEST_PHONE_NUMBER_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val MAX_ATTACHMENT_IMAGE_ORIGINAL_BYTES = 15 * 1024 * 1024
        private const val MAX_ATTACHMENT_IMAGE_PAYLOAD_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTACHMENT_PDF_BYTES = 10 * 1024 * 1024
        private const val MAX_ATTACHMENT_TEXT_BYTES = 2 * 1024 * 1024
        private const val ATTACHMENT_MENU_TAP_GUARD_MS = 600L
        private const val MAX_CHAT_TIMELINE_EVENTS_FOR_RENDER = 120
        private const val MAX_RECENT_ASSISTANT_MESSAGES_FOR_RENDER = 24
        private const val MAX_IN_MEMORY_TASK_MESSAGES = 180
    }

    private val gson: Gson = GsonBuilder().create()
    private val timestampFormatter = KoreanTimestampFormatter()

    private lateinit var apiService: VibeApiService
    private lateinit var downloadApiService: VibeApiService
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainContent: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var chatCard: View
    private lateinit var recyclerTasks: RecyclerView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var inputPrompt: EditText
    private lateinit var inputPhoneGate: EditText
    private lateinit var btnAttachReferenceImage: TextView
    private lateinit var selectedAttachmentChip: TextView
    private lateinit var selectedAttachmentPreviewScroller: HorizontalScrollView
    private lateinit var selectedAttachmentPreviewList: LinearLayout
    private lateinit var btnSend: Button
    private lateinit var btnNewChat: Button
    private lateinit var btnOpenLibrary: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var drawerNewChatRow: LinearLayout
    private lateinit var drawerLibraryRow: LinearLayout
    private lateinit var drawerSettingsRow: LinearLayout
    private lateinit var btnSavePhoneGate: Button
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var composerBar: LinearLayout
    private lateinit var topLogChip: LinearLayout
    private lateinit var topTitleChip: LinearLayout
    private lateinit var uiDraftContextBar: LinearLayout
    private lateinit var checkUseSavedUi: CheckBox
    private lateinit var btnOpenUiEditor: TextView
    private lateinit var phoneGateOverlay: View
    private lateinit var phoneGateContent: View
    private lateinit var drawerContent: LinearLayout
    private lateinit var topTitleText: TextView
    private lateinit var inputModeLabel: TextView
    private lateinit var emptyChatText: TextView
    private var topBarBaseTopPadding: Int = 0
    private var mainContentBaseBottomPadding: Int = 0
    private var recyclerMessagesBaseBottomPadding: Int = 0
    private var drawerContentBaseLeftPadding: Int = 0
    private var drawerContentBaseRightPadding: Int = 0
    private var phoneGateContentBaseLeftPadding: Int = 0
    private var phoneGateContentBaseRightPadding: Int = 0

    private val preferencesStore by lazy { HostPreferencesStore(this, gson, TAG) }
    private val composerDraftViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this)[ComposerDraftViewModel::class.java]
    }
    private val composerDraftAttachmentStore by lazy(LazyThreadSafetyMode.NONE) {
        ComposerDraftAttachmentStore(File(cacheDir, "composer_draft_attachments"))
    }
    private val taskAdapter = TaskSummaryAdapter(
        onClick = { summary -> selectTask(summary.taskId, autoInstallOnSuccess = false) },
        onDelete = { summary -> confirmHideTaskFromChatList(summary) }
    )
    private val chatAdapter by lazy {
        ChatMessageAdapter(
            messageSelectionActionModeCallback = messageSelectionActionModeCallback,
            formatMessageTimestamp = ::formatMessageTimestampForBubble,
            isConfirmationHandled = handledConfirmationMessageIds::contains,
            onConfirmationAccept = ::handleConfirmationAccepted,
            onConfirmationDismiss = ::handleConfirmationDismissed,
            onPromptReviewOpen = ::openPromptReview,
            onArtifactDownload = ::handleArtifactDownloadRequested,
            onArtifactInstall = ::handleArtifactInstallRequested,
            onBuildCancel = ::handleBuildCancelRequested
        )
    }

    private var screenState = ChatScreenState()
    private var currentTaskId: String? = null
    private var latestApkUrl: String? = null
    private var latestDownloadedApkFile: File? = null
    private var latestDownloadedTaskId: String? = null
    private var pollingJob: Job? = null
    private var lastCrashTaskId: String? = null
    private var lastCrashPackage: String? = null
    private var lastStackTrace: String? = null
    private lateinit var deviceId: String
    private lateinit var userIdentity: UserIdentity
    private val runtimeErrorTaskIds = mutableSetOf<String>()
    private val pendingRuntimeErrors = mutableMapOf<String, RuntimeErrorRecord>()
    private val cancellingTaskIds = mutableSetOf<String>()
    private var persistedRuntimeErrorsLoaded: Boolean = false
    private val taskConversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val loadedTaskChatIds = mutableSetOf<String>()
    private val taskTimelineRenderCache = TaskTimelineRenderCache()
    private val taskTimelineEventCursorById = mutableMapOf<String, String>()
    private val taskInputQueueManager by lazy {
        TaskInputQueueManager(
            isProcessingStatus = { processingAnimationBaseText(it) != null },
            loadingTaskStatus = getString(R.string.status_loading_task)
        )
    }
    private val taskArtifactStates = mutableMapOf<String, PersistedArtifactState>()
    private val uiEditorContextByTask = mutableMapOf<String, UiEditorContextResponseDto>()
    private val useSavedUiByTask = mutableMapOf<String, Boolean>()
    private var isRenderingUiDraftContext = false
    private val taskLastStatusKeys = mutableMapOf<String, String>()
    private val hiddenTaskIds = mutableSetOf<String>()
    private var taskSummaryById: Map<String, TaskSummary> = emptyMap()
    private var pendingTaskSelectionKey: String? = null
    private var isDownloadingApk: Boolean = false
    private var downloadingApkTaskId: String? = null
    private var downloadingApkUrl: String? = null
    private var downloadingArtifactPath: String? = null
    private var downloadProgressPercent: Int? = null
    private var downloadProgressBytes: Long = 0L
    private var skipNextResumeRestore: Boolean = false
    private var hasAttemptedPhonePermissionRequest: Boolean = false
    private var restoreTaskJob: Job? = null
    private var taskSyncJob: Job? = null
    private var taskSelectionGeneration: Long = 0L
    private val handledConfirmationMessageIds = mutableSetOf<String>()
    private var pendingInitialChatScrollTaskId: String? = null
    private var pendingChatAnchorMessageId: String? = null
    private var pendingChatAnchorTopOffset: Int? = null
    private var clearPendingChatAnchorAfterScroll: Boolean = false
    private var pendingRestoredChatScrollTaskId: String? = null
    private var pendingRestoredChatScrollSnapshot: ChatScrollSnapshot? = null
    private var pendingScrollLatestAfterResponse: Boolean = false
    private var chatScrollStartedByUser: Boolean = false
    private var chatAutoScrollLockedByUser: Boolean = false
    private var chatShouldStickToBottom: Boolean = true
    private var manualChatScrollSnapshot: ChatScrollSnapshot? = null
    private val pendingResponseScrollTaskIds = mutableSetOf<String>()
    private val notifiedBuildSuccessTaskIds = mutableSetOf<String>()
    private val buildMonitorStartedTaskIds = mutableSetOf<String>()
    private var isMessageTextSelectionActive = false
    private val selectedAttachments: MutableList<SelectedAttachment>
        get() = composerDraftViewModel.selectedAttachments
    private var lastRenderedTaskListFingerprint: Long? = null
    private var lastRenderedMessageListFingerprint: Long? = null
    private var lastRenderedAttachmentFingerprint: Long? = null
    private var pendingCameraImageUri: Uri? = null
    private var attachmentFlowInProgress: Boolean = false
    private var pendingInstallApkFile: File? = null
    private var pendingInstallLaunchTaskId: String? = null
    private var pendingInstallLaunchPackageName: String? = null
    private var pendingInstallPreviousVersionCode: Long? = null
    private var pendingInstallPreviousUpdateTime: Long? = null
    private var pendingInstallArtifactIdentity: String? = null
    private var pendingInstallerLaunched: Boolean = false
    private var pendingInstallResolutionJob: Job? = null
    private val attachmentOnlyPromptKeys by lazy(LazyThreadSafetyMode.NONE) {
        setOf(
            getString(R.string.attachment_only_generate_prompt),
            getString(R.string.attachment_only_refine_prompt),
            getString(R.string.attachment_only_chat_prompt)
        ).map(::normalizeMessageTextForDedupe).toSet()
    }

    private val pickReferenceImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishAttachmentFlow()
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val resultData = result.data
            val uris = buildList {
                resultData?.clipData?.let { clipData ->
                    for (index in 0 until clipData.itemCount) {
                        add(clipData.getItemAt(index).uri)
                    }
                }
                resultData?.data?.let(::add)
            }.distinct()
            if (uris.isNotEmpty()) {
                handleAttachmentsSelected(uris, SelectedAttachmentKind.IMAGE)
            }
        }

    private val pickReferenceImageFallbackLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            finishAttachmentFlow()
            if (uris.isNotEmpty()) {
                handleAttachmentsSelected(uris, SelectedAttachmentKind.IMAGE)
            }
        }

    private val captureImageLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved: Boolean ->
            finishAttachmentFlow()
            val uri = pendingCameraImageUri
            pendingCameraImageUri = null
            if (saved && uri != null) {
                handleAttachmentSelected(uri, SelectedAttachmentKind.IMAGE)
            }
        }

    private val pickDocumentAttachmentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            finishAttachmentFlow()
            if (uri != null) {
                val mimeType = contentResolver.getType(uri).orEmpty()
                val kind = if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
                    SelectedAttachmentKind.PDF
                } else {
                    SelectedAttachmentKind.TEXT
                }
                handleAttachmentSelected(uri, kind)
            }
        }

    private val promptReviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val taskId = data.getStringExtra(PromptReviewActivity.EXTRA_TASK_ID).orEmpty()
            val finalPrompt = data.getStringExtra(PromptReviewActivity.EXTRA_PROMPT).orEmpty()
            val messageId = data.getStringExtra(PromptReviewActivity.EXTRA_MESSAGE_ID).orEmpty()
            submitReviewedInitialPrompt(taskId, finalPrompt, messageId)
        }

    private val uiEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val taskId = result.data?.getStringExtra(UiEditorActivity.EXTRA_TASK_ID)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: screenState.selectedTaskId
                ?: return@registerForActivityResult
            refreshTaskUiEditorContext(taskId)
        }

    private val crashReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "kr.ac.kangwon.hai.action.CRASH_REPORT") return

            val rawTaskId = intent.getStringExtra("task_id")
            lastCrashPackage = intent.getStringExtra("package_name")
            val errorMessage = intent.getStringExtra("error_message")
            val reportKind = intent.getStringExtra("report_kind")
            lastStackTrace = intent.getStringExtra("stack_trace")
            val pkg = lastCrashPackage.orEmpty()
            if (!CrashReportSenderPolicy.accepts(this, pkg)) {
                Log.w(TAG, "Crash report dropped because sender package did not match")
                return
            }
            val stack = lastStackTrace.orEmpty()
            val taskId = resolveCrashTaskId(rawTaskId, pkg)
            lastCrashTaskId = taskId ?: rawTaskId

            if (!taskId.isNullOrBlank()) {
                handleRuntimeError(
                    taskId = taskId,
                    packageName = pkg.ifBlank { "알 수 없는 앱" },
                    stackTrace = stack,
                    errorMessage = errorMessage,
                    reportKind = reportKind
                )
            } else {
                Log.w(
                    TAG,
                    "Crash report dropped because task_id could not be resolved raw_task_id=${rawTaskId ?: "-"} package_name=${pkg.ifBlank { "-" }}"
                )
            }
        }
    }

    private val packageInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
            val installedPackage = intent.data?.schemeSpecificPart?.trim().orEmpty()
            if (installedPackage.isBlank()) return
            val pendingPackage = pendingInstallLaunchPackageName
                ?: loadPendingInstallLaunchPackageName()
                ?: return
            if (installedPackage == pendingPackage) {
                launchPendingInstalledAppIfReady("package-broadcast")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        setupRecyclerViews()
        setupNetwork()
        createNotificationChannel()
        deviceId = preferencesStore.getOrCreateDeviceId()
        userIdentity = getOrCreateUserIdentity()
        Log.d(TAG, "App start identity_ready=${deviceId.isNotBlank()}")
        val requestedNotificationPermission = requestNotificationPermissionIfNeeded()
        if (!requestedNotificationPermission && !hasRequiredPhoneNumber()) {
            requestPhoneNumberPermissionIfNeeded()
        }
        setupListeners()
        applyWindowInsets()
        if (savedInstanceState == null && selectedAttachments.isEmpty()) {
            composerDraftAttachmentStore.clearStaleFiles()
        }
        restoreUiState(savedInstanceState)
        loadHiddenTaskIds()
        loadNotifiedBuildSuccessTaskIds()
        loadPersistedTaskChats()
        loadPendingInstallState()
        loadPersistedArtifactStates()
        clearObsoleteApkDownloads()
        loadPersistedRuntimeErrors()
        reconcilePersistedRuntimeErrors()
        pendingTaskSelectionKey = visibleTaskIdCandidate(savedInstanceState?.getString(STATE_SELECTED_TASK_ID))
            ?: visibleTaskIdCandidate(intent?.getStringExtra(STATE_SELECTED_TASK_ID))
            ?: visibleTaskIdCandidate(getLastSelectedTaskId())
        applyImmediateBranchedTaskState(intent)
        renderState()

        ContextCompat.registerReceiver(
            this,
            crashReceiver,
            IntentFilter("kr.ac.kangwon.hai.action.CRASH_REPORT"),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            packageInstallReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        if (hasRequiredPhoneNumber()) {
            skipNextResumeRestore = true
            restoreCurrentTaskState(trigger = "onCreate")
        }
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        mainContent = findViewById(R.id.mainContent)
        topBar = findViewById(R.id.topBar)
        chatCard = findViewById(R.id.chatCard)
        recyclerTasks = findViewById(R.id.recyclerTasks)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        inputPrompt = findViewById(R.id.inputPrompt)
        inputPhoneGate = findViewById(R.id.inputPhoneGate)
        btnAttachReferenceImage = findViewById(R.id.btnAttachReferenceImage)
        selectedAttachmentChip = findViewById(R.id.selectedAttachmentChip)
        selectedAttachmentPreviewScroller = findViewById(R.id.selectedAttachmentPreviewScroller)
        selectedAttachmentPreviewList = findViewById(R.id.selectedAttachmentPreviewList)
        btnSend = findViewById(R.id.btnSend)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnOpenLibrary = findViewById(R.id.btnOpenLibrary)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        drawerNewChatRow = findViewById(R.id.drawerNewChatRow)
        drawerLibraryRow = findViewById(R.id.drawerLibraryRow)
        drawerSettingsRow = findViewById(R.id.drawerSettingsRow)
        btnSavePhoneGate = findViewById(R.id.btnSavePhoneGate)
        btnOpenDrawer = findViewById(R.id.btnOpenDrawer)
        composerBar = findViewById(R.id.composerBar)
        topLogChip = findViewById(R.id.topLogChip)
        topTitleChip = findViewById(R.id.topTitleChip)
        uiDraftContextBar = findViewById(R.id.uiDraftContextBar)
        checkUseSavedUi = findViewById(R.id.checkUseSavedUi)
        btnOpenUiEditor = findViewById(R.id.btnOpenUiEditor)
        phoneGateOverlay = findViewById(R.id.phoneGateOverlay)
        phoneGateContent = findViewById(R.id.phoneGateContent)
        drawerContent = findViewById(R.id.drawerContent)
        topTitleText = findViewById(R.id.topTitleText)
        inputModeLabel = findViewById(R.id.inputModeLabel)
        emptyChatText = findViewById(R.id.emptyChatText)
        topBarBaseTopPadding = topBar.paddingTop
        mainContentBaseBottomPadding = mainContent.paddingBottom
        recyclerMessagesBaseBottomPadding = recyclerMessages.paddingBottom
        drawerContentBaseLeftPadding = drawerContent.paddingLeft
        drawerContentBaseRightPadding = drawerContent.paddingRight
        phoneGateContentBaseLeftPadding = phoneGateContent.paddingLeft
        phoneGateContentBaseRightPadding = phoneGateContent.paddingRight
        topBar.bringToFront()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val contentBottomInset = if (imeVisible) ime.bottom else systemBars.bottom

            topBar.updatePadding(top = topBarBaseTopPadding + systemBars.top)
            mainContent.updatePadding(bottom = mainContentBaseBottomPadding + contentBottomInset)
            recyclerMessages.updatePadding(
                bottom = recyclerMessagesBaseBottomPadding + if (imeVisible) dp(8) else systemBars.bottom
            )

            (chatCard.layoutParams as ViewGroup.MarginLayoutParams).apply {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                topMargin = 0
                bottomMargin = 0
            }
            chatCard.requestLayout()

            composerBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = dp(12) + systemBars.left
                rightMargin = dp(12) + systemBars.right
                bottomMargin = dp(14)
            }

            drawerContent.updatePadding(
                left = drawerContentBaseLeftPadding + systemBars.left,
                top = systemBars.top + dp(16),
                right = drawerContentBaseRightPadding,
                bottom = contentBottomInset + dp(16)
            )

            phoneGateContent.updatePadding(
                left = phoneGateContentBaseLeftPadding + systemBars.left,
                top = systemBars.top + dp(24),
                right = phoneGateContentBaseRightPadding + systemBars.right,
                bottom = systemBars.bottom + dp(24)
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        visibleTaskIdCandidate(
            screenState.pollingTaskId ?: currentTaskId ?: screenState.selectedTaskId ?: getLastSelectedTaskId()
        )
            ?.let(::stopBuildCompletionMonitoring)
        val darkModeEnabled = preferencesStore.loadDarkModeEnabled()
        AppThemeController.applyDarkModePreference(darkModeEnabled)
        if (AppThemeController.shouldRecreateForPreference(this, darkModeEnabled)) {
            recreate()
            return
        }
        loadPersistedArtifactStates()
        loadPersistedRuntimeErrors()
        if (!hasRequiredPhoneNumber() && PhoneNumberResolver.hasPermission(this)) {
            tryFillPhoneNumberFromSim()
        }
        val launchedInstallerNow = retryPendingApkInstallIfReady()
        if (!launchedInstallerNow && !launchPendingInstalledAppIfReady("onResume")) {
            resolveReturnedInstallerIfNeeded()
        }
        if (skipNextResumeRestore) {
            skipNextResumeRestore = false
            return
        }
        restoreCurrentTaskState(trigger = "onResume")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTaskId = intent?.getStringExtra(STATE_SELECTED_TASK_ID)?.trim().orEmpty()
        if (requestedTaskId.isNotBlank() && requestedTaskId !in hiddenTaskIds) {
            pendingTaskSelectionKey = requestedTaskId
            applyImmediateBranchedTaskState(intent)
            if (hasRequiredPhoneNumber()) {
                restoreCurrentTaskState(trigger = "onNewIntent")
            }
        }
    }

    private fun applyImmediateBranchedTaskState(sourceIntent: Intent?): Boolean {
        if (sourceIntent?.getBooleanExtra(EXTRA_BRANCHED_TASK_CREATED, false) != true) return false
        val taskId = visibleTaskIdCandidate(sourceIntent.getStringExtra(EXTRA_SELECTED_TASK_ID)) ?: return false
        val appName = taskDisplayName(sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_APP_NAME))
        val packageName = sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_PACKAGE_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val versionName = sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_VERSION)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "선택한"
        val status = sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_STATUS)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.status_generating)
        val branchMessage = sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_MESSAGE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.task_log_branch_chat_message, versionName)
        val createdAt = sourceIntent.getStringExtra(EXTRA_BRANCHED_TASK_CREATED_AT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: currentTimestampString()

        appendTaskTimelineMessage(
            taskId,
            ChatMessage(
                id = "branch-created-$taskId",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_assistant),
                body = branchMessage,
                createdAt = createdAt,
                eventType = "task_branched"
            )
        )
        ensureTaskSummaryVisible(
            taskId = taskId,
            title = appName?.let { "$it $versionName" },
            appName = appName,
            packageName = packageName,
            status = status,
            hasApk = false
        )
        currentTaskId = taskId
        pendingTaskSelectionKey = taskId
        persistLastSelectedTaskId(taskId)
        requestScrollLatestAfterResponse(force = true)
        screenState = screenState.copy(
            selectedTaskId = taskId,
            displayedAppName = appName,
            messages = buildTaskTimeline(taskId),
            inputMode = InputMode.READ_ONLY,
            currentStatus = status,
            statusDetail = branchMessage,
            canDownload = false,
            canInstall = false
        )
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_CREATED)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_APP_NAME)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_PACKAGE_NAME)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_STATUS)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_MESSAGE)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_VERSION)
        sourceIntent.removeExtra(EXTRA_BRANCHED_TASK_CREATED_AT)
        renderState()
        return true
    }

    override fun onStop() {
        ensureBackgroundMonitoringForActiveTask()
        persistTaskChats()
        super.onStop()
    }

    private fun restoreCurrentTaskState(trigger: String) {
        if (!hasRequiredPhoneNumber()) return
        val taskId = visibleTaskIdCandidate(pendingTaskSelectionKey)
            ?: visibleTaskIdCandidate(currentTaskId)
            ?: visibleTaskIdCandidate(screenState.selectedTaskId)
            ?: visibleTaskIdCandidate(getLastSelectedTaskId())
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        restoreTaskJob = lifecycleScope.launch {
            if (taskId.isNullOrBlank()) {
                fetchTaskList(
                    autoSelectPendingTask = false,
                    selectionGeneration = selectionGeneration
                )
                return@launch
            }

            try {
                ensureTaskChatLoadedAsync(taskId)
                if (!isTaskSelectionGenerationCurrent(selectionGeneration)) {
                    return@launch
                }
                showPersistedTaskPreview(taskId)
                pendingTaskSelectionKey = null
                syncTaskStatus(
                    taskId,
                    autoInstallOnSuccess = false,
                    source = trigger,
                    staleFallback = true,
                    closeDrawerOnSuccess = false,
                    selectionGeneration = selectionGeneration
                )
                if (isTaskSelectionGenerationCurrent(selectionGeneration)) {
                    refreshTaskUiEditorContext(taskId)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logApiFailure("/status/{task_id}", taskId = taskId, deviceId = deviceId, throwable = e)
                if (
                    e is HttpException &&
                    e.code() == 404 &&
                    isTaskSelectionGenerationCurrent(selectionGeneration)
                ) {
                    clearMissingRestoredTask(taskId)
                }
            }

            if (isTaskSelectionGenerationCurrent(selectionGeneration)) {
                fetchTaskList(
                    autoSelectPendingTask = false,
                    selectionGeneration = selectionGeneration
                )
            }
        }
    }

    private fun clearMissingRestoredTask(taskId: String) {
        val before = TaskRestoreSelection(
            pendingTaskId = pendingTaskSelectionKey,
            currentTaskId = currentTaskId,
            selectedTaskId = screenState.selectedTaskId,
            lastSelectedTaskId = getLastSelectedTaskId()
        )
        val recovered = TaskRestorePolicy.removeMissingTask(taskId, before)
        if (recovered == before) return

        pendingTaskSelectionKey = recovered.pendingTaskId
        currentTaskId = recovered.currentTaskId
        if (recovered.lastSelectedTaskId != before.lastSelectedTaskId) {
            persistLastSelectedTaskId(recovered.lastSelectedTaskId)
        }
        if (recovered.selectedTaskId != before.selectedTaskId) {
            latestApkUrl = null
            latestDownloadedApkFile = null
            latestDownloadedTaskId = null
            screenState = screenState.copy(
                selectedTaskId = recovered.selectedTaskId,
                displayedAppName = null,
                messages = emptyList(),
                pollingTaskId = screenState.pollingTaskId?.takeUnless { it == taskId },
                inputMode = InputMode.NEW_GENERATE,
                currentStatus = getString(R.string.status_new_chat),
                statusDetail = getString(R.string.status_new_chat_detail),
                canDownload = false,
                canInstall = false
            )
            renderState()
        }
        Log.w(TAG, "Cleared missing restored task reference task_id=$taskId")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (!hasRequiredPhoneNumber()) {
                    requestPhoneNumberPermissionIfNeeded()
                }
            }
            REQUEST_PHONE_NUMBER_PERMISSION -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    tryFillPhoneNumberFromSim()
                } else {
                    Toast.makeText(this, R.string.phone_permission_required_message, Toast.LENGTH_LONG).show()
                    renderState()
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        recyclerTasks.layoutManager = LinearLayoutManager(this)
        recyclerTasks.adapter = taskAdapter

        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.itemAnimator = null
        recyclerMessages.adapter = chatAdapter
        recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        chatScrollStartedByUser = true
                        lockChatAutoScrollFromUser()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (chatScrollStartedByUser) {
                            updateManualChatScrollState()
                        }
                        chatScrollStartedByUser = false
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (chatScrollStartedByUser) {
                    updateManualChatScrollState()
                }
            }
        })
    }

    private fun setupNetwork() {
        apiService = createVibeApiService(gson = gson)
        downloadApiService = createDownloadVibeApiService(gson = gson)
    }

    private fun createNotificationChannel() {
        BuildNotificationController.createChannels(this, includeMonitorChannel = false)
    }

    private fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        showNotificationPermissionPrompt()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun showNotificationPermissionPrompt() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_rationale_title)
            .setMessage(R.string.notification_permission_rationale_message)
            .setPositiveButton(R.string.notification_permission_rationale_positive) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            .setNegativeButton(R.string.notification_permission_rationale_negative) { _, _ ->
                requestPhonePermissionAfterNotificationPromptIfNeeded()
            }
            .setOnCancelListener {
                requestPhonePermissionAfterNotificationPromptIfNeeded()
            }
            .show()
    }

    private fun requestPhonePermissionAfterNotificationPromptIfNeeded() {
        if (!hasRequiredPhoneNumber()) {
            requestPhoneNumberPermissionIfNeeded()
        }
    }

    private fun setupListeners() {
        inputPhoneGate.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                isFormatting = true

                val formatted = KoreanPhoneNumberFormatter.format(s.toString())

                if (formatted != s.toString()) {
                    s.replace(0, s.length, formatted)
                }

                isFormatting = false
            }
        })

        btnOpenDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val startNewChat = View.OnClickListener {
            resetForNewChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        drawerNewChatRow.setOnClickListener(startNewChat)
        btnNewChat.setOnClickListener(startNewChat)

        drawerSettingsRow.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val openLibrary = View.OnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        drawerLibraryRow.setOnClickListener(openLibrary)

        btnSavePhoneGate.setOnClickListener {
            requestPhoneNumberPermissionIfNeeded()
        }

        btnSend.setOnClickListener {
            hideKeyboardAndClearInputFocus()
            submitMessage()
        }

        btnAttachReferenceImage.setOnClickListener {
            showAttachmentMenu()
        }

        val renameTaskClick = View.OnClickListener {
            showRenameTaskDialog()
        }
        topTitleChip.setOnClickListener(renameTaskClick)
        topTitleText.setOnClickListener(renameTaskClick)

        inputPrompt.setOnTouchListener { view, event ->
            if (view.canScrollVertically(-1) || view.canScrollVertically(1)) {
                view.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        topLogChip.setOnClickListener {
            openTaskLogDetail()
        }
        checkUseSavedUi.setOnCheckedChangeListener { _, checked ->
            if (isRenderingUiDraftContext) return@setOnCheckedChangeListener
            screenState.selectedTaskId?.let { taskId ->
                useSavedUiByTask[taskId] = checked && uiEditorContextByTask[taskId]?.has_saved_ui == true
            }
        }
        btnOpenUiEditor.paintFlags = btnOpenUiEditor.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        btnOpenUiEditor.setOnClickListener { openUiEditorForSelectedTask() }
    }

    private fun openTaskLogDetail() {
        val taskId = screenState.selectedTaskId?.trim()?.takeIf { it.isNotBlank() }
            ?: currentTaskId?.trim()?.takeIf { it.isNotBlank() }
        launchTaskLogDetail(taskId)
    }

    private fun openUiEditorForSelectedTask() {
        val taskId = screenState.selectedTaskId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        refreshTaskUiEditorContext(taskId, openEditorAfterLoad = true)
    }

    private fun refreshTaskUiEditorContext(
        taskId: String,
        openEditorAfterLoad: Boolean = false
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/tasks/{task_id}/ui/editor-context") ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    apiService.getTaskUiEditorContext(
                        taskId = apiTaskId,
                        deviceId = deviceId,
                        userId = null,
                        phoneNumber = userIdentity.phoneNumber
                    )
                }
            }
            result.onSuccess { context ->
                uiEditorContextByTask[apiTaskId] = context
                if (!context.has_saved_ui) useSavedUiByTask[apiTaskId] = false
                if (screenState.selectedTaskId == apiTaskId) renderState()
                if (openEditorAfterLoad) {
                    if (context.source_available && context.revision_label.isNotBlank()) {
                        uiEditorLauncher.launch(
                            Intent(this@MainActivity, UiEditorActivity::class.java)
                                .putExtra(UiEditorActivity.EXTRA_TASK_ID, apiTaskId)
                                .putExtra(UiEditorActivity.EXTRA_REVISION_LABEL, context.revision_label)
                                .putExtra(
                                    UiEditorActivity.EXTRA_APP_NAME,
                                    taskSummaryById[apiTaskId]?.appName ?: screenState.displayedAppName.orEmpty()
                                )
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.ui_editor_context_unavailable,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.onFailure { error ->
                logApiFailure(
                    "/tasks/{task_id}/ui/editor-context",
                    taskId = apiTaskId,
                    deviceId = deviceId,
                    throwable = error
                )
                if (openEditorAfterLoad) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ui_editor_context_load_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun shouldUseSavedUi(taskId: String?): Boolean {
        val normalizedTaskId = taskId?.trim().orEmpty()
        return normalizedTaskId.isNotBlank() &&
            useSavedUiByTask[normalizedTaskId] == true &&
            uiEditorContextByTask[normalizedTaskId]?.has_saved_ui == true
    }

    private fun launchTaskLogDetail(taskId: String?) {
        TaskLogDetailLauncher.open(
            context = this,
            taskId = taskId,
            summary = taskId?.let { taskSummaryById[it] },
            currentStatus = screenState.currentStatus,
            displayedAppName = screenState.displayedAppName,
            messages = taskId?.let { buildTaskTimeline(it) } ?: screenState.messages,
            formatTimestamp = ::formatMessageTimestamp
        )
    }

    private fun loadTaskList(autoSelectPendingTask: Boolean = false) {
        if (!hasRequiredPhoneNumber()) return
        val selectionGeneration = if (autoSelectPendingTask) {
            taskSelectionGeneration
        } else {
            null
        }
        lifecycleScope.launch {
            fetchTaskList(
                autoSelectPendingTask = autoSelectPendingTask,
                selectionGeneration = selectionGeneration
            )
        }
    }

    private suspend fun fetchTaskList(
        autoSelectPendingTask: Boolean,
        selectionGeneration: Long? = null
    ) {
        try {
            logApiRequest("/tasks", deviceId = deviceId)
            val tasksJson = apiService.getTasks(
                deviceId = deviceId,
                userId = null,
                phoneNumber = userIdentity.phoneNumber
            )
            val summaries = parseTaskSummaries(tasksJson)
                .filterNot { it.taskId in hiddenTaskIds }
                .sortedByDescending { it.updatedAt ?: "" }
            taskSummaryById = summaries.associateBy { it.taskId }
            screenState = screenState.copy(taskList = summaries)
            renderState()
            if (autoSelectPendingTask) {
                if (selectionGeneration != null && !isTaskSelectionGenerationCurrent(selectionGeneration)) {
                    Log.d(TAG, "Skip stale auto task selection generation=$selectionGeneration")
                    return
                }
                val pendingKey = pendingTaskSelectionKey?.takeIf { it.isNotBlank() }
                val resolvedTaskId = pendingKey?.let { resolveExactTaskIdCandidate(it, summaries) }
                if (selectionGeneration != null && !isTaskSelectionGenerationCurrent(selectionGeneration)) {
                    Log.d(TAG, "Skip stale auto task selection after resolve generation=$selectionGeneration")
                    return
                }
                pendingTaskSelectionKey = null
                if (resolvedTaskId != null) {
                    selectTask(resolvedTaskId, autoInstallOnSuccess = false)
                } else if (summaries.isEmpty()) {
                    persistLastSelectedTaskId(null)
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logApiFailure("/tasks", deviceId = deviceId, throwable = e)
            showLocalSystemMessage(
                getString(R.string.message_title_status),
                getString(R.string.tasks_load_failed, userVisibleErrorMessage(e)),
                kind = MessageKind.LOG
            )
        }
    }

    private fun resetForNewChat() {
        handOffVisibleBuildToBackground()
        stopPolling()
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        advanceTaskSelectionGeneration()
        currentTaskId = null
        pendingTaskSelectionKey = null
        chatAutoScrollLockedByUser = false
        chatShouldStickToBottom = true
        chatScrollStartedByUser = false
        manualChatScrollSnapshot = null
        persistLastSelectedTaskId(null)
        latestApkUrl = null
        latestDownloadedApkFile = null
        latestDownloadedTaskId = null
        clearSelectedAttachment(render = false)
        inputPrompt.setText("")
        screenState = screenState.copy(
            selectedTaskId = null,
            displayedAppName = null,
            messages = emptyList(),
            pollingTaskId = null,
            inputMode = InputMode.NEW_GENERATE,
            currentStatus = getString(R.string.status_new_chat),
            statusDetail = getString(R.string.status_new_chat_detail),
            canDownload = false,
            canInstall = false
        )
        renderState()
    }

    private fun hideTaskFromChatList(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        hiddenTaskIds += normalizedTaskId
        persistHiddenTaskIds()
        stopBuildCompletionMonitoring(normalizedTaskId)
        taskConversationMessages.remove(normalizedTaskId)
        loadedTaskChatIds.remove(normalizedTaskId)
        taskTimelineRenderCache.remove(normalizedTaskId)
        taskTimelineEventCursorById.remove(normalizedTaskId)
        preferencesStore.deleteTaskChat(normalizedTaskId)
        taskLastStatusKeys.remove(normalizedTaskId)
        if (currentTaskId == normalizedTaskId || screenState.selectedTaskId == normalizedTaskId) {
            resetForNewChat()
        }
        val filteredTasks = screenState.taskList.filterNot { it.taskId == normalizedTaskId }
        taskSummaryById = taskSummaryById.filterKeys { it != normalizedTaskId }
        screenState = screenState.copy(taskList = filteredTasks)
        renderState()
        Toast.makeText(this, R.string.task_hidden_from_chat_list, Toast.LENGTH_SHORT).show()
    }

    private fun confirmHideTaskFromChatList(summary: TaskSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.task_hide_confirm_title)
            .setMessage(getString(R.string.task_hide_confirm_message, summary.title))
            .setNegativeButton(R.string.confirmation_cancel, null)
            .setPositiveButton(R.string.task_hide_confirm_positive) { _, _ ->
                hideTaskFromChatList(summary.taskId)
            }
            .show()
    }

    private fun startBuildCompletionMonitoring(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return
        buildMonitorStartedTaskIds.add(normalizedTaskId)
        try {
            BuildMonitorService.startMonitoring(this, normalizedTaskId)
        } catch (e: RuntimeException) {
            buildMonitorStartedTaskIds -= normalizedTaskId
            Log.w(TAG, "Unable to start build completion monitor task_id=$normalizedTaskId", e)
        }
    }

    private fun stopBuildCompletionMonitoring(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        buildMonitorStartedTaskIds -= normalizedTaskId
        BuildMonitorService.stopMonitoring(this, normalizedTaskId)
    }

    private fun ensureBackgroundMonitoringForActiveTask() {
        val activeTaskId = screenState.pollingTaskId
            ?: currentTaskId
            ?: screenState.selectedTaskId
            ?: return
        if (
            screenState.inputMode == InputMode.READ_ONLY ||
            processingAnimationBaseText(screenState.currentStatus) != null ||
            processingAnimationBaseText(screenState.statusDetail.orEmpty()) != null
        ) {
            startBuildCompletionMonitoring(activeTaskId)
        }
    }

    private fun handOffVisibleBuildToBackground(nextTaskId: String? = null) {
        val activeTaskId = screenState.pollingTaskId
            ?: screenState.selectedTaskId
            ?: currentTaskId
            ?: return
        if (activeTaskId == nextTaskId || activeTaskId in hiddenTaskIds) return
        if (
            screenState.pollingTaskId == activeTaskId ||
            screenState.inputMode == InputMode.READ_ONLY ||
            processingAnimationBaseText(screenState.currentStatus) != null ||
            processingAnimationBaseText(screenState.statusDetail.orEmpty()) != null
        ) {
            startBuildCompletionMonitoring(activeTaskId)
        }
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        inputPrompt.setText(savedInstanceState.getString(STATE_INPUT_PROMPT).orEmpty())
        if (selectedAttachments.isEmpty()) {
            val descriptors = savedInstanceState.getString(STATE_COMPOSER_ATTACHMENTS)
                ?.takeIf { it.isNotBlank() }
                ?.let { serialized ->
                    runCatching {
                        gson.fromJson(serialized, Array<PersistedComposerAttachment>::class.java).toList()
                    }.getOrNull()
                }
                .orEmpty()
            selectedAttachments += descriptors.mapNotNull(composerDraftAttachmentStore::restore)
        }
        val restoredTaskId = visibleTaskIdCandidate(savedInstanceState.getString(STATE_SELECTED_TASK_ID))
        val restoredMessageId = savedInstanceState.getString(STATE_CHAT_SCROLL_MESSAGE_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (
            !restoredTaskId.isNullOrBlank() &&
            !restoredMessageId.isNullOrBlank() &&
            savedInstanceState.containsKey(STATE_CHAT_SCROLL_TOP_OFFSET)
        ) {
            pendingRestoredChatScrollTaskId = restoredTaskId
            pendingRestoredChatScrollSnapshot = ChatScrollSnapshot(
                messageId = restoredMessageId,
                topOffset = savedInstanceState.getInt(STATE_CHAT_SCROLL_TOP_OFFSET)
            )
            chatAutoScrollLockedByUser = true
            chatShouldStickToBottom = false
        }
    }

    private fun submitMessage() {
        val displayPrompt = inputPrompt.text.toString().trim()
        val attachments = selectedAttachments.toList()
        if (displayPrompt.isBlank() && attachments.isEmpty()) return
        val prompt = displayPrompt
        val attachedImagePreview = attachments.toChatImagePreview()
        val useSavedUi = shouldUseSavedUi(currentTaskId ?: screenState.selectedTaskId)

        if (isComposerOnNewChatSurface()) {
            clearComposerDraftAfterSubmit()
            currentTaskId = null
            pendingTaskSelectionKey = null
            persistLastSelectedTaskId(null)
            screenState = screenState.copy(
                selectedTaskId = null,
                displayedAppName = null,
                inputMode = InputMode.NEW_GENERATE,
                canDownload = false,
                canInstall = false
            )
            startAppSynthesis(prompt, attachedImagePreview, attachments = attachments, displayPrompt = displayPrompt)
            return
        }

        when (screenState.inputMode) {
            InputMode.NEW_GENERATE -> {
                clearComposerDraftAfterSubmit()
                startAppSynthesis(prompt, attachedImagePreview, attachments = attachments, displayPrompt = displayPrompt)
            }
            InputMode.CHAT -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    clearComposerDraftAfterSubmit()
                    startTaskChatMessage(
                        taskId,
                        prompt,
                        attachedImagePreview,
                        attachments,
                        displayPrompt = displayPrompt,
                        useSavedUi = useSavedUi
                    )
                } else {
                    clearComposerDraftAfterSubmit()
                    startAppSynthesis(prompt, attachedImagePreview, attachments = attachments, displayPrompt = displayPrompt)
                }
            }
            InputMode.CONTINUE_CLARIFICATION -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    clearComposerDraftAfterSubmit()
                    continueClarification(
                        taskId,
                        prompt,
                        attachments = attachments,
                        displayPrompt = displayPrompt,
                        useSavedUi = useSavedUi
                    )
                }
            }
            InputMode.REFINE_EXISTING -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    clearComposerDraftAfterSubmit()
                    dispatchLatestTaskFeedback(
                        taskId,
                        prompt,
                        attachedImagePreview,
                        attachments,
                        displayPrompt = displayPrompt,
                        useSavedUi = useSavedUi
                    )
                }
            }
            InputMode.RETRY_FAILED -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    clearComposerDraftAfterSubmit()
                    dispatchLatestTaskFeedback(
                        taskId,
                        prompt,
                        attachedImagePreview,
                        attachments,
                        displayPrompt = displayPrompt,
                        useSavedUi = useSavedUi
                    )
                }
            }
            InputMode.READ_ONLY -> {
                val taskId = currentTaskId ?: screenState.selectedTaskId
                if (!taskId.isNullOrBlank() && isTaskInputQueueActive(taskId)) {
                    clearComposerDraftAfterSubmit()
                    enqueueInputForActiveTask(
                        taskId,
                        prompt,
                        displayPrompt,
                        attachedImagePreview,
                        attachments,
                        useSavedUi
                    )
                } else {
                    Toast.makeText(this, R.string.read_only_hint, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearComposerDraftAfterSubmit() {
        inputPrompt.setText("")
        clearSelectedAttachment(render = false)
    }

    private fun isComposerOnNewChatSurface(): Boolean {
        if (screenState.inputMode == InputMode.NEW_GENERATE) return true
        if (screenState.inputMode == InputMode.READ_ONLY) return false
        val hasSelectedTask = !screenState.selectedTaskId.isNullOrBlank()
        val hasVisibleMessages = screenState.messages.any { it.kind != MessageKind.DATE_SEPARATOR }
        return !hasSelectedTask && !hasVisibleMessages
    }

    private fun clearSelectedAttachment(render: Boolean = true) {
        composerDraftAttachmentStore.clear(selectedAttachments)
        selectedAttachments.clear()
        lastRenderedAttachmentFingerprint = null
        if (render) {
            renderState()
        }
    }

    private fun enqueueInputForActiveTask(
        taskId: String,
        prompt: String,
        displayPrompt: String,
        imagePreview: ChatImagePreview?,
        attachments: List<SelectedAttachment>,
        useSavedUi: Boolean
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/generate") ?: return
        taskInputQueueManager.enqueue(
            apiTaskId,
            QueuedTaskInput(
                prompt = prompt,
                displayPrompt = displayPrompt,
                imagePreview = imagePreview,
                attachments = attachments,
                useSavedUi = useSavedUi
            )
        )
        appendOptimisticTaskMessage(
            apiTaskId,
            ChatMessage(
                id = "queued-input-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.USER,
                title = getString(R.string.message_title_user),
                body = displayPrompt,
                createdAt = currentTimestampString(),
                imagePreviewBase64 = imagePreview?.base64,
                imagePreviewName = imagePreview?.displayName,
                imagePreviews = attachments.toChatImagePreviews()
            ),
            allowDuplicateContent = true
        )
        addTaskEvent(
            apiTaskId,
            ChatMessage(
                id = "queued-input-status-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_queued_input_added),
                createdAt = currentTimestampString()
            )
        )
        clearSelectedAttachment()
    }

    private fun isTaskInputQueueActive(taskId: String): Boolean {
        return taskInputQueueManager.isQueueActive(
            taskId = taskId,
            state = TaskInputQueueState(
                selectedTaskId = screenState.selectedTaskId,
                currentTaskId = currentTaskId,
                pollingTaskId = screenState.pollingTaskId,
                isPollingActive = pollingJob?.isActive == true,
                inputMode = screenState.inputMode,
                currentStatus = screenState.currentStatus,
                statusDetail = screenState.statusDetail
            )
        )
    }

    private fun processNextQueuedInputIfReady(taskId: String): Boolean {
        val nextInput = taskInputQueueManager.dequeueNext(taskId) ?: return false
        addTaskEvent(
            taskId,
            ChatMessage(
                id = "queued-input-start-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_queued_input_start),
                createdAt = currentTimestampString()
            )
        )
        startFollowupSynthesis(
            taskId = taskId,
            prompt = nextInput.prompt,
            imagePreview = nextInput.imagePreview,
            attachments = nextInput.attachments,
            mode = InputMode.REFINE_EXISTING,
            appendUserMessage = false,
            displayPrompt = nextInput.displayPrompt,
            useSavedUi = nextInput.useSavedUi
        )
        return true
    }

    private fun dispatchLatestTaskFeedback(
        taskId: String,
        feedback: String,
        imagePreview: ChatImagePreview? = null,
        attachments: List<SelectedAttachment> = selectedAttachments.toList(),
        displayPrompt: String = feedback,
        useSavedUi: Boolean = false
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        val referenceImagePreview = if (screenState.inputMode == InputMode.REFINE_EXISTING) {
            imagePreview ?: attachments.toChatImagePreview()
        } else {
            null
        }
        startFollowupSynthesis(
            taskId = apiTaskId,
            prompt = feedback,
            imagePreview = referenceImagePreview,
            attachments = attachments,
            mode = screenState.inputMode,
            displayPrompt = displayPrompt,
            useSavedUi = useSavedUi
        )
    }

    private fun openPromptReview(message: ChatMessage) {
        val taskId = message.promptReviewTaskId
            ?: message.confirmTaskId
            ?: screenState.selectedTaskId
            ?: currentTaskId
            ?: return
        val prompt = message.promptReviewText
            ?: message.confirmPayload
            ?: message.body
        if (prompt.isBlank()) return
        promptReviewLauncher.launch(
            Intent(this, PromptReviewActivity::class.java)
                .putExtra(PromptReviewActivity.EXTRA_TASK_ID, taskId)
                .putExtra(PromptReviewActivity.EXTRA_PROMPT, prompt)
                .putExtra(PromptReviewActivity.EXTRA_MESSAGE_ID, message.id)
        )
    }

    private fun submitReviewedInitialPrompt(taskId: String, finalPrompt: String, promptReviewMessageId: String = "") {
        val trimmedPrompt = finalPrompt.trim()
        if (trimmedPrompt.isBlank()) {
            Toast.makeText(this, R.string.prompt_review_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val apiTaskId = resolveApiTaskId(taskId, "/generate") ?: return
        if (promptReviewMessageId.isNotBlank()) {
            if (handledConfirmationMessageIds.add(promptReviewMessageId)) {
                refreshConfirmationActions(setOf(promptReviewMessageId))
            }
        }
        val displayPrompt = getString(R.string.prompt_review_sent_bubble)
        ensureTaskChatLoaded(apiTaskId)
        appendOptimisticTaskMessage(
            apiTaskId,
            ChatMessage(
                id = "prompt-review-submit-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.USER,
                title = getString(R.string.message_title_user),
                body = displayPrompt,
                createdAt = currentTimestampString()
            ),
            allowDuplicateContent = true
        )
        startFollowupSynthesis(
            taskId = apiTaskId,
            prompt = trimmedPrompt,
            attachments = emptyList(),
            mode = InputMode.CONTINUE_CLARIFICATION,
            appendUserMessage = false,
            displayPrompt = displayPrompt,
            requestAction = "submit_initial_prompt"
        )
    }

    private fun handleConfirmationAccepted(message: ChatMessage) {
        if (message.confirmAction == "submit_initial_prompt") {
            openPromptReview(message)
            return
        }
        if (!handledConfirmationMessageIds.add(message.id)) return
        val taskId = message.confirmTaskId ?: screenState.selectedTaskId ?: currentTaskId ?: return
        val payload = message.confirmPayload.orEmpty()
        recordConfirmationInteraction(
            taskId = taskId,
            message = message,
            eventType = "confirmation_accepted",
            selectedAction = message.confirmAction.orEmpty(),
            selectedPayload = payload
        )
        when (message.confirmAction.orEmpty()) {
            "refine" -> startRefinement(taskId, payload)
            "retry" -> startRetry(taskId, payload)
            "repair_runtime" -> {
                val pendingRuntimeError = pendingRuntimeErrors[taskId]
                if (pendingRuntimeError != null && pendingRuntimeError.awaitingUserConfirmation) {
                    pendingRuntimeErrors[taskId] = pendingRuntimeError.copy(awaitingUserConfirmation = false)
                    persistPendingRuntimeErrors()
                    addTaskEvent(
                        taskId,
                        ChatMessage(
                            id = "runtime-repair-start-$taskId-${System.currentTimeMillis()}",
                            kind = MessageKind.STATUS,
                            title = getString(R.string.message_title_status),
                            body = getString(R.string.runtime_repair_in_progress)
                        )
                    )
                    startFollowupSynthesis(
                        taskId = taskId,
                        prompt = buildRuntimeRepairPrompt(pendingRuntimeError),
                        mode = InputMode.RETRY_FAILED,
                        appendUserMessage = false
                    )
                }
            }
            "continue_generate" -> continueClarification(taskId, payload, appendUserMessage = false)
            "generate_confirm" -> continueClarification(taskId, payload.ifBlank { "네" }, appendUserMessage = false)
            "route_confirm" -> dispatchLatestTaskFeedback(taskId, payload.ifBlank { "계속 진행해줘" })
        }
        renderState()
    }

    private fun handleConfirmationDismissed(message: ChatMessage) {
        if (!handledConfirmationMessageIds.add(message.id)) return
        val taskId = message.confirmTaskId ?: screenState.selectedTaskId ?: currentTaskId ?: return
        recordConfirmationInteraction(
            taskId = taskId,
            message = message,
            eventType = "confirmation_dismissed",
            selectedAction = message.confirmAction.orEmpty(),
            selectedPayload = message.confirmPayload.orEmpty()
        )
        if (message.confirmAction == "generate_confirm") {
            val body = getString(R.string.confirmation_generate_cancelled)
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "confirm-generate-dismissed-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = body
                )
            )
            screenState = screenState.copy(
                selectedTaskId = taskId,
                inputMode = InputMode.CONTINUE_CLARIFICATION,
                currentStatus = displayStatusText("Pending Decision"),
                statusDetail = body
            )
            renderState()
            setComposerEnabled(true)
            return
        }
        appendOptimisticTaskMessage(
            taskId,
            ChatMessage(
                id = "confirm-dismissed-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_assistant),
                body = getString(R.string.confirmation_dismissed)
            )
        )
        renderState()
    }

    private fun recordConfirmationInteraction(
        taskId: String,
        message: ChatMessage,
        eventType: String,
        selectedAction: String,
        selectedPayload: String
    ) {
        Log.d(
            TAG,
            "Confirmation handled locally task_id=$taskId event=$eventType action=$selectedAction payload_length=${selectedPayload.length} message_id=${message.id}"
        )
    }

    private fun startAppSynthesis(
        prompt: String,
        imagePreview: ChatImagePreview? = null,
        attachments: List<SelectedAttachment> = selectedAttachments.toList(),
        sourceTaskId: String? = null,
        displayPrompt: String? = null,
        requestAction: String? = null,
        useSavedUi: Boolean = false
    ) {
        val deviceInfo = collectDeviceInfo()
        val referenceImagePreview = imagePreview ?: attachments.toChatImagePreview()
        val referenceImageName = referenceImagePreview?.displayName
        val referenceImageBase64 = referenceImagePreview?.base64
        val attachmentPayloads = attachments.toPayloads().takeIf { it.isNotEmpty() }
        val visiblePrompt = displayPrompt ?: prompt
        if (sourceTaskId == null) {
            appendLocalUserMessage(visiblePrompt, referenceImagePreview, attachments.toChatImagePreviews())
        }
        if (sourceTaskId == null) {
            showThinkingMessage()
        }
        lifecycleScope.launch {
            try {
                setComposerEnabled(sourceTaskId != null)
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_thinking),
                    statusDetail = null
                )
                renderState()

                logApiRequest("/generate", deviceId = deviceId)
                val response = apiService.generateApp(
                    BuildRequest(
                        task_id = sourceTaskId,
                        prompt = prompt,
                        display_prompt = displayPrompt,
                        device_info = deviceInfo,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber,
                        reference_image_name = referenceImageName,
                        reference_image_base64 = referenceImageBase64,
                        request_action = requestAction,
                        attachments = attachmentPayloads,
                        use_ui_editor_draft = useSavedUi
                    )
                )
                if (sourceTaskId == null) {
                    moveLocalConversationToTask(response.task_id)
                    pendingResponseScrollTaskIds += response.task_id
                }
                currentTaskId = response.task_id
                if (sourceTaskId != null) {
                    useSavedUiByTask[sourceTaskId] = false
                    uiEditorContextByTask.remove(sourceTaskId)
                    refreshTaskUiEditorContext(response.task_id)
                }
                removeLoadingMessages(response.task_id)
                val shouldStartBuildWorkflow = shouldStartBuildWorkflow(response)
                applyGenerateDecisionResponse(response)
                if (shouldStartBuildWorkflow) {
                    refreshCurrentTaskAfterFollowup(
                        response.task_id,
                        autoInstallOnSuccess = true,
                        optimisticStatus = buildWorkflowStartStatusText(response),
                        optimisticDetail = buildWorkflowStartDetail(response)
                    )
                } else {
                    reenterTaskConversation(response.task_id, scrollToTop = false, scrollToLatest = true)
                    setComposerEnabled(true)
                }
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                setComposerEnabled(true)
                removeLoadingMessages(sourceTaskId)
                logApiFailure("/generate", deviceId = deviceId, throwable = e)
                showLocalSystemMessage(getString(R.string.message_title_log), getString(R.string.generate_failed, userVisibleErrorMessage(e)))
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.generate_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
    }

    private fun continueClarification(
        taskId: String,
        prompt: String,
        appendUserMessage: Boolean = true,
        attachments: List<SelectedAttachment> = selectedAttachments.toList(),
        displayPrompt: String = prompt,
        requestAction: String? = null,
        useSavedUi: Boolean = false
    ) {
        startFollowupSynthesis(
            taskId,
            prompt,
            imagePreview = attachments.toChatImagePreview(),
            attachments = attachments,
            mode = InputMode.CONTINUE_CLARIFICATION,
            appendUserMessage = appendUserMessage,
            displayPrompt = displayPrompt,
            requestAction = requestAction,
            useSavedUi = useSavedUi
        )
    }

    private fun selectTask(taskId: String, autoInstallOnSuccess: Boolean) {
        val resolvedTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        if (resolvedTaskId.isBlank() || resolvedTaskId in hiddenTaskIds) return
        handOffVisibleBuildToBackground(nextTaskId = resolvedTaskId)
        stopPolling()
        stopBuildCompletionMonitoring(resolvedTaskId)
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        currentTaskId = resolvedTaskId
        pendingTaskSelectionKey = null
        persistLastSelectedTaskId(resolvedTaskId)
        requestScrollLatestAfterResponse(force = true)
        val summary = taskSummaryById[resolvedTaskId]
        screenState = screenState.copy(
            selectedTaskId = resolvedTaskId,
            displayedAppName = summary?.appName,
            messages = taskConversationMessages[resolvedTaskId]
                ?.let { buildTaskTimeline(resolvedTaskId) }
                .orEmpty(),
            currentStatus = getString(R.string.status_loading_task),
            statusDetail = getString(R.string.status_loading_task_detail),
            canDownload = persistedApkUrlForTask(resolvedTaskId) != null,
            canInstall = persistedDownloadedApkFileForTask(resolvedTaskId) != null
        )
        drawerLayout.closeDrawer(GravityCompat.START)
        renderState()
        refreshTaskUiEditorContext(resolvedTaskId)

        taskSyncJob = lifecycleScope.launch {
            try {
                ensureTaskChatLoadedAsync(resolvedTaskId)
                if (!isTaskSelectionGenerationCurrent(selectionGeneration)) {
                    return@launch
                }
                screenState = screenState.copy(messages = buildTaskTimeline(resolvedTaskId))
                renderState()
                syncTaskStatus(
                    resolvedTaskId,
                    autoInstallOnSuccess = autoInstallOnSuccess,
                    source = "selectTask",
                    staleFallback = false,
                    closeDrawerOnSuccess = false,
                    requestedTaskId = taskId,
                    selectionGeneration = selectionGeneration
                )
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logApiFailure("/status/{task_id}", taskId = resolvedTaskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    resolvedTaskId,
                    ChatMessage(
                        id = "status-error-$resolvedTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.status_fetch_failed, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.status_fetch_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
    }

    private fun refreshCurrentTaskAfterFollowup(
        taskId: String,
        autoInstallOnSuccess: Boolean,
        optimisticStatus: String? = null,
        optimisticDetail: String? = null
    ) {
        val resolvedTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        if (resolvedTaskId.isBlank()) return
        stopPolling()
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        currentTaskId = resolvedTaskId
        pendingTaskSelectionKey = null
        persistLastSelectedTaskId(resolvedTaskId)
        if (pendingInitialChatScrollTaskId == resolvedTaskId) {
            pendingInitialChatScrollTaskId = null
        }
        requestScrollLatestAfterResponse(force = true)
        ensureTaskSummaryVisible(
            taskId = resolvedTaskId,
            title = taskSummaryById[resolvedTaskId]?.title ?: screenState.messages.firstOrNull { it.kind == MessageKind.USER }?.body,
            appName = taskSummaryById[resolvedTaskId]?.appName ?: screenState.displayedAppName,
            packageName = taskSummaryById[resolvedTaskId]?.packageName,
            status = optimisticStatus ?: getString(R.string.status_loading_task),
            hasApk = persistedApkUrlForTask(resolvedTaskId) != null
        )
        screenState = screenState.copy(
            selectedTaskId = resolvedTaskId,
            messages = buildTaskTimeline(resolvedTaskId),
            currentStatus = optimisticStatus ?: getString(R.string.status_loading_task),
            statusDetail = optimisticDetail ?: getString(R.string.status_loading_task_detail),
            canDownload = persistedApkUrlForTask(resolvedTaskId) != null,
            canInstall = persistedDownloadedApkFileForTask(resolvedTaskId) != null
        )
        renderState()

        taskSyncJob = lifecycleScope.launch {
            try {
                syncTaskStatus(
                    resolvedTaskId,
                    autoInstallOnSuccess = autoInstallOnSuccess,
                    source = "followup",
                    staleFallback = false,
                    closeDrawerOnSuccess = false,
                    requestedTaskId = taskId,
                    selectionGeneration = selectionGeneration
                )
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logApiFailure("/status/{task_id}", taskId = resolvedTaskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    resolvedTaskId,
                    ChatMessage(
                        id = "followup-status-error-$resolvedTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.status_fetch_failed, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.status_fetch_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
    }

    private fun applyGenerateDecisionResponse(response: BuildResponse) {
        val taskId = response.task_id
        val summary = response.summary?.trim().orEmpty()
        val message = response.message?.trim().orEmpty()
        val questions = response.questions.orEmpty().filter { it.isNotBlank() }
        val confirmationAction = response.confirmation_action?.trim().orEmpty()
        val confirmationPayload = response.confirmation_payload?.trim().orEmpty()
        val preparedPrompt = response.prepared_prompt?.trim().orEmpty()
            .ifBlank { confirmationPayload }
        val responseAppName = taskDisplayName(response.generated_app_name)
            ?: taskDisplayName(response.app_name)
        val responsePackageName = response.package_name?.trim()?.takeIf { it.isNotBlank() }

        if (TaskStatusPolicy.isCancelled(response.status)) {
            addTaskEvent(
                taskId,
                ChatMessage(
                    id = "generate-cancelled-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.STATUS,
                    title = getString(R.string.message_title_status),
                    body = getString(R.string.status_cancelled),
                    createdAt = currentTimestampString()
                )
            )
            screenState = screenState.copy(
                selectedTaskId = taskId,
                inputMode = InputMode.CHAT,
                currentStatus = getString(R.string.status_cancelled),
                statusDetail = response.message?.trim()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.status_cancelled)
            )
            renderState()
            setComposerEnabled(true)
            return
        }

        if (responseAppName != null || responsePackageName != null) {
            ensureTaskSummaryVisible(
                taskId = taskId,
                title = responseAppName
                    ?: summary.takeIf { it.isNotBlank() }
                    ?: taskSummaryById[taskId]?.title,
                appName = responseAppName ?: taskSummaryById[taskId]?.appName,
                packageName = responsePackageName ?: taskSummaryById[taskId]?.packageName,
                status = resolveStatusDisplayText(response.status, null, null),
                hasApk = false
            )
            screenState = screenState.copy(
                selectedTaskId = taskId,
                displayedAppName = responseAppName ?: screenState.displayedAppName
            )
        }

        appendImageReferenceMessages(
            taskId,
            response.image_reference_summary,
            response.image_conflict_note
        )

        if (summary.isNotBlank() && shouldRenderDecisionSummary(response)) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-summary-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = summary
                )
            )
        }

        if (
            response.tool == "answer_question" &&
            message.isNotBlank() &&
            isAssistantRenderMode(response) &&
            !shouldSuppressDecisionAssistantMessage(response)
        ) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-answer-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = message
                )
            )
            screenState = screenState.copy(
                selectedTaskId = taskId,
                inputMode = InputMode.CHAT,
                currentStatus = resolveStatusDisplayText(response.status, null, null),
                statusDetail = message
            )
            renderState()
            setComposerEnabled(true)
            return
        }

        if (response.tool == "ask_confirmation") {
            if (isPromptReviewRenderMode(response) && preparedPrompt.isNotBlank()) {
                val promptReviewMessage = message.ifBlank {
                    summary.ifBlank { getString(R.string.prompt_review_open) }
                }
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "decision-prompt-review-$taskId-${preparedPrompt.hashCode()}",
                        kind = MessageKind.CONFIRMATION,
                        title = getString(R.string.confirmation_title),
                        body = preparedPrompt,
                        detail = promptReviewMessage.takeIf { it.isNotBlank() },
                        createdAt = currentTimestampString(),
                        confirmAction = "submit_initial_prompt",
                        confirmTaskId = taskId,
                        confirmPayload = preparedPrompt,
                        promptReviewTaskId = taskId,
                        promptReviewText = preparedPrompt
                    )
                )
                screenState = screenState.copy(
                    selectedTaskId = taskId,
                    inputMode = InputMode.CONTINUE_CLARIFICATION,
                    currentStatus = resolveStatusDisplayText(response.status, null, null),
                    statusDetail = promptReviewMessage
                )
                renderState()
                setComposerEnabled(false)
                return
            }
            if (isConfirmationRenderMode(response) && confirmationAction.isNotBlank()) {
                val confirmationBody = questions.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.confirmation_generate_preview)
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "decision-confirmation-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.CONFIRMATION,
                        title = getString(R.string.confirmation_title),
                        body = confirmationBody,
                        detail = summary.takeIf { it.isNotBlank() },
                        confirmAction = confirmationAction,
                        confirmTaskId = taskId,
                        confirmPayload = confirmationPayload
                    )
                )
                val confirmationDetail = summary.ifBlank {
                    message.ifBlank { confirmationBody }
                }
                screenState = screenState.copy(
                    selectedTaskId = taskId,
                    inputMode = InputMode.CONTINUE_CLARIFICATION,
                    currentStatus = resolveStatusDisplayText(response.status, null, null),
                    statusDetail = confirmationDetail
                )
                renderState()
                setComposerEnabled(true)
                return
            }
            val clarificationBody = buildClarificationBubbleBody(
                message = message,
                reason = response.reason,
                questions = questions
            )
            if (!clarificationBody.isNullOrBlank()) {
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "decision-clarification-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.ASSISTANT,
                        title = getString(R.string.message_title_assistant),
                        body = clarificationBody
                    )
                )
            }
            val clarificationDetail = clarificationBody
                ?: response.reason?.trim()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.status_no_detail)
            screenState = screenState.copy(
                selectedTaskId = taskId,
                inputMode = InputMode.CONTINUE_CLARIFICATION,
                currentStatus = resolveStatusDisplayText(response.status, null, null),
                statusDetail = clarificationDetail
            )
            renderState()
            setComposerEnabled(true)
            return
        }

        val questionBody = buildClarificationBubbleBody(
            message = null,
            reason = null,
            questions = questions
        )
        if (!questionBody.isNullOrBlank()) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-question-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = questionBody
                )
            )
        }

        if (response.tool == "research_then_build") {
            addTaskEvent(
                taskId,
                ChatMessage(
                    id = "web-research-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.STATUS,
                    title = getString(R.string.message_title_status),
                    body = getString(R.string.status_web_researching),
                    createdAt = currentTimestampString()
                )
            )
        }

        val buildStartStatus = buildWorkflowStartStatusText(response)
        val buildStartDetail = buildWorkflowStartDetail(response)

        screenState = screenState.copy(
            selectedTaskId = taskId,
            inputMode = InputMode.READ_ONLY,
            currentStatus = buildStartStatus,
            statusDetail = buildStartDetail
        )
        renderState()
    }

    private fun startRefinement(taskId: String, feedback: String) {
        val attachments = selectedAttachments.toList()
        startFollowupSynthesis(
            taskId = taskId,
            prompt = feedback,
            imagePreview = attachments.toChatImagePreview(),
            attachments = attachments,
            mode = InputMode.REFINE_EXISTING
        )
    }

    private fun startRetry(taskId: String, feedback: String) {
        startFollowupSynthesis(taskId = taskId, prompt = feedback, mode = InputMode.RETRY_FAILED)
    }

    private fun applyStatus(
        taskId: String,
        response: StatusResponse,
        autoInstallOnSuccess: Boolean,
        syncPolling: Boolean
    ) {
        if (taskId in hiddenTaskIds) {
            if (currentTaskId == taskId || screenState.selectedTaskId == taskId) {
                resetForNewChat()
            }
            return
        }
        if (!TaskStatusUpdatePolicy.targetsVisibleTask(taskId, screenState.selectedTaskId)) {
            applyInactiveTaskStatus(taskId, response)
            return
        }
        currentTaskId = taskId
        val evaluation = TaskStatusPolicy.evaluate(response)
        val normalizedStatus = evaluation.normalizedStatus
        val isSuccess = evaluation.isSuccess
        val isCancelled = evaluation.isCancelled
        val isClarifying = evaluation.isClarifying
        val isFailedBuild = TaskStatusPolicy.isRetryableFailure(normalizedStatus)
        val isRetryable = evaluation.isRetryable
        val isPollingStatus = evaluation.isPolling
        val isErrorResponse = evaluation.isResponseError
        val allowArtifactActions = isSuccess && !isPollingStatus
        val progressMode = evaluation.progressMode
        latestApkUrl = resolveApkUrl(taskId, response, isSuccess)
        val hasArtifact = latestApkUrl != null || persistedApkUrlForTask(taskId) != null
        updateTaskArtifactState(
            taskId,
            apkUrl = latestApkUrl,
            downloadedApkFile = persistedDownloadedApkFileForTask(taskId)
        )
        val pendingRuntimeError = pendingRuntimeErrors[taskId]
        if (!isSuccess) {
            if (notifiedBuildSuccessTaskIds.remove(taskId)) {
                persistNotifiedBuildSuccessTaskIds()
            }
        }
        if (isSuccess && pendingRuntimeError?.awaitingUserConfirmation == false) {
            runtimeErrorTaskIds -= taskId
            pendingRuntimeErrors.remove(taskId)
            persistPendingRuntimeErrors()
        }
        val resolvedAppName = taskDisplayName(response.generated_app_name)
            ?: taskDisplayName(response.app_name)
            ?: taskSummaryById[taskId]?.appName
        ensureTaskChatLoaded(taskId)
        var messages = try {
            mergeConversationMessages(taskId, response)
        } catch (e: Exception) {
            Log.e(TAG, "Status render fallback task_id=$taskId status=${response.status}", e)
            val fallbackMessages = buildFallbackMessages(taskId, response).filter { it.kind != MessageKind.STATUS }
            fallbackMessages.forEach { message ->
                appendTaskTimelineMessage(taskId, message, allowDuplicateContent = true)
            }
            appendStatusTransitionMessage(taskId, response)
            buildTaskTimeline(taskId)
        }
        messages = ensureCurrentBuildStageMessage(taskId, response, messages)
        messages = finalizeFinishedBuildProgressMessages(taskId, response, messages)
        messages = ensureLatestLogMessage(taskId, response, messages)
        if (allowArtifactActions && latestApkUrl != null) {
            upsertApkArtifactMessage(taskId, response, resolvedAppName)
            messages = buildTaskTimeline(taskId)
        }
        reconcilePromptReviewHandling(taskId, response, messages)
        val inputMode = when {
            isPollingStatus -> InputMode.READ_ONLY
            isSuccess -> InputMode.CHAT
            isCancelled -> InputMode.CHAT
            isClarifying -> InputMode.CONTINUE_CLARIFICATION
            isFailedBuild || isRetryable -> InputMode.RETRY_FAILED
            isErrorResponse -> InputMode.READ_ONLY
            hasArtifact -> InputMode.CHAT
            else -> screenState.inputMode
        }
        val currentStatusText = if (isErrorResponse) {
            getString(R.string.status_error)
        } else {
            resolveStatusDisplayText(normalizedStatus, response.status_display_text.orEmpty(), progressMode)
        }
        val statusDetailText = when {
            !response.status_message.isNullOrBlank() -> response.status_message
            !response.current_build_stage_detail.isNullOrBlank() -> response.current_build_stage_detail
            !response.current_build_stage.isNullOrBlank() -> response.current_build_stage
            !response.latest_log.isNullOrBlank() -> response.latest_log
            !response.log.isNullOrBlank() -> response.log
            isErrorResponse -> resolveStatusDisplayText(normalizedStatus, response.status_display_text.orEmpty(), progressMode)
            else -> getString(R.string.status_no_detail)
        }
        refreshTaskSummaryFromStatus(
            taskId = taskId,
            response = response,
            resolvedAppName = resolvedAppName,
            statusText = currentStatusText,
            hasApk = latestApkUrl != null
        )

        screenState = screenState.copy(
            selectedTaskId = taskId,
            displayedAppName = resolvedAppName,
            messages = messages,
            inputMode = inputMode,
            currentStatus = currentStatusText,
            statusDetail = statusDetailText,
            canDownload = allowArtifactActions && persistedApkUrlForTask(taskId) != null,
            canInstall = allowArtifactActions && persistedDownloadedApkFileForTask(taskId) != null
        )

        if (pendingResponseScrollTaskIds.remove(taskId) && !isPollingStatus) {
            requestScrollLatestAfterResponse()
        }
        renderState()

        if (!isPollingStatus) {
            stopBuildCompletionMonitoring(taskId)
        }

        if (isSuccess) {
            loadNotifiedBuildSuccessTaskIds()
        }
        if (isSuccess && notifiedBuildSuccessTaskIds.add(taskId)) {
            persistNotifiedBuildSuccessTaskIds()
            notifyBuildCompleted(
                taskId,
                buildTaskContentTitle(
                    initialPrompt = response.conversation_state
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.let { firstString(it, "initial_user_prompt") },
                    appName = resolvedAppName,
                    conversationState = response.conversation_state
                ) ?: taskSummaryById[taskId]?.title ?: resolvedAppName
            )
        }

        if (isSuccess && autoInstallOnSuccess && latestApkUrl != null) {
            downloadAndInstall(taskId, latestApkUrl!!, response.apk_path)
        }

        if (syncPolling) {
            if (isPollingStatus && screenState.selectedTaskId == taskId) {
                startPolling(taskId)
            } else {
                if (processNextQueuedInputIfReady(taskId)) return
                stopPolling()
                setComposerEnabled(inputMode != InputMode.READ_ONLY)
            }
        }
    }

    private fun applyInactiveTaskStatus(taskId: String, response: StatusResponse) {
        val evaluation = TaskStatusPolicy.evaluate(response)
        val normalizedStatus = evaluation.normalizedStatus
        val isSuccess = evaluation.isSuccess
        val isErrorResponse = evaluation.isResponseError
        val progressMode = evaluation.progressMode
        val resolvedApkUrl = resolveApkUrl(taskId, response, isSuccess)
        val resolvedAppName = taskDisplayName(response.generated_app_name)
            ?: taskDisplayName(response.app_name)
            ?: taskSummaryById[taskId]?.appName
        updateTaskArtifactState(
            taskId,
            apkUrl = resolvedApkUrl,
            downloadedApkFile = persistedDownloadedApkFileForTask(taskId)
        )

        if (!isSuccess && notifiedBuildSuccessTaskIds.remove(taskId)) {
            persistNotifiedBuildSuccessTaskIds()
        }
        val pendingRuntimeError = pendingRuntimeErrors[taskId]
        if (isSuccess && pendingRuntimeError?.awaitingUserConfirmation == false) {
            runtimeErrorTaskIds -= taskId
            pendingRuntimeErrors.remove(taskId)
            persistPendingRuntimeErrors()
        }

        val statusText = if (isErrorResponse) {
            getString(R.string.status_error)
        } else {
            resolveStatusDisplayText(
                normalizedStatus,
                response.status_display_text.orEmpty(),
                progressMode
            )
        }
        refreshTaskSummaryFromStatus(
            taskId = taskId,
            response = response,
            resolvedAppName = resolvedAppName,
            statusText = statusText,
            hasApk = resolvedApkUrl != null
        )

        if (!evaluation.isPolling) {
            stopBuildCompletionMonitoring(taskId)
        }
        if (isSuccess) {
            loadNotifiedBuildSuccessTaskIds()
        }
        if (isSuccess && notifiedBuildSuccessTaskIds.add(taskId)) {
            persistNotifiedBuildSuccessTaskIds()
            notifyBuildCompleted(
                taskId,
                buildTaskContentTitle(
                    initialPrompt = response.conversation_state
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.let { firstString(it, "initial_user_prompt") },
                    appName = resolvedAppName,
                    conversationState = response.conversation_state
                ) ?: taskSummaryById[taskId]?.title ?: resolvedAppName
            )
        }

        // Only the drawer row is allowed to change for an inactive task. The
        // visible chat, composer draft, and selected task remain untouched.
        renderState()
    }

    private fun notifyBuildCompleted(taskId: String, appName: String?) {
        val resolvedName = appName?.takeIf { it.isNotBlank() } ?: getString(R.string.untitled_task)
        BuildNotificationController.showTerminal(
            context = this,
            taskId = taskId,
            taskName = resolvedName,
            type = TerminalBuildNotification.SUCCESS
        )
    }

    private fun showRenameTaskDialog() {
        val taskId = screenState.selectedTaskId?.trim()?.takeIf { it.isNotBlank() }
            ?: currentTaskId?.trim()?.takeIf { it.isNotBlank() }
        if (taskId.isNullOrBlank() || taskId in hiddenTaskIds) {
            Toast.makeText(this, R.string.rename_task_no_task, Toast.LENGTH_SHORT).show()
            return
        }

        val currentName = taskDisplayName(screenState.displayedAppName)
            ?: taskDisplayName(taskSummaryById[taskId]?.appName)
            ?: taskSummaryById[taskId]?.title
            ?: getString(R.string.untitled_task)
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(40))
            setText(currentName)
            setSelectAllOnFocus(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_task_title)
            .setMessage(R.string.rename_task_message)
            .setView(input)
            .setNegativeButton(R.string.confirmation_cancel, null)
            .setPositiveButton(R.string.rename_task_positive, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            input.post {
                input.selectAll()
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nextName = input.text?.toString()?.trim().orEmpty()
                when {
                    nextName.isBlank() -> input.error = getString(R.string.rename_task_empty_error)
                    nextName == currentName -> dialog.dismiss()
                    else -> {
                        dialog.dismiss()
                        renameCurrentTask(taskId, nextName)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun renameCurrentTask(taskId: String, nextName: String) {
        val normalizedTaskId = taskId.trim()
        val normalizedName = nextName.trim()
        if (normalizedTaskId.isBlank() || normalizedName.isBlank()) return
        lifecycleScope.launch {
            try {
                val response = apiService.renameTask(
                    taskId = normalizedTaskId,
                    deviceId = deviceId,
                    userId = null,
                    phoneNumber = userIdentity.phoneNumber,
                    request = TaskRenameRequest(app_name = normalizedName)
                )
                val confirmedName = taskDisplayName(response.app_name)
                    ?: taskDisplayName(response.generated_app_name)
                    ?: normalizedName
                applyTaskNameLocally(
                    taskId = normalizedTaskId,
                    appName = confirmedName,
                    updatedAt = response.updated_at.ifBlank { null }
                )
                Toast.makeText(this@MainActivity, R.string.rename_task_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logApiFailure("/tasks/{task_id}", taskId = normalizedTaskId, deviceId = deviceId, throwable = e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.rename_task_failed, userVisibleErrorMessage(e)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applyTaskNameLocally(taskId: String, appName: String, updatedAt: String? = null) {
        val normalizedTaskId = taskId.trim()
        val normalizedAppName = appName.trim()
        if (normalizedTaskId.isBlank() || normalizedAppName.isBlank()) return
        val existing = taskSummaryById[normalizedTaskId]
        val updatedSummary = TaskSummary(
            taskId = normalizedTaskId,
            title = normalizedAppName,
            appName = normalizedAppName,
            packageName = existing?.packageName,
            subtitle = normalizedAppName,
            status = existing?.status ?: screenState.currentStatus,
            updatedAt = taskSummaryLastBubbleTimestamp(normalizedTaskId)
                ?: formatTaskSummaryTimestamp(updatedAt)
                ?: currentTaskSummaryTimestampString(),
            hasApk = existing?.hasApk == true || persistedApkUrlForTask(normalizedTaskId) != null,
            hasRuntimeError = existing?.hasRuntimeError ?: (normalizedTaskId in runtimeErrorTaskIds)
        )
        taskSummaryById = taskSummaryById + (normalizedTaskId to updatedSummary)
        renameArtifactMessages(normalizedTaskId, normalizedAppName)
        val nextMessages = if (screenState.selectedTaskId == normalizedTaskId) {
            buildTaskTimeline(normalizedTaskId)
        } else {
            screenState.messages
        }
        screenState = screenState.copy(
            taskList = TaskSummaryListPolicy.upsert(screenState.taskList, updatedSummary),
            displayedAppName = if (screenState.selectedTaskId == normalizedTaskId) {
                normalizedAppName
            } else {
                screenState.displayedAppName
            },
            messages = nextMessages
        )
        persistTaskChat(normalizedTaskId)
        renderState()
    }

    private fun renameArtifactMessages(taskId: String, appName: String) {
        val normalizedTaskId = taskId.trim()
        if (!ensureTaskChatLoaded(normalizedTaskId)) return
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        var changed = false
        val nextTimeline = timeline.map { message ->
            if (message.artifactTaskId != normalizedTaskId) return@map message
            val revisionLabel = message.artifactRevisionLabel?.trim().orEmpty()
            val renamedBody = if (revisionLabel.isNotBlank()) "$appName $revisionLabel" else appName
            if (message.body == renamedBody) {
                message
            } else {
                changed = true
                message.copy(body = renamedBody)
            }
        }
        if (changed) {
            taskConversationMessages[normalizedTaskId] = nextTimeline.toMutableList()
            taskTimelineRenderCache.markChanged(normalizedTaskId)
            persistTaskChat(normalizedTaskId)
        }
    }

    private fun loadNotifiedBuildSuccessTaskIds() {
        notifiedBuildSuccessTaskIds.clear()
        notifiedBuildSuccessTaskIds += preferencesStore.loadNotifiedBuildSuccessTaskIds()
    }

    private fun persistNotifiedBuildSuccessTaskIds() {
        preferencesStore.saveNotifiedBuildSuccessTaskIds(notifiedBuildSuccessTaskIds)
    }

    private fun startPolling(taskId: String) {
        if (pollingJob?.isActive == true && screenState.pollingTaskId == taskId) return

        stopPolling()
        screenState = screenState.copy(pollingTaskId = taskId)
        renderState()

        pollingJob = lifecycleScope.launch {
            while (isActive && screenState.selectedTaskId == taskId) {
                delay(POLL_INTERVAL_MS)
                try {
                    logStatusFetchTaskId(taskId, source = "polling")
                    logTaskIdForApi("/status/{task_id}", taskId)
                    logApiRequest("/status/{task_id}", taskId = taskId, deviceId = deviceId)
                    val response = fetchTaskStatus(taskId)
                    val stillVisible = TaskStatusUpdatePolicy.targetsVisibleTask(
                        taskId,
                        screenState.selectedTaskId
                    )
                    applyStatus(taskId, response, autoInstallOnSuccess = true, syncPolling = false)
                    if (!stillVisible) {
                        break
                    }
                    if (!TaskStatusPolicy.evaluate(response).isPolling) {
                        break
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logApiFailure("/status/{task_id}", taskId = taskId, deviceId = deviceId, throwable = e)
                    if (!TaskStatusUpdatePolicy.targetsVisibleTask(taskId, screenState.selectedTaskId)) {
                        break
                    }
                    addTaskEvent(
                        taskId,
                        ChatMessage(
                            id = "polling-error-$taskId-${System.currentTimeMillis()}",
                            kind = MessageKind.LOG,
                            title = getString(R.string.message_title_log),
                            body = getString(R.string.polling_failed, userVisibleErrorMessage(e))
                        )
                    )
                    screenState = screenState.copy(
                        pollingTaskId = null,
                        currentStatus = getString(R.string.status_warning),
                        statusDetail = getString(R.string.polling_failed, userVisibleErrorMessage(e))
                    )
                    renderState()
                    break
                }
            }

            if (screenState.pollingTaskId == taskId) {
                screenState = screenState.copy(pollingTaskId = null)
                renderState()
            }
            if (TaskStatusUpdatePolicy.targetsVisibleTask(taskId, screenState.selectedTaskId)) {
                if (processNextQueuedInputIfReady(taskId)) {
                    return@launch
                }
                setComposerEnabled(screenState.inputMode != InputMode.READ_ONLY)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        if (screenState.pollingTaskId != null) {
            screenState = screenState.copy(pollingTaskId = null)
            renderState()
        }
    }

    private fun startFollowupSynthesis(
        taskId: String,
        prompt: String,
        imagePreview: ChatImagePreview? = null,
        attachments: List<SelectedAttachment> = selectedAttachments.toList(),
        mode: InputMode,
        appendUserMessage: Boolean = true,
        displayPrompt: String = prompt,
        requestAction: String? = null,
        useSavedUi: Boolean = false
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/generate") ?: return
        currentTaskId = apiTaskId
        if (screenState.selectedTaskId != apiTaskId) {
            requestScrollLatestAfterResponse(force = true)
            screenState = screenState.copy(
                selectedTaskId = apiTaskId,
                displayedAppName = taskSummaryById[apiTaskId]?.appName ?: screenState.displayedAppName,
                messages = buildTaskTimeline(apiTaskId),
                canDownload = persistedApkUrlForTask(apiTaskId) != null,
                canInstall = persistedDownloadedApkFileForTask(apiTaskId) != null
            )
        }
        if (appendUserMessage) {
            appendOptimisticTaskMessage(
                apiTaskId,
                ChatMessage(
                    id = "followup-origin-$apiTaskId-${System.currentTimeMillis()}",
                    kind = MessageKind.USER,
                    title = getString(R.string.message_title_user),
                    body = displayPrompt,
                    createdAt = currentTimestampString(),
                    imagePreviewBase64 = imagePreview?.base64,
                    imagePreviewName = imagePreview?.displayName,
                    imagePreviews = attachments.toChatImagePreviews()
                ),
                allowDuplicateContent = true
            )
        }
        addTaskEvent(
            apiTaskId,
            ChatMessage(
                id = "followup-status-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_thinking),
                detail = null,
                cancelTaskId = apiTaskId,
                isLoading = true
            )
        )
        addTaskEvent(
            apiTaskId,
            ChatMessage(
                id = "followup-log-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_log),
                body = "후속 요청 전송",
                detail = prompt
            )
        )
        startAppSynthesis(
            prompt = prompt,
            imagePreview = imagePreview,
            attachments = attachments,
            sourceTaskId = apiTaskId,
            displayPrompt = displayPrompt,
            requestAction = requestAction,
            useSavedUi = useSavedUi
        )
    }

    private fun startTaskChatMessage(
        taskId: String,
        prompt: String,
        imagePreview: ChatImagePreview? = null,
        attachments: List<SelectedAttachment> = selectedAttachments.toList(),
        displayPrompt: String = prompt,
        useSavedUi: Boolean = false
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/generate") ?: return
        currentTaskId = apiTaskId
        if (screenState.selectedTaskId != apiTaskId) {
            requestScrollLatestAfterResponse(force = true)
            screenState = screenState.copy(
                selectedTaskId = apiTaskId,
                displayedAppName = taskSummaryById[apiTaskId]?.appName ?: screenState.displayedAppName,
                messages = buildTaskTimeline(apiTaskId),
                canDownload = persistedApkUrlForTask(apiTaskId) != null,
                canInstall = persistedDownloadedApkFileForTask(apiTaskId) != null
            )
        }
        appendOptimisticTaskMessage(
            apiTaskId,
            ChatMessage(
                id = "chat-origin-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.USER,
                title = getString(R.string.message_title_user),
                body = displayPrompt,
                createdAt = currentTimestampString(),
                imagePreviewBase64 = imagePreview?.base64,
                imagePreviewName = imagePreview?.displayName,
                imagePreviews = attachments.toChatImagePreviews()
            ),
            allowDuplicateContent = true
        )
        addTaskEvent(
            apiTaskId,
            ChatMessage(
                id = "chat-status-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_thinking),
                detail = null,
                cancelTaskId = apiTaskId,
                isLoading = true
            )
        )
        startAppSynthesis(
            prompt = prompt,
            imagePreview = imagePreview,
            attachments = attachments,
            sourceTaskId = apiTaskId,
            displayPrompt = displayPrompt,
            useSavedUi = useSavedUi
        )
    }

    private fun buildRuntimeRepairPrompt(record: RuntimeErrorRecord): String {
        return """
기존 앱에서 런타임 오류가 발생했습니다. 현재 task를 이어서 오류를 수정하세요.

- package_name: ${record.packageName}
- error_summary: ${record.summary.ifBlank { "실행 중 오류" }}

stack_trace:
${record.stackTrace}
""".trim()
    }

    private fun reportRuntimeErrorToServer(taskId: String, record: RuntimeErrorRecord) {
        lifecycleScope.launch {
            try {
                apiService.reportRuntimeError(
                    taskId = taskId,
                    deviceId = deviceId,
                    phoneNumber = userIdentity.phoneNumber,
                    request = RuntimeErrorReportRequest(
                        package_name = record.packageName,
                        summary = record.summary,
                        stack_trace = record.stackTrace,
                        error_message = record.errorMessage,
                        report_kind = record.reportKind
                    )
                )
                pendingRuntimeErrors[taskId] = record.copy(serverReported = true)
                persistPendingRuntimeErrors()
                Log.d(
                    TAG,
                    "Runtime error reported to server task_id=$taskId package_name=${record.packageName} stack_length=${record.stackTrace.length}"
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.w(TAG, "Runtime error report failed task_id=$taskId", e)
            }
        }
    }

    private fun collectDeviceInfo(): DeviceInfo {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL).map { it.name }
        val displayMetrics = resources.displayMetrics
        return DeviceInfo(
            model = Build.MODEL,
            sdk = Build.VERSION.SDK_INT,
            width = displayMetrics.widthPixels,
            height = displayMetrics.heightPixels,
            sensors = sensors
        )
    }

    private fun getOrCreateUserIdentity(): UserIdentity {
        val savedPhoneNumber = preferencesStore.loadPhoneNumber()
        val phoneNumber = savedPhoneNumber ?: PhoneNumberResolver.readFromSim(this)
        if (savedPhoneNumber.isNullOrBlank() && !phoneNumber.isNullOrBlank()) {
            preferencesStore.savePhoneNumber(phoneNumber)
        }
        Log.d(TAG, "Loaded user identity phone_number_available=${!phoneNumber.isNullOrBlank()}")
        return UserIdentity(
            phoneNumber = phoneNumber
        )
    }

    private fun hasRequiredPhoneNumber(): Boolean {
        return ::deviceId.isInitialized &&
            deviceId.isNotBlank() &&
            PhoneNumberResolver.hasPermission(this) &&
            !userIdentity.phoneNumber.isNullOrBlank()
    }

    private fun requestPhoneNumberPermissionIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.READ_PHONE_NUMBERS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.READ_PHONE_STATE
        }
        if (permissions.isNotEmpty()) {
            val shouldOpenSettings = hasAttemptedPhonePermissionRequest &&
                permissions.all { permission ->
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                }
            if (shouldOpenSettings) {
                openAppPermissionSettings()
                Toast.makeText(this, R.string.phone_permission_settings_message, Toast.LENGTH_SHORT).show()
                return
            }
            hasAttemptedPhonePermissionRequest = true
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PHONE_NUMBER_PERMISSION)
        } else {
            tryFillPhoneNumberFromSim()
        }
    }

    private fun tryFillPhoneNumberFromSim() {
        val phoneNumber = PhoneNumberResolver.readFromSim(this)
        if (phoneNumber.isNullOrBlank()) {
            Toast.makeText(this, R.string.phone_permission_unavailable_message, Toast.LENGTH_LONG).show()
            renderState()
            return
        }
        preferencesStore.savePhoneNumber(phoneNumber)
        userIdentity = UserIdentity(phoneNumber = phoneNumber)
        inputPhoneGate.setText(phoneNumber)
        renderState()
        pendingTaskSelectionKey = getLastSelectedTaskId()
        restoreCurrentTaskState(trigger = "phoneGate")
        Toast.makeText(this, R.string.phone_gate_saved, Toast.LENGTH_SHORT).show()
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun persistLastSelectedTaskId(taskId: String?) {
        preferencesStore.saveLastSelectedTaskId(taskId)
    }

    private fun getLastSelectedTaskId(): String? {
        return preferencesStore.loadLastSelectedTaskId()
    }

    private fun loadHiddenTaskIds() {
        hiddenTaskIds.clear()
        hiddenTaskIds += preferencesStore.loadHiddenTaskIds()
    }

    private fun visibleTaskIdCandidate(taskId: String?): String? {
        val normalizedTaskId = taskId?.trim().orEmpty()
        return normalizedTaskId.takeIf { it.isNotBlank() && it !in hiddenTaskIds }
    }

    private fun persistHiddenTaskIds() {
        preferencesStore.saveHiddenTaskIds(hiddenTaskIds)
    }

    private fun loadPersistedTaskChats() {
        taskConversationMessages.clear()
        loadedTaskChatIds.clear()
        taskTimelineRenderCache.clear()
        taskTimelineEventCursorById.clear()
        preferencesStore.migrateLegacyTaskChatsIfNeeded(hiddenTaskIds)
    }

    private fun ensureTaskChatLoaded(taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return false
        if (normalizedTaskId in loadedTaskChatIds) return true
        if (preferencesStore.hasTaskChat(normalizedTaskId)) {
            Log.w(TAG, "Task chat must be loaded off main thread task_id=$normalizedTaskId")
            return false
        }
        loadedTaskChatIds += normalizedTaskId
        return true
    }

    private suspend fun ensureTaskChatLoadedAsync(taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return false
        if (normalizedTaskId in loadedTaskChatIds) return true
        val loadedMessages = withContext(Dispatchers.IO) {
            preferencesStore.loadTaskChat(normalizedTaskId)
        }
        if (normalizedTaskId in hiddenTaskIds) return false
        if (normalizedTaskId !in loadedTaskChatIds && loadedMessages.isNotEmpty()) {
            taskConversationMessages[normalizedTaskId] = loadedMessages.toMutableList()
            taskTimelineRenderCache.markChanged(normalizedTaskId)
            TimelineCursorPolicy.restoredCursor(
                taskId = normalizedTaskId,
                messageIds = loadedMessages.map(ChatMessage::id)
            )?.let { cursor -> taskTimelineEventCursorById[normalizedTaskId] = cursor }
        }
        loadedTaskChatIds += normalizedTaskId
        return true
    }

    private fun editableTaskTimeline(taskId: String): MutableList<ChatMessage>? {
        val normalizedTaskId = taskId.trim()
        if (!ensureTaskChatLoaded(normalizedTaskId)) return null
        return taskConversationMessages.getOrPut(normalizedTaskId) { mutableListOf() }
    }

    private fun loadPersistedArtifactStates() {
        taskArtifactStates.clear()
        val restored = preferencesStore.loadTaskArtifactStates()
        taskArtifactStates += restored.mapValues { (_, state) ->
            state.copy(downloadedApkPath = null)
        }
        if (restored.values.any { !it.downloadedApkPath.isNullOrBlank() }) {
            persistTaskArtifactStates()
        }
        syncArtifactPointersForActiveTask()
    }

    private fun clearObsoleteApkDownloads() {
        val keepFile = pendingInstallApkFile?.takeIf { it.exists() }
        lifecycleScope.launch(Dispatchers.IO) {
            val deletedCount = ApkArtifactActionHandler.clearManagedDownloadsSafely(
                context = this@MainActivity,
                keepFile = keepFile
            )
            if (deletedCount > 0) {
                Log.i(TAG, "Cleared obsolete generated APK cache files count=$deletedCount")
            }
        }
    }

    private fun persistTaskArtifactStates() {
        preferencesStore.saveTaskArtifactStates(taskArtifactStates)
    }

    private fun syncArtifactPointersForActiveTask() {
        val activeTaskId = currentTaskId?.trim().takeUnless { it.isNullOrBlank() }
            ?: screenState.selectedTaskId?.trim().takeUnless { it.isNullOrBlank() }
        if (activeTaskId.isNullOrBlank() || activeTaskId in hiddenTaskIds) {
            latestApkUrl = null
            latestDownloadedTaskId = null
            latestDownloadedApkFile = null
            return
        }
        latestApkUrl = persistedApkUrlForTask(activeTaskId)
        val downloadedFile = persistedDownloadedApkFileForTask(activeTaskId)
        latestDownloadedTaskId = if (downloadedFile != null) activeTaskId else null
        latestDownloadedApkFile = downloadedFile
    }

    private fun persistedApkUrlForTask(taskId: String): String? {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return null
        val persistedUrl = taskArtifactStates[normalizedTaskId]?.apkUrl?.trim().orEmpty()
        if (persistedUrl.isNotBlank()) return persistedUrl
        val summary = taskSummaryById[normalizedTaskId]
        return if (summary?.hasApk == true) "${HostAppConfig.BASE_URL}/download/$normalizedTaskId" else null
    }

    private fun persistedDownloadedApkFileForTask(taskId: String): File? {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return null
        if (pendingInstallLaunchTaskId != normalizedTaskId) return null
        return pendingInstallApkFile?.takeIf { it.exists() }
    }

    private fun artifactDownloadCacheFile(taskId: String, url: String?, artifactPath: String?): File {
        return ApkArtifactActionHandler.artifactDownloadCacheFile(this, taskId, url, artifactPath)
    }

    private fun cachedDownloadedApkFileForArtifact(taskId: String, url: String?, artifactPath: String?): File? {
        return artifactDownloadCacheFile(taskId, url, artifactPath).takeIf { it.exists() }
    }

    private fun updateTaskArtifactState(
        taskId: String,
        apkUrl: String? = taskArtifactStates[taskId]?.apkUrl,
        downloadedApkFile: File? = persistedDownloadedApkFileForTask(taskId)
    ) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val normalizedUrl = apkUrl?.trim()?.ifBlank { null }
        val normalizedPath = downloadedApkFile?.absolutePath?.takeIf { downloadedApkFile.exists() }
        if (normalizedUrl == null && normalizedPath == null) {
            taskArtifactStates.remove(normalizedTaskId)
        } else {
            taskArtifactStates[normalizedTaskId] = PersistedArtifactState(
                apkUrl = normalizedUrl,
                downloadedApkPath = null
            )
        }
        if (currentTaskId == normalizedTaskId || screenState.selectedTaskId == normalizedTaskId) {
            latestApkUrl = normalizedUrl
            latestDownloadedTaskId = if (normalizedPath != null) normalizedTaskId else null
            latestDownloadedApkFile = normalizedPath?.let(::File)
        }
        persistTaskArtifactStates()
    }

    private fun persistTaskChats() {
        val taskIds = (loadedTaskChatIds + taskConversationMessages.keys)
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in hiddenTaskIds }
            .toSet()
        val committed = taskIds.all { persistTaskChat(it) }
        if (!committed) {
            Log.w(TAG, "Failed to commit one or more task chats")
        }
    }

    private fun persistTaskChat(taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return true
        trimTaskTimelineInMemory(normalizedTaskId)
        taskTimelineRenderCache.markChanged(normalizedTaskId)
        preferencesStore.enqueueTaskChatSave(
            normalizedTaskId,
            taskConversationMessages[normalizedTaskId].orEmpty()
        )
        return true
    }

    private fun showPersistedTaskPreview(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return
        if (normalizedTaskId !in loadedTaskChatIds) return
        val hasPersistedMessages = taskConversationMessages[normalizedTaskId].orEmpty().isNotEmpty()
        if (!hasPersistedMessages) return
        val wasAlreadySelected = screenState.selectedTaskId == normalizedTaskId
        if (!wasAlreadySelected && !hasPendingRestoredChatScroll(normalizedTaskId)) {
            requestScrollLatestAfterResponse(force = true)
        }

        val summary = taskSummaryById[normalizedTaskId]
        val hasArtifact = summary?.hasApk == true || persistedApkUrlForTask(normalizedTaskId) != null
        screenState = screenState.copy(
            selectedTaskId = normalizedTaskId,
            displayedAppName = summary?.appName ?: screenState.displayedAppName,
            messages = buildTaskTimeline(normalizedTaskId),
            inputMode = if (hasArtifact) InputMode.CHAT else screenState.inputMode,
            currentStatus = if (hasArtifact) getString(R.string.status_ready_to_chat) else getString(R.string.status_loading_task),
            statusDetail = getString(R.string.status_syncing_saved_state),
            canDownload = hasArtifact,
            canInstall = persistedDownloadedApkFileForTask(normalizedTaskId) != null
        )
        renderState()
    }

    private suspend fun syncTaskStatus(
        taskId: String,
        autoInstallOnSuccess: Boolean,
        source: String,
        staleFallback: Boolean,
        closeDrawerOnSuccess: Boolean,
        requestedTaskId: String = taskId,
        selectionGeneration: Long? = null
    ) {
        logTaskSelection(requestedTaskId, taskId)
        logStatusFetchTaskId(taskId, source = source)
        logTaskIdForApi("/status/{task_id}", taskId)
        logApiRequest("/status/{task_id}", taskId = taskId, deviceId = deviceId)
        try {
            val status = fetchTaskStatus(taskId)
            if (selectionGeneration != null && !isTaskSelectionGenerationCurrent(selectionGeneration)) {
                return
            }
            applyStatus(taskId, status, autoInstallOnSuccess, syncPolling = true)
            if (selectionGeneration != null && !isTaskSelectionGenerationCurrent(selectionGeneration)) {
                return
            }
            if (closeDrawerOnSuccess) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            if (selectionGeneration != null && !isTaskSelectionGenerationCurrent(selectionGeneration)) {
                return
            }
            if (staleFallback) {
                addTaskEvent(
                    taskId,
                    ChatMessage(
                        id = "stale-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.status_showing_saved_state, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    selectedTaskId = taskId,
                    currentStatus = getString(R.string.status_warning),
                    statusDetail = getString(R.string.status_showing_saved_state, userVisibleErrorMessage(e))
                )
                renderState()
            }
            throw e
        }
    }

    private suspend fun fetchTaskStatus(
        taskId: String,
        includeLogs: Boolean = false,
        includeTimeline: Boolean = true
    ): StatusResponse {
        val normalizedTaskId = taskId.trim()
        return apiService.getStatus(
            taskId = normalizedTaskId,
            deviceId = deviceId,
            userId = null,
            phoneNumber = userIdentity.phoneNumber,
            includeLogs = includeLogs,
            includeTimeline = includeTimeline,
            timelineAfterEventId = taskTimelineEventCursorById[normalizedTaskId]
                .takeIf { includeTimeline }
        )
    }

    private fun downloadAndInstall(taskId: String, url: String, artifactPath: String? = null) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val downloadStartedAt = SystemClock.elapsedRealtime()
        val normalizedUrl = url.trim()
        val normalizedArtifactPath = artifactPath?.trim()?.takeIf { it.isNotBlank() }
        isDownloadingApk = true
        downloadingApkTaskId = normalizedTaskId
        downloadingApkUrl = normalizedUrl
        downloadingArtifactPath = normalizedArtifactPath
        downloadProgressPercent = 0
        downloadProgressBytes = 0L
        screenState = screenState.copy(
            currentStatus = getString(R.string.download_apk_in_progress),
            statusDetail = currentDownloadProgressText(),
            canDownload = false
        )
        renderState()
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadTaskId = resolveApiTaskId(normalizedTaskId, "/download/{task_id}")?.trim().takeUnless { it.isNullOrBlank() }
            try {
                downloadTaskId?.let { logTaskSelection(it, it) }
                logApiRequest("/download/{task_id}", taskId = downloadTaskId, deviceId = deviceId, extra = "url=$normalizedUrl")
                val resolvedDownloadTaskId = downloadTaskId
                    ?: throw IllegalStateException("missing task_id for download")
                val progressRenderPolicy = DownloadProgressRenderPolicy()
                val apkFile = ApkArtifactActionHandler.downloadToCache(
                    context = this@MainActivity,
                    apiService = downloadApiService,
                    taskId = resolvedDownloadTaskId,
                    url = normalizedUrl,
                    artifactPath = normalizedArtifactPath,
                    deviceId = deviceId,
                    phoneNumber = userIdentity.phoneNumber,
                    onProgress = { progress ->
                        val renderDecision = progressRenderPolicy.evaluate(
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        if (renderDecision.shouldRender) {
                            withContext(Dispatchers.Main) {
                                downloadProgressPercent = renderDecision.percent
                                downloadProgressBytes = progress.downloadedBytes
                                screenState = screenState.copy(
                                    currentStatus = getString(R.string.download_apk_in_progress),
                                    statusDetail = currentDownloadProgressText(),
                                    canDownload = false
                                )
                                renderState()
                            }
                        }
                    },
                    onRetry = { attempt, maxAttempts ->
                        progressRenderPolicy.reset()
                        withContext(Dispatchers.Main) {
                            screenState = screenState.copy(
                                currentStatus = getString(R.string.download_apk_in_progress),
                                statusDetail = getString(R.string.download_apk_retrying, attempt, maxAttempts),
                                canDownload = false
                            )
                            renderState()
                        }
                    }
                )

                latestDownloadedApkFile = apkFile
                latestDownloadedTaskId = downloadTaskId
                downloadTaskId?.let { updateTaskArtifactState(it, apkUrl = normalizedUrl, downloadedApkFile = apkFile) }
                withContext(Dispatchers.Main) {
                    Log.i(
                        TAG,
                        "APK download completed task_id=$normalizedTaskId " +
                            "duration_ms=${SystemClock.elapsedRealtime() - downloadStartedAt} size_bytes=${apkFile.length()}"
                    )
                    isDownloadingApk = false
                    downloadingApkTaskId = null
                    downloadingApkUrl = null
                    downloadingArtifactPath = null
                    downloadProgressPercent = null
                    downloadProgressBytes = 0L
                    downloadTaskId?.let { markArtifactDownloaded(it, normalizedUrl, normalizedArtifactPath, apkFile) }
                    downloadTaskId?.let { taskId ->
                        addTaskEvent(
                            taskId,
                            ChatMessage(
                                id = "download-$taskId-${System.currentTimeMillis()}",
                                kind = MessageKind.STATUS,
                                title = getString(R.string.message_title_status),
                                body = getString(R.string.status_downloaded)
                            )
                        )
                    }
                    screenState = screenState.copy(
                        currentStatus = getString(R.string.status_downloaded),
                        statusDetail = getString(R.string.install_apk),
                        canInstall = screenState.selectedTaskId?.let(::persistedDownloadedApkFileForTask) != null,
                        canDownload = false
                    )
                    renderState()
                    installApk(
                        apkFile,
                        taskId = downloadTaskId,
                        artifactIdentity = downloadTaskId?.let {
                            ApkArtifactActionHandler.artifactIdentity(
                                it,
                                normalizedUrl,
                                normalizedArtifactPath
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                withContext(Dispatchers.Main) {
                    Log.w(
                        TAG,
                        "APK download failed task_id=$normalizedTaskId " +
                            "duration_ms=${SystemClock.elapsedRealtime() - downloadStartedAt}",
                        e
                    )
                    isDownloadingApk = false
                    downloadingApkTaskId = null
                    downloadingApkUrl = null
                    downloadingArtifactPath = null
                    downloadProgressPercent = null
                    downloadProgressBytes = 0L
                    logApiFailure("/download/{task_id}", taskId = downloadTaskId, deviceId = deviceId, throwable = e)
                    downloadTaskId?.let { taskId ->
                        addTaskEvent(
                            taskId,
                            ChatMessage(
                                id = "download-error-$taskId-${System.currentTimeMillis()}",
                                kind = MessageKind.LOG,
                                title = getString(R.string.message_title_log),
                                body = getString(R.string.download_failed, userVisibleErrorMessage(e))
                            )
                        )
                    }
                    screenState = screenState.copy(
                        currentStatus = getString(R.string.status_error),
                        statusDetail = getString(R.string.download_failed, userVisibleErrorMessage(e)),
                        canDownload = true
                    )
                    renderState()
                }
            }
        }
    }

    private fun installApk(
        file: File,
        taskId: String? = latestDownloadedTaskId,
        packageName: String? = null,
        artifactIdentity: String? = null
    ) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.task_log_apk_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotBlank() }
        val resolvedPackageName = resolveInstallLaunchPackageName(file, normalizedTaskId, packageName)
        if (ApkArtifactActionHandler.needsInstallPermission(this)) {
            rememberPendingInstallState(
                file = file,
                taskId = normalizedTaskId,
                packageName = resolvedPackageName,
                artifactIdentity = artifactIdentity,
                installerLaunched = false
            )
            Toast.makeText(this, R.string.install_permission_required, Toast.LENGTH_LONG).show()
            if (!ApkArtifactActionHandler.requestInstallPermission(this)) {
                clearPendingInstallState()
                Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        rememberPendingInstallState(
            file = file,
            taskId = normalizedTaskId,
            packageName = resolvedPackageName,
            artifactIdentity = artifactIdentity,
            installerLaunched = false
        )
        if (ApkArtifactActionHandler.launchApkInstaller(this, file)) {
            markPendingInstallerLaunched()
        } else {
            clearPendingInstallState()
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun retryPendingApkInstallIfReady(): Boolean {
        loadPendingInstallState()
        if (pendingInstallerLaunched) return false
        val file = pendingInstallApkFile ?: return false
        if (!file.exists()) {
            clearPendingInstallState()
            Toast.makeText(this, R.string.task_log_apk_missing, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!ApkArtifactActionHandler.needsInstallPermission(this)) {
            installApk(
                file,
                taskId = pendingInstallLaunchTaskId,
                packageName = pendingInstallLaunchPackageName,
                artifactIdentity = pendingInstallArtifactIdentity
            )
            return pendingInstallerLaunched
        }
        clearPendingInstallState()
        return false
    }

    private fun resolveInstallLaunchPackageName(
        file: File,
        taskId: String?,
        packageName: String?
    ): String? {
        return packageNameFromApk(file)
            ?: packageName?.trim()?.takeIf { it.isNotBlank() }
            ?: taskId?.let { taskSummaryById[it]?.packageName?.trim()?.takeIf { packageName -> packageName.isNotBlank() } }
    }

    private fun packageNameFromApk(file: File): String? {
        return ApkArtifactActionHandler.packageNameFromApk(this, file)
    }

    private fun rememberPendingInstallState(
        file: File,
        taskId: String?,
        packageName: String?,
        artifactIdentity: String?,
        installerLaunched: Boolean
    ) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedPackageName = packageName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedArtifactIdentity = artifactIdentity?.trim()?.takeIf { it.isNotBlank() }
        pendingInstallApkFile = file
        pendingInstallLaunchTaskId = normalizedTaskId
        pendingInstallLaunchPackageName = normalizedPackageName
        pendingInstallArtifactIdentity = normalizedArtifactIdentity
        val previousSnapshot = normalizedPackageName
            ?.let { ApkArtifactActionHandler.installedPackageSnapshot(this, it) }
        pendingInstallPreviousVersionCode = previousSnapshot?.versionCode
        pendingInstallPreviousUpdateTime = previousSnapshot?.lastUpdateTime
        pendingInstallerLaunched = installerLaunched
        getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                putString(HostAppConfig.PREF_PENDING_INSTALL_APK_PATH, file.absolutePath)
                if (normalizedTaskId != null) {
                    putString(HostAppConfig.PREF_PENDING_INSTALL_TASK_ID, normalizedTaskId)
                } else {
                    remove(HostAppConfig.PREF_PENDING_INSTALL_TASK_ID)
                }
                if (normalizedPackageName != null) {
                    putString(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_NAME, normalizedPackageName)
                } else {
                    remove(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_NAME)
                }
                if (previousSnapshot != null) {
                    putLong(
                        PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE,
                        previousSnapshot.versionCode
                    )
                    putLong(
                        PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME,
                        previousSnapshot.lastUpdateTime
                    )
                } else {
                    remove(PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE)
                    remove(PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME)
                }
                if (normalizedArtifactIdentity != null) {
                    putString(
                        PREF_PENDING_INSTALL_ARTIFACT_IDENTITY,
                        normalizedArtifactIdentity
                    )
                } else {
                    remove(PREF_PENDING_INSTALL_ARTIFACT_IDENTITY)
                }
                remove(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_WAS_INSTALLED)
                putBoolean(PREF_PENDING_INSTALLER_LAUNCHED, installerLaunched)
            }
            .apply()
    }

    private fun markPendingInstallerLaunched() {
        pendingInstallerLaunched = true
        getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PENDING_INSTALLER_LAUNCHED, true)
            .apply()
    }

    private fun loadPendingInstallState() {
        val prefs = getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (pendingInstallApkFile == null) {
            pendingInstallApkFile = prefs
                .getString(HostAppConfig.PREF_PENDING_INSTALL_APK_PATH, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.exists() }
        }
        if (pendingInstallLaunchTaskId.isNullOrBlank()) {
            pendingInstallLaunchTaskId = prefs
                .getString(HostAppConfig.PREF_PENDING_INSTALL_TASK_ID, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        if (pendingInstallLaunchPackageName.isNullOrBlank()) {
            pendingInstallLaunchPackageName = prefs
                .getString(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_NAME, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        if (pendingInstallArtifactIdentity.isNullOrBlank()) {
            pendingInstallArtifactIdentity = prefs
                .getString(PREF_PENDING_INSTALL_ARTIFACT_IDENTITY, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        pendingInstallPreviousVersionCode = prefs
            .takeIf { it.contains(PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE) }
            ?.getLong(PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE, 0L)
        pendingInstallPreviousUpdateTime = prefs
            .takeIf { it.contains(PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME) }
            ?.getLong(PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME, 0L)
        pendingInstallerLaunched = prefs.getBoolean(
            PREF_PENDING_INSTALLER_LAUNCHED,
            pendingInstallerLaunched
        )
    }

    private fun loadPendingInstallLaunchPackageName(): String? {
        loadPendingInstallState()
        return pendingInstallLaunchPackageName
    }

    private fun clearPendingInstallState() {
        val file = pendingInstallApkFile
        val taskId = pendingInstallLaunchTaskId
        pendingInstallResolutionJob?.cancel()
        pendingInstallResolutionJob = null
        pendingInstallApkFile = null
        pendingInstallLaunchTaskId = null
        pendingInstallLaunchPackageName = null
        pendingInstallPreviousVersionCode = null
        pendingInstallPreviousUpdateTime = null
        pendingInstallArtifactIdentity = null
        pendingInstallerLaunched = false
        getSharedPreferences(HostAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(HostAppConfig.PREF_PENDING_INSTALL_APK_PATH)
            .remove(HostAppConfig.PREF_PENDING_INSTALL_TASK_ID)
            .remove(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_NAME)
            .remove(HostAppConfig.PREF_PENDING_INSTALL_PACKAGE_WAS_INSTALLED)
            .remove(PREF_PENDING_INSTALL_PREVIOUS_VERSION_CODE)
            .remove(PREF_PENDING_INSTALL_PREVIOUS_UPDATE_TIME)
            .remove(PREF_PENDING_INSTALL_ARTIFACT_IDENTITY)
            .remove(PREF_PENDING_INSTALLER_LAUNCHED)
            .apply()
        ApkArtifactActionHandler.deleteTransientDownload(file)
        taskId?.let(::clearArtifactDownloadedState)
    }

    private fun launchPendingInstalledAppIfReady(source: String): Boolean {
        loadPendingInstallState()
        val packageName = pendingInstallLaunchPackageName?.trim()?.takeIf { it.isNotBlank() }
            ?: return false
        val installedSnapshot = ApkArtifactActionHandler.installedPackageSnapshot(this, packageName)
        val previousSnapshot = pendingInstallPreviousVersionCode?.let { versionCode ->
            InstalledPackageSnapshot(
                versionCode = versionCode,
                lastUpdateTime = pendingInstallPreviousUpdateTime ?: 0L
            )
        }
        if (
            source != "package-broadcast" &&
            !GeneratedAppInstallPolicy.installationCompleted(previousSnapshot, installedSnapshot)
        ) {
            return false
        }
        if (installedSnapshot == null) {
            return false
        }
        if (packageManager.getLaunchIntentForPackage(packageName) == null) {
            Log.w(TAG, "Installed generated app has no launcher activity package_name=$packageName source=$source")
            clearPendingInstallState()
            Toast.makeText(this, R.string.generated_app_launch_failed, Toast.LENGTH_LONG).show()
            return false
        }
        val launched = ApkArtifactActionHandler.launchInstalledPackage(this, packageName)
        if (launched) {
            Log.i(TAG, "Launched generated app package_name=$packageName source=$source")
            ApkArtifactActionHandler.recordInstalledArtifact(
                this,
                packageName,
                pendingInstallArtifactIdentity
            )
            clearPendingInstallState()
        } else {
            Toast.makeText(this, R.string.generated_app_launch_failed, Toast.LENGTH_LONG).show()
        }
        return launched
    }

    private fun resolveReturnedInstallerIfNeeded() {
        if (!pendingInstallerLaunched || pendingInstallApkFile == null) return
        pendingInstallResolutionJob?.cancel()
        pendingInstallResolutionJob = lifecycleScope.launch {
            repeat(INSTALL_RESOLUTION_ATTEMPTS) {
                delay(INSTALL_RESOLUTION_DELAY_MS)
                if (launchPendingInstalledAppIfReady("installer-result")) {
                    return@launch
                }
            }
            clearPendingInstallState()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return ApkArtifactActionHandler.installedPackageSnapshot(this, packageName) != null
    }

    private fun renderState() {
        val phoneGateVisible = renderPhoneGate()
        renderTaskListIfChanged()
        val visibleMessages = visibleMessagesForRender()
        renderMessagesAndScrollState(visibleMessages)
        if (phoneGateVisible) return
        renderComposer(visibleMessages)
    }

    private fun renderPhoneGate(): Boolean {
        val phoneGateVisible = !hasRequiredPhoneNumber()
        updateTopTitle(phoneGateVisible)
        phoneGateOverlay.visibility = if (phoneGateVisible) View.VISIBLE else View.GONE
        mainContent.visibility = if (phoneGateVisible) View.INVISIBLE else View.VISIBLE
        drawerLayout.setDrawerLockMode(
            if (phoneGateVisible) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
        if (phoneGateVisible) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        btnOpenDrawer.isEnabled = !phoneGateVisible
        btnNewChat.isEnabled = !phoneGateVisible
        drawerNewChatRow.isEnabled = !phoneGateVisible
        btnOpenLibrary.isEnabled = !phoneGateVisible
        btnOpenSettings.isEnabled = !phoneGateVisible
        drawerLibraryRow.isEnabled = !phoneGateVisible
        drawerSettingsRow.isEnabled = !phoneGateVisible
        inputPhoneGate.isEnabled = false
        inputPhoneGate.visibility = if (phoneGateVisible) View.GONE else View.VISIBLE
        btnSavePhoneGate.isEnabled = true
        renderUiDraftContext(phoneGateVisible)
        if (phoneGateVisible) {
            inputModeLabel.text = getString(R.string.phone_gate_title)
            inputPrompt.hint = getString(R.string.phone_gate_hint)
            setComposerEnabled(false)
        }
        return phoneGateVisible
    }

    private fun renderTaskListIfChanged() {
        val taskListFingerprint = UiRenderFingerprint.taskList(
            screenState.taskList,
            screenState.selectedTaskId,
            runtimeErrorTaskIds
        )
        if (taskListFingerprint != lastRenderedTaskListFingerprint) {
            lastRenderedTaskListFingerprint = taskListFingerprint
            val taskListForRender = screenState.taskList.map { task ->
                task.copy(hasRuntimeError = task.taskId in runtimeErrorTaskIds)
            }
            taskAdapter.submitList(taskListForRender, screenState.selectedTaskId)
        }
    }

    private fun visibleMessagesForRender(): List<ChatMessage> {
        val baseVisibleMessages = screenState.messages
            .map(::withTransientArtifactDownloadState)
            .filter { it.kind != MessageKind.LOG }
            .filterNot(::isRedundantDownloadedStatusMessage)
            .filterNot(::isRedundantModificationEchoMessage)
        return withColdStartMessage(withDownloadProgressMessage(baseVisibleMessages))
    }

    private fun renderMessagesAndScrollState(visibleMessages: List<ChatMessage>) {
        activateRestoredChatScrollIfReady(screenState.selectedTaskId, visibleMessages)
        val hasTransientProgressUpdate = visibleMessages.any {
            it.isLoading ||
                it.artifactDownloading ||
                processingAnimationBaseText(it.body) != null
        } || isProcessingAnimationActiveState()
        val shouldPinBottomForTransientUpdate = visibleMessages.isNotEmpty() &&
            hasTransientProgressUpdate &&
            chatShouldStickToBottom &&
            !chatAutoScrollLockedByUser &&
            !isMessageTextSelectionActive
        val hasPendingInitialScroll = !screenState.selectedTaskId.isNullOrBlank() &&
            pendingInitialChatScrollTaskId == screenState.selectedTaskId
        val shouldAutoScrollNewMessage = !hasTransientProgressUpdate &&
            !chatAutoScrollLockedByUser &&
            !hasPendingInitialScroll &&
            (shouldAutoScrollMessages(visibleMessages) || chatShouldStickToBottom)
        val anchorMessageId = pendingChatAnchorMessageId
        val clearAnchorAfterScroll = clearPendingChatAnchorAfterScroll
        val shouldPreserveManualScroll = visibleMessages.isNotEmpty() &&
            !chatAutoScrollLockedByUser &&
            !isChatScrollInteractionActive() &&
            !shouldPinBottomForTransientUpdate &&
            (hasTransientProgressUpdate || !isChatNearBottom())
        val scrollLatestAfterResponse = pendingScrollLatestAfterResponse &&
            !chatAutoScrollLockedByUser &&
            !shouldPreserveManualScroll &&
            !shouldPinBottomForTransientUpdate
        if (
            ChatResponseScrollPolicy.shouldClearPendingScroll(
                pending = pendingScrollLatestAfterResponse,
                scrollNow = scrollLatestAfterResponse,
                pinBottomForTransientUpdate = shouldPinBottomForTransientUpdate
            )
        ) {
            pendingScrollLatestAfterResponse = false
        }
        if (scrollLatestAfterResponse) {
            pendingScrollLatestAfterResponse = false
            pendingChatAnchorMessageId = null
            pendingChatAnchorTopOffset = null
            clearPendingChatAnchorAfterScroll = false
        }
        val messageListFingerprint = UiRenderFingerprint.messages(screenState.selectedTaskId, visibleMessages)
        val shouldSubmitMessages = messageListFingerprint != lastRenderedMessageListFingerprint ||
            scrollLatestAfterResponse ||
            !anchorMessageId.isNullOrBlank()
        if (shouldSubmitMessages) {
            lastRenderedMessageListFingerprint = messageListFingerprint
            submitChatMessagesWhenSafe(
                visibleMessages = visibleMessages,
                anchorMessageId = if (scrollLatestAfterResponse) null else anchorMessageId,
                clearAnchorAfterScroll = clearAnchorAfterScroll,
                scrollLatestAfterResponse = scrollLatestAfterResponse,
                preserveVisiblePosition = shouldPreserveManualScroll &&
                    !scrollLatestAfterResponse &&
                    anchorMessageId.isNullOrBlank(),
                preserveScrollSnapshot = manualChatScrollSnapshot,
                preserveBottomPosition = shouldPinBottomForTransientUpdate &&
                    !scrollLatestAfterResponse &&
                    anchorMessageId.isNullOrBlank()
            )
        }
        emptyChatText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE

        val selectedTaskId = screenState.selectedTaskId
        if (scrollLatestAfterResponse || !anchorMessageId.isNullOrBlank()) {
            // submitList commit callback applies the requested scroll after DiffUtil finishes.
        } else if (
            visibleMessages.isNotEmpty() &&
            !selectedTaskId.isNullOrBlank() &&
            pendingInitialChatScrollTaskId == selectedTaskId
        ) {
            pendingInitialChatScrollTaskId = null
        } else if (shouldAutoScrollNewMessage && !shouldPreserveManualScroll) {
            recyclerMessages.post {
                recyclerMessages.scrollToPosition(visibleMessages.lastIndex)
            }
        }
    }

    private fun renderComposer(visibleMessages: List<ChatMessage>) {
        val awaitingPromptReview = screenState.inputMode == InputMode.CONTINUE_CLARIFICATION &&
            visibleMessages.any { message ->
                message.promptReviewTaskId == screenState.selectedTaskId &&
                    !message.promptReviewText.isNullOrBlank() &&
                    message.id !in handledConfirmationMessageIds
            }
        if (awaitingPromptReview) {
            inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_prompt_review))
            inputPrompt.hint = getString(R.string.prompt_hint_review)
            setComposerEnabled(false)
        } else when (screenState.inputMode) {
            InputMode.NEW_GENERATE -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_new_chat))
                inputPrompt.hint = getString(R.string.prompt_hint_new)
                setComposerEnabled(true)
            }
            InputMode.CHAT -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_chat))
                inputPrompt.hint = getString(R.string.prompt_hint_chat)
                setComposerEnabled(true)
            }
            InputMode.CONTINUE_CLARIFICATION -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_continue))
                inputPrompt.hint = getString(R.string.prompt_hint_continue)
                setComposerEnabled(true)
            }
            InputMode.REFINE_EXISTING -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_refine))
                inputPrompt.hint = getString(R.string.prompt_hint_refine)
                setComposerEnabled(true)
            }
            InputMode.RETRY_FAILED -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_retry))
                inputPrompt.hint = getString(R.string.prompt_hint_retry)
                setComposerEnabled(true)
            }
            InputMode.READ_ONLY -> {
                val activeTaskId = currentTaskId ?: screenState.selectedTaskId
                val canQueueInput = activeTaskId?.let(::isTaskInputQueueActive) == true
                inputModeLabel.text = buildModeLabel(
                    if (canQueueInput) {
                        getString(R.string.input_mode_queue)
                    } else {
                        getString(R.string.input_mode_read_only)
                    }
                )
                inputPrompt.hint = if (canQueueInput) {
                    getString(R.string.prompt_hint_queue)
                } else {
                    getString(R.string.prompt_hint_read_only)
                }
                setComposerEnabled(canQueueInput)
            }
        }
        renderSelectedAttachmentsIfChanged()
        inputModeLabel.visibility = if (isDownloadingApk) View.VISIBLE else View.GONE
    }

    private fun renderUiDraftContext(phoneGateVisible: Boolean) {
        val taskId = screenState.selectedTaskId
        val context = taskId?.let(uiEditorContextByTask::get)
        val canEditCurrentRevision = !phoneGateVisible &&
            !taskId.isNullOrBlank() &&
            context?.source_available == true &&
            context.revision_label.isNotBlank()
        uiDraftContextBar.visibility = if (canEditCurrentRevision) View.VISIBLE else View.GONE
        btnOpenUiEditor.isEnabled = canEditCurrentRevision
        btnOpenUiEditor.alpha = if (canEditCurrentRevision) 1f else 0.45f

        val canSelectSavedUi = canEditCurrentRevision &&
            context?.has_saved_ui == true &&
            screenState.inputMode != InputMode.NEW_GENERATE &&
            screenState.inputMode != InputMode.READ_ONLY
        val checked = canSelectSavedUi && taskId?.let { useSavedUiByTask[it] } == true
        isRenderingUiDraftContext = true
        checkUseSavedUi.isEnabled = canSelectSavedUi
        checkUseSavedUi.alpha = if (canSelectSavedUi) 1f else 0.55f
        checkUseSavedUi.isChecked = checked
        isRenderingUiDraftContext = false
    }

    private fun renderSelectedAttachmentsIfChanged(force: Boolean = false) {
        val fingerprint = UiRenderFingerprint.attachments(selectedAttachments)
        if (!force && fingerprint == lastRenderedAttachmentFingerprint) return
        lastRenderedAttachmentFingerprint = fingerprint

        selectedAttachmentChip.text = buildAttachmentChipLabel(selectedAttachments)
        selectedAttachmentChip.visibility = if (selectedAttachments.isEmpty()) View.GONE else View.VISIBLE
        selectedAttachmentChip.setOnClickListener {
            clearSelectedAttachment()
        }

        val imageAttachments = selectedAttachments.filter { it.kind == SelectedAttachmentKind.IMAGE }
        selectedAttachmentPreviewList.removeAllViews()
        if (imageAttachments.isEmpty()) {
            selectedAttachmentPreviewScroller.visibility = View.GONE
            return
        }

        selectedAttachmentPreviewScroller.visibility = View.VISIBLE
        imageAttachments.forEachIndexed { index, attachment ->
            selectedAttachmentPreviewList.addView(createComposerImagePreview(attachment, index))
        }
    }

    private fun createComposerImagePreview(attachment: SelectedAttachment, index: Int): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                if (index > 0) marginStart = dp(8)
                marginEnd = dp(2)
            }
            val imageView = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.bg_surface_card)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.attachment_preview_open)
                isClickable = true
                bindInlineImagePreview(this, attachment.base64, View.INVISIBLE, maxDimension = 320)
                setOnClickListener {
                    showSelectedAttachmentImageDialog(attachment)
                }
            }
            addView(imageView)

            val removeButton = TextView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_composer_action)
                contentDescription = getString(R.string.attachment_preview_remove)
                gravity = Gravity.CENTER
                text = "x"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    composerDraftAttachmentStore.delete(attachment)
                    selectedAttachments.remove(attachment)
                    renderSelectedAttachmentsIfChanged(force = true)
                }
            }
            addView(removeButton)
        }
    }

    private fun showSelectedAttachmentImageDialog(attachment: SelectedAttachment) {
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_app))
            contentDescription = getString(R.string.attachment_preview_open)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bindInlineImagePreview(imageView, attachment.base64, View.GONE, maxDimension = 2048)
        val content = ScrollView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(imageView)
        }
        AlertDialog.Builder(this)
            .setTitle(attachment.displayName.ifBlank { getString(R.string.attached_image_dialog_title) })
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateTopTitle(phoneGateVisible: Boolean) {
        val selectedTaskId = screenState.selectedTaskId?.trim()?.takeIf { it.isNotBlank() }
        val resolvedAppName = if (phoneGateVisible) {
            null
        } else {
            taskDisplayName(screenState.displayedAppName)
                ?: selectedTaskId?.let { taskDisplayName(taskSummaryById[it]?.appName) }
        }
        topTitleText.text = resolvedAppName ?: getString(R.string.app_title)
    }

    private fun shouldAutoScrollMessages(visibleMessages: List<ChatMessage>): Boolean {
        if (visibleMessages.isEmpty()) return false
        if (isMessageTextSelectionActive) return false
        val selectedTaskId = screenState.selectedTaskId
        if (!selectedTaskId.isNullOrBlank() && pendingInitialChatScrollTaskId == selectedTaskId) return false
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager ?: return false
        val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findLastVisibleItemPosition()
        val currentCount = chatAdapter.itemCount
        if (currentCount <= 0) return true
        return lastVisible >= currentCount - 1
    }

    private fun isChatNearBottom(thresholdItems: Int = 1): Boolean {
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager ?: return true
        val currentCount = chatAdapter.itemCount
        if (currentCount <= 0) return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return true
        return lastVisible >= currentCount - 1 - thresholdItems
    }

    private fun isChatNearBottomByPixels(thresholdPx: Int = dp(48)): Boolean {
        return chatBottomOffsetPx() <= thresholdPx
    }

    private fun chatBottomOffsetPx(): Int {
        val range = recyclerMessages.computeVerticalScrollRange()
        val extent = recyclerMessages.computeVerticalScrollExtent()
        val offset = recyclerMessages.computeVerticalScrollOffset()
        if (range <= 0 || extent <= 0) return 0
        return (range - extent - offset).coerceAtLeast(0)
    }

    private fun withColdStartMessage(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isNotEmpty()) return messages
        if (screenState.inputMode != InputMode.NEW_GENERATE) return messages
        if (!screenState.selectedTaskId.isNullOrBlank()) return messages
        if (!pendingTaskSelectionKey.isNullOrBlank()) return messages
        if (!getLastSelectedTaskId().isNullOrBlank()) return messages
        return listOf(
            ChatMessage(
                id = "cold-start-assistant",
                kind = MessageKind.ASSISTANT,
                title = null,
                body = getString(R.string.cold_start_assistant_message)
            )
        )
    }

    private fun isRedundantDownloadedStatusMessage(message: ChatMessage): Boolean {
        return message.kind == MessageKind.STATUS && message.body == getString(R.string.status_downloaded)
    }

    private fun isRedundantModificationEchoMessage(message: ChatMessage): Boolean {
        if (message.kind != MessageKind.ASSISTANT) return false
        val normalized = normalizeMessageTextForDedupe(message.body)
        if (!normalized.contains("이번 수정") || !normalized.contains("반영")) return false
        return normalized.contains("수정할게요") ||
            normalized.contains("수정합니다") ||
            normalized.startsWith("기존")
    }

    private fun currentDownloadProgressText(): String {
        downloadProgressPercent?.let { return getString(R.string.download_apk_progress, it) }
        if (downloadProgressBytes > 0L) {
            return getString(R.string.download_apk_progress_bytes, formatDownloadBytes(downloadProgressBytes))
        }
        return getString(R.string.download_apk_in_progress)
    }

    private fun formatDownloadBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1fMB", mib)
    }

    private fun withDownloadProgressMessage(messages: List<ChatMessage>): List<ChatMessage> {
        val taskId = downloadingApkTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return messages
        val activeTaskId = screenState.selectedTaskId?.trim()?.takeIf { it.isNotBlank() }
            ?: currentTaskId?.trim()?.takeIf { it.isNotBlank() }
        if (!isDownloadingApk || activeTaskId != taskId) return messages
        if (messages.any(::isCurrentDownloadArtifact)) return messages
        val progressId = "download-progress-$taskId"
        if (messages.any { it.id == progressId }) return messages
        return messages + ChatMessage(
            id = progressId,
            kind = MessageKind.STATUS,
            title = getString(R.string.message_title_status),
            body = currentDownloadProgressText(),
            isLoading = true
        )
    }

    private fun withTransientArtifactDownloadState(message: ChatMessage): ChatMessage {
        val downloading = isCurrentDownloadArtifact(message)
        val progressPercent = if (downloading) downloadProgressPercent else null
        val progressText = if (downloading) currentDownloadProgressText() else null
        return if (
            message.artifactDownloading == downloading &&
            message.artifactDownloadProgressPercent == progressPercent &&
            message.artifactDownloadProgressText == progressText
        ) {
            message
        } else {
            message.copy(
                artifactDownloading = downloading,
                artifactDownloadProgressPercent = progressPercent,
                artifactDownloadProgressText = progressText
            )
        }
    }

    private fun isCurrentDownloadArtifact(message: ChatMessage): Boolean {
        if (!isDownloadingApk) return false
        val taskId = downloadingApkTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (message.artifactTaskId?.trim() != taskId) return false

        return isCurrentDownloadArtifactFor(
            taskId = taskId,
            artifactPath = message.artifactApkPath,
            artifactUrl = message.artifactApkUrl
        )
    }

    private fun isCurrentDownloadArtifactFor(taskId: String, artifactPath: String?, artifactUrl: String?): Boolean {
        if (!isDownloadingApk) return false
        val activeDownloadTaskId = downloadingApkTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return ApkArtifactActionHandler.artifactsMatch(
            targetTaskId = activeDownloadTaskId,
            targetUrl = downloadingApkUrl,
            targetArtifactPath = downloadingArtifactPath,
            candidateTaskId = taskId,
            candidateUrl = artifactUrl,
            candidateArtifactPath = artifactPath
        )
    }

    private fun upsertApkArtifactMessage(taskId: String, response: StatusResponse, appName: String?) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val artifactMessage = apkArtifactMessage(
            taskId = normalizedTaskId,
            response = response,
            appName = appName,
            eventId = "latest-${response.build_attempts}-${response.apk_path.orEmpty().hashCode()}-${response.apk_url.orEmpty().hashCode()}",
            createdAt = currentTimestampString()
        )

        val timeline = editableTaskTimeline(normalizedTaskId) ?: return
        if (timeline.any { it.id == artifactMessage.id }) return
        val artifactPath = artifactMessage.artifactApkPath?.trim().orEmpty()
        val artifactUrl = artifactMessage.artifactApkUrl?.trim().orEmpty()
        val artifactKey = TaskProgressTimelinePolicy.artifactDedupeKey(artifactMessage)
        val existingArtifactIndex = timeline.indexOfLast { message ->
            message.artifactTaskId == normalizedTaskId &&
                (
                    artifactKey != null && TaskProgressTimelinePolicy.artifactDedupeKey(message) == artifactKey ||
                        isSameApkArtifact(message, artifactPath, artifactUrl)
                    )
        }
        if (existingArtifactIndex >= 0) {
            timeline[existingArtifactIndex] = mergeApkArtifactMessages(
                existing = timeline[existingArtifactIndex],
                incoming = artifactMessage
            ).withUniqueId(normalizedTaskId, existingArtifactIndex)
            persistTaskChat(normalizedTaskId)
            return
        }
        timeline += artifactMessage.withUniqueId(normalizedTaskId, timeline.size)
        persistTaskChat(normalizedTaskId)
    }

    private fun isSameApkArtifact(message: ChatMessage, artifactPath: String, artifactUrl: String): Boolean {
        val messagePath = message.artifactApkPath?.trim().orEmpty()
        val messageUrl = message.artifactApkUrl?.trim().orEmpty()
        return when {
            artifactPath.isNotBlank() && messagePath.isNotBlank() -> messagePath == artifactPath
            artifactPath.isBlank() && artifactUrl.isNotBlank() && messageUrl.isNotBlank() -> messageUrl == artifactUrl
            else -> false
        }
    }

    private fun mergeApkArtifactMessages(existing: ChatMessage, incoming: ChatMessage): ChatMessage {
        if (existing.artifactTaskId.isNullOrBlank() || incoming.artifactTaskId.isNullOrBlank()) {
            return incoming
        }
        return incoming.copy(
            id = existing.id,
            artifactApkUrl = incoming.artifactApkUrl?.trim()?.takeIf { it.isNotBlank() } ?: existing.artifactApkUrl,
            artifactApkPath = incoming.artifactApkPath?.trim()?.takeIf { it.isNotBlank() } ?: existing.artifactApkPath,
            artifactDownloadedPath = incoming.artifactDownloadedPath?.trim()?.takeIf { it.isNotBlank() }
                ?.takeIf { File(it).exists() },
            artifactRevisionLabel = incoming.artifactRevisionLabel?.trim()?.takeIf { it.isNotBlank() }
                ?: existing.artifactRevisionLabel,
            artifactBuildAttempt = incoming.artifactBuildAttempt ?: existing.artifactBuildAttempt,
            artifactCanDownload = incoming.artifactCanDownload || existing.artifactCanDownload,
            artifactCanInstall = incoming.artifactCanInstall,
            artifactInstalled = incoming.artifactInstalled,
            artifactPackageName = incoming.artifactPackageName?.trim()?.takeIf { it.isNotBlank() }
                ?: existing.artifactPackageName,
            artifactDownloading = incoming.artifactDownloading || existing.artifactDownloading,
            artifactDownloadProgressPercent = incoming.artifactDownloadProgressPercent
                ?: existing.artifactDownloadProgressPercent,
            artifactDownloadProgressText = incoming.artifactDownloadProgressText
                ?: existing.artifactDownloadProgressText,
            createdAt = existing.createdAt ?: incoming.createdAt
        )
    }

    private fun apkArtifactMessage(
        taskId: String,
        response: StatusResponse,
        appName: String?,
        eventId: String,
        createdAt: String
    ): ChatMessage {
        val artifactApkUrl = response.apk_url
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: persistedApkUrlForTask(taskId)
            ?: "${HostAppConfig.BASE_URL}/download/$taskId"
        val artifactApkPath = response.apk_path?.trim()?.takeIf { it.isNotBlank() }
        val revisionLabel = apkArtifactRevisionLabel(response)
        val artifactPackageName = response.package_name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: taskSummaryById[taskId]?.packageName?.trim()?.takeIf { it.isNotBlank() }
        val installableFile = persistedDownloadedApkFileForTask(taskId)
        val artifactIdentity = ApkArtifactActionHandler.artifactIdentity(
            taskId,
            artifactApkUrl,
            artifactApkPath
        )
        val installed = artifactPackageName?.let { packageName ->
            isPackageInstalled(packageName) &&
                ApkArtifactActionHandler.installedArtifactMatches(
                    this,
                    packageName,
                    artifactIdentity
                )
        } == true
        return ChatMessage(
            id = "artifact-$taskId-$eventId",
            kind = MessageKind.STATUS,
            title = null,
            body = apkArtifactFileName(response, appName, revisionLabel),
            detail = apkArtifactMeta(response, revisionLabel),
            createdAt = createdAt,
            artifactTaskId = taskId,
            artifactApkUrl = artifactApkUrl,
            artifactApkPath = artifactApkPath,
            artifactDownloadedPath = installableFile?.absolutePath,
            artifactRevisionLabel = revisionLabel,
            artifactBuildAttempt = response.build_attempts.takeIf { it > 0 },
            artifactCanDownload = artifactApkUrl.isNotBlank(),
            artifactCanInstall = installableFile != null,
            artifactInstalled = installed,
            artifactPackageName = artifactPackageName,
            artifactDownloading = isCurrentDownloadArtifactFor(taskId, artifactApkPath, artifactApkUrl),
            artifactDownloadProgressPercent = if (isCurrentDownloadArtifactFor(taskId, artifactApkPath, artifactApkUrl)) {
                downloadProgressPercent
            } else {
                null
            },
            artifactDownloadProgressText = if (isCurrentDownloadArtifactFor(taskId, artifactApkPath, artifactApkUrl)) {
                currentDownloadProgressText()
            } else {
                null
            }
        )
    }

    private fun markArtifactDownloaded(taskId: String, url: String?, artifactPath: String?, file: File) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val normalizedPath = artifactPath?.trim().orEmpty()
        val normalizedUrl = url?.trim().orEmpty()
        ensureTaskChatLoaded(normalizedTaskId)
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        var changed = false
        val nextTimeline = timeline.map { message ->
            if (message.artifactTaskId != normalizedTaskId) return@map message
            val samePath = normalizedPath.isNotBlank() && message.artifactApkPath?.trim() == normalizedPath
            val sameUrl = normalizedPath.isBlank() && normalizedUrl.isNotBlank() && message.artifactApkUrl?.trim() == normalizedUrl
            if (!samePath && !sameUrl) return@map message
            changed = true
            message.copy(
                artifactDownloadedPath = file.absolutePath,
                artifactCanInstall = true,
                artifactDownloading = false,
                artifactDownloadProgressPercent = null,
                artifactDownloadProgressText = null
            )
        }
        if (!changed) return
        taskConversationMessages[normalizedTaskId] = nextTimeline.toMutableList()
        taskTimelineRenderCache.markChanged(normalizedTaskId)
        persistTaskChat(normalizedTaskId)
        if (screenState.selectedTaskId == normalizedTaskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(normalizedTaskId))
        }
    }

    private fun clearArtifactDownloadedState(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        latestDownloadedApkFile = null
        if (latestDownloadedTaskId == normalizedTaskId) {
            latestDownloadedTaskId = null
        }
        updateTaskArtifactState(
            taskId = normalizedTaskId,
            apkUrl = taskArtifactStates[normalizedTaskId]?.apkUrl,
            downloadedApkFile = null
        )
        if (!ensureTaskChatLoaded(normalizedTaskId)) return
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        taskConversationMessages[normalizedTaskId] = timeline.map { message ->
            if (message.artifactTaskId != normalizedTaskId) {
                message
            } else {
                message.copy(
                    artifactDownloadedPath = null,
                    artifactCanInstall = false,
                    artifactDownloading = false,
                    artifactDownloadProgressPercent = null,
                    artifactDownloadProgressText = null
                )
            }
        }.toMutableList()
        taskTimelineRenderCache.markChanged(normalizedTaskId)
        persistTaskChat(normalizedTaskId)
        if (screenState.selectedTaskId == normalizedTaskId) {
            screenState = screenState.copy(
                messages = buildTaskTimeline(normalizedTaskId),
                canInstall = false,
                canDownload = persistedApkUrlForTask(normalizedTaskId) != null
            )
            renderState()
        }
    }

    private fun apkArtifactFileName(response: StatusResponse, appName: String?, revisionLabel: String): String {
        val nameFromApp = appName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            ?.trim('.', ' ')
            ?.takeIf { it.isNotBlank() }
        if (nameFromApp != null) return "$nameFromApp $revisionLabel"

        return listOf(response.apk_path, response.apk_url)
            .asSequence()
            .map { it.orEmpty().substringBefore('?').substringAfterLast('/').trim() }
            .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?.let { fileName ->
                val base = fileName.removeSuffix(".apk").removeSuffix(".APK")
                "$base $revisionLabel"
            }
            ?: getString(R.string.artifact_file_label)
    }

    private fun apkArtifactMeta(response: StatusResponse, revisionLabel: String): String {
        val sizeLabel = response.apk_size_bytes
            ?.takeIf { it > 0L }
            ?.let(::formatFileSize)
            ?: getString(R.string.artifact_size_pending)
        val versionKind = if (revisionNumberFromLabel(revisionLabel) <= 1) "최초 생성" else "수정본"
        return listOf(
            revisionLabel,
            versionKind,
            getString(R.string.library_file_type_apk),
            sizeLabel
        ).joinToString(" · ")
    }

    private fun apkArtifactRevisionLabel(response: StatusResponse): String {
        val fromPath = Regex("""(?:^|/)rev_0*(\d+)(?:/|$)""")
            .find(response.apk_path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val revision = fromPath ?: response.build_attempts.takeIf { it > 0 } ?: 1
        return "v$revision"
    }

    private fun revisionNumberFromLabel(label: String): Int {
        return label.trim().removePrefix("v").toIntOrNull() ?: 1
    }

    private fun handleArtifactDownloadRequested(message: ChatMessage) {
        handleArtifactInstallRequested(message)
    }

    private fun handleBuildCancelRequested(message: ChatMessage) {
        val rawTaskId = message.cancelTaskId?.trim().orEmpty()
        if (rawTaskId.isBlank()) return
        val taskId = resolveApiTaskId(rawTaskId, "/tasks/{task_id}/cancel") ?: return
        if (!cancellingTaskIds.add(taskId)) return
        addTaskEvent(
            taskId,
            ChatMessage(
                id = "build-cancel-requested-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.build_cancel_requested),
                createdAt = currentTimestampString()
            )
        )
        lifecycleScope.launch {
            try {
                logTaskIdForApi("/tasks/{task_id}/cancel", taskId)
                logApiRequest("/tasks/{task_id}/cancel", taskId = taskId, deviceId = deviceId)
                val response = apiService.cancelTask(
                    taskId,
                    deviceId,
                    null,
                    userIdentity.phoneNumber
                )
                applyStatus(taskId, response, autoInstallOnSuccess = false, syncPolling = true)
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logApiFailure("/tasks/{task_id}/cancel", taskId = taskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    taskId,
                    ChatMessage(
                        id = "build-cancel-failed-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.build_cancel_failed, userVisibleErrorMessage(e)),
                        createdAt = currentTimestampString()
                    )
                )
                renderState()
            } finally {
                cancellingTaskIds.remove(taskId)
            }
        }
    }

    private fun handleArtifactInstallRequested(message: ChatMessage) {
        if (isDownloadingApk) return
        val taskId = message.artifactTaskId?.trim().orEmpty()
        if (taskId.isBlank()) return
        val packageName = message.artifactPackageName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: taskSummaryById[taskId]?.packageName?.trim()?.takeIf { it.isNotBlank() }
        val artifactPath = message.artifactApkPath?.trim()?.takeIf { it.isNotBlank() }
        val artifactUrl = message.artifactApkUrl?.trim()?.takeIf { it.isNotBlank() }
        val artifactIdentity = ApkArtifactActionHandler.artifactIdentity(
            taskId,
            artifactUrl,
            artifactPath
        )
        if (
            packageName != null &&
            isPackageInstalled(packageName) &&
            ApkArtifactActionHandler.installedArtifactMatches(
                this,
                packageName,
                artifactIdentity
            )
        ) {
            launchGeneratedApp(packageName)
            return
        }
        val messageDownloadedFile = message.artifactDownloadedPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }
        val cachedArtifactFile = cachedDownloadedApkFileForArtifact(taskId, artifactUrl, artifactPath)
        val downloadedFile = if (artifactPath != null) {
            cachedArtifactFile
        } else {
            messageDownloadedFile
                ?: cachedArtifactFile
                ?: persistedDownloadedApkFileForTask(taskId)
        }
        if (downloadedFile != null) {
            installApk(
                downloadedFile,
                taskId = taskId,
                artifactIdentity = artifactIdentity
            )
            return
        }
        val apkUrl = artifactUrl ?: persistedApkUrlForTask(taskId)
        apkUrl?.let { downloadAndInstall(taskId, it, artifactPath) }
    }

    private fun launchGeneratedApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null || runCatching { startActivity(launchIntent) }.isFailure) {
            Toast.makeText(this, R.string.generated_app_launch_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.artifact_size_pending)
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun setComposerEnabled(enabled: Boolean) {
        inputPrompt.isEnabled = enabled
        updateAttachmentButtonState()
        btnSend.isEnabled = enabled
        btnSend.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun updateAttachmentButtonState() {
        val enabled = inputPrompt.isEnabled && !attachmentFlowInProgress
        btnAttachReferenceImage.isEnabled = enabled
        btnAttachReferenceImage.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun hideKeyboardAndClearInputFocus() {
        inputPrompt.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(inputPrompt.windowToken, 0)
    }

    private fun buildModeLabel(modeText: String): String {
        val status = screenState.currentStatus.takeIf { it.isNotBlank() } ?: return modeText
        return "$modeText · 현재: $status"
    }

    private fun appendLocalUserMessage(
        message: String,
        imagePreview: ChatImagePreview? = null,
        imagePreviews: List<ChatImagePreview> = emptyList()
    ) {
        val localUserMessage = ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            kind = MessageKind.USER,
            title = getString(R.string.message_title_user),
            body = message,
            createdAt = currentTimestampString(),
            imagePreviewBase64 = imagePreview?.base64,
            imagePreviewName = imagePreview?.displayName,
            imagePreviews = imagePreviews
        )
        val localMessages = screenState.messages + localUserMessage
        screenState = screenState.copy(messages = localMessages)
        requestScrollLatestAfterResponse(force = true)
        renderState()
    }

    private fun showAttachmentMenu() {
        if (attachmentFlowInProgress) return
        attachmentFlowInProgress = true
        updateAttachmentButtonState()
        val dialog = BottomSheetDialog(this)
        dialog.setOnDismissListener {
            finishAttachmentFlow()
        }
        var choiceEnabledAt = Long.MAX_VALUE
        dialog.setOnShowListener {
            choiceEnabledAt = SystemClock.elapsedRealtime() + ATTACHMENT_MENU_TAP_GUARD_MS
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        val grabber = View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        content.addView(
            grabber,
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(
            buildAttachmentSheetTile(
                title = getString(R.string.attachment_menu_camera),
                iconRes = android.R.drawable.ic_menu_camera
            ) {
                launchAttachmentPicker(dialog, choiceEnabledAt) {
                    launchCameraAttachment()
                }
            }
        )
        row.addView(
            buildAttachmentSheetTile(
                title = getString(R.string.attachment_menu_photo),
                iconRes = android.R.drawable.ic_menu_gallery
            ) {
                launchAttachmentPicker(dialog, choiceEnabledAt) {
                    launchReferenceImagePicker()
                }
            }
        )
        row.addView(
            buildAttachmentSheetTile(
                title = getString(R.string.attachment_menu_file),
                iconRes = R.drawable.ic_artifact_file
            ) {
                launchAttachmentPicker(dialog, choiceEnabledAt) {
                    pickDocumentAttachmentLauncher.launch(arrayOf("application/pdf", "text/*"))
                }
            }
        )
        content.addView(row)
        dialog.setContentView(content)
        try {
            dialog.show()
        } catch (e: RuntimeException) {
            finishAttachmentFlow()
            Toast.makeText(
                this,
                getString(R.string.attachment_pick_failed, userVisibleErrorMessage(e)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchAttachmentPicker(
        dialog: BottomSheetDialog,
        choiceEnabledAt: Long,
        launch: () -> Unit
    ) {
        if (!attachmentFlowInProgress) return
        if (SystemClock.elapsedRealtime() < choiceEnabledAt) return
        dialog.setOnDismissListener(null)
        dialog.dismiss()
        try {
            launch()
        } catch (e: RuntimeException) {
            finishAttachmentFlow()
            Toast.makeText(
                this,
                getString(R.string.attachment_pick_failed, userVisibleErrorMessage(e)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun finishAttachmentFlow() {
        if (!attachmentFlowInProgress) return
        attachmentFlowInProgress = false
        updateAttachmentButtonState()
    }

    private fun buildAttachmentSheetTile(title: String, iconRes: Int, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(14))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_surface_alt)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.text_primary)
                },
                LinearLayout.LayoutParams(dp(32), dp(32))
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 16f
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(118), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
    }

    private fun launchCameraAttachment() {
        val cacheRoot = externalCacheDir
        if (cacheRoot == null) {
            finishAttachmentFlow()
            Toast.makeText(this, R.string.attachment_camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val imageFile = File.createTempFile("camera_attachment_", ".jpg", cacheRoot)
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", imageFile)
        pendingCameraImageUri = uri
        captureImageLauncher.launch(uri)
    }

    private fun launchReferenceImagePicker() {
        val intent = AttachmentPickerIntentFactory.multipleImages()
        if (intent.resolveActivity(packageManager) != null) {
            pickReferenceImageLauncher.launch(intent)
        } else {
            pickReferenceImageFallbackLauncher.launch(arrayOf("image/*"))
        }
    }

    private fun handleAttachmentSelected(uri: Uri, kind: SelectedAttachmentKind) {
        handleAttachmentsSelected(listOf(uri), kind)
    }

    private fun handleAttachmentsSelected(uris: List<Uri>, kind: SelectedAttachmentKind) {
        val attachmentStartedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            try {
                val attachments = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        buildSelectedAttachment(
                            contentResolver = contentResolver,
                            uri = uri,
                            requestedKind = kind,
                            maxOriginalImageBytes = MAX_ATTACHMENT_IMAGE_ORIGINAL_BYTES,
                            maxImagePayloadBytes = MAX_ATTACHMENT_IMAGE_PAYLOAD_BYTES,
                            maxPdfBytes = MAX_ATTACHMENT_PDF_BYTES,
                            maxTextBytes = MAX_ATTACHMENT_TEXT_BYTES
                        )
                    }
                }
                if (attachments.isEmpty()) {
                    val messageRes = when (kind) {
                        SelectedAttachmentKind.IMAGE -> R.string.attachment_image_too_large
                        SelectedAttachmentKind.PDF -> R.string.attachment_pdf_too_large
                        SelectedAttachmentKind.TEXT -> R.string.attachment_text_too_large
                    }
                    Toast.makeText(this@MainActivity, messageRes, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val attachmentsToPersist = if (kind == SelectedAttachmentKind.IMAGE) {
                    attachments
                } else {
                    attachments.take(1)
                }
                val persistedAttachments = withContext(Dispatchers.IO) {
                    attachmentsToPersist.mapNotNull(composerDraftAttachmentStore::persist)
                }
                if (persistedAttachments.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.attachment_temp_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                if (kind == SelectedAttachmentKind.IMAGE) {
                    selectedAttachments += persistedAttachments
                } else {
                    clearSelectedAttachment(render = false)
                    selectedAttachments += persistedAttachments.first()
                }
                Log.i(
                    TAG,
                    "Attachments prepared kind=${kind.name} requested=${uris.size} saved=${persistedAttachments.size} " +
                        "duration_ms=${SystemClock.elapsedRealtime() - attachmentStartedAt}"
                )
                renderState()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.attachment_pick_failed, userVisibleErrorMessage(e)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun moveLocalConversationToTask(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        if (!currentTaskId.isNullOrBlank() || !screenState.selectedTaskId.isNullOrBlank()) return

        val localMessages = screenState.messages
        if (localMessages.isEmpty()) return

        localMessages.forEach { message ->
            appendTaskTimelineMessage(normalizedTaskId, message)
        }
        if (pendingInitialChatScrollTaskId == normalizedTaskId) {
            pendingInitialChatScrollTaskId = null
        }
        requestScrollLatestAfterResponse(force = true)
        screenState = screenState.copy(
            selectedTaskId = normalizedTaskId,
            messages = buildTaskTimeline(normalizedTaskId)
        )
        renderState()
    }

    private fun parseTaskSummaries(json: JsonElement): List<TaskSummary> {
        val dtos = when {
            json.isJsonArray -> gson.fromJson(json, Array<TaskSummaryDto>::class.java).toList()
            json.isJsonObject && json.asJsonObject.has("tasks") -> {
                gson.fromJson(json, TasksEnvelope::class.java).tasks.orEmpty()
            }
            else -> emptyList()
        }

        return dtos.mapNotNull { dto ->
            val taskId = dto.task_id.trim()
            if (taskId.isBlank()) return@mapNotNull null
            if (requestScopeFromState(dto.conversation_state) == "non_app_request") return@mapNotNull null
            val appName = taskDisplayName(dto.app_name)
                ?: taskDisplayName(dto.generated_app_name)
            val initialPrompt = dto.initial_user_prompt.trim().takeIf { it.isNotBlank() }
            val title = buildTaskContentTitle(
                initialPrompt = initialPrompt,
                appName = appName,
                conversationState = dto.conversation_state
            ) ?: getString(R.string.untitled_task)
            val createdAt = formatTaskSummaryTimestamp(dto.created_at)
            val lastBubbleAt = if (taskId in loadedTaskChatIds) {
                taskSummaryLastBubbleTimestamp(taskId)
            } else {
                null
            }
            val displayUpdatedAt = lastBubbleAt
                ?: formatTaskSummaryTimestamp(dto.last_bubble_at)
                ?: formatTaskSummaryTimestamp(dto.updated_at)
                ?: createdAt
            TaskSummary(
                taskId = taskId,
                title = title,
                appName = appName,
                packageName = dto.package_name.ifBlank { null },
                subtitle = appName ?: createdAt.orEmpty().ifBlank { taskId },
                status = dto.status_display_text.ifBlank { displayStatusText(dto.status) },
                updatedAt = displayUpdatedAt,
                hasApk = dto.apk_url.isNotBlank(),
                hasRuntimeError = taskId in runtimeErrorTaskIds
            )
        }
    }

    private fun requestScopeFromState(conversationState: JsonElement?): String {
        return conversationState
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { firstString(it, "request_scope") }
            ?.trim()
            ?.lowercase()
            .orEmpty()
    }

    private fun mergeConversationMessages(
        taskId: String,
        response: StatusResponse
    ): List<ChatMessage> {
        val timelinePage = extractTimelineEventPage(response, taskId)
        val timelineEvents = timelinePage.events
        seedConversationMessages(taskId, response, timelineEvents)
        backfillTimelineEventTypes(taskId, timelineEvents)
        backfillTimelineAttachments(taskId, timelineEvents)
        val seededTimeline = buildTaskTimeline(taskId)
        val incomingMessages = when {
            seededTimeline.isNotEmpty() ->
                buildIncrementalMessages(taskId, response, seededTimeline, timelineEvents)
                    .filter(::shouldKeepChatTimelineMessage)
            else ->
                emptyList()
        }
        incomingMessages.forEach { message ->
            appendTaskTimelineMessage(
                taskId,
                message,
                allowDuplicateContent = !message.artifactTaskId.isNullOrBlank()
            )
        }
        appendStatusTransitionMessage(taskId, response)
        return buildTaskTimeline(taskId).also {
            timelinePage.nextCursor
                ?.takeIf { cursor -> cursor.isNotBlank() }
                ?.let { cursor -> taskTimelineEventCursorById[taskId] = cursor }
        }
    }

    private fun backfillTimelineEventTypes(
        taskId: String,
        timelineEvents: List<TimelineEventSnapshot>
    ) {
        val normalizedTaskId = taskId.trim()
        if (!ensureTaskChatLoaded(normalizedTaskId)) return
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        val changed = ChatTimelineEventTypeBackfill.backfill(
            timeline = timeline,
            sourceEvents = timelineEvents.map { event ->
                ChatTimelineEventTypeBackfill.SourceEvent(
                    kind = event.kind,
                    body = event.body,
                    eventType = event.eventType
                )
            }
        )
        if (changed) {
            persistTaskChat(normalizedTaskId)
        }
    }

    private fun seedConversationMessages(
        taskId: String,
        response: StatusResponse,
        timelineEvents: List<TimelineEventSnapshot>
    ) {
        val obj = response.conversation_state?.takeIf { it.isJsonObject }?.asJsonObject
        if (obj != null) {
            val initialPrompt = firstString(obj, "initial_user_prompt")?.trim().orEmpty()
            val suppressInitialPromptBubble = obj.get("suppress_initial_prompt_bubble")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean == true ||
                obj.get("branch_origin")?.isJsonObject == true
            if (initialPrompt.isNotBlank() && suppressInitialPromptBubble) {
                val normalizedTaskId = taskId.trim()
                val timeline = taskConversationMessages[normalizedTaskId]
                val removed = timeline?.removeAll { message ->
                    message.kind == MessageKind.USER &&
                        message.id.startsWith("seed-user-") &&
                        hasSameMessageText(message.body, initialPrompt)
                } == true
                if (removed) {
                    persistTaskChat(normalizedTaskId)
                }
            }
            if (initialPrompt.isNotBlank() && !suppressInitialPromptBubble) {
                val initialCreatedAt = response.created_at.trim().takeIf { it.isNotBlank() }
                    ?: currentTimestampString()
                val normalizedTaskId = taskId.trim()
                val timeline = taskConversationMessages[normalizedTaskId]
                val hasCanonicalInitialEvent = timelineEvents.any { event ->
                    event.kind.equals("user", ignoreCase = true) &&
                        event.eventType == "user_message" &&
                        hasSameMessageText(event.body, initialPrompt)
                }
                val hasPersistedInitialMessage = timeline?.any { message ->
                    message.kind == MessageKind.USER &&
                        !message.id.startsWith("seed-user-") &&
                        hasSameMessageText(message.body, initialPrompt)
                } == true
                val existingSeedIndex = timeline?.indexOfFirst { message ->
                    message.kind == MessageKind.USER &&
                        message.id.startsWith("seed-user-") &&
                        hasSameMessageText(message.body, initialPrompt)
                } ?: -1
                if (timeline != null && (hasCanonicalInitialEvent || hasPersistedInitialMessage) && existingSeedIndex >= 0) {
                    timeline.removeAt(existingSeedIndex)
                    persistTaskChat(normalizedTaskId)
                } else if (timeline != null && existingSeedIndex >= 0) {
                    val existingSeed = timeline[existingSeedIndex]
                    if (existingSeed.createdAt != initialCreatedAt) {
                        timeline[existingSeedIndex] = existingSeed.copy(createdAt = initialCreatedAt)
                        persistTaskChat(normalizedTaskId)
                    }
                } else if (!hasCanonicalInitialEvent && !taskHasUserMessageText(taskId, initialPrompt)) {
                    appendTaskTimelineMessage(
                        taskId,
                        ChatMessage(
                            id = "seed-user-$taskId",
                            kind = MessageKind.USER,
                            title = getString(R.string.message_title_user),
                            body = initialPrompt,
                            createdAt = initialCreatedAt
                        )
                    )
                }
            }

            val latestSummary = firstString(obj, "latest_summary")
                ?.trim()
                ?.takeUnless { isPrebuildConfirmationHeader(it) }
                .orEmpty()
            val confirmationAction = firstString(obj, "confirmation_action")?.trim().orEmpty()
            val confirmationPayload = firstString(obj, "confirmation_payload")?.trim().orEmpty()
            val renderMode = firstString(obj, "render_mode")?.trim().orEmpty()
            val awaitingConfirmation = obj.get("awaiting_confirmation")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            val awaitingPromptReview = obj.get("awaiting_prompt_review")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
                renderMode == "prompt_review_bubble" ||
                confirmationAction == "submit_initial_prompt" ||
                isPromptReviewRenderMode(response)
            val preparedPrompt = firstString(obj, "prepared_prompt")?.trim().orEmpty()
                .ifBlank { response.prepared_prompt?.trim().orEmpty() }
                .ifBlank { confirmationPayload }
            if (
                latestSummary.isNotBlank() &&
                renderMode != "confirmation_bubble" &&
                renderMode != "prompt_review_bubble" &&
                !isUserPromptEcho(taskId, latestSummary, initialPrompt) &&
                !timelineContainsBody(taskId, latestSummary)
            ) {
                appendTaskTimelineMessage(
                    taskId,
                    ChatMessage(
                        id = "seed-summary-$taskId",
                        kind = MessageKind.ASSISTANT,
                        title = getString(R.string.message_title_assistant),
                        body = latestSummary,
                        createdAt = currentTimestampString()
                    )
                )
            }

            val latestQuestions = stringList(obj, "latest_assistant_questions")
            if (awaitingPromptReview && preparedPrompt.isNotBlank()) {
                if (!timelineContainsBody(taskId, preparedPrompt)) {
                    appendTaskTimelineMessage(
                        taskId,
                        ChatMessage(
                            id = "seed-prompt-review-$taskId-${preparedPrompt.hashCode()}",
                            kind = MessageKind.CONFIRMATION,
                            title = getString(R.string.confirmation_title),
                            body = preparedPrompt,
                            detail = latestSummary.takeIf { it.isNotBlank() },
                            createdAt = currentTimestampString(),
                            confirmAction = "submit_initial_prompt",
                            confirmTaskId = taskId,
                            confirmPayload = preparedPrompt,
                            promptReviewTaskId = taskId,
                            promptReviewText = preparedPrompt
                        )
                    )
                }
            } else if (awaitingConfirmation && confirmationAction.isNotBlank()) {
                latestQuestions.firstOrNull()
                    ?.takeIf { it.isNotBlank() && !timelineContainsBody(taskId, it) }
                    ?.let { question ->
                        appendTaskTimelineMessage(
                            taskId,
                            ChatMessage(
                                id = "seed-confirmation-$taskId",
                                kind = MessageKind.CONFIRMATION,
                                title = getString(R.string.confirmation_title),
                                body = question,
                                detail = latestSummary.takeIf { it.isNotBlank() },
                                createdAt = currentTimestampString(),
                                confirmAction = confirmationAction,
                                confirmTaskId = taskId,
                                confirmPayload = confirmationPayload
                            )
                        )
                    }
            } else {
                val questionBody = buildClarificationBubbleBody(
                    message = null,
                    reason = null,
                    questions = latestQuestions
                )
                if (!questionBody.isNullOrBlank() &&
                    !timelineContainsBody(taskId, questionBody) &&
                    !timelineContainsClarificationQuestions(taskId, latestQuestions)
                ) {
                    appendTaskTimelineMessage(
                        taskId,
                        ChatMessage(
                            id = "seed-question-$taskId",
                            kind = MessageKind.ASSISTANT,
                            title = getString(R.string.message_title_assistant),
                            body = questionBody,
                            createdAt = currentTimestampString()
                        )
                    )
                }
            }
        }

        val latestFailure = resolveFailureBubbleText(response)
        if (
            latestFailure.isNotBlank() &&
            !timelineContainsBody(taskId, latestFailure, kind = MessageKind.ASSISTANT)
        ) {
            appendTaskTimelineMessage(
                taskId,
                ChatMessage(
                    id = "seed-failure-$taskId-${response.latest_assistant_message_type.orEmpty()}-${latestFailure.hashCode()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = latestFailure,
                    createdAt = currentTimestampString()
                )
            )
        }
    }

    private fun resolveFailureBubbleText(response: StatusResponse): String {
        val explicit = response.latest_failure_message?.trim().orEmpty()
        if (!TaskStatusPolicy.isRetryableFailure(response.status)) return ""

        val detail = (listOf(explicit) + listOf(
            response.latest_assistant_message,
            response.status_message,
            response.latest_log,
            response.log
        ))
            .asSequence()
            .map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("[") &&
                    !line.contains("핵심 로그") &&
                    !line.contains("실패 (")
            }
            ?.take(220)
            .orEmpty()

        val visibleDetail = detail.ifBlank {
            "앱 생성 과정에서 빌드 오류가 발생했어요."
        }
        return getString(R.string.build_failure_bubble, visibleDetail)
    }

    private fun timelineContainsBody(
        taskId: String,
        body: String,
        kind: MessageKind? = null
    ): Boolean {
        return buildTaskTimeline(taskId).any { message ->
            (kind == null || message.kind == kind) && hasSameMessageText(message.body, body)
        }
    }

    private fun taskHasUserMessageText(taskId: String, body: String): Boolean {
        val normalizedTaskId = taskId.trim()
        val taskMessages = taskConversationMessages[normalizedTaskId].orEmpty()
        val activeScreenMessages = if (screenState.selectedTaskId == normalizedTaskId || currentTaskId == normalizedTaskId) {
            screenState.messages
        } else {
            emptyList()
        }
        return (taskMessages.asSequence() + activeScreenMessages.asSequence())
            .any { it.kind == MessageKind.USER && hasSameMessageText(it.body, body) }
    }

    private fun isUserPromptEcho(taskId: String, body: String, initialPrompt: String? = null): Boolean {
        val normalizedBody = body.trim()
        if (normalizedBody.isBlank()) return false
        if (!initialPrompt.isNullOrBlank() && hasSameMessageText(normalizedBody, initialPrompt)) return true
        return taskHasUserMessageText(taskId, normalizedBody)
    }

    private fun timelineContainsClarificationQuestions(taskId: String, questions: List<String>): Boolean {
        val questionKeys = questions
            .flatMap { clarificationQuestionKeys(it, requireQuestionMark = false) }
            .toSet()
        if (questionKeys.isEmpty()) return false
        return buildTaskTimeline(taskId).any { message ->
            isAssistantLikeMessage(message) &&
                clarificationQuestionKeys(message.body, requireQuestionMark = false).containsAll(questionKeys)
        }
    }

    private fun buildClarificationBubbleBody(message: String?, reason: String?, questions: List<String>): String? {
        val intro = message?.trim()?.takeIf { it.isNotBlank() }
            ?: reason?.trim()?.takeIf { it.isNotBlank() }
        val seen = linkedSetOf<String>()
        intro?.let { seen += it }
        val numberedQuestions = questions
            .mapNotNull { it.trim().takeIf { question -> question.isNotBlank() } }
            .filter { seen.add(it) }
            .map { question -> "- $question" }
            .joinToString("\n")

        return listOfNotNull(intro, numberedQuestions.takeIf { it.isNotBlank() })
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun extractTimelineEventPage(
        response: StatusResponse,
        taskId: String = response.task_id
    ): TimelineEventPage {
        val array = response.timeline_events?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return TimelineEventPage(
                events = emptyList(),
                nextCursor = TimelineCursorPolicy.nextCursor(response.timeline_cursor, emptyList())
            )
        val eventIds = (0 until array.size()).map { index ->
            array[index]
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { obj -> firstString(obj, "event_id") }
                ?.trim()
                .orEmpty()
        }
        val startIndex = TimelineCursorPolicy.firstUnprocessedIndex(
            eventIds = eventIds,
            processedEventId = taskTimelineEventCursorById[taskId],
            maxEvents = MAX_CHAT_TIMELINE_EVENTS_FOR_RENDER
        )
        val events = (startIndex until array.size()).mapNotNull { index ->
            val item = array[index]
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val payload = timelineEventPayloadObject(obj)
            TimelineEventSnapshot(
                eventId = firstString(obj, "event_id")?.trim().orEmpty(),
                createdAt = firstString(obj, "created_at")?.trim().orEmpty(),
                kind = firstString(obj, "kind")?.trim().orEmpty(),
                title = firstString(obj, "title")?.trim().orEmpty(),
                body = firstString(obj, "body")?.trim().orEmpty(),
                detail = firstString(obj, "detail")?.trim().orEmpty(),
                eventType = firstString(obj, "event_type")?.trim().orEmpty(),
                confirmationAction = firstString(obj, "confirmation_action") ?: payload?.let { firstString(it, "confirmation_action") },
                confirmationPayload = firstString(obj, "confirmation_payload") ?: payload?.let { firstString(it, "confirmation_payload") },
                preparedPrompt = firstString(obj, "prepared_prompt") ?: payload?.let { firstString(it, "prepared_prompt") },
                renderMode = firstString(obj, "render_mode") ?: payload?.let { firstString(it, "render_mode") },
                apkUrl = firstString(obj, "apk_url") ?: payload?.let { firstString(it, "apk_url") },
                apkPath = firstString(obj, "apk_path") ?: payload?.let { firstString(it, "apk_path") },
                apkSizeBytes = firstLong(obj, "apk_size_bytes") ?: payload?.let { firstLong(it, "apk_size_bytes") },
                appName = firstString(obj, "app_name") ?: payload?.let { firstString(it, "app_name") },
                packageName = firstString(obj, "package_name") ?: payload?.let { firstString(it, "package_name") },
                imagePreviews = timelineEventImagePreviews(taskId, obj)
            ).takeIf { it.body.isNotBlank() || it.imagePreviews.isNotEmpty() }
        }
        return TimelineEventPage(
            events = events,
            nextCursor = TimelineCursorPolicy.nextCursor(response.timeline_cursor, eventIds)
        )
    }

    private fun timelineEventImagePreviews(taskId: String, obj: JsonObject): List<ChatImagePreview> {
        val attachments = obj.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return attachments.mapNotNull { item ->
            val attachment = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val attachmentId = firstString(attachment, "attachment_id")?.trim().orEmpty()
            val kind = firstString(attachment, "kind")?.trim()?.lowercase().orEmpty()
            val mimeType = firstString(attachment, "mime_type")?.trim()?.lowercase().orEmpty()
            if (attachmentId.isBlank() || (kind != "image" && !mimeType.startsWith("image/"))) {
                return@mapNotNull null
            }
            ChatImagePreview(
                displayName = firstString(attachment, "name")?.trim().orEmpty().ifBlank { "첨부 이미지" },
                remoteUrl = taskAttachmentUrl(taskId, attachmentId)
            )
        }
    }

    private fun taskAttachmentUrl(taskId: String, attachmentId: String): String {
        return Uri.parse(
            "${HostAppConfig.BASE_URL.trimEnd('/')}/tasks/$taskId/attachments/$attachmentId"
        ).buildUpon()
            .appendQueryParameter("device_id", deviceId)
            .build()
            .toString()
    }

    private fun timelineEventPayloadObject(obj: JsonObject): JsonObject? {
        val payload = obj.get("payload")
        if (payload?.isJsonObject == true) return payload.asJsonObject
        val payloadJson = firstString(obj, "payload_json")?.trim().orEmpty()
        if (payloadJson.isBlank()) return null
        return runCatching { gson.fromJson(payloadJson, JsonObject::class.java) }.getOrNull()
    }

    private fun visibleUserMessageBody(
        body: String,
        imagePreviews: List<ChatImagePreview>,
        isUserMessage: Boolean
    ): String {
        if (!isUserMessage) return body
        val normalizedBody = normalizeMessageTextForDedupe(body)
        val canonicalBody = AttachmentOnlyMessagePolicy.canonicalUserBody(
            normalizedBody = normalizedBody,
            hasImages = imagePreviews.isNotEmpty(),
            normalizedSyntheticPrompts = attachmentOnlyPromptKeys
        )
        return if (canonicalBody.isBlank()) "" else body
    }

    private fun timelineEventToMessage(taskId: String, event: TimelineEventSnapshot): ChatMessage {
        val messageKind = when (event.kind.lowercase()) {
            "user" -> MessageKind.USER
            "assistant" -> MessageKind.ASSISTANT
            "confirmation" -> MessageKind.CONFIRMATION
            "status" -> MessageKind.STATUS
            else -> MessageKind.LOG
        }
        val confirmationAction = event.confirmationAction?.trim().orEmpty()
        val renderMode = event.renderMode?.trim().orEmpty()
        val preparedPrompt = event.preparedPrompt?.trim().orEmpty()
        val promptReviewText = preparedPrompt
            .ifBlank { event.confirmationPayload?.trim().orEmpty() }
            .ifBlank { event.body }
            .takeIf {
                messageKind == MessageKind.CONFIRMATION &&
                    (confirmationAction == "submit_initial_prompt" || renderMode == "prompt_review_bubble")
            }
        return ChatMessage(
            id = "timeline-$taskId-${event.eventId.ifBlank { event.body.hashCode().toString() }}",
            kind = messageKind,
            title = if (messageKind == MessageKind.CONFIRMATION) {
                getString(R.string.confirmation_title)
            } else event.title.ifBlank {
                when (messageKind) {
                    MessageKind.USER -> getString(R.string.message_title_user)
                    MessageKind.ASSISTANT, MessageKind.CONFIRMATION -> getString(R.string.message_title_assistant)
                    MessageKind.STATUS -> getString(R.string.message_title_status)
                    MessageKind.LOG, MessageKind.BUILD_LOG -> getString(R.string.message_title_log)
                    MessageKind.DATE_SEPARATOR -> ""
                }
            },
            body = visibleUserMessageBody(
                body = event.body,
                imagePreviews = event.imagePreviews,
                isUserMessage = messageKind == MessageKind.USER
            ),
            detail = event.detail.ifBlank { null },
            createdAt = event.createdAt.ifBlank { currentTimestampString() },
            eventType = event.eventType.ifBlank { null },
            imagePreviewBase64 = event.imagePreviews.firstOrNull()?.base64,
            imagePreviewName = event.imagePreviews.firstOrNull()?.displayName,
            imagePreviews = event.imagePreviews,
            confirmAction = confirmationAction.ifBlank { null },
            confirmTaskId = taskId.takeIf { messageKind == MessageKind.CONFIRMATION },
            confirmPayload = event.confirmationPayload?.trim()?.ifBlank { null } ?: promptReviewText,
            promptReviewTaskId = taskId.takeIf { promptReviewText != null },
            promptReviewText = promptReviewText
        )
    }

    private fun backfillTimelineAttachments(
        taskId: String,
        timelineEvents: List<TimelineEventSnapshot>
    ) {
        val normalizedTaskId = taskId.trim()
        if (!ensureTaskChatLoaded(normalizedTaskId)) return
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        var changed = false

        timelineEvents
            .filter { it.kind.equals("user", ignoreCase = true) && it.imagePreviews.isNotEmpty() }
            .forEach { event ->
                val visibleEventBody = visibleUserMessageBody(
                    body = event.body,
                    imagePreviews = event.imagePreviews,
                    isUserMessage = true
                )
                val serverMessageId =
                    "timeline-$normalizedTaskId-${event.eventId.ifBlank { event.body.hashCode().toString() }}"
                val exactIndex = timeline.indexOfFirst { it.id == serverMessageId }
                val matchingIndex = if (exactIndex >= 0) {
                    exactIndex
                } else {
                    timeline.indices.reversed().firstOrNull { index ->
                        val existing = timeline[index]
                        existing.kind == MessageKind.USER &&
                            hasSameMessageText(existing.body, visibleEventBody) &&
                            messagesOccurredNearEachOther(existing.createdAt, event.createdAt)
                    } ?: -1
                }
                if (matchingIndex < 0) return@forEach

                val existing = timeline[matchingIndex]
                val mergedPreviews = mergeImagePreviewSources(
                    existing = existing.allImagePreviews(),
                    incoming = event.imagePreviews
                )
                if (mergedPreviews != existing.allImagePreviews()) {
                    timeline[matchingIndex] = existing.copy(
                        imagePreviewBase64 = mergedPreviews.firstOrNull()?.base64,
                        imagePreviewName = mergedPreviews.firstOrNull()?.displayName,
                        imagePreviews = mergedPreviews
                    )
                    changed = true
                }
            }

        if (changed) {
            persistTaskChat(normalizedTaskId)
        }
    }

    private fun mergeImagePreviewSources(
        existing: List<ChatImagePreview>,
        incoming: List<ChatImagePreview>
    ): List<ChatImagePreview> {
        val merged = existing.toMutableList()
        incoming.forEach { next ->
            val existingIndex = merged.indexOfFirst {
                it.displayName.equals(next.displayName, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                val current = merged[existingIndex]
                merged[existingIndex] = current.copy(
                    displayName = current.displayName.ifBlank { next.displayName },
                    base64 = current.base64.ifBlank { next.base64 },
                    remoteUrl = next.remoteUrl?.takeIf { it.isNotBlank() } ?: current.remoteUrl
                )
            } else {
                merged += next
            }
        }
        return merged
    }

    private fun messagesOccurredNearEachOther(first: String?, second: String?): Boolean {
        val firstTime = parseMessageTimestamp(first)?.time ?: return true
        val secondTime = parseMessageTimestamp(second)?.time ?: return true
        return kotlin.math.abs(firstTime - secondTime) <= 120_000L
    }

    private fun extractServerTimelineMessages(
        taskId: String,
        response: StatusResponse,
        existingTimeline: List<ChatMessage>,
        timelineEvents: List<TimelineEventSnapshot>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val existingMessageIds = existingTimeline.asSequence().map { it.id }.toHashSet()
        timelineEvents.forEach { event ->
            val message = timelineEventToMessage(taskId, event)
            if (
                message.id !in existingMessageIds &&
                messages.none { it.id == message.id || it.sameContentAs(message) }
            ) {
                messages += message
            }
            if (event.eventType == "task_succeeded") {
                val artifactResponse = response.copy(
                    apk_url = event.apkUrl ?: response.apk_url,
                    apk_path = event.apkPath ?: response.apk_path,
                    apk_size_bytes = event.apkSizeBytes ?: response.apk_size_bytes,
                    app_name = event.appName ?: response.app_name,
                    generated_app_name = event.appName ?: response.generated_app_name,
                    package_name = event.packageName ?: response.package_name
                )
                val artifact = apkArtifactMessage(
                    taskId = taskId,
                    response = artifactResponse,
                    appName = taskDisplayName(artifactResponse.generated_app_name) ?: taskDisplayName(artifactResponse.app_name),
                    eventId = event.eventId,
                    createdAt = event.createdAt.ifBlank { message.createdAt ?: currentTimestampString() }
                )
                if (messages.none { it.id == artifact.id }) {
                    messages += artifact
                }
            }
        }
        return messages
    }

    private fun hasStructuredRawLogs(response: StatusResponse): Boolean {
        val array = response.raw_log_sections?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return array.any { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            val content = firstString(obj, "content")?.trim().orEmpty()
            content.isNotBlank()
        }
    }

    private fun buildIncrementalMessages(
        taskId: String,
        response: StatusResponse,
        existingTimeline: List<ChatMessage>,
        timelineEvents: List<TimelineEventSnapshot>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += extractServerTimelineMessages(taskId, response, existingTimeline, timelineEvents)
        messages += extractRecentAssistantMessages(taskId, response, existingTimeline)
        if (hasStructuredRawLogs(response)) {
            return messages
        }
        val localizedLog = resolveFullLogText(response)
        if (!localizedLog.isNullOrBlank() && existingTimeline.none { it.kind == MessageKind.LOG && it.detail == localizedLog }) {
            messages += ChatMessage(
                id = "log-$taskId-${response.status}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_log),
                body = statusSummaryText(response),
                detail = localizedLog,
                createdAt = currentTimestampString()
            )
        }
        return messages
    }

    private fun ensureLatestLogMessage(
        taskId: String,
        response: StatusResponse,
        currentMessages: List<ChatMessage>
    ): List<ChatMessage> {
        if (hasStructuredRawLogs(response)) {
            return currentMessages
        }
        val logText = resolveDisplayLogText(response) ?: return currentMessages
        val normalizedLogText = logText.trim()
        if (normalizedLogText.isBlank()) return currentMessages

        val alreadyExists = currentMessages.any { message ->
            if (message.kind != MessageKind.LOG) return@any false
            val existingLogText = message.detail?.trim().takeUnless { it.isNullOrBlank() }
                ?: message.body.trim()
            existingLogText == normalizedLogText
        }
        if (alreadyExists) return currentMessages

        appendTaskTimelineMessage(
            taskId,
            ChatMessage(
                id = "ensure-log-$taskId-${response.status}-${normalizedLogText.hashCode()}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_log),
                body = statusSummaryText(response),
                detail = normalizedLogText,
                createdAt = currentTimestampString()
            )
        )
        return buildTaskTimeline(taskId)
    }

    private fun ensureCurrentBuildStageMessage(
        taskId: String,
        response: StatusResponse,
        currentMessages: List<ChatMessage>
    ): List<ChatMessage> {
        if (!TaskProgressTimelinePolicy.isActiveBuildStatus(response.status)) {
            return currentMessages
        }

        val timeline = editableTaskTimeline(taskId) ?: return currentMessages

        val logLine = TaskProgressTimelinePolicy.buildProgressDetail(
            response.current_build_stage,
            response.current_build_stage_detail,
            response.status_message
        )
        if (!logLine.isNullOrBlank()) {
            appendTaskTimelineMessage(
                taskId,
                ChatMessage(
                    id = "current-build-log-$taskId-${logLine.hashCode()}",
                    kind = MessageKind.STATUS,
                    title = "빌드",
                    body = logLine,
                    detail = null,
                    createdAt = currentTimestampString()
                )
            )
        }

        val loadingMessageChanged = TaskProgressTimelinePolicy.upsertSingleActiveLoadingMessage(
            timeline = timeline,
            incoming = ChatMessage(
                id = "current-build-stage-$taskId",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_generating),
                detail = null,
                createdAt = currentTimestampString(),
                cancelTaskId = if (isTaskCancellationAllowed(response)) taskId else null,
                isLoading = true
            )
        )
        if (loadingMessageChanged) {
            persistTaskChat(taskId)
        }
        return buildTaskTimeline(taskId)
    }

    private fun finalizeFinishedBuildProgressMessages(
        taskId: String,
        response: StatusResponse,
        currentMessages: List<ChatMessage>
    ): List<ChatMessage> {
        if (!TaskProgressTimelinePolicy.isTerminalBuildStatus(response.status)) {
            return currentMessages
        }
        val normalizedTaskId = taskId.trim()
        if (!ensureTaskChatLoaded(normalizedTaskId)) return currentMessages
        val timeline = taskConversationMessages[normalizedTaskId] ?: return currentMessages
        val progressLogs = timeline
            .filter { message ->
                message.kind == MessageKind.STATUS &&
                    message.id.startsWith("current-build-log-") &&
                    message.body.isNotBlank()
            }
            .distinctBy { compactMessageTextForDedupe(normalizeMessageTextForDedupe(it.body)) }
        var changed = false
        progressLogs.forEach { message ->
            val finalizedId = "finished-progress-log-${message.id}"
            if (timeline.none { it.id == finalizedId }) {
                timeline += message.copy(id = finalizedId, detail = null, isLoading = false)
                changed = true
            }
        }
        val terminalProgressMessage = TaskProgressTimelinePolicy.terminalProgressMessage(
            status = response.status,
            rawMessage = response.status_message ?: response.current_build_stage ?: response.current_build_stage_detail
        )
        if (!terminalProgressMessage.isNullOrBlank()) {
            val finalizedId = "finished-progress-log-$taskId-${terminalProgressMessage.hashCode()}"
            val terminalMessage = ChatMessage(
                id = finalizedId,
                kind = MessageKind.STATUS,
                title = "빌드",
                body = terminalProgressMessage,
                detail = null,
                createdAt = currentTimestampString()
            )
            if (timeline.none { it.id == finalizedId } && timeline.none { shouldDropIncomingDuplicateMessage(it, terminalMessage) }) {
                timeline += terminalMessage
                changed = true
            }
        }
        val removedLoading = timeline.removeAll { message ->
            TaskProgressTimelinePolicy.shouldRemoveLoadingMessage(response.status, message)
        }
        if (!changed && !removedLoading) return currentMessages
        persistTaskChat(normalizedTaskId)
        return buildTaskTimeline(normalizedTaskId)
    }

    private fun extractRecentAssistantMessages(
        taskId: String,
        response: StatusResponse,
        existingTimeline: List<ChatMessage>
    ): List<ChatMessage> {
        if (suppressAssistantBubble(response)) {
            return emptyList()
        }
        val recent = response.recent_messages?.takeIf { it.isJsonArray }?.asJsonArray
        val messages = mutableListOf<ChatMessage>()
        val recentStartIndex = ((recent?.size() ?: 0) - MAX_RECENT_ASSISTANT_MESSAGES_FOR_RENDER).coerceAtLeast(0)
        for (index in recentStartIndex until (recent?.size() ?: 0)) {
            val item = recent?.get(index) ?: continue
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val role = firstString(obj, "role")?.trim().orEmpty().lowercase()
            val messageType = firstString(obj, "message_type")?.trim().orEmpty().lowercase()
            val content = firstString(obj, "content")?.trim().orEmpty()
            if (content.isBlank()) continue
            if (!role.contains("assistant") && !role.contains("clarification")) continue
            if (messageType == "task_log") continue

            val message = ChatMessage(
                id = "recent-$taskId-$messageType-$index-${content.hashCode()}",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_assistant),
                body = content,
                createdAt = firstString(obj, "created_at")?.trim().orEmpty().ifBlank { currentTimestampString() },
                eventType = messageType.ifBlank { null }
            )
            if (existingTimeline.any { it.sameContentAs(message) }) continue
            if (messages.any { it.sameContentAs(message) }) continue
            if (isRedundantAggregatedAssistantMessage(taskId, message)) continue
            if (isRedundantOperationalAssistantMessage(taskId, message)) continue
            messages += message
        }

        if (messages.isNotEmpty()) {
            return messages
        }

        val fallback = response.latest_assistant_message?.trim().orEmpty()
        if (fallback.isBlank()) return emptyList()
        val fallbackMessage = ChatMessage(
            id = "latest-assistant-$taskId-${response.latest_assistant_message_type.orEmpty()}-${fallback.hashCode()}",
            kind = MessageKind.ASSISTANT,
            title = getString(R.string.message_title_assistant),
            body = fallback,
            eventType = response.latest_assistant_message_type?.trim()?.lowercase()?.ifBlank { null },
            createdAt = currentTimestampString()
        )
        if (existingTimeline.any { it.sameContentAs(fallbackMessage) }) return emptyList()
        if (isRedundantAggregatedAssistantMessage(taskId, fallbackMessage)) return emptyList()
        if (isRedundantOperationalAssistantMessage(taskId, fallbackMessage)) return emptyList()
        return listOf(fallbackMessage)
    }

    private fun buildFallbackMessages(taskId: String, response: StatusResponse): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        resolveFullLogText(response)?.takeIf {
            messages.none { msg -> msg.kind == MessageKind.LOG && msg.detail == it }
        }?.let { log ->
            messages += ChatMessage(
                id = "fallback-log-$taskId-${response.status}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_log),
                body = statusSummaryText(response),
                detail = log
            )
        }

        if (messages.isEmpty()) {
            messages += ChatMessage(
                id = "fallback-empty-$taskId",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = response.status,
                detail = getString(R.string.status_no_detail)
            )
        }
        return messages
    }

    private fun statusSummaryText(response: StatusResponse): String {
        val attemptSuffix = response.build_attempts.takeIf { it > 0 }?.let { getString(R.string.status_attempts, it) }.orEmpty()
        val appSuffix = taskDisplayName(response.generated_app_name)
            ?: taskDisplayName(response.app_name)
            ?: ""
        return listOf(resolveStatusDisplayText(response.status, response.status_display_text.orEmpty(), response.progress_mode), appSuffix, attemptSuffix).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun buildTaskContentTitle(initialPrompt: String?, appName: String?, conversationState: JsonElement?): String? {
        val summary = conversationState
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { firstString(it, "latest_summary") }
        return listOf(
            summarizeTaskTitleCandidate(appName),
            summarizeTaskTitleCandidate(summary),
            summarizeTaskTitleCandidate(initialPrompt)
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun refreshTaskSummaryFromStatus(
        taskId: String,
        response: StatusResponse,
        resolvedAppName: String?,
        statusText: String,
        hasApk: Boolean
    ) {
        if (taskId in hiddenTaskIds) return
        val existing = taskSummaryById[taskId]
        val updatedTitle = buildTaskContentTitle(
            initialPrompt = response.conversation_state
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { firstString(it, "initial_user_prompt") }
                ?: existing?.title,
            appName = resolvedAppName,
            conversationState = response.conversation_state
        ) ?: existing?.title ?: getString(R.string.untitled_task)
        val updated = TaskSummary(
            taskId = taskId,
            title = updatedTitle,
            appName = resolvedAppName ?: existing?.appName,
            packageName = response.package_name?.ifBlank { null } ?: existing?.packageName,
            subtitle = (resolvedAppName ?: existing?.appName) ?: existing?.subtitle ?: currentTaskSummaryTimestampString(),
            status = statusText,
            updatedAt = taskSummaryLastBubbleTimestamp(taskId) ?: currentTaskSummaryTimestampString(),
            hasApk = hasApk || existing?.hasApk == true,
            hasRuntimeError = existing?.hasRuntimeError ?: (taskId in runtimeErrorTaskIds)
        )
        val updatedList = TaskSummaryListPolicy.upsert(screenState.taskList, updated)
        taskSummaryById = (taskSummaryById + (taskId to updated))
        screenState = screenState.copy(taskList = updatedList)
    }

    private fun ensureTaskSummaryVisible(
        taskId: String,
        title: String?,
        appName: String?,
        packageName: String?,
        status: String,
        hasApk: Boolean
    ) {
        if (taskId.isBlank() || taskId in hiddenTaskIds) return
        val existing = taskSummaryById[taskId]
        val summary = TaskSummary(
            taskId = taskId,
            title = title
                ?.let(::summarizeTaskTitleCandidate)
                ?: existing?.title
                ?: getString(R.string.untitled_task),
            appName = appName ?: existing?.appName,
            packageName = packageName ?: existing?.packageName,
            subtitle = appName ?: existing?.subtitle ?: currentTaskSummaryTimestampString(),
            status = status,
            updatedAt = taskSummaryLastBubbleTimestamp(taskId) ?: currentTaskSummaryTimestampString(),
            hasApk = hasApk || existing?.hasApk == true,
            hasRuntimeError = existing?.hasRuntimeError ?: (taskId in runtimeErrorTaskIds)
        )
        taskSummaryById = taskSummaryById + (taskId to summary)
        screenState = screenState.copy(
            taskList = TaskSummaryListPolicy.upsert(screenState.taskList, summary)
        )
    }

    private fun summarizeTaskTitleCandidate(rawValue: String?): String? {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isBlank() || isTransientTaskTitle(normalized)) return null
        val cleaned = normalized
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace(Regex("^[-*•]\\s*"), "")
            .replace(Regex("(만들어줘|생성해줘|개발해줘|구현해줘|빌드해줘|수정해줘|추가해줘|변경해줘)$"), "")
            .replace(Regex("(을|를) 만들게요$"), "")
            .replace(Regex("(을|를) 이렇게 수정할게요$"), "")
            .replace(Regex("^기존\\s+"), "")
            .trim()
            .trimEnd('.', '!', '?')
        if (cleaned.isBlank()) return null
        return if (cleaned.length > 32) "${cleaned.take(29).trimEnd()}..." else cleaned
    }

    private fun taskDisplayName(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank() || isTransientTaskTitle(value)) return null
        return value
    }

    private fun isTransientTaskTitle(value: String): Boolean {
        val normalized = value.trim()
        return normalized in setOf(
            "Pending Decision",
            "Clarification Needed",
            "Processing",
            "Reviewing",
            "Repairing",
            "Success",
            "Failed",
            "Error",
            "Rejected",
            "API 정보 확인 필요",
            "판단 실패",
            "추가 확인 필요",
            "대화 중",
            "확인 필요",
            "거절됨",
            "웹 페이지 읽기 실패",
            "검색 실패",
            "웹 데이터 파싱 실패",
            "외부 정보 품질 부족",
            "검색 해석 실패",
            "외부 데이터 계약 누락",
            "앱 설계 중...",
            getString(R.string.status_generating),
            "요청을 검토하고 있어요",
            "추가 정보가 필요해요",
            "앱을 생성하고 있어요",
            "피드백을 반영하고 있어요",
            "결과를 점검하고 있어요",
            "오류를 수정하고 있어요",
            "앱 생성에 실패했어요",
            "앱 생성이 완료되었어요",
            "요청을 처리할 수 없어요"
        )
    }

    private fun resolveApkUrl(taskId: String, response: StatusResponse, isSuccess: Boolean): String? {
        val apkUrl = response.apk_url.orEmpty()
        return when {
            apkUrl.isNotBlank() && apkUrl.startsWith("http") -> apkUrl
            apkUrl.isNotBlank() -> "${HostAppConfig.BASE_URL}$apkUrl"
            isSuccess && taskId.isNotBlank() -> "${HostAppConfig.BASE_URL}/download/$taskId"
            else -> null
        }
    }

    private fun isTaskCancellationAllowed(response: StatusResponse): Boolean {
        response.cancel_allowed?.let { return it }
        response.allowed_next_actions?.let { actions ->
            return actions.any { it.equals("cancel", ignoreCase = true) }
        }
        return TaskProgressTimelinePolicy.isActiveBuildStatus(response.status)
    }

    private fun shouldStartBuildWorkflow(response: BuildResponse): Boolean {
        return response.interaction_type?.trim()?.lowercase() == "build_started"
    }

    private fun isConfirmationRenderMode(response: BuildResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "confirmation_bubble"
    }

    private fun isPromptReviewRenderMode(response: BuildResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "prompt_review_bubble" ||
            response.confirmation_action?.trim()?.lowercase() == "submit_initial_prompt" ||
            response.interaction_type?.trim()?.lowercase() == "needs_initial_prompt_review"
    }

    private fun isAssistantRenderMode(response: BuildResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "assistant_message"
    }

    private fun shouldRenderDecisionSummary(response: BuildResponse): Boolean {
        if (isPromptReviewRenderMode(response)) return false
        if (isConfirmationRenderMode(response)) return false
        if (shouldSuppressDecisionAssistantMessage(response)) return false
        return response.tool == "answer_question" && isAssistantRenderMode(response)
    }

    private fun shouldSuppressDecisionAssistantMessage(response: BuildResponse): Boolean {
        if (response.suppress_assistant_bubble == true) return true
        val renderMode = response.render_mode?.trim()?.lowercase().orEmpty()
        if (renderMode == "status_only") return true
        val requestScope = response.request_scope?.trim()?.lowercase().orEmpty()
        val interactionType = response.interaction_type?.trim()?.lowercase().orEmpty()
        val looksLikeRevision = requestScope in setOf("existing_app", "existing_task", "revision", "modification") ||
            interactionType in setOf("build_started", "revision_started", "modification_started")
        return looksLikeRevision && isModificationBuildResponse(response)
    }

    private fun isConfirmationRenderMode(response: StatusResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "confirmation_bubble"
    }

    private fun isPromptReviewRenderMode(response: StatusResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "prompt_review_bubble" ||
            response.pending_decision_reason?.trim()?.lowercase() == "initial_prompt_review"
    }

    private fun reconcilePromptReviewHandling(
        taskId: String,
        response: StatusResponse,
        messages: List<ChatMessage>
    ) {
        val promptReviewMessages = messages.filter { message ->
            PromptReviewMessagePolicy.isPromptReview(message) &&
                (message.promptReviewTaskId ?: message.confirmTaskId) == taskId
        }
        if (promptReviewMessages.isEmpty()) return

        val awaitingPromptReview = isPromptReviewRenderMode(response)
        val preparedPrompt = response.prepared_prompt?.trim().orEmpty()
            .ifBlank {
                response.conversation_state
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { firstString(it, "prepared_prompt") }
                    ?.trim()
                    .orEmpty()
            }
        val activeMessageId = if (awaitingPromptReview) {
            promptReviewMessages.lastOrNull { message ->
                preparedPrompt.isBlank() || hasSameMessageText(message.promptReviewText.orEmpty(), preparedPrompt)
            }?.id
        } else {
            null
        }

        val changedMessageIds = mutableSetOf<String>()
        promptReviewMessages.forEach { message ->
            if (message.id == activeMessageId) {
                if (handledConfirmationMessageIds.remove(message.id)) {
                    changedMessageIds += message.id
                }
            } else {
                if (handledConfirmationMessageIds.add(message.id)) {
                    changedMessageIds += message.id
                }
            }
        }
        refreshConfirmationActions(changedMessageIds)
    }

    private fun refreshConfirmationActions(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        recyclerMessages.post {
            chatAdapter.refreshConfirmationActions(messageIds)
        }
    }

    private fun suppressAssistantBubble(response: StatusResponse): Boolean {
        return response.suppress_assistant_bubble == true ||
            response.render_mode?.trim()?.lowercase() == "status_only"
    }

    private fun statusValue(status: String): String {
        return status.trim().ifBlank { getString(R.string.status_unknown) }
    }

    private fun displayStatusText(status: String?): String {
        return displayStatusText(status, null)
    }

    private fun resolveStatusDisplayText(status: String?, serverStatusText: String?, progressMode: String?): String {
        return serverStatusText?.trim()?.takeIf { it.isNotBlank() }
            ?: displayStatusText(status, progressMode)
    }

    private fun displayStatusText(status: String?, progressMode: String?): String {
        val raw = status?.trim().orEmpty()
        return when (TaskStatusPolicy.normalize(raw)) {
            "pending decision" -> "요청을 검토하고 있어요"
            "clarification needed", "clarification required", "clarifying" -> "추가 정보가 필요해요"
            "queued", "processing", "building", "running", "in progress", "working" -> getString(R.string.status_generating)
            "reviewing" -> "결과를 점검하고 있어요"
            "repairing" -> "오류를 수정하고 있어요"
            "failed", "error" -> "앱 생성에 실패했어요"
            "ratelimited" -> "앱 생성 한도를 모두 사용했어요"
            "cancelled", "canceled" -> getString(R.string.status_cancelled)
            "success" -> "앱 생성이 완료되었어요"
            "rejected" -> "요청을 처리할 수 없어요"
            "not found" -> "작업을 찾을 수 없어요"
            "device mismatch" -> "기기 정보가 일치하지 않아요"
            "invalid state" -> "현재 상태에서는 이 요청을 처리할 수 없어요"
            else -> raw.ifBlank { getString(R.string.status_unknown) }
        }
    }

    private fun isModificationBuildResponse(response: BuildResponse): Boolean {
        val summary = response.summary?.trim().orEmpty()
        val message = response.message?.trim().orEmpty()
        return summary.contains("수정 방향은") ||
            summary.contains("이번 수정") ||
            summary.contains("이렇게 수정할게요") ||
            message.contains("이번 수정") ||
            message.contains("기존 앱 수정")
    }

    private fun buildWorkflowStartStatusText(response: BuildResponse): String {
        return getString(R.string.status_generating)
    }

    private fun buildWorkflowStartDetail(response: BuildResponse): String? {
        return null
    }

    private fun compactStatusLabel(status: String): String {
        return statusValue(status)
            .split('\n')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: getString(R.string.status_unknown)
    }

    private fun isCompactStatus(text: String): Boolean {
        val compact = compactStatusLabel(text)
        if (compact.length > 48 || compact.contains(":")) return false
        return TaskStatusPolicy.normalize(compact) in setOf(
            "success",
            "failed",
            "error",
            "rejected",
            "clarification needed",
            "clarification required",
            "clarifying",
            "pending decision",
            "processing",
            "queued",
            "building",
            "reviewing",
            "repairing",
            "running",
            "cancelled",
            "canceled",
            "in progress",
            "working"
        )
    }

    private fun resolveExactTaskIdCandidate(candidate: String, summaries: List<TaskSummary>): String? {
        val normalizedCandidate = candidate.trim()
        if (normalizedCandidate.isBlank()) return null
        return summaries.firstOrNull { it.taskId == normalizedCandidate }?.taskId
    }

    private fun resolveApiTaskId(candidate: String?, endpoint: String): String? {
        val normalizedCandidate = candidate?.trim().orEmpty()
        val selectedTaskId = screenState.selectedTaskId?.trim().orEmpty()
        val activeTaskId = currentTaskId?.trim().orEmpty()
        val resolvedTaskId = when {
            normalizedCandidate.isNotBlank() && taskSummaryById.containsKey(normalizedCandidate) -> normalizedCandidate
            normalizedCandidate.isNotBlank() && !matchesDisplayIdentifier(normalizedCandidate) -> normalizedCandidate
            normalizedCandidate.isNotBlank() && selectedTaskId == normalizedCandidate -> normalizedCandidate
            normalizedCandidate.isNotBlank() && activeTaskId == normalizedCandidate -> normalizedCandidate
            selectedTaskId.isNotBlank() && taskSummaryById.containsKey(selectedTaskId) -> selectedTaskId
            activeTaskId.isNotBlank() && taskSummaryById.containsKey(activeTaskId) -> activeTaskId
            else -> null
        }

        if (resolvedTaskId == null) {
            Log.w(
                TAG,
                "API task resolution failed endpoint=$endpoint candidate=${if (normalizedCandidate.isBlank()) "-" else normalizedCandidate} selected_task_id=${if (selectedTaskId.isBlank()) "-" else selectedTaskId} current_task_id=${if (activeTaskId.isBlank()) "-" else activeTaskId}"
            )
        }
        return resolvedTaskId
    }

    private fun matchesDisplayIdentifier(value: String): Boolean {
        return taskSummaryById.values.any { summary ->
            summary.appName == value || summary.title == value || summary.packageName == value
        }
    }

    private fun isCrashPlaceholderTaskId(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return true
        return normalized == "unknown" ||
            normalized == "task-unknown" ||
            normalized == "unknown_app" ||
            normalized == "unknown task"
    }

    private fun isCrashPlaceholderPackageName(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return true
        return normalized == "unknown" ||
            normalized == "unknown_app" ||
            normalized == "kr.ac.kangwon.hai.baseproject"
    }

    private fun resolveCrashTaskId(rawTaskId: String?, packageName: String?): String? {
        val normalizedRawTaskId = rawTaskId?.trim().orEmpty()
        val normalizedPackageName = packageName?.trim().orEmpty()
        val summaries = screenState.taskList.ifEmpty { taskSummaryById.values.toList() }
        val hasConcreteRawTaskId = !isCrashPlaceholderTaskId(normalizedRawTaskId)
        val hasConcretePackageName = !isCrashPlaceholderPackageName(normalizedPackageName)

        val resolvedTaskId = when {
            hasConcreteRawTaskId && taskSummaryById.containsKey(normalizedRawTaskId) -> normalizedRawTaskId
            hasConcretePackageName -> {
                summaries.firstOrNull { it.packageName == normalizedPackageName }?.taskId
                    ?: summaries.firstOrNull {
                        val packageLeaf = it.packageName?.substringAfterLast('.').orEmpty().lowercase()
                        packageLeaf.isNotBlank() && packageLeaf == normalizedRawTaskId.lowercase()
                    }?.taskId
            }
            hasConcreteRawTaskId && !matchesDisplayIdentifier(normalizedRawTaskId) -> normalizedRawTaskId
            else -> null
        } ?: summaries.firstOrNull { it.appName == normalizedRawTaskId }?.taskId
            ?: summaries.firstOrNull { it.title == normalizedRawTaskId }?.taskId

        Log.d(
            TAG,
            "Crash task resolution raw_task_id=${if (normalizedRawTaskId.isBlank()) "-" else normalizedRawTaskId} package_name=${if (normalizedPackageName.isBlank()) "-" else normalizedPackageName} resolved_task_id=${resolvedTaskId ?: "-"}"
        )
        return resolvedTaskId
    }

    private fun logTaskSelection(requestedTaskId: String, resolvedTaskId: String) {
        val summary = taskSummaryById[resolvedTaskId]
        Log.d(
            TAG,
            "Task selection requested_task_id=$requestedTaskId resolved_task_id=$resolvedTaskId summary_available=${summary != null}"
        )
    }

    private fun logStatusFetchTaskId(taskId: String, source: String) {
        Log.d(TAG, "Status fetch source=$source task_id=$taskId")
    }

    private fun logTaskIdForApi(endpoint: String, taskId: String) {
        Log.d(TAG, "API task_id endpoint=$endpoint task_id=$taskId")
    }

    private fun appendImageReferenceMessages(taskId: String, summary: String?, conflictNote: String?) {
        val trimmedSummary = summary?.trim().orEmpty()
        if (trimmedSummary.isNotBlank()) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "image-reference-summary-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = trimmedSummary
                )
            )
        }

        val trimmedConflict = conflictNote?.trim().orEmpty()
        if (trimmedConflict.isNotBlank()) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "image-reference-conflict-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = trimmedConflict
                )
            )
        }
    }

    private fun userVisibleErrorMessage(throwable: Throwable): String {
        val rootCause = generateSequence(throwable) { it.cause }.last()
        return when {
            throwable is HttpException && throwable.code() == 429 -> "앱 생성 한도를 모두 사용했어요. 잠시 후 다시 시도해 주세요."
            throwable is HttpException && throwable.code() == 409 -> "현재 상태에서는 이 요청을 바로 처리할 수 없어요."
            throwable is HttpException && throwable.code() in 500..599 -> "서버 처리 중 문제가 발생했어요. 잠시 후 다시 시도해 주세요."
            rootCause is SocketTimeoutException -> "응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요."
            rootCause is UnknownHostException || rootCause is ConnectException -> "네트워크 연결을 확인해 주세요."
            rootCause is IOException -> "네트워크 문제로 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
            else -> "잠시 후 다시 시도해 주세요."
        }
    }

    private fun handleRuntimeError(
        taskId: String,
        packageName: String,
        stackTrace: String,
        errorMessage: String? = null,
        reportKind: String? = null
    ) {
        if (taskId in hiddenTaskIds) return
        val compactStackTrace = RuntimeErrorStoragePolicy.compactStackTrace(stackTrace)
        val existing = pendingRuntimeErrors[taskId]
        if (existing?.stackTrace == compactStackTrace && existing.awaitingUserConfirmation) {
            return
        }

        runtimeErrorTaskIds += taskId
        pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
            packageName = packageName,
            stackTrace = compactStackTrace,
            summary = "",
            errorMessage = errorMessage?.trim()?.ifBlank { null },
            reportKind = reportKind?.trim()?.ifBlank { null },
            awaitingUserConfirmation = true,
            serverReported = existing?.serverReported == true
        )
        persistPendingRuntimeErrors()

        addTaskEvent(
            taskId = taskId,
            message = ChatMessage(
                id = "runtime-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_runtime),
                body = getString(R.string.runtime_error_body, packageName.ifBlank { "알 수 없는 앱" }),
                detail = buildRuntimeLogDetail(
                    errorMessage = errorMessage,
                    stackTrace = compactStackTrace
                )
            )
        )
        addTaskEvent(
            taskId = taskId,
            message = ChatMessage(
                id = "runtime-analyzing-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.runtime_error_analysis_pending)
            )
        )
        currentTaskId = taskId
        persistLastSelectedTaskId(taskId)
        reenterTaskConversation(taskId, scrollToTop = false, scrollToLatest = true)
        loadTaskList(autoSelectPendingTask = false)
        requestRuntimeErrorSummary(taskId, packageName, compactStackTrace, errorMessage, reportKind)
    }

    private fun requestRuntimeErrorSummary(
        taskId: String,
        packageName: String,
        stackTrace: String,
        errorMessage: String? = null,
        reportKind: String? = null
    ) {
        val analysis = RuntimeErrorAnalyzer.analyze(
            stackTrace = stackTrace,
            errorMessage = errorMessage,
            reportKind = reportKind
        )
        val summary = analysis.summary
        val updatedRecord = RuntimeErrorRecord(
            packageName = packageName,
            stackTrace = stackTrace,
            summary = summary,
            errorMessage = errorMessage?.trim()?.ifBlank { null },
            reportKind = reportKind?.trim()?.ifBlank { null },
            awaitingUserConfirmation = true,
            serverReported = pendingRuntimeErrors[taskId]?.serverReported == true
        )
        pendingRuntimeErrors[taskId] = updatedRecord
        persistPendingRuntimeErrors()
        appendOptimisticTaskMessage(
            taskId,
            ChatMessage(
                id = "runtime-assistant-local-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_assistant),
                body = if (analysis.friendlyExplanation.isBlank()) {
                    getString(R.string.runtime_error_detected_generic)
                } else {
                    getString(R.string.runtime_error_detected_explained, analysis.friendlyExplanation)
                },
                detail = buildString {
                    append("기술 오류: ")
                    append(summary.ifBlank { getString(R.string.runtime_error_original_missing) })
                    append("\n\n")
                    append("현재 서버에서는 자동 복구 API를 쓰지 않아요. 수정 요청을 보내면 새 작업으로 다시 생성할 수 있어요.")
                }
            )
        )
        addTaskEvent(
            taskId,
            ChatMessage(
                id = "runtime-confirm-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.CONFIRMATION,
                title = getString(R.string.confirmation_title),
                body = getString(R.string.confirmation_repair_preview),
                confirmAction = "repair_runtime",
                confirmTaskId = taskId,
                confirmPayload = summary
            )
        )
        if (!updatedRecord.serverReported) {
            reportRuntimeErrorToServer(taskId, updatedRecord)
        }
        renderState()
    }

    private fun buildRuntimeLogDetail(errorMessage: String?, stackTrace: String): String {
        val originalError = errorMessage?.trim()?.ifBlank { null }
            ?: RuntimeErrorAnalyzer.analyze(stackTrace = stackTrace).actualError
            ?: getString(R.string.runtime_error_original_missing)
        return getString(
            R.string.runtime_error_log_detail_template,
            originalError,
            stackTrace.take(1500)
        )
    }

    private fun persistPendingRuntimeErrors() {
        preferencesStore.savePendingRuntimeErrors(pendingRuntimeErrors)
    }

    private fun loadPersistedRuntimeErrors() {
        if (persistedRuntimeErrorsLoaded) return
        persistedRuntimeErrorsLoaded = true
        var shouldRewritePersistedRecords = false
        preferencesStore.loadPendingRuntimeErrors().forEach { (taskId, record) ->
            val resolvedTaskId = (resolveCrashTaskId(taskId, record.packageName) ?: taskId).trim()
            if (resolvedTaskId.isBlank() || resolvedTaskId in hiddenTaskIds) {
                shouldRewritePersistedRecords = true
                return@forEach
            }
            val restoredRecord = record.copy(
                packageName = record.packageName.trim().ifBlank { "알 수 없는 앱" },
                stackTrace = RuntimeErrorStoragePolicy.compactStackTrace(record.stackTrace),
                errorMessage = record.errorMessage?.trim()?.ifBlank { null },
                reportKind = record.reportKind?.trim()?.ifBlank { null }
            )
            pendingRuntimeErrors[resolvedTaskId] = restoredRecord
            runtimeErrorTaskIds += resolvedTaskId
            if (resolvedTaskId != taskId || restoredRecord != record) {
                shouldRewritePersistedRecords = true
            }
        }
        if (shouldRewritePersistedRecords) {
            persistPendingRuntimeErrors()
        }
    }

    private fun reconcilePersistedRuntimeErrors() {
        val snapshot = pendingRuntimeErrors.toMap()
        if (snapshot.isEmpty()) return

        lifecycleScope.launch {
            snapshot.forEach { (taskId, record) ->
                if (taskId in hiddenTaskIds) return@forEach
                try {
                    logTaskIdForApi("/status/{task_id}", taskId)
                    logApiRequest("/status/{task_id}", taskId = taskId, deviceId = deviceId, extra = "reconcile_runtime_error=true")
                    val status = fetchTaskStatus(taskId, includeTimeline = false)
                    if (TaskStatusPolicy.isSuccess(status.status) && !record.awaitingUserConfirmation) {
                        clearStaleRuntimeErrorState(taskId, removeTimeline = false)
                    }
                } catch (e: HttpException) {
                    if (e.code() == 404) {
                        clearStaleRuntimeErrorState(taskId, removeTimeline = true)
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    Log.w(TAG, "Runtime error reconciliation skipped task_id=$taskId", e)
                }
            }
        }
    }

    private fun clearStaleRuntimeErrorState(taskId: String, removeTimeline: Boolean) {
        val normalizedTaskId = taskId.trim()
        var changed = false
        if (pendingRuntimeErrors.remove(normalizedTaskId) != null) {
            changed = true
        }
        if (runtimeErrorTaskIds.remove(normalizedTaskId)) {
            changed = true
        }
        if (removeTimeline) {
            if (taskConversationMessages.remove(normalizedTaskId) != null) {
                changed = true
            }
            loadedTaskChatIds.remove(normalizedTaskId)
            taskTimelineRenderCache.remove(normalizedTaskId)
            taskTimelineEventCursorById.remove(normalizedTaskId)
            preferencesStore.deleteTaskChat(normalizedTaskId)
        }
        if (removeTimeline && screenState.selectedTaskId == normalizedTaskId) {
            currentTaskId = null
            screenState = screenState.copy(
                selectedTaskId = null,
                displayedAppName = null,
                messages = emptyList(),
                currentStatus = getString(R.string.status_new_chat),
                statusDetail = getString(R.string.status_new_chat_detail),
                canDownload = false,
                canInstall = false
            )
            persistLastSelectedTaskId(null)
            changed = true
        } else if (getLastSelectedTaskId() == normalizedTaskId && removeTimeline) {
            persistLastSelectedTaskId(null)
            changed = true
        }

        if (changed) {
            persistPendingRuntimeErrors()
            if (!removeTimeline) {
                persistTaskChat(normalizedTaskId)
            }
            renderState()
        }
    }

    private fun resolveFullLogText(response: StatusResponse): String? {
        return response.full_log?.takeIf { it.isNotBlank() }
            ?: response.log?.takeIf { it.isNotBlank() }
    }

    private fun resolveDisplayLogText(response: StatusResponse): String? {
        return resolveFullLogText(response)?.trim()?.takeIf { it.isNotBlank() }
            ?: response.latest_log?.trim()?.takeIf { it.isNotBlank() }
            ?: response.status_message?.trim()?.takeIf {
                it.isNotBlank() &&
                    (
                        TaskStatusPolicy.isRetryableFailure(response.status) ||
                            TaskStatusPolicy.isResponseError(response.status)
                    )
            }
    }

    private fun animatableProcessingLabels(): Set<String> {
        return setOf(
            getString(R.string.status_thinking),
            getString(R.string.status_generating)
        )
    }

    private fun processingAnimationBaseText(text: String): String? {
        val normalized = text.trim().trimEnd('.')
        return animatableProcessingLabels().firstOrNull { it == normalized }
    }

    private fun isProcessingAnimationActiveState(): Boolean {
        if (screenState.pollingTaskId?.isNotBlank() == true) return true
        if (screenState.currentStatus == getString(R.string.status_sending)) return true
        if (processingAnimationBaseText(screenState.currentStatus) != null) return true
        if (processingAnimationBaseText(screenState.statusDetail.orEmpty()) != null) return true
        return false
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonNull) continue
            if (value.isJsonPrimitive) {
                return value.asString
            }
        }
        return null
    }

    private fun firstLong(obj: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonNull) continue
            if (value.isJsonPrimitive) {
                value.asJsonPrimitive.takeIf { it.isNumber }?.asLong?.let { return it }
                value.asString.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun stringList(obj: JsonObject, key: String): List<String> {
        val value = obj.get(key) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() }
            }
            value.isJsonPrimitive -> listOfNotNull(value.asString.trim().takeIf { it.isNotBlank() })
            else -> emptyList()
        }
    }

    private fun addTaskEvent(taskId: String, message: ChatMessage) {
        appendTaskTimelineMessage(taskId, message, allowDuplicateContent = true)
        if (screenState.selectedTaskId == taskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(taskId))
            if (message.isLoading) {
                pendingResponseScrollTaskIds += taskId
            } else if (pendingResponseScrollTaskIds.remove(taskId) && message.kind != MessageKind.USER) {
                requestScrollLatestAfterResponse()
            }
            renderState()
        }
    }

    private fun appendOptimisticTaskMessage(taskId: String, message: ChatMessage, allowDuplicateContent: Boolean = false) {
        appendTaskTimelineMessage(taskId, message, allowDuplicateContent = allowDuplicateContent)
        if (screenState.selectedTaskId == taskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(taskId))
            if (message.kind == MessageKind.USER) {
                requestScrollLatestAfterResponse(force = true)
            } else if (message.isLoading) {
                if (message.isLoading) {
                    pendingResponseScrollTaskIds += taskId
                }
            } else if (pendingResponseScrollTaskIds.remove(taskId)) {
                requestScrollLatestAfterResponse()
            }
            renderState()
        }
    }

    private fun appendStatusTransitionMessage(taskId: String, response: StatusResponse) {
        if (!isCompactStatus(response.status)) return
        if (
            TaskStatusPolicy.normalize(response.status) == "pending decision" &&
            response.progress_mode.isNullOrBlank() &&
            !TaskStatusPolicy.isWebResearchInProgress(response)
        ) {
            return
        }

        val statusKey = buildStatusTransitionKey(response)
        val progressMode = response.progress_mode?.takeIf { it.isNotBlank() }
        if (taskLastStatusKeys[taskId] == statusKey) return
        taskLastStatusKeys[taskId] = statusKey
        appendTaskTimelineMessage(
            taskId,
            ChatMessage(
                id = "status-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = if (TaskStatusPolicy.isWebResearchInProgress(response)) {
                    getString(R.string.status_web_researching)
                } else {
                    resolveStatusDisplayText(response.status, response.status_display_text.orEmpty(), progressMode)
                },
                detail = null,
                createdAt = currentTimestampString()
            ),
            allowDuplicateContent = true
        )
    }

    private fun buildTaskTimeline(taskId: String): List<ChatMessage> {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || !ensureTaskChatLoaded(normalizedTaskId)) return emptyList()
        return taskTimelineRenderCache.getOrBuild(normalizedTaskId) {
            taskConversationMessages[normalizedTaskId]
                .orEmpty()
                .filter { shouldShowChatMessage(it) }
                .mapIndexed { index, message -> index to message }
                .sortedWith(
                    compareBy<Pair<Int, ChatMessage>> { (_, message) ->
                        parseMessageTimestamp(message.createdAt)?.time ?: Long.MAX_VALUE
                    }.thenBy { (index, _) -> index }
                )
                .map { (_, message) -> message }
                .let(TaskProgressTimelinePolicy::keepLatestDuplicateArtifacts)
                .filterVisibleDuplicateMessages()
                .map { message ->
                    TaskProgressTimelinePolicy.stripLoadingDetailWhenLogsHidden(
                        showLogs = false,
                        message = message,
                        isProcessingBody = { processingAnimationBaseText(it) != null }
                    )
                }
                .let(TaskProgressTimelinePolicy::moveLoadingMessagesToEnd)
                .let(::withDateSeparators)
        }
    }

    private fun withDateSeparators(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val result = mutableListOf<ChatMessage>()
        var lastDateKey: String? = null
        messages.forEach { message ->
            if (message.kind == MessageKind.DATE_SEPARATOR) return@forEach
            val messageDate = timestampFormatter.localDate(message.createdAt)
            val dateKey = messageDate?.toString()
            if (dateKey != null && dateKey != lastDateKey) {
                result += ChatMessage(
                    id = "date-separator-$dateKey-${result.size}",
                    kind = MessageKind.DATE_SEPARATOR,
                    title = null,
                    body = timestampFormatter.formatDateSeparator(messageDate),
                    createdAt = dateKey
                )
                lastDateKey = dateKey
            }
            result += message
        }
        return result
    }

    private fun shouldShowChatMessage(message: ChatMessage): Boolean {
        if (isPrebuildConfirmationHeader(message.body)) return false
        if (isHiddenOperationalBuildMessage(message.body)) return false
        val body = processingAnimationBaseText(message.body)
            ?: ChatTimelineVisibility.normalizeBody(message.body)

        if (message.kind == MessageKind.STATUS && TaskProgressTimelinePolicy.shouldAlwaysShowStatusMessage(message)) {
            return true
        }

        return ChatTimelineVisibility.shouldShowMainChatMessage(
            message = message,
            normalizedBody = body,
            visibleStatusBodies = visibleStatusBodies(),
            showProgressTimeline = false
        )
    }

    private fun shouldKeepChatTimelineMessage(message: ChatMessage): Boolean {
        if (isPrebuildConfirmationHeader(message.body)) return false
        if (isHiddenOperationalBuildMessage(message.body)) return false
        if (message.kind != MessageKind.STATUS) return true

        val body = processingAnimationBaseText(message.body)
            ?: ChatTimelineVisibility.normalizeBody(message.body)
        return ChatTimelineVisibility.shouldShowStatusMessage(
            message = message,
            normalizedBody = body,
            visibleStatusBodies = visibleStatusBodies(),
            showProgressTimeline = true
        )
    }

    private fun visibleStatusBodies(): Set<String> {
        return setOf(
            getString(R.string.status_thinking),
            getString(R.string.status_generating),
            getString(R.string.status_downloaded),
            getString(R.string.status_cancelled),
            getString(R.string.status_queued_input_added),
            getString(R.string.status_queued_input_start),
            getString(R.string.runtime_error_analysis_pending),
            getString(R.string.runtime_repair_in_progress),
            TaskProgressTimelinePolicy.INSTALLABLE_APK_READY_MESSAGE,
            displayStatusText("Success"),
            displayStatusText("Failed"),
            displayStatusText("Error"),
            displayStatusText("RateLimited")
        )
    }

    private fun List<ChatMessage>.filterVisibleDuplicateMessages(): List<ChatMessage> {
        val seen = mutableMapOf<String, ChatMessage>()
        return filter { message ->
            if (message.isLoading) {
                return@filter true
            }
            val key = visibleMessageDedupeKey(message) ?: return@filter true
            if (key.isBlank()) return@filter true
            val previous = seen[key]
            if (previous == null) {
                seen[key] = message
                return@filter true
            }
            if (message.kind == MessageKind.USER && !isLikelyUserPromptEcho(previous, message)) {
                seen[key] = message
                return@filter true
            }
            false
        }
    }

    private fun visibleMessageDedupeKey(message: ChatMessage): String? {
        if (message.isLoading) return null
        val artifactKey = TaskProgressTimelinePolicy.artifactDedupeKey(message)
        if (artifactKey != null) return artifactKey
        if (isCancelledCompletionStatusMessage(message)) return "status:cancelled-complete"
        val imagePreviews = message.allImagePreviews()
        val normalizedBody = normalizeMessageTextForDedupe(message.body)
        val canonicalBody = if (message.kind == MessageKind.USER) {
            AttachmentOnlyMessagePolicy.canonicalUserBody(
                normalizedBody = normalizedBody,
                hasImages = imagePreviews.isNotEmpty(),
                normalizedSyntheticPrompts = attachmentOnlyPromptKeys
            )
        } else {
            normalizedBody
        }
        val bodyKey = clarificationQuestionDedupeKey(message)
            ?: compactMessageTextForDedupe(canonicalBody)
        if (bodyKey.isBlank()) {
            return if (message.kind == MessageKind.USER && imagePreviews.isNotEmpty()) {
                "user-image:${AttachmentOnlyMessagePolicy.imageIdentity(imagePreviews)}"
            } else {
                null
            }
        }
        if (message.kind != MessageKind.USER) return bodyKey
        if (isLocalUserMessage(message) || isServerUserMessage(message)) {
            return "user-echo:$bodyKey"
        }
        val imageKey = imagePreviews
            .joinToString("|") { preview ->
                "${preview.displayName}:${UiRenderFingerprint.binaryPayload(preview.base64)}:${preview.remoteUrl.orEmpty()}"
            }
        return "user:$bodyKey:$imageKey"
    }

    private fun shouldDropIncomingDuplicateMessage(existing: ChatMessage, incoming: ChatMessage): Boolean {
        if (incoming.isLoading) return false
        if (PromptReviewMessagePolicy.areEquivalent(existing, incoming)) return true
        if (incoming.kind == MessageKind.USER) {
            return isLikelyUserPromptEcho(existing, incoming)
        }
        return isCancelledCompletionStatusMessage(existing) &&
            isCancelledCompletionStatusMessage(incoming)
    }

    private fun isLikelyUserPromptEcho(first: ChatMessage, second: ChatMessage): Boolean {
        if (first.kind != MessageKind.USER || second.kind != MessageKind.USER) return false
        if (!first.sameContentAs(second)) return false
        if (isLocalUserMessage(first) != isLocalUserMessage(second) && (isServerUserMessage(first) || isServerUserMessage(second))) {
            return true
        }
        val firstTime = parseMessageTimestamp(first.createdAt)?.time
        val secondTime = parseMessageTimestamp(second.createdAt)?.time
        if (firstTime == null || secondTime == null) return false
        return kotlin.math.abs(firstTime - secondTime) <= 120_000L
    }

    private fun isLocalUserMessage(message: ChatMessage): Boolean {
        return message.id.startsWith("local-") ||
            message.id.startsWith("followup-origin-") ||
            message.id.startsWith("chat-origin-") ||
            message.id.startsWith("queued-input-") ||
            message.id.startsWith("prompt-review-submit-")
    }

    private fun isServerUserMessage(message: ChatMessage): Boolean {
        return message.id.startsWith("timeline-") || message.id.startsWith("seed-user-")
    }

    private fun isCancelledCompletionStatusMessage(message: ChatMessage): Boolean {
        if (message.kind != MessageKind.STATUS) return false
        val body = compactMessageTextForDedupe(normalizeMessageTextForDedupe(message.body))
        if (!body.contains("중단") || body.contains("요청")) return false
        return body.contains("앱 생성") || body.contains("생성 작업")
    }

    private fun appendTaskTimelineMessage(taskId: String, message: ChatMessage, allowDuplicateContent: Boolean = false) {
        if (isPrebuildConfirmationHeader(message.body)) return
        if (isHiddenOperationalBuildMessage(message.body)) return
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val timeline = editableTaskTimeline(normalizedTaskId) ?: return
        if (message.id.startsWith("timeline-") && timeline.any { it.id == message.id }) return
        val duplicateIndex = timeline.indexOfFirst { shouldDropIncomingDuplicateMessage(it, message) }
        if (duplicateIndex >= 0) {
            val existing = timeline[duplicateIndex]
            val mergedUserEcho = when {
                isLocalUserMessage(existing) && isServerUserMessage(message) ->
                    AttachmentOnlyMessagePolicy.mergeLocalWithServerEcho(existing, message)
                isServerUserMessage(existing) && isLocalUserMessage(message) ->
                    AttachmentOnlyMessagePolicy.mergeLocalWithServerEcho(message, existing)
                else -> null
            }
            if (mergedUserEcho != null && mergedUserEcho != existing) {
                timeline[duplicateIndex] = mergedUserEcho
                persistTaskChat(normalizedTaskId)
            }
            return
        }
        if (!message.artifactTaskId.isNullOrBlank()) {
            val artifactKey = TaskProgressTimelinePolicy.artifactDedupeKey(message)
            val existingArtifactIndex = timeline.indexOfFirst { it.id == message.id }
                .takeIf { it >= 0 }
                ?: artifactKey?.let { key ->
                    timeline.indexOfLast { existing ->
                        TaskProgressTimelinePolicy.artifactDedupeKey(existing) == key
                    }.takeIf { it >= 0 }
                }
                ?: -1
            if (existingArtifactIndex >= 0) {
                timeline[existingArtifactIndex] = mergeApkArtifactMessages(
                    existing = timeline[existingArtifactIndex],
                    incoming = message
                ).withUniqueId(normalizedTaskId, existingArtifactIndex)
                persistTaskChat(normalizedTaskId)
                return
            }
        }
        if (message.isLoading && message.kind == MessageKind.STATUS) {
            TaskProgressTimelinePolicy.removeMatchingProgressMessages(
                timeline = timeline,
                message = message,
                progressKey = ::progressStatusDedupeKey
            )
            timeline += message.withUniqueId(normalizedTaskId, timeline.size)
            persistTaskChat(normalizedTaskId)
            return
        }
        if (mergeProgressStatusMessage(timeline, message)) {
            persistTaskChat(normalizedTaskId)
            return
        }
        if (!allowDuplicateContent && timelineContainsEquivalentClarificationMessage(timeline, message)) return
        if (timeline.lastOrNull()?.sameContentAs(message) == true) return
        val alreadyExists = timeline.any { it.sameContentAs(message) }
        if (!allowDuplicateContent && alreadyExists) return
        timeline += message.withUniqueId(normalizedTaskId, timeline.size)
        persistTaskChat(normalizedTaskId)
    }

    private fun trimTaskTimelineInMemory(taskId: String) {
        val normalizedTaskId = taskId.trim()
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        if (timeline.size <= MAX_IN_MEMORY_TASK_MESSAGES) return
        taskConversationMessages[normalizedTaskId] = timeline
            .takeLast(MAX_IN_MEMORY_TASK_MESSAGES)
            .toMutableList()
    }

    private fun timelineContainsEquivalentClarificationMessage(timeline: List<ChatMessage>, message: ChatMessage): Boolean {
        if (!isAssistantLikeMessage(message)) return false
        val incomingQuestionKeys = clarificationQuestionKeys(message.body, requireQuestionMark = true)
        if (incomingQuestionKeys.isEmpty()) return false
        return timeline.any { existing ->
            isAssistantLikeMessage(existing) &&
                clarificationQuestionKeys(existing.body, requireQuestionMark = false).containsAll(incomingQuestionKeys)
        }
    }

    private fun clarificationQuestionDedupeKey(message: ChatMessage): String? {
        if (!isAssistantLikeMessage(message)) return null
        val questionKeys = clarificationQuestionKeys(message.body, requireQuestionMark = true)
        if (questionKeys.isEmpty()) return null
        return "clarification:${questionKeys.joinToString("|")}"
    }

    private fun clarificationQuestionKeys(value: String?, requireQuestionMark: Boolean): Set<String> {
        return splitAggregatedAssistantBody(value.orEmpty())
            .asSequence()
            .map { compactMessageTextForDedupe(normalizeMessageTextForDedupe(it)) }
            .filter { it.isNotBlank() }
            .filterNot { isPrebuildConfirmationHeader(it) }
            .filterNot { isClarificationSummaryLine(it) }
            .filter { !requireQuestionMark || it.contains("?") }
            .toSet()
    }

    private fun isAssistantLikeMessage(message: ChatMessage): Boolean {
        return message.kind == MessageKind.ASSISTANT || message.kind == MessageKind.CONFIRMATION
    }

    private fun isClarificationSummaryLine(value: String): Boolean {
        val normalized = compactMessageTextForDedupe(normalizeMessageTextForDedupe(value))
        return normalized == "앱 목적은 파악됐지만, 바로 빌드하기엔 명세가 조금 더 필요해요." ||
            normalized == "수정 방향은 파악됐지만, 바로 반영하기엔 명세가 조금 더 필요해요."
    }

    private fun mergeProgressStatusMessage(timeline: MutableList<ChatMessage>, message: ChatMessage): Boolean {
        if (message.kind != MessageKind.STATUS) return false
        val incomingKey = progressStatusDedupeKey(message.body) ?: return false
        val matchingIndices = timeline.mapIndexedNotNull { index, existing ->
            index.takeIf {
                existing.kind == MessageKind.STATUS &&
                    progressStatusDedupeKey(existing.body) == incomingKey
            }
        }
        val existingIndex = matchingIndices.lastOrNull()
            ?: timeline.indexOfLast { it.kind == MessageKind.STATUS }
        if (existingIndex < 0) return false
        val existing = timeline[existingIndex]
        if (progressStatusDedupeKey(existing.body) != incomingKey) return false
        timeline[existingIndex] = existing.copy(
            body = processingAnimationBaseText(existing.body) ?: processingAnimationBaseText(message.body) ?: message.body,
            detail = message.detail ?: existing.detail,
            createdAt = message.createdAt ?: existing.createdAt,
            isLoading = message.isLoading || existing.isLoading
        )
        matchingIndices
            .dropLast(1)
            .asReversed()
            .forEach { index -> timeline.removeAt(index) }
        return true
    }

    private fun progressStatusDedupeKey(body: String): String? {
        val baseText = processingAnimationBaseText(body) ?: body.trim().trimEnd('.')
        val mergeableLabels = animatableProcessingLabels() + setOf(
            getString(R.string.runtime_error_analysis_pending).trim().trimEnd('.'),
            getString(R.string.runtime_repair_in_progress).trim().trimEnd('.')
        )
        return mergeableLabels.firstOrNull { it == baseText }
            ?.let { compactMessageTextForDedupe(normalizeMessageTextForDedupe(it)) }
    }

    private fun isRedundantAggregatedAssistantMessage(taskId: String, message: ChatMessage): Boolean {
        if (message.kind != MessageKind.ASSISTANT) return false
        val parts = splitAggregatedAssistantBody(message.body)
        if (parts.size < 2) return false
        val existingAssistantBodies = buildTaskTimeline(taskId)
            .filter { it.kind == MessageKind.ASSISTANT || it.kind == MessageKind.CONFIRMATION }
            .map { compactMessageTextForDedupe(normalizeMessageTextForDedupe(it.body)) }
            .filter { it.isNotBlank() }
            .toSet()
        if (existingAssistantBodies.isEmpty()) return false
        return parts.all { part ->
            compactMessageTextForDedupe(normalizeMessageTextForDedupe(part)) in existingAssistantBodies
        }
    }

    private fun splitAggregatedAssistantBody(body: String): List<String> {
        return body
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^[-*•]\\s+"), "")
                    .replace(Regex("^\\d+[.)]\\s+"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun isRedundantOperationalAssistantMessage(taskId: String, message: ChatMessage): Boolean {
        if (message.kind != MessageKind.ASSISTANT) return false
        val body = compactMessageTextForDedupe(normalizeMessageTextForDedupe(message.body))
        if (body.isBlank()) return false
        val normalizedTaskId = taskId.trim()
        ensureTaskChatLoaded(normalizedTaskId)
        val timeline = taskConversationMessages[normalizedTaskId].orEmpty()
        val hasConfirmation = timeline.any { it.kind == MessageKind.CONFIRMATION }
        val statusBodies = timeline
            .filter { it.kind == MessageKind.STATUS }
            .map { compactMessageTextForDedupe(normalizeMessageTextForDedupe(it.body)) }
        return when {
            hasConfirmation && body in operationalConfirmationAssistantBodies() -> true
            body == compactMessageTextForDedupe(normalizeMessageTextForDedupe("기존 작업 기준으로 복구 재시도를 시작합니다.")) ->
                statusBodies.any { it == compactMessageTextForDedupe(normalizeMessageTextForDedupe(getString(R.string.status_retrying_progress))) }
            body == compactMessageTextForDedupe(normalizeMessageTextForDedupe("감지된 런타임 오류를 기준으로 복구 빌드를 시작합니다.")) ->
                statusBodies.any {
                    it == compactMessageTextForDedupe(normalizeMessageTextForDedupe(getString(R.string.status_retrying_progress))) ||
                        it == compactMessageTextForDedupe(normalizeMessageTextForDedupe(getString(R.string.runtime_repair_in_progress)))
                }
            else -> false
        }
    }

    private fun operationalConfirmationAssistantBodies(): Set<String> {
        return setOf(
            getString(R.string.confirmation_refine_preview),
            getString(R.string.confirmation_retry_preview),
            getString(R.string.confirmation_repair_preview),
            getString(R.string.confirmation_continue_preview),
            getString(R.string.confirmation_generate_preview),
        ).map {
            compactMessageTextForDedupe(normalizeMessageTextForDedupe(it))
        }.toSet()
    }
    private fun reenterTaskConversation(
        taskId: String,
        scrollToTop: Boolean = true,
        scrollToLatest: Boolean = false
    ) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        if (scrollToTop) {
            pendingInitialChatScrollTaskId = normalizedTaskId
            chatAutoScrollLockedByUser = true
            chatShouldStickToBottom = false
        } else if (pendingInitialChatScrollTaskId == normalizedTaskId) {
            pendingInitialChatScrollTaskId = null
        }
        if (scrollToLatest) {
            requestScrollLatestAfterResponse(force = true)
        }
        screenState = screenState.copy(
            selectedTaskId = normalizedTaskId,
            displayedAppName = taskSummaryById[normalizedTaskId]?.appName,
            messages = buildTaskTimeline(normalizedTaskId),
            canDownload = persistedApkUrlForTask(normalizedTaskId) != null,
            canInstall = persistedDownloadedApkFileForTask(normalizedTaskId) != null
        )
        renderState()
    }

    private fun buildStatusTransitionKey(response: StatusResponse): String {
        return listOf(
            TaskStatusPolicy.normalize(response.status),
            if (TaskStatusPolicy.isWebResearchInProgress(response)) "web_research" else "",
            response.package_name,
            response.apk_url,
            response.apk_path,
            response.apk_size_bytes?.toString().orEmpty(),
            response.build_success.toString(),
            response.build_attempts.toString()
        ).joinToString("|")
    }

    private fun ChatMessage.sameContentAs(other: ChatMessage): Boolean {
        return ChatMessageTextPolicy.areSameContent(this, other, attachmentOnlyPromptKeys)
    }

    private fun hasSameMessageText(left: String?, right: String?): Boolean {
        return ChatMessageTextPolicy.sameText(left, right)
    }

    private fun normalizeMessageTextForDedupe(value: String?): String {
        return ChatMessageTextPolicy.normalize(value)
    }

    private fun compactMessageTextForDedupe(value: String): String {
        return ChatMessageTextPolicy.compact(value)
    }

    private fun isPrebuildConfirmationHeader(value: String?): Boolean {
        return ChatMessageTextPolicy.isPrebuildConfirmationHeader(value)
    }

    private fun isHiddenOperationalBuildMessage(value: String?): Boolean {
        return ChatMessageTextPolicy.isHiddenOperationalBuildMessage(value)
    }

    private fun currentTimestampString(): String {
        return timestampFormatter.nowServerTimestamp()
    }

    private fun currentTaskSummaryTimestampString(): String {
        return timestampFormatter.nowSummaryTimestamp()
    }

    private fun advanceTaskSelectionGeneration(): Long {
        taskSelectionGeneration += 1L
        return taskSelectionGeneration
    }

    private fun isTaskSelectionGenerationCurrent(generation: Long): Boolean {
        return taskSelectionGeneration == generation
    }

    private fun formatMessageTimestamp(value: String?): String? {
        return timestampFormatter.formatDisplay(value)
    }

    private fun formatTaskSummaryTimestamp(value: String?): String? {
        return timestampFormatter.formatDisplay(value)
    }

    private fun taskSummaryLastBubbleTimestamp(taskId: String): String? {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank() || normalizedTaskId in hiddenTaskIds) return null
        return buildTaskTimeline(normalizedTaskId)
            .asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.kind == MessageKind.DATE_SEPARATOR) return@firstNotNullOfOrNull null
                timestampFormatter.formatDisplay(message.createdAt)
            }
    }

    private fun formatMessageTimestampForBubble(value: String?): String? {
        return timestampFormatter.formatBubble(value)
    }

    private fun parseMessageTimestamp(value: String?): Date? {
        return timestampFormatter.parseDate(value)
    }

    private fun ChatMessage.withUniqueId(taskId: String, position: Int): ChatMessage {
        if (kind == MessageKind.USER || isLoading || !artifactTaskId.isNullOrBlank()) {
            return copy(createdAt = createdAt ?: currentTimestampString())
        }
        return copy(
            id = "$taskId-$position-${kind.name.lowercase()}-${body.hashCode()}-${detail.hashCode()}",
            createdAt = createdAt ?: currentTimestampString()
        )
    }

    private fun showThinkingMessage() {
        showLocalSystemMessage(
            title = getString(R.string.message_title_status),
            body = getString(R.string.status_thinking),
            detail = null,
            kind = MessageKind.STATUS,
            isLoading = true
        )
    }

    private fun showLocalSystemMessage(
        title: String,
        body: String,
        detail: String? = null,
        kind: MessageKind = MessageKind.STATUS,
        isLoading: Boolean = false
    ) {
        val message = ChatMessage(
            id = "local-system-${System.currentTimeMillis()}",
            kind = kind,
            title = title,
            body = body,
            detail = detail,
            createdAt = currentTimestampString(),
            isLoading = isLoading
        )
        val taskId = currentTaskId?.takeIf { it.isNotBlank() }
            ?: screenState.selectedTaskId?.takeIf { it.isNotBlank() }
        if (!taskId.isNullOrBlank()) {
            addTaskEvent(taskId, message.copy(id = "local-system-$taskId-${System.currentTimeMillis()}"))
            return
        }
        screenState = screenState.copy(messages = screenState.messages + message)
        if (isLoading) {
            requestScrollLatestAfterResponse(force = true)
        }
        renderState()
    }

    private fun removeLoadingMessages(taskId: String?) {
        val normalizedTaskId = taskId?.trim().orEmpty()
        if (normalizedTaskId.isBlank()) {
            val nextMessages = screenState.messages.filterNot { it.isLoading }
            if (nextMessages.size != screenState.messages.size) {
                screenState = screenState.copy(messages = nextMessages)
                renderState()
            }
            return
        }

        ensureTaskChatLoaded(normalizedTaskId)
        val timeline = taskConversationMessages[normalizedTaskId] ?: return
        val nextTimeline = timeline.filterNot { it.isLoading }
        if (nextTimeline.size == timeline.size) return
        taskConversationMessages[normalizedTaskId] = nextTimeline.toMutableList()
        persistTaskChat(normalizedTaskId)
        if (screenState.selectedTaskId == normalizedTaskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(normalizedTaskId))
            renderState()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun scrollChatToAnchorAfterLayout(anchorMessageId: String, clearAfterScroll: Boolean) {
        recyclerMessages.post {
            scrollChatToAnchor(anchorMessageId)
            recyclerMessages.post {
                val restored = scrollChatToAnchor(anchorMessageId)
                if (restored && clearAfterScroll && pendingChatAnchorMessageId == anchorMessageId) {
                    pendingChatAnchorMessageId = null
                    pendingChatAnchorTopOffset = null
                    clearPendingChatAnchorAfterScroll = false
                }
            }
        }
    }

    private fun scrollChatToAnchor(anchorMessageId: String): Boolean {
        val anchorIndex = chatAdapter.currentList.indexOfFirst { it.id == anchorMessageId }
        if (anchorIndex < 0) return false
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager ?: return false
        val topOffset = pendingChatAnchorTopOffset
            ?.takeIf { pendingChatAnchorMessageId == anchorMessageId }
            ?: (recyclerMessages.height * 0.12f).toInt().coerceAtLeast(dp(12))
        layoutManager.scrollToPositionWithOffset(anchorIndex, topOffset)
        return true
    }

    private fun scrollLatestMessageAfterLayout() {
        unlockChatAutoScroll()
        recyclerMessages.post {
            scrollLatestMessage()
            recyclerMessages.post {
                scrollLatestMessage()
            }
        }
    }

    private fun scrollLatestMessage() {
        val lastIndex = chatAdapter.itemCount - 1
        if (lastIndex < 0) return
        unlockChatAutoScroll()
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager
        val lastChild = layoutManager?.findViewByPosition(lastIndex)
        if (lastChild == null) {
            recyclerMessages.scrollToPosition(lastIndex)
            return
        }
        val bottomOffset = recyclerMessages.height - recyclerMessages.paddingBottom - lastChild.height
        layoutManager.scrollToPositionWithOffset(lastIndex, bottomOffset)
    }

    private fun captureChatScrollSnapshot(): ChatScrollSnapshot? {
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager ?: return null
        val viewportTop = recyclerMessages.paddingTop
        val viewportBottom = recyclerMessages.height - recyclerMessages.paddingBottom
        val viewportCenter = viewportTop + ((viewportBottom - viewportTop) / 2)
        var fallback: Pair<View, ChatMessage>? = null
        var best: Pair<View, ChatMessage>? = null
        var bestDistance = Int.MAX_VALUE
        for (index in 0 until recyclerMessages.childCount) {
            val child = recyclerMessages.getChildAt(index) ?: continue
            val adapterPosition = recyclerMessages.getChildAdapterPosition(child)
                .takeIf { it != RecyclerView.NO_POSITION }
                ?: continue
            val message = chatAdapter.currentList.getOrNull(adapterPosition) ?: continue
            if (fallback == null) {
                fallback = child to message
            }
            if (!isStableChatScrollAnchor(message)) continue
            val childCenter = child.top + (child.height / 2)
            val distance = kotlin.math.abs(childCenter - viewportCenter)
            if (distance < bestDistance) {
                best = child to message
                bestDistance = distance
            }
        }
        val (view, message) = best ?: fallback ?: return null
        return ChatScrollSnapshot(
            messageId = message.id,
            topOffset = view.top
        )
    }

    private fun isStableChatScrollAnchor(message: ChatMessage): Boolean {
        return message.kind != MessageKind.DATE_SEPARATOR &&
            !message.isLoading &&
            !message.artifactDownloading &&
            message.artifactTaskId.isNullOrBlank()
    }

    private fun updateManualChatScrollState() {
        if (isChatNearBottomByPixels(dp(2))) {
            unlockChatAutoScroll()
        } else {
            lockChatAutoScrollFromUser()
        }
    }

    private fun lockChatAutoScrollFromUser() {
        chatAutoScrollLockedByUser = true
        chatShouldStickToBottom = false
        pendingScrollLatestAfterResponse = false
        manualChatScrollSnapshot = captureChatScrollSnapshot() ?: manualChatScrollSnapshot
    }

    private fun unlockChatAutoScroll() {
        chatAutoScrollLockedByUser = false
        chatShouldStickToBottom = true
        manualChatScrollSnapshot = null
    }

    private fun isChatScrollInteractionActive(): Boolean {
        return chatScrollStartedByUser || recyclerMessages.scrollState != RecyclerView.SCROLL_STATE_IDLE
    }

    private fun restoreChatScrollSnapshotAfterLayout(snapshot: ChatScrollSnapshot) {
        if (isChatScrollInteractionActive()) return
        recyclerMessages.post {
            if (isChatScrollInteractionActive()) return@post
            restoreChatScrollSnapshot(snapshot)
            recyclerMessages.post {
                if (isChatScrollInteractionActive()) return@post
                restoreChatScrollSnapshot(snapshot)
            }
        }
    }

    private fun restoreChatScrollSnapshot(snapshot: ChatScrollSnapshot) {
        if (isChatScrollInteractionActive()) return
        val index = chatAdapter.currentList.indexOfFirst { it.id == snapshot.messageId }
        if (index < 0) return
        val layoutManager = recyclerMessages.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(index, snapshot.topOffset)
    }

    private fun captureChatBottomScrollSnapshot(): ChatBottomScrollSnapshot? {
        if (chatAdapter.itemCount <= 0) return null
        return ChatBottomScrollSnapshot(bottomOffset = chatBottomOffsetPx())
    }

    private fun restoreChatBottomScrollSnapshotAfterLayout(snapshot: ChatBottomScrollSnapshot) {
        if (isChatScrollInteractionActive()) return
        unlockChatAutoScroll()
        recyclerMessages.post {
            if (isChatScrollInteractionActive()) return@post
            restoreChatBottomScrollSnapshot(snapshot)
            recyclerMessages.post {
                if (isChatScrollInteractionActive()) return@post
                restoreChatBottomScrollSnapshot(snapshot)
            }
        }
    }

    private fun restoreChatBottomScrollSnapshot(snapshot: ChatBottomScrollSnapshot) {
        if (isChatScrollInteractionActive()) return
        val range = recyclerMessages.computeVerticalScrollRange()
        val extent = recyclerMessages.computeVerticalScrollExtent()
        val currentOffset = recyclerMessages.computeVerticalScrollOffset()
        if (range <= 0 || extent <= 0) return
        val targetOffset = (range - extent - snapshot.bottomOffset).coerceAtLeast(0)
        val delta = targetOffset - currentOffset
        if (delta != 0) {
            recyclerMessages.scrollBy(0, delta)
        }
    }

    private fun hasPendingRestoredChatScroll(taskId: String): Boolean {
        return pendingRestoredChatScrollTaskId == taskId &&
            pendingRestoredChatScrollSnapshot != null
    }

    private fun activateRestoredChatScrollIfReady(
        taskId: String?,
        visibleMessages: List<ChatMessage>
    ) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (!hasPendingRestoredChatScroll(normalizedTaskId)) return
        val snapshot = pendingRestoredChatScrollSnapshot ?: return
        if (visibleMessages.none { it.id == snapshot.messageId }) return

        pendingRestoredChatScrollTaskId = null
        pendingRestoredChatScrollSnapshot = null
        pendingInitialChatScrollTaskId = null
        pendingScrollLatestAfterResponse = false
        pendingChatAnchorMessageId = snapshot.messageId
        pendingChatAnchorTopOffset = snapshot.topOffset
        clearPendingChatAnchorAfterScroll = true
        chatAutoScrollLockedByUser = true
        chatShouldStickToBottom = false
        manualChatScrollSnapshot = snapshot
    }

    private fun requestScrollLatestAfterResponse(force: Boolean = false) {
        clearInitialScrollForActiveTask()
        if (!force && (chatAutoScrollLockedByUser || !chatShouldStickToBottom)) {
            pendingScrollLatestAfterResponse = false
            return
        }
        unlockChatAutoScroll()
        pendingScrollLatestAfterResponse = true
    }

    private fun clearInitialScrollForActiveTask() {
        val activeTaskId = screenState.selectedTaskId?.trim()?.takeIf { it.isNotBlank() }
            ?: currentTaskId?.trim()?.takeIf { it.isNotBlank() }
        if (!activeTaskId.isNullOrBlank() && pendingInitialChatScrollTaskId == activeTaskId) {
            pendingInitialChatScrollTaskId = null
        }
    }

    private fun logApiRequest(endpoint: String, taskId: String? = null, deviceId: String, extra: String? = null) {
        val suffix = extra?.let { " $it" }.orEmpty()
        Log.d(TAG, "API request endpoint=$endpoint task_id=${taskId ?: "-"} identity_ready=${deviceId.isNotBlank()}$suffix")
    }

    private fun logApiFailure(endpoint: String, taskId: String? = null, deviceId: String, throwable: Throwable) {
        if (throwable is HttpException) {
            val rawBody = try {
                throwable.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            Log.e(
                TAG,
                "API failure endpoint=$endpoint task_id=${taskId ?: "-"} identity_ready=${deviceId.isNotBlank()} http=${throwable.code()} body_length=${rawBody?.length ?: 0}",
                throwable
            )
        } else {
            Log.e(
                TAG,
                "API failure endpoint=$endpoint task_id=${taskId ?: "-"} identity_ready=${deviceId.isNotBlank()} error_type=${throwable::class.java.simpleName}",
                throwable
            )
        }
    }

    override fun onDestroy() {
        stopPolling()
        runCatching { unregisterReceiver(crashReceiver) }
        runCatching { unregisterReceiver(packageInstallReceiver) }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selectedTaskId = visibleTaskIdCandidate(screenState.selectedTaskId ?: currentTaskId)
        outState.putString(STATE_SELECTED_TASK_ID, selectedTaskId)
        outState.putString(STATE_INPUT_PROMPT, inputPrompt.text?.toString().orEmpty())
        val persistedAttachments = selectedAttachments.mapNotNull(composerDraftAttachmentStore::describe)
        if (persistedAttachments.isNotEmpty()) {
            outState.putString(STATE_COMPOSER_ATTACHMENTS, gson.toJson(persistedAttachments))
        }
        if (!selectedTaskId.isNullOrBlank() && !isChatNearBottomByPixels()) {
            val snapshot = captureChatScrollSnapshot() ?: manualChatScrollSnapshot
            if (snapshot != null) {
                outState.putString(STATE_CHAT_SCROLL_MESSAGE_ID, snapshot.messageId)
                outState.putInt(STATE_CHAT_SCROLL_TOP_OFFSET, snapshot.topOffset)
            }
        }
    }

    private val messageSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            isMessageTextSelectionActive = true
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode?) {
            recyclerMessages.post {
                isMessageTextSelectionActive = false
                renderStateWhenRecyclerMessagesSettled()
            }
        }
    }

    private fun submitChatMessagesWhenSafe(
        visibleMessages: List<ChatMessage>,
        anchorMessageId: String? = null,
        clearAnchorAfterScroll: Boolean = false,
        scrollLatestAfterResponse: Boolean = false,
        preserveVisiblePosition: Boolean = false,
        preserveScrollSnapshot: ChatScrollSnapshot? = null,
        preserveBottomPosition: Boolean = false
    ) {
        when (chooseChatMessageRefreshAction(isMessageTextSelectionActive, recyclerMessages.isComputingLayout)) {
            ChatMessageRefreshAction.SKIP_FOR_TEXT_SELECTION -> return
            ChatMessageRefreshAction.DEFER_FOR_RECYCLER_LAYOUT -> {
                recyclerMessages.post {
                    submitChatMessagesWhenSafe(
                        visibleMessages,
                        anchorMessageId,
                        clearAnchorAfterScroll,
                        scrollLatestAfterResponse,
                        preserveVisiblePosition,
                        preserveScrollSnapshot,
                        preserveBottomPosition
                    )
                }
                return
            }
            ChatMessageRefreshAction.SUBMIT -> {
                val bottomSnapshot = if (
                    preserveBottomPosition &&
                    !scrollLatestAfterResponse &&
                    anchorMessageId.isNullOrBlank()
                ) {
                    captureChatBottomScrollSnapshot()
                } else {
                    null
                }
                val scrollSnapshot = if (
                    preserveVisiblePosition &&
                    bottomSnapshot == null &&
                    !scrollLatestAfterResponse &&
                    anchorMessageId.isNullOrBlank()
                ) {
                    preserveScrollSnapshot
                        ?.takeIf { snapshot -> visibleMessages.any { it.id == snapshot.messageId } }
                        ?: captureChatScrollSnapshot()
                } else {
                    null
                }
                chatAdapter.submitList(visibleMessages) {
                    if (isChatScrollInteractionActive()) {
                        return@submitList
                    }
                    if (scrollLatestAfterResponse) {
                        scrollLatestMessageAfterLayout()
                    } else if (!anchorMessageId.isNullOrBlank()) {
                        scrollChatToAnchorAfterLayout(anchorMessageId, clearAnchorAfterScroll)
                    } else if (bottomSnapshot != null) {
                        restoreChatBottomScrollSnapshotAfterLayout(bottomSnapshot)
                    } else if (scrollSnapshot != null) {
                        restoreChatScrollSnapshotAfterLayout(scrollSnapshot)
                    }
                }
            }
        }
    }

    private fun renderStateWhenRecyclerMessagesSettled() {
        if (recyclerMessages.isComputingLayout) {
            recyclerMessages.post { renderStateWhenRecyclerMessagesSettled() }
            return
        }
        renderState()
    }
}
