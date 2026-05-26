package kr.ac.kangwon.hai.vibefactory

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val TAG = "VibeFactoryHost"
        private const val STATE_SELECTED_TASK_ID = "selected_task_id"
        private const val STATE_INPUT_PROMPT = "input_prompt"
        private const val STATE_SHOW_LOGS = "show_logs"
        private const val PROCESSING_STATUS_ANIMATION_MS = 700L
        private const val REQUEST_PHONE_NUMBER_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val BUILD_NOTIFICATION_CHANNEL_ID = "build_complete"
        private const val MAX_ATTACHMENT_IMAGE_ORIGINAL_BYTES = 15 * 1024 * 1024
        private const val MAX_ATTACHMENT_IMAGE_PAYLOAD_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTACHMENT_PDF_BYTES = 10 * 1024 * 1024
        private const val MAX_ATTACHMENT_TEXT_BYTES = 2 * 1024 * 1024
    }

    private val gson: Gson = GsonBuilder().create()
    private val serverTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val displayTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val bubbleTimestampFormat = SimpleDateFormat("a h:mm", Locale.KOREA)

    private lateinit var apiService: VibeApiService
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainContent: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var chatCard: View
    private lateinit var recyclerTasks: RecyclerView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var inputPrompt: EditText
    private lateinit var inputPhoneGate: EditText
    private lateinit var btnAttachReferenceImage: Button
    private lateinit var selectedAttachmentChip: TextView
    private lateinit var btnSend: Button
    private lateinit var btnNewChat: Button
    private lateinit var btnOpenLibrary: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var btnSavePhoneGate: Button
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var composerBar: LinearLayout
    private lateinit var logPanel: LinearLayout
    private lateinit var logPanelScroll: ScrollView
    private lateinit var logPanelTitle: TextView
    private lateinit var logPanelBody: TextView
    private lateinit var btnLogScrollBottom: Button
    private lateinit var phoneGateOverlay: View
    private lateinit var phoneGateContent: View
    private lateinit var drawerContent: LinearLayout
    private lateinit var switchShowLogs: SwitchCompat
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
            onArtifactDownload = ::handleArtifactDownloadRequested,
            onArtifactInstall = ::handleArtifactInstallRequested
        )
    }

    private var screenState = ChatScreenState()
    private var currentTaskId: String? = null
    private var latestApkUrl: String? = null
    private var latestDownloadedApkFile: File? = null
    private var latestDownloadedTaskId: String? = null
    private var pollingJob: Job? = null
    private var processingStatusAnimationJob: Job? = null
    private var processingStatusAnimationFrame: Int = 0
    private var lastCrashTaskId: String? = null
    private var lastCrashPackage: String? = null
    private var lastStackTrace: String? = null
    private lateinit var deviceId: String
    private lateinit var userIdentity: UserIdentity
    private val runtimeErrorTaskIds = mutableSetOf<String>()
    private val pendingRuntimeErrors = mutableMapOf<String, RuntimeErrorRecord>()
    private val taskConversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val taskArtifactStates = mutableMapOf<String, PersistedArtifactState>()
    private val taskLastStatusKeys = mutableMapOf<String, String>()
    private val hiddenTaskIds = mutableSetOf<String>()
    private var taskSummaryById: Map<String, TaskSummary> = emptyMap()
    private var pendingTaskSelectionKey: String? = null
    private var showLogs: Boolean = false
    private var isDownloadingApk: Boolean = false
    private var skipNextResumeRestore: Boolean = false
    private var hasAttemptedPhonePermissionRequest: Boolean = false
    private var restoreTaskJob: Job? = null
    private var taskSyncJob: Job? = null
    private var taskSelectionGeneration: Long = 0L
    private val handledConfirmationMessageIds = mutableSetOf<String>()
    private var pendingInitialChatScrollTaskId: String? = null
    private val notifiedBuildSuccessTaskIds = mutableSetOf<String>()
    private var isMessageTextSelectionActive = false
    private var selectedAttachment: SelectedAttachment? = null
    private var pendingCameraImageUri: Uri? = null
    private val taskRawLogSections = mutableMapOf<String, List<LogSectionSnapshot>>()

    private data class TimelineEventSnapshot(
        val eventId: String,
        val createdAt: String,
        val kind: String,
        val title: String,
        val body: String,
        val detail: String
    )

    private data class LogSectionSnapshot(
        val title: String,
        val content: String
    )

    private val pickReferenceImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleAttachmentSelected(uri, SelectedAttachmentKind.IMAGE)
            }
        }

    private val captureImageLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved: Boolean ->
            val uri = pendingCameraImageUri
            pendingCameraImageUri = null
            if (saved && uri != null) {
                handleAttachmentSelected(uri, SelectedAttachmentKind.IMAGE)
            }
        }

    private val pickDocumentAttachmentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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

    private val crashReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "kr.ac.kangwon.hai.action.CRASH_REPORT") return

            val rawTaskId = intent.getStringExtra("task_id")
            lastCrashPackage = intent.getStringExtra("package_name")
            val errorMessage = intent.getStringExtra("error_message")
            val reportKind = intent.getStringExtra("report_kind")
            lastStackTrace = intent.getStringExtra("stack_trace")
            val pkg = lastCrashPackage.orEmpty()
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
        Log.d(TAG, "App start device_id=$deviceId")
        val requestedNotificationPermission = requestNotificationPermissionIfNeeded()
        if (!requestedNotificationPermission && !hasRequiredPhoneNumber()) {
            requestPhoneNumberPermissionIfNeeded()
        }
        setupListeners()
        applyWindowInsets()
        restoreUiState(savedInstanceState)
        loadHiddenTaskIds()
        loadNotifiedBuildSuccessTaskIds()
        loadPersistedTaskChats()
        loadPersistedArtifactStates()
        loadPersistedRuntimeErrors()
        reconcilePersistedRuntimeErrors()
        renderState()

        registerReceiver(crashReceiver, IntentFilter("kr.ac.kangwon.hai.action.CRASH_REPORT"), RECEIVER_EXPORTED)
        pendingTaskSelectionKey = savedInstanceState?.getString(STATE_SELECTED_TASK_ID)
            ?: intent?.getStringExtra(STATE_SELECTED_TASK_ID)
            ?: getLastSelectedTaskId()
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
        btnSend = findViewById(R.id.btnSend)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnOpenLibrary = findViewById(R.id.btnOpenLibrary)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnSavePhoneGate = findViewById(R.id.btnSavePhoneGate)
        btnOpenDrawer = findViewById(R.id.btnOpenDrawer)
        composerBar = findViewById(R.id.composerBar)
        logPanel = findViewById(R.id.logPanel)
        logPanelScroll = findViewById(R.id.logPanelScroll)
        logPanelTitle = findViewById(R.id.logPanelTitle)
        logPanelBody = findViewById(R.id.logPanelBody)
        btnLogScrollBottom = findViewById(R.id.btnLogScrollBottom)
        phoneGateOverlay = findViewById(R.id.phoneGateOverlay)
        phoneGateContent = findViewById(R.id.phoneGateContent)
        drawerContent = findViewById(R.id.drawerContent)
        switchShowLogs = findViewById(R.id.switchShowLogs)
        inputModeLabel = findViewById(R.id.inputModeLabel)
        emptyChatText = findViewById(R.id.emptyChatText)
        topBarBaseTopPadding = topBar.paddingTop
        mainContentBaseBottomPadding = mainContent.paddingBottom
        recyclerMessagesBaseBottomPadding = recyclerMessages.paddingBottom
        drawerContentBaseLeftPadding = drawerContent.paddingLeft
        drawerContentBaseRightPadding = drawerContent.paddingRight
        phoneGateContentBaseLeftPadding = phoneGateContent.paddingLeft
        phoneGateContentBaseRightPadding = phoneGateContent.paddingRight
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
        val darkModeEnabled = preferencesStore.loadDarkModeEnabled()
        AppThemeController.applyDarkModePreference(darkModeEnabled)
        if (AppThemeController.shouldRecreateForPreference(this, darkModeEnabled)) {
            recreate()
            return
        }
        loadPersistedArtifactStates()
        loadPersistedRuntimeErrors()
        if (!hasRequiredPhoneNumber() && hasPhoneNumberPermissionGranted()) {
            tryFillPhoneNumberFromSim()
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
        if (requestedTaskId.isNotBlank()) {
            pendingTaskSelectionKey = requestedTaskId
            if (hasRequiredPhoneNumber()) {
                restoreCurrentTaskState(trigger = "onNewIntent")
            }
        }
    }

    override fun onStop() {
        persistTaskChats()
        super.onStop()
    }

    private fun restoreCurrentTaskState(trigger: String) {
        if (!hasRequiredPhoneNumber()) return
        val fallbackPersistedTaskId = taskConversationMessages.keys.firstOrNull { it.isNotBlank() && !hiddenTaskIds.contains(it) }
        val taskId = currentTaskId?.takeIf { it.isNotBlank() }
            ?: screenState.selectedTaskId?.takeIf { it.isNotBlank() }
            ?: pendingTaskSelectionKey?.takeIf { it.isNotBlank() }
            ?: getLastSelectedTaskId()?.takeIf { it.isNotBlank() }
            ?: fallbackPersistedTaskId
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
                showPersistedTaskPreview(taskId)
                fetchTaskList(autoSelectPendingTask = false)
                pendingTaskSelectionKey = null
                syncTaskStatus(
                    taskId,
                    autoInstallOnSuccess = false,
                    source = trigger,
                    staleFallback = true,
                    closeDrawerOnSuccess = false,
                    selectionGeneration = selectionGeneration
                )
            } catch (e: Exception) {
                logApiFailure("/status/{task_id}", taskId = taskId, deviceId = deviceId, throwable = e)
            }
        }
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
        recyclerMessages.adapter = chatAdapter
    }

    private fun setupNetwork() {
        apiService = createVibeApiService(gson = gson)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            BUILD_NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_builds),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
        return true
    }

    private fun setupListeners() {
        inputPhoneGate.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                isFormatting = true

                val digits = s.toString().filter { it.isDigit() }.take(11)
                val formatted = formatKoreanPhoneNumber(digits)

                if (formatted != s.toString()) {
                    s.replace(0, s.length, formatted)
                }

                isFormatting = false
            }

            private fun formatKoreanPhoneNumber(digits: String): String {
                return when {
                    digits.startsWith("02") -> {
                        when {
                            digits.length <= 2 -> digits
                            digits.length <= 5 -> "${digits.substring(0, 2)}-${digits.substring(2)}"
                            digits.length <= 9 -> "${digits.substring(0, 2)}-${digits.substring(2, digits.length - 4)}-${digits.takeLast(4)}"
                            else -> "${digits.substring(0, 2)}-${digits.substring(2, 6)}-${digits.substring(6, 10)}"
                        }
                    }
                    else -> {
                        when {
                            digits.length <= 3 -> digits
                            digits.length <= 7 -> "${digits.substring(0, 3)}-${digits.substring(3)}"
                            else -> "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
                        }
                    }
                }
            }
        })

        btnOpenDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnNewChat.setOnClickListener {
            resetForNewChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        btnOpenSettings.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val openLibrary = View.OnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        btnOpenLibrary.setOnClickListener(openLibrary)
        findViewById<LinearLayout>(R.id.drawerLibraryRow).setOnClickListener(openLibrary)

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

        inputPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (showLogs) {
                    showLogs = false
                    switchShowLogs.isChecked = false
                }
            }
        }

        switchShowLogs.setOnCheckedChangeListener { _, isChecked ->
            showLogs = isChecked
            renderState()
        }

        logPanelScroll.isVerticalScrollBarEnabled = true
        logPanelScroll.isScrollbarFadingEnabled = false
        btnLogScrollBottom.setOnClickListener {
            scrollLogPanelToBottom()
        }
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
            logApiFailure("/tasks", deviceId = deviceId, throwable = e)
            showLocalSystemMessage(
                getString(R.string.message_title_status),
                getString(R.string.tasks_load_failed, userVisibleErrorMessage(e)),
                kind = MessageKind.LOG
            )
        }
    }

    private fun resetForNewChat() {
        stopPolling()
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        advanceTaskSelectionGeneration()
        currentTaskId = null
        pendingTaskSelectionKey = null
        persistLastSelectedTaskId(null)
        latestApkUrl = null
        latestDownloadedApkFile = null
        latestDownloadedTaskId = null
        selectedAttachment = null
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
        BuildMonitorService.stopMonitoring(this, normalizedTaskId)
        taskConversationMessages.remove(normalizedTaskId)
        taskLastStatusKeys.remove(normalizedTaskId)
        persistTaskChats()
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

    private fun restoreUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        showLogs = savedInstanceState.getBoolean(STATE_SHOW_LOGS, false)
        switchShowLogs.isChecked = showLogs
        inputPrompt.setText(savedInstanceState.getString(STATE_INPUT_PROMPT).orEmpty())
    }

    private fun submitMessage() {
        val prompt = inputPrompt.text.toString().trim()
        if (prompt.isBlank()) return
        val attachment = selectedAttachment
        val attachedImagePreview = attachment?.toChatImagePreview()
        inputPrompt.setText("")

        when (screenState.inputMode) {
            InputMode.NEW_GENERATE -> startAppSynthesis(prompt, attachedImagePreview, attachment = attachment)
            InputMode.CONTINUE_CLARIFICATION -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    continueClarification(taskId, prompt, attachment = attachment)
                }
            }
            InputMode.REFINE_EXISTING -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    dispatchLatestTaskFeedback(taskId, prompt, attachedImagePreview, attachment)
                }
            }
            InputMode.RETRY_FAILED -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    dispatchLatestTaskFeedback(taskId, prompt, attachedImagePreview, attachment)
                }
            }
            InputMode.READ_ONLY -> {
                Toast.makeText(this, R.string.read_only_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentReferenceImageName(): String? = selectedAttachment?.takeIf { it.kind == SelectedAttachmentKind.IMAGE }?.displayName

    private fun currentReferenceImageBase64(): String? = selectedAttachment?.takeIf { it.kind == SelectedAttachmentKind.IMAGE }?.base64

    private fun clearSelectedAttachment() {
        selectedAttachment = null
        renderState()
    }

    private fun dispatchLatestTaskFeedback(
        taskId: String,
        feedback: String,
        imagePreview: ChatImagePreview? = null,
        attachment: SelectedAttachment? = selectedAttachment
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        val referenceImagePreview = if (screenState.inputMode == InputMode.REFINE_EXISTING) {
            imagePreview ?: selectedAttachment?.toChatImagePreview()
        } else {
            null
        }
        startFollowupSynthesis(
            taskId = apiTaskId,
            prompt = feedback,
            imagePreview = referenceImagePreview,
            attachment = attachment,
            mode = screenState.inputMode
        )
    }

    private fun handleConfirmationAccepted(message: ChatMessage) {
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
        attachment: SelectedAttachment? = selectedAttachment,
        sourceTaskId: String? = null,
        displayPrompt: String? = null
    ) {
        val deviceInfo = collectDeviceInfo()
        val referenceImagePreview = imagePreview ?: attachment?.toChatImagePreview()
        val referenceImageName = referenceImagePreview?.displayName
        val referenceImageBase64 = referenceImagePreview?.base64
        val attachments = attachment?.let { listOf(it.toPayload()) }
        val visiblePrompt = displayPrompt ?: prompt
        if (sourceTaskId == null) {
            appendLocalUserMessage(visiblePrompt, referenceImagePreview)
        }
        showLocalSystemMessage(getString(R.string.message_title_status), getString(R.string.status_generate_pending))
        lifecycleScope.launch {
            try {
                setComposerEnabled(false)
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_sending),
                    statusDetail = getString(R.string.status_generate_pending)
                )
                renderState()

                logApiRequest("/generate", deviceId = deviceId)
                val response = apiService.generateApp(
                    BuildRequest(
                        task_id = sourceTaskId,
                        prompt = prompt,
                        device_info = deviceInfo,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber,
                        reference_image_name = referenceImageName,
                        reference_image_base64 = referenceImageBase64,
                        attachments = attachments
                    )
                )
                clearSelectedAttachment()
                if (sourceTaskId == null) {
                    moveLocalConversationToTask(response.task_id)
                }
                currentTaskId = response.task_id
                val shouldStartBuildWorkflow = shouldStartBuildWorkflow(response)
                if (shouldStartBuildWorkflow && shouldPoll(response.status.orEmpty())) {
                    BuildMonitorService.startMonitoring(this@MainActivity, response.task_id)
                    addTaskEvent(
                        response.task_id,
                        ChatMessage(
                            id = "generate-${response.task_id}-${System.currentTimeMillis()}",
                            kind = MessageKind.STATUS,
                            title = getString(R.string.message_title_status),
                            body = getString(R.string.status_generate_pending)
                        )
                    )
                }
                applyGenerateDecisionResponse(response)
                if (shouldStartBuildWorkflow) {
                    refreshCurrentTaskAfterFollowup(
                        response.task_id,
                        autoInstallOnSuccess = true,
                        optimisticStatus = buildWorkflowStartStatusText(response),
                        optimisticDetail = buildWorkflowStartDetail(response)
                    )
                } else {
                    reenterTaskConversation(response.task_id)
                    setComposerEnabled(true)
                }
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                setComposerEnabled(true)
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
        attachment: SelectedAttachment? = selectedAttachment
    ) {
        startFollowupSynthesis(
            taskId,
            prompt,
            imagePreview = attachment?.toChatImagePreview(),
            attachment = attachment,
            mode = InputMode.CONTINUE_CLARIFICATION,
            appendUserMessage = appendUserMessage
        )
    }

    private fun selectTask(taskId: String, autoInstallOnSuccess: Boolean) {
        val resolvedTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        if (resolvedTaskId.isBlank()) return
        stopPolling()
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        currentTaskId = resolvedTaskId
        pendingTaskSelectionKey = null
        persistLastSelectedTaskId(resolvedTaskId)
        val summary = taskSummaryById[resolvedTaskId]
        screenState = screenState.copy(
            selectedTaskId = resolvedTaskId,
            displayedAppName = summary?.appName,
            messages = buildTaskTimeline(resolvedTaskId),
            currentStatus = getString(R.string.status_loading_task),
            statusDetail = getString(R.string.status_loading_task_detail, resolvedTaskId),
            canDownload = persistedApkUrlForTask(resolvedTaskId) != null,
            canInstall = persistedDownloadedApkFileForTask(resolvedTaskId) != null
        )
        drawerLayout.closeDrawer(GravityCompat.START)
        renderState()

        taskSyncJob = lifecycleScope.launch {
            try {
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
        pendingInitialChatScrollTaskId = resolvedTaskId
        screenState = screenState.copy(
            selectedTaskId = resolvedTaskId,
            messages = buildTaskTimeline(resolvedTaskId),
            currentStatus = optimisticStatus ?: getString(R.string.status_loading_task),
            statusDetail = optimisticDetail ?: getString(R.string.status_loading_task_detail, resolvedTaskId),
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

        if (response.tool == "answer_question" && message.isNotBlank() && isAssistantRenderMode(response)) {
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
                inputMode = InputMode.CONTINUE_CLARIFICATION,
                currentStatus = resolveStatusDisplayText(response.status, null, null),
                statusDetail = message
            )
            renderState()
            setComposerEnabled(true)
            return
        }

        if (response.tool == "ask_confirmation") {
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
            val clarificationMessages = buildList {
                message.takeIf { it.isNotBlank() }?.let(::add)
                if (isEmpty()) {
                    response.reason?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
                questions.forEach { question ->
                    if (question !in this) {
                        add(question)
                    }
                }
            }

            clarificationMessages.forEachIndexed { index, clarification ->
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "decision-clarification-$taskId-$index-${System.currentTimeMillis()}",
                        kind = MessageKind.ASSISTANT,
                        title = getString(R.string.message_title_assistant),
                        body = clarification
                    )
                )
            }
            val clarificationDetail = clarificationMessages.firstOrNull()
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

        questions.forEachIndexed { index, question ->
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-question-$taskId-$index-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = question
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

        addTaskEvent(
            taskId,
            ChatMessage(
                id = "decision-build-status-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = buildStartStatus,
                detail = buildStartDetail,
                createdAt = currentTimestampString()
            )
        )

        screenState = screenState.copy(
            selectedTaskId = taskId,
            inputMode = InputMode.READ_ONLY,
            currentStatus = buildStartStatus,
            statusDetail = buildStartDetail
        )
        renderState()
    }

    private fun startRefinement(taskId: String, feedback: String) {
        startFollowupSynthesis(
            taskId = taskId,
            prompt = feedback,
            imagePreview = selectedAttachment?.toChatImagePreview(),
            attachment = selectedAttachment,
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
        currentTaskId = taskId
        val normalizedStatus = response.status.trim()
        val isSuccess = isSuccessStatus(normalizedStatus)
        val isClarifying = isClarificationResponse(response) || isClarificationStatus(normalizedStatus)
        val isFailedBuild = isRetryableStatus(normalizedStatus)
        val isRetryable = isRetryAllowed(response)
        val isPollingStatus = shouldPoll(normalizedStatus)
        val isErrorResponse = isStatusErrorResponse(normalizedStatus)
        val allowArtifactActions = isSuccess && !isPollingStatus
        val progressMode = response.progress_mode?.takeIf { it.isNotBlank() }
        taskRawLogSections[taskId] = extractRawLogSections(response)
        latestApkUrl = resolveApkUrl(taskId, response, isSuccess)
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
        messages = ensureLatestLogMessage(taskId, response, messages)
        val inputMode = when {
            isPollingStatus -> InputMode.READ_ONLY
            isSuccess -> InputMode.REFINE_EXISTING
            isClarifying -> InputMode.CONTINUE_CLARIFICATION
            isFailedBuild || isRetryable -> InputMode.RETRY_FAILED
            isErrorResponse -> InputMode.READ_ONLY
            screenState.selectedTaskId == taskId -> InputMode.READ_ONLY
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
        val resolvedAppName = taskDisplayName(response.generated_app_name)
            ?: taskDisplayName(response.app_name)
            ?: taskSummaryById[taskId]?.appName
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

        renderState()

        if (isPollingStatus) {
            BuildMonitorService.startMonitoring(this, taskId)
        } else {
            BuildMonitorService.stopMonitoring(this, taskId)
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
            downloadAndInstall(taskId, latestApkUrl!!)
        }

        if (syncPolling) {
            if (isPollingStatus && screenState.selectedTaskId == taskId) {
                startPolling(taskId)
            } else {
                stopPolling()
                setComposerEnabled(inputMode != InputMode.READ_ONLY)
            }
        }
    }

    private fun notifyBuildCompleted(taskId: String, appName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(STATE_SELECTED_TASK_ID, taskId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            taskId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resolvedName = appName?.takeIf { it.isNotBlank() } ?: getString(R.string.untitled_task)
        val notification = NotificationCompat.Builder(this, BUILD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.logo3)
            .setContentTitle(getString(R.string.notification_build_success_title))
            .setContentText(getString(R.string.notification_build_success_body, resolvedName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(taskId.hashCode(), notification)
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
                    val response = apiService.getStatus(
                        taskId,
                        deviceId,
                        null,
                        userIdentity.phoneNumber
                    )
                    applyStatus(taskId, response, autoInstallOnSuccess = true, syncPolling = false)
                    if (!shouldPoll(response.status.trim())) {
                        break
                    }
                } catch (e: Exception) {
                    logApiFailure("/status/{task_id}", taskId = taskId, deviceId = deviceId, throwable = e)
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

            screenState = screenState.copy(pollingTaskId = null)
            renderState()
            setComposerEnabled(screenState.inputMode != InputMode.READ_ONLY)
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

    private fun beginTaskRegeneration(taskId: String, status: String, detail: String) {
        latestApkUrl = null
        if (latestDownloadedTaskId == taskId) {
            latestDownloadedTaskId = null
            latestDownloadedApkFile = null
        }
        screenState = screenState.copy(
            currentStatus = status,
            statusDetail = detail,
            canDownload = false,
            canInstall = false
        )
        renderState()
    }

    private fun startFollowupSynthesis(
        taskId: String,
        prompt: String,
        imagePreview: ChatImagePreview? = null,
        attachment: SelectedAttachment? = selectedAttachment,
        mode: InputMode,
        appendUserMessage: Boolean = true
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/generate") ?: return
        currentTaskId = apiTaskId
        if (screenState.selectedTaskId != apiTaskId) {
            pendingInitialChatScrollTaskId = apiTaskId
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
                    body = prompt,
                    createdAt = currentTimestampString(),
                    imagePreviewBase64 = imagePreview?.base64,
                    imagePreviewName = imagePreview?.displayName
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
                body = when (mode) {
                    InputMode.CONTINUE_CLARIFICATION -> getString(R.string.status_continue_pending)
                    InputMode.REFINE_EXISTING -> getString(R.string.status_refining_progress)
                    InputMode.RETRY_FAILED -> getString(R.string.status_retry_reviewing)
                    else -> getString(R.string.status_sending)
                },
                detail = "기존 작업에 대한 후속 요청을 전달했어요."
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
            attachment = attachment,
            sourceTaskId = apiTaskId,
            displayPrompt = prompt
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
        val phoneNumber = savedPhoneNumber ?: readPhoneNumberFromSim()
        if (savedPhoneNumber.isNullOrBlank() && !phoneNumber.isNullOrBlank()) {
            preferencesStore.savePhoneNumber(phoneNumber)
        }
        Log.d(TAG, "Loaded user_identity phone_number=${phoneNumber ?: "-"}")
        return UserIdentity(
            phoneNumber = phoneNumber
        )
    }

    private fun hasRequiredPhoneNumber(): Boolean {
        return ::deviceId.isInitialized &&
            deviceId.isNotBlank() &&
            hasPhoneNumberPermissionGranted() &&
            !userIdentity.phoneNumber.isNullOrBlank()
    }

    private fun hasPhoneNumberPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
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
        val phoneNumber = readPhoneNumberFromSim()
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

    private fun readPhoneNumberFromSim(): String? {
        if (!hasPhoneNumberPermissionGranted()) return null

        return try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subscriptionManager = getSystemService(SubscriptionManager::class.java) ?: return null
                val subscriptionId = SubscriptionManager.getDefaultSubscriptionId()
                val subscriptionNumber = if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    subscriptionManager.getPhoneNumber(subscriptionId).trim()
                } else {
                    ""
                }
                normalizePhoneNumber(subscriptionNumber) ?: normalizePhoneNumber(readLegacyLine1Number(telephonyManager))
            } else {
                normalizePhoneNumber(readLegacyLine1Number(telephonyManager))
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyLine1Number(telephonyManager: TelephonyManager): String {
        return telephonyManager.line1Number?.trim().orEmpty()
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun normalizePhoneNumber(raw: String?): String? {
        val digits = raw.orEmpty().filter { it.isDigit() }
        if (digits.isBlank()) return null
        return when {
            digits.startsWith("82") && digits.length >= 11 -> "0" + digits.drop(2)
            else -> digits
        }.takeIf { it.isNotBlank() }
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

    private fun persistHiddenTaskIds() {
        preferencesStore.saveHiddenTaskIds(hiddenTaskIds)
    }

    private fun loadPersistedTaskChats() {
        val restoredChats = preferencesStore.loadTaskChats()
        taskConversationMessages.clear()
        restoredChats.timelines.forEach { (taskId, messages) ->
            taskConversationMessages[taskId] = messages.toMutableList()
        }
        restoredChats.statusMessages.forEach { (taskId, message) ->
            appendTaskTimelineMessage(taskId, message, allowDuplicateContent = true)
        }
    }

    private fun loadPersistedArtifactStates() {
        taskArtifactStates.clear()
        taskArtifactStates += preferencesStore.loadTaskArtifactStates()
        syncArtifactPointersForActiveTask()
    }

    private fun persistTaskArtifactStates() {
        preferencesStore.saveTaskArtifactStates(taskArtifactStates)
    }

    private fun syncArtifactPointersForActiveTask() {
        val activeTaskId = currentTaskId?.trim().takeUnless { it.isNullOrBlank() }
            ?: screenState.selectedTaskId?.trim().takeUnless { it.isNullOrBlank() }
        if (activeTaskId.isNullOrBlank()) {
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
        val path = taskArtifactStates[normalizedTaskId]?.downloadedApkPath?.trim().orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() }
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
                downloadedApkPath = normalizedPath
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
        val committed = preferencesStore.saveTaskChats(taskConversationMessages)
        if (!committed) {
            Log.w(TAG, "Failed to commit task chats")
        }
    }

    private fun showPersistedTaskPreview(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        val hasPersistedMessages = taskConversationMessages[normalizedTaskId].orEmpty().isNotEmpty()
        if (!hasPersistedMessages) return
        pendingInitialChatScrollTaskId = normalizedTaskId

        val summary = taskSummaryById[normalizedTaskId]
        screenState = screenState.copy(
            selectedTaskId = normalizedTaskId,
            displayedAppName = summary?.appName ?: screenState.displayedAppName,
            messages = buildTaskTimeline(normalizedTaskId),
            currentStatus = getString(R.string.status_loading_task),
            statusDetail = getString(R.string.status_syncing_saved_state),
            canDownload = persistedApkUrlForTask(normalizedTaskId) != null,
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
            val status = apiService.getStatus(
                taskId,
                deviceId,
                null,
                userIdentity.phoneNumber
            )
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

    private fun downloadAndInstall(taskId: String, url: String) {
        isDownloadingApk = true
        renderState()
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadTaskId = resolveApiTaskId(taskId, "/download/{task_id}")?.trim().takeUnless { it.isNullOrBlank() }
            try {
                downloadTaskId?.let { logTaskSelection(it, it) }
                logApiRequest("/download/{task_id}", taskId = downloadTaskId, deviceId = deviceId, extra = "url=$url")
                val response = if (!downloadTaskId.isNullOrBlank()) {
                    apiService.downloadApk(
                        downloadTaskId,
                        deviceId,
                        null,
                        userIdentity.phoneNumber
                    )
                } else {
                    throw IllegalStateException("missing task_id for download")
                }
                if (!response.isSuccessful) {
                    val rawBody = response.errorBody()?.string()
                    Log.e(
                        TAG,
                        "API failure endpoint=/download/{task_id} task_id=${downloadTaskId ?: "-"} device_id=$deviceId http=${response.code()} body=${rawBody ?: "<empty>"}"
                    )
                    throw IllegalStateException("server response ${response.code()}")
                }

                val apkFile = File(externalCacheDir, "generated_app_${downloadTaskId ?: "latest"}.apk")
                response.body()?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                latestDownloadedApkFile = apkFile
                latestDownloadedTaskId = downloadTaskId
                downloadTaskId?.let { updateTaskArtifactState(it, apkUrl = latestApkUrl, downloadedApkFile = apkFile) }
                withContext(Dispatchers.Main) {
                    isDownloadingApk = false
                    downloadTaskId?.let { taskId ->
                        addTaskEvent(
                            taskId,
                            ChatMessage(
                                id = "download-$taskId-${System.currentTimeMillis()}",
                                kind = MessageKind.STATUS,
                                title = getString(R.string.message_title_status),
                                body = getString(R.string.status_downloaded),
                                detail = apkFile.absolutePath
                            )
                        )
                    }
                    screenState = screenState.copy(
                        currentStatus = getString(R.string.status_downloaded),
                        statusDetail = apkFile.absolutePath,
                        canInstall = screenState.selectedTaskId?.let(::persistedDownloadedApkFileForTask) != null,
                        canDownload = false
                    )
                    renderState()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloadingApk = false
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

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun renderState() {
        val phoneGateVisible = !hasRequiredPhoneNumber()
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
        btnOpenLibrary.isEnabled = !phoneGateVisible
        btnOpenSettings.isEnabled = !phoneGateVisible
        inputPhoneGate.isEnabled = false
        inputPhoneGate.visibility = if (phoneGateVisible) View.GONE else View.VISIBLE
        btnSavePhoneGate.isEnabled = true

        taskAdapter.submitList(
            screenState.taskList.map { it.copy(hasRuntimeError = it.taskId in runtimeErrorTaskIds) },
            screenState.selectedTaskId
        )
        val baseVisibleMessages = screenState.messages
            .filter { it.kind != MessageKind.LOG }
            .filterNot(::isRedundantDownloadedStatusMessage)
        val timelineVisibleMessages = if (shouldAnimateProcessingStatus(screenState.messages)) {
            animateProcessingStatusBubble(baseVisibleMessages)
        } else {
            baseVisibleMessages
        }
        val visibleMessages = appendArtifactCardMessage(timelineVisibleMessages)
        val shouldAutoScrollNewMessage = shouldAutoScrollMessages(visibleMessages)
        val aggregatedLogText = buildLogPanelText(screenState.selectedTaskId, screenState.messages)
        if (!isMessageTextSelectionActive) {
            chatAdapter.submitList(visibleMessages)
        }
        emptyChatText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
        logPanel.visibility = if (showLogs && aggregatedLogText.isNotBlank()) View.VISIBLE else View.GONE
        btnLogScrollBottom.visibility = if (showLogs && aggregatedLogText.isNotBlank()) View.VISIBLE else View.GONE
        logPanelTitle.text = "작업 로그"
        val previousLogText = logPanelBody.text?.toString().orEmpty()
        val nextLogText = aggregatedLogText
        val previousLogScrollY = logPanelScroll.scrollY
        val shouldRestoreLogScroll = showLogs &&
            previousLogText != nextLogText &&
            previousLogText.isNotBlank()
        if (previousLogText != nextLogText) {
            logPanelBody.text = nextLogText
        }
        if (showLogs && aggregatedLogText.isNotBlank()) {
            if (shouldRestoreLogScroll) {
                restoreLogPanelScrollAfterLayout(previousLogScrollY)
            }
        }
        syncProcessingStatusAnimation(screenState.messages)

        if (phoneGateVisible) {
            inputModeLabel.text = getString(R.string.phone_gate_title)
            inputPrompt.hint = getString(R.string.phone_gate_hint)
            setComposerEnabled(false)
            return
        }

        when (screenState.inputMode) {
            InputMode.NEW_GENERATE -> {
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_new_chat))
                inputPrompt.hint = getString(R.string.prompt_hint_new)
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
                inputModeLabel.text = buildModeLabel(getString(R.string.input_mode_read_only))
                inputPrompt.hint = getString(R.string.prompt_hint_read_only)
                setComposerEnabled(false)
            }
        }
        selectedAttachmentChip.text = selectedAttachment?.chipLabel().orEmpty()
        selectedAttachmentChip.visibility = if (selectedAttachment == null) View.GONE else View.VISIBLE
        selectedAttachmentChip.setOnClickListener {
            clearSelectedAttachment()
        }

        val selectedTaskId = screenState.selectedTaskId
        if (visibleMessages.isNotEmpty() && !selectedTaskId.isNullOrBlank() && pendingInitialChatScrollTaskId == selectedTaskId) {
            recyclerMessages.post {
                recyclerMessages.scrollToPosition(0)
            }
            pendingInitialChatScrollTaskId = null
        } else if (shouldAutoScrollNewMessage) {
            recyclerMessages.post {
                recyclerMessages.scrollToPosition(visibleMessages.lastIndex)
            }
        }
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
        return lastVisible >= currentCount - 2
    }

    private fun appendArtifactCardMessage(messages: List<ChatMessage>): List<ChatMessage> {
        val selectedTaskId = screenState.selectedTaskId?.trim().orEmpty()
        if (selectedTaskId.isBlank()) return messages
        if (!screenState.canDownload && !screenState.canInstall && !isDownloadingApk) return messages
        val fileName = "예시용_앱"
        val sizeLabel = "10 MB · apk"
        val artifactMessage = ChatMessage(
            id = "artifact-$selectedTaskId",
            kind = MessageKind.STATUS,
            title = null,
            body = fileName,
            detail = sizeLabel,
            createdAt = messages.lastOrNull()?.createdAt ?: currentTimestampString(),
            artifactTaskId = selectedTaskId,
            artifactCanDownload = screenState.canDownload,
            artifactCanInstall = screenState.canInstall,
            artifactDownloading = isDownloadingApk
        )
        return messages + artifactMessage
    }

    private fun isRedundantDownloadedStatusMessage(message: ChatMessage): Boolean {
        return message.kind == MessageKind.STATUS && message.body == getString(R.string.status_downloaded)
    }

    private fun handleArtifactDownloadRequested(message: ChatMessage) {
        handleArtifactInstallRequested(message)
    }

    private fun handleArtifactInstallRequested(message: ChatMessage) {
        if (isDownloadingApk) return
        val taskId = message.artifactTaskId?.trim().orEmpty()
        if (taskId.isBlank()) return
        val downloadedFile = persistedDownloadedApkFileForTask(taskId)
        if (downloadedFile != null) {
            installApk(downloadedFile)
            return
        }
        persistedApkUrlForTask(taskId)?.let { downloadAndInstall(taskId, it) }
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
        btnAttachReferenceImage.isEnabled = enabled
        btnAttachReferenceImage.alpha = if (enabled) 1.0f else 0.5f
        btnSend.isEnabled = enabled
        btnSend.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun hideKeyboardAndClearInputFocus() {
        inputPrompt.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(inputPrompt.windowToken, 0)
    }

    private fun restoreLogPanelScrollAfterLayout(previousScrollY: Int) {
        logPanelScroll.post {
            logPanelScroll.post {
                val maxScrollY = (logPanelBody.height - logPanelScroll.height).coerceAtLeast(0)
                logPanelScroll.scrollTo(0, previousScrollY.coerceIn(0, maxScrollY))
            }
        }
    }

    private fun scrollLogPanelToBottom() {
        logPanelScroll.post {
            val maxScrollY = (logPanelBody.height - logPanelScroll.height).coerceAtLeast(0)
            logPanelScroll.smoothScrollTo(0, maxScrollY)
        }
    }

    private fun buildModeLabel(modeText: String): String {
        val status = screenState.currentStatus.takeIf { it.isNotBlank() } ?: return modeText
        return "$modeText · 현재: $status"
    }

    private fun appendLocalUserMessage(message: String, imagePreview: ChatImagePreview? = null) {
        val localMessages = screenState.messages + ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            kind = MessageKind.USER,
            title = getString(R.string.message_title_user),
            body = message,
            createdAt = currentTimestampString(),
            imagePreviewBase64 = imagePreview?.base64,
            imagePreviewName = imagePreview?.displayName
        )
        screenState = screenState.copy(messages = localMessages)
        renderState()
    }

    private fun showAttachmentMenu() {
        val dialog = BottomSheetDialog(this)
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
                dialog.dismiss()
                launchCameraAttachment()
            }
        )
        row.addView(
            buildAttachmentSheetTile(
                title = getString(R.string.attachment_menu_photo),
                iconRes = android.R.drawable.ic_menu_gallery
            ) {
                dialog.dismiss()
                pickReferenceImageLauncher.launch("image/*")
            }
        )
        row.addView(
            buildAttachmentSheetTile(
                title = getString(R.string.attachment_menu_file),
                iconRes = R.drawable.ic_artifact_file
            ) {
                dialog.dismiss()
                pickDocumentAttachmentLauncher.launch(arrayOf("application/pdf", "text/*"))
            }
        )
        content.addView(row)
        dialog.setContentView(content)
        dialog.show()
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
            Toast.makeText(this, R.string.attachment_camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val imageFile = File.createTempFile("camera_attachment_", ".jpg", cacheRoot)
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", imageFile)
        pendingCameraImageUri = uri
        captureImageLauncher.launch(uri)
    }

    private fun handleAttachmentSelected(uri: Uri, kind: SelectedAttachmentKind) {
        lifecycleScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) {
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
                if (attachment == null) {
                    val messageRes = when (kind) {
                        SelectedAttachmentKind.IMAGE -> R.string.attachment_image_too_large
                        SelectedAttachmentKind.PDF -> R.string.attachment_pdf_too_large
                        SelectedAttachmentKind.TEXT -> R.string.attachment_text_too_large
                    }
                    Toast.makeText(this@MainActivity, messageRes, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                selectedAttachment = attachment
                renderState()
            } catch (e: Exception) {
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
        pendingInitialChatScrollTaskId = normalizedTaskId
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
            val appName = taskDisplayName(dto.app_name)
                ?: taskDisplayName(dto.generated_app_name)
            val initialPrompt = dto.initial_user_prompt.trim().takeIf { it.isNotBlank() }
            val title = buildTaskContentTitle(
                initialPrompt = initialPrompt,
                appName = appName,
                conversationState = dto.conversation_state
            ) ?: getString(R.string.untitled_task)
            TaskSummary(
                taskId = taskId,
                title = title,
                appName = appName,
                packageName = dto.package_name.ifBlank { null },
                subtitle = appName ?: dto.created_at.ifBlank { taskId },
                status = dto.status_display_text.ifBlank { displayStatusText(dto.status) },
                updatedAt = dto.updated_at.ifBlank { dto.created_at.ifBlank { null } },
                hasApk = dto.apk_url.isNotBlank(),
                hasRuntimeError = taskId in runtimeErrorTaskIds
            )
        }
    }

    private fun mergeConversationMessages(
        taskId: String,
        response: StatusResponse
    ): List<ChatMessage> {
        seedConversationMessages(taskId, response)
        val seededTimeline = buildTaskTimeline(taskId)
        val incomingMessages = when {
            seededTimeline.isNotEmpty() ->
                buildIncrementalMessages(taskId, response, seededTimeline).filter { it.kind != MessageKind.STATUS }
            else ->
                emptyList()
        }
        incomingMessages.forEach { message ->
            appendTaskTimelineMessage(taskId, message)
        }
        appendStatusTransitionMessage(taskId, response)
        return buildTaskTimeline(taskId)
    }

    private fun seedConversationMessages(taskId: String, response: StatusResponse) {
        val obj = response.conversation_state?.takeIf { it.isJsonObject }?.asJsonObject
        if (obj != null) {
            val initialPrompt = firstString(obj, "initial_user_prompt")?.trim().orEmpty()
            if (initialPrompt.isNotBlank()) {
                appendTaskTimelineMessage(
                    taskId,
                    ChatMessage(
                        id = "seed-user-$taskId",
                        kind = MessageKind.USER,
                        title = getString(R.string.message_title_user),
                        body = initialPrompt,
                        createdAt = currentTimestampString()
                    )
                )
            }

            val latestSummary = firstString(obj, "latest_summary")?.trim().orEmpty()
            val confirmationAction = firstString(obj, "confirmation_action")?.trim().orEmpty()
            val confirmationPayload = firstString(obj, "confirmation_payload")?.trim().orEmpty()
            val renderMode = firstString(obj, "render_mode")?.trim().orEmpty()
            val awaitingConfirmation = obj.get("awaiting_confirmation")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            if (latestSummary.isNotBlank() && renderMode != "confirmation_bubble" && !timelineContainsBody(taskId, latestSummary)) {
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
            if (awaitingConfirmation && confirmationAction.isNotBlank()) {
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
                latestQuestions.forEachIndexed { index, question ->
                    if (question.isBlank() || timelineContainsBody(taskId, question)) return@forEachIndexed
                    appendTaskTimelineMessage(
                        taskId,
                        ChatMessage(
                            id = "seed-question-$taskId-$index",
                            kind = MessageKind.ASSISTANT,
                            title = getString(R.string.message_title_assistant),
                            body = question,
                            createdAt = currentTimestampString()
                        )
                    )
                }
            }
        }

        val latestFailure = resolveFailureBubbleText(response)
        if (latestFailure.isNotBlank() && !timelineContainsBody(taskId, latestFailure)) {
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
        if (explicit.isNotBlank()) return explicit
        if (!isRetryableStatus(response.status)) return ""

        val detail = listOf(
            response.latest_assistant_message,
            response.status_message,
            response.latest_log,
            response.log
        )
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

        return if (detail.isNotBlank()) {
            "앱 빌드에 실패했어요. 원인은 $detail"
        } else {
            "앱 빌드에 실패했어요. 로그 보기에서 자세한 내용을 확인할 수 있어요."
        }
    }

    private fun timelineContainsBody(taskId: String, body: String): Boolean {
        return buildTaskTimeline(taskId).any { hasSameMessageText(it.body, body) }
    }

    private fun extractTimelineEvents(response: StatusResponse): List<TimelineEventSnapshot> {
        val array = response.timeline_events?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            TimelineEventSnapshot(
                eventId = firstString(obj, "event_id")?.trim().orEmpty(),
                createdAt = firstString(obj, "created_at")?.trim().orEmpty(),
                kind = firstString(obj, "kind")?.trim().orEmpty(),
                title = firstString(obj, "title")?.trim().orEmpty(),
                body = firstString(obj, "body")?.trim().orEmpty(),
                detail = firstString(obj, "detail")?.trim().orEmpty()
            ).takeIf { it.body.isNotBlank() }
        }
    }

    private fun timelineEventToMessage(taskId: String, event: TimelineEventSnapshot): ChatMessage {
        val messageKind = when (event.kind.lowercase()) {
            "user" -> MessageKind.USER
            "assistant" -> MessageKind.ASSISTANT
            "status" -> MessageKind.STATUS
            else -> MessageKind.LOG
        }
        return ChatMessage(
            id = "timeline-$taskId-${event.eventId.ifBlank { event.body.hashCode().toString() }}",
            kind = messageKind,
            title = event.title.ifBlank {
                when (messageKind) {
                    MessageKind.USER -> getString(R.string.message_title_user)
                    MessageKind.ASSISTANT, MessageKind.CONFIRMATION -> getString(R.string.message_title_assistant)
                    MessageKind.STATUS -> getString(R.string.message_title_status)
                    MessageKind.LOG, MessageKind.BUILD_LOG -> getString(R.string.message_title_log)
                }
            },
            body = event.body,
            detail = event.detail.ifBlank { null },
            createdAt = event.createdAt.ifBlank { currentTimestampString() }
        )
    }

    private fun extractServerTimelineMessages(
        taskId: String,
        response: StatusResponse,
        existingTimeline: List<ChatMessage>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        extractTimelineEvents(response).forEach { event ->
            val message = timelineEventToMessage(taskId, event)
            if (existingTimeline.any { it.sameContentAs(message) }) return@forEach
            if (messages.any { it.sameContentAs(message) }) return@forEach
            messages += message
        }
        return messages
    }

    private fun extractRawLogSections(response: StatusResponse): List<LogSectionSnapshot> {
        val array = response.raw_log_sections?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = firstString(obj, "title")?.trim().orEmpty()
            val content = firstString(obj, "content")?.trim().orEmpty()
            LogSectionSnapshot(title = title, content = content).takeIf { it.content.isNotBlank() }
        }
    }

    private fun hasStructuredRawLogs(response: StatusResponse): Boolean {
        return extractRawLogSections(response).isNotEmpty()
    }

    private fun buildIncrementalMessages(taskId: String, response: StatusResponse, existingTimeline: List<ChatMessage>): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += extractServerTimelineMessages(taskId, response, existingTimeline)
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
        recent?.forEachIndexed { index, item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val role = firstString(obj, "role")?.trim().orEmpty().lowercase()
            val messageType = firstString(obj, "message_type")?.trim().orEmpty().lowercase()
            val content = firstString(obj, "content")?.trim().orEmpty()
            if (content.isBlank()) return@forEachIndexed
            if (!role.contains("assistant") && !role.contains("clarification")) return@forEachIndexed
            if (messageType == "task_log") return@forEachIndexed

            val message = ChatMessage(
                id = "recent-$taskId-$messageType-$index-${content.hashCode()}",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_assistant),
                body = content,
                createdAt = firstString(obj, "created_at")?.trim().orEmpty().ifBlank { currentTimestampString() }
            )
            if (existingTimeline.any { it.sameContentAs(message) }) return@forEachIndexed
            if (messages.any { it.sameContentAs(message) }) return@forEachIndexed
            if (isRedundantAggregatedAssistantMessage(taskId, message)) return@forEachIndexed
            if (isRedundantOperationalAssistantMessage(taskId, message)) return@forEachIndexed
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
            createdAt = currentTimestampString()
        )
        if (existingTimeline.any { it.sameContentAs(fallbackMessage) }) return emptyList()
        if (isRedundantAggregatedAssistantMessage(taskId, fallbackMessage)) return emptyList()
        if (isRedundantOperationalAssistantMessage(taskId, fallbackMessage)) return emptyList()
        return listOf(fallbackMessage)
    }

    private fun extractPlannerMessages(taskId: String, response: StatusResponse): List<ChatMessage> {
        val plannerTexts = linkedSetOf<String>()
        collectPlannerTexts(response.log_lines, plannerTexts)
        collectPlannerTextsFromRawLog(resolveFullLogText(response).orEmpty(), plannerTexts)
        return plannerTexts.mapIndexed { index, text ->
            ChatMessage(
                id = "planner-$taskId-$index",
                kind = MessageKind.ASSISTANT,
                title = getString(R.string.message_title_refinement_plan),
                body = localizePlannerText(text)
            )
        }
    }

    private fun collectPlannerTexts(element: JsonElement?, sink: MutableSet<String>) {
        if (element == null || element.isJsonNull) return
        when {
            element.isJsonArray -> {
                element.asJsonArray.forEach { child -> collectPlannerTexts(child, sink) }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val text = firstString(obj, "message", "text", "content", "summary", "body", "detail", "details")
                val metadata = listOfNotNull(
                    firstString(obj, "type", "kind", "stage", "source", "role", "agent", "label", "event"),
                    firstString(obj, "category", "step", "name")
                ).joinToString(" ").lowercase()

                if (!text.isNullOrBlank() && isPlannerText(text, metadata)) {
                    sink += text.trim()
                }

                obj.entrySet().forEach { (_, value) -> collectPlannerTexts(value, sink) }
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val text = element.asString.trim()
                if (isPlannerText(text)) {
                    sink += text
                }
            }
        }
    }

    private fun collectPlannerTextsFromRawLog(rawLog: String?, sink: MutableSet<String>) {
        rawLog
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && isPlannerText(it) }
            ?.forEach { sink += it }
    }

    private fun isPlannerText(text: String, metadata: String = ""): Boolean {
        val normalizedText = text.lowercase()
        val normalizedMetadata = metadata.lowercase()
        return normalizedMetadata.contains("planner") ||
            normalizedMetadata.contains("refinement") ||
            normalizedMetadata.contains("refine_plan") ||
            normalizedMetadata.contains("refinement_plan") ||
            normalizedMetadata.contains("plan") ||
            normalizedText.contains("refinement plan") ||
            normalizedText.contains("planner:") ||
            normalizedText.contains("[planner]") ||
            normalizedText.contains("수정 계획") ||
            normalizedText.contains("어떻게 고칠지")
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

    private fun extractConversationPreview(conversationState: JsonElement?): String? {
        val obj = conversationState?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return firstString(obj, "initial_user_prompt", "latest_summary")?.trim()?.takeIf { it.isNotBlank() }
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
        val existing = taskSummaryById[taskId] ?: return
        val updatedTitle = buildTaskContentTitle(
            initialPrompt = response.conversation_state
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { firstString(it, "initial_user_prompt") }
                ?: existing.title,
            appName = resolvedAppName,
            conversationState = response.conversation_state
        ) ?: existing.title
        val updated = existing.copy(
            title = updatedTitle,
            appName = resolvedAppName ?: existing.appName,
            subtitle = (resolvedAppName ?: existing.appName) ?: existing.subtitle,
            status = statusText,
            hasApk = hasApk || existing.hasApk
        )
        taskSummaryById = taskSummaryById.toMutableMap().apply {
            put(taskId, updated)
        }
        val updatedList = screenState.taskList.map { summary ->
            if (summary.taskId == taskId) updated else summary
        }
        screenState = screenState.copy(taskList = updatedList)
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

    private fun isSuccessStatus(status: String): Boolean {
        return normalizeStatusKey(status) == "success"
    }

    private fun isClarificationStatus(status: String): Boolean {
        return normalizeStatusKey(status) in setOf(
            "pending decision",
            "clarification needed",
            "clarification required",
            "clarifying",
            "rejected"
        )
    }

    private fun isClarificationResponse(response: StatusResponse): Boolean {
        return response.requires_user_input == true ||
            response.pending_decision_reason?.trim()?.lowercase() == "clarification"
    }

    private fun isRetryableStatus(status: String): Boolean {
        return normalizeStatusKey(status) in setOf("failed", "error")
    }

    private fun isRetryAllowed(response: StatusResponse): Boolean {
        response.retry_allowed?.let { return it }
        response.allowed_next_actions?.let { actions ->
            return actions.any { it.equals("retry", ignoreCase = true) }
        }
        return isRetryableStatus(response.status)
    }

    private fun isStatusErrorResponse(status: String): Boolean {
        return normalizeStatusKey(status) in setOf(
            "not found",
            "device mismatch",
            "invalid state"
        )
    }

    private fun shouldPoll(status: String): Boolean {
        return normalizeStatusKey(status) in setOf(
            "pending decision",
            "clarification needed",
            "clarification required",
            "clarifying",
            "readytobuild",
            "ready to build",
            "queued",
            "building",
            "processing",
            "running",
            "in progress",
            "working",
            "reviewing",
            "repairing"
        )
    }

    private fun shouldStartBuildWorkflow(response: BuildResponse): Boolean {
        return response.interaction_type?.trim()?.lowercase() == "build_started"
    }

    private fun isConfirmationRenderMode(response: BuildResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "confirmation_bubble"
    }

    private fun isAssistantRenderMode(response: BuildResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "assistant_message"
    }

    private fun shouldRenderDecisionSummary(response: BuildResponse): Boolean {
        if (isConfirmationRenderMode(response)) return false
        return response.tool == "answer_question" && isAssistantRenderMode(response)
    }

    private fun isConfirmationRenderMode(response: StatusResponse): Boolean {
        return response.render_mode?.trim()?.lowercase() == "confirmation_bubble"
    }

    private fun suppressAssistantBubble(response: StatusResponse): Boolean {
        return response.suppress_assistant_bubble == true ||
            response.render_mode?.trim()?.lowercase() == "status_only"
    }

    private fun resolveMessageKind(role: String, title: String?, content: String): MessageKind {
        return when {
            role.contains("user") -> MessageKind.USER
            role.contains("confirmation") -> MessageKind.CONFIRMATION
            role.contains("assistant") || role.contains("clarification") -> MessageKind.ASSISTANT
            role.contains("build") || role.contains("system") -> MessageKind.ASSISTANT
            role.contains("status") && isCompactStatus(content) -> MessageKind.STATUS
            role.contains("status") -> MessageKind.ASSISTANT
            else -> MessageKind.ASSISTANT
        }
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
        return when (normalizeStatusKey(raw)) {
            "pending decision" -> "요청을 검토하고 있어요"
            "clarification needed", "clarification required", "clarifying" -> "추가 정보가 필요해요"
            "processing" -> when (progressMode) {
                "refine" -> getString(R.string.status_refining_progress)
                "retry" -> getString(R.string.status_retrying_progress)
                else -> "앱을 생성하고 있어요"
            }
            "reviewing" -> "결과를 점검하고 있어요"
            "repairing" -> "오류를 수정하고 있어요"
            "failed", "error" -> "앱 생성에 실패했어요"
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
            summary.contains("이렇게 수정할게요") ||
            message.contains("기존 앱 수정")
    }

    private fun buildWorkflowStartStatusText(response: BuildResponse): String {
        return if (isModificationBuildResponse(response)) {
            getString(R.string.status_refining_progress)
        } else {
            displayStatusText("Processing", "generate")
        }
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
        return normalizeStatusKey(compact) in setOf(
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
            "in progress",
            "working"
        )
    }

    private fun normalizeStatusKey(status: String): String {
        return compactStatusLabel(status)
            .lowercase()
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
            "Task selection requested_task_id=$requestedTaskId resolved_task_id=$resolvedTaskId app_name=${summary?.appName ?: "-"} title=${summary?.title ?: "-"}"
        )
    }

    private fun logStatusFetchTaskId(taskId: String, source: String) {
        Log.d(TAG, "Status fetch source=$source task_id=$taskId app_name=${taskSummaryById[taskId]?.appName ?: "-"}")
    }

    private fun logTaskIdForApi(endpoint: String, taskId: String) {
        Log.d(TAG, "API task_id endpoint=$endpoint task_id=$taskId")
    }

    private fun extractRefinePlanAssistantMessage(planResponse: JsonElement?): String? {
        val jsonObject = planResponse?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return jsonObject.get("assistant_message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractRefinePlanSummary(planResponse: JsonElement?): String? {
        val jsonObject = planResponse?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val summary = jsonObject.get("summary") ?: return null
        return when {
            summary.isJsonNull -> null
            summary.isJsonPrimitive -> summary.asString
            else -> summary.toString()
        }
    }

    private fun extractRefinePlanImageReferenceSummary(planResponse: JsonElement?): String? {
        val jsonObject = planResponse?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return firstString(jsonObject, "image_reference_summary")?.takeIf { it.isNotBlank() }
    }

    private fun extractRefinePlanImageConflictNote(planResponse: JsonElement?): String? {
        val jsonObject = planResponse?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return firstString(jsonObject, "image_conflict_note")?.takeIf { it.isNotBlank() }
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
            throwable is HttpException && throwable.code() == 409 -> "현재 상태에서는 이 요청을 바로 처리할 수 없어요."
            throwable is HttpException && throwable.code() in 500..599 -> "서버 처리 중 문제가 발생했어요. 잠시 후 다시 시도해 주세요."
            rootCause is SocketTimeoutException -> "응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요."
            rootCause is UnknownHostException || rootCause is ConnectException -> "네트워크 연결을 확인해 주세요."
            rootCause is IOException -> "네트워크 문제로 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
            else -> "잠시 후 다시 시도해 주세요."
        }
    }

    private fun shouldReenterRuntimeErrorTask(taskId: String): Boolean {
        if (taskId.isBlank()) return false
        if (screenState.selectedTaskId == taskId || currentTaskId == taskId) return true
        if (taskSummaryById.containsKey(taskId)) return true
        return taskConversationMessages[taskId].orEmpty().isNotEmpty()
    }

    private fun handleRuntimeError(
        taskId: String,
        packageName: String,
        stackTrace: String,
        errorMessage: String? = null,
        reportKind: String? = null
    ) {
        val existing = pendingRuntimeErrors[taskId]
        if (existing?.stackTrace == stackTrace && existing.awaitingUserConfirmation) {
            return
        }

        runtimeErrorTaskIds += taskId
        pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
            packageName = packageName,
            stackTrace = stackTrace,
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
                    stackTrace = stackTrace
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
        reenterTaskConversation(taskId)
        loadTaskList(autoSelectPendingTask = false)
        requestRuntimeErrorSummary(taskId, packageName, stackTrace, errorMessage, reportKind)
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
        preferencesStore.loadPendingRuntimeErrors().forEach { (taskId, record) ->
            val resolvedTaskId = resolveCrashTaskId(taskId, record.packageName) ?: taskId
            if (record.awaitingUserConfirmation) {
                handleRuntimeError(
                    taskId = resolvedTaskId,
                    packageName = record.packageName,
                    stackTrace = record.stackTrace,
                    errorMessage = record.errorMessage,
                    reportKind = record.reportKind
                )
            } else {
                pendingRuntimeErrors[resolvedTaskId] = record
                runtimeErrorTaskIds += resolvedTaskId
            }
        }
    }

    private fun reconcilePersistedRuntimeErrors() {
        val snapshot = pendingRuntimeErrors.toMap()
        if (snapshot.isEmpty()) return

        lifecycleScope.launch {
            snapshot.forEach { (taskId, record) ->
                try {
                    logTaskIdForApi("/status/{task_id}", taskId)
                    logApiRequest("/status/{task_id}", taskId = taskId, deviceId = deviceId, extra = "reconcile_runtime_error=true")
                    val status = apiService.getStatus(
                        taskId,
                        deviceId,
                        null,
                        userIdentity.phoneNumber
                    )
                    if (isSuccessStatus(status.status) && !record.awaitingUserConfirmation) {
                        clearStaleRuntimeErrorState(taskId, removeTimeline = false)
                    }
                } catch (e: HttpException) {
                    if (e.code() == 404) {
                        clearStaleRuntimeErrorState(taskId, removeTimeline = true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Runtime error reconciliation skipped task_id=$taskId", e)
                }
            }
        }
    }

    private fun clearStaleRuntimeErrorState(taskId: String, removeTimeline: Boolean) {
        var changed = false
        if (pendingRuntimeErrors.remove(taskId) != null) {
            changed = true
        }
        if (runtimeErrorTaskIds.remove(taskId)) {
            changed = true
        }
        if (removeTimeline && taskConversationMessages.remove(taskId) != null) {
            changed = true
        }
        if (removeTimeline && screenState.selectedTaskId == taskId) {
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
        } else if (getLastSelectedTaskId() == taskId && removeTimeline) {
            persistLastSelectedTaskId(null)
            changed = true
        }

        if (changed) {
            persistPendingRuntimeErrors()
            persistTaskChats()
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
                it.isNotBlank() && (isRetryableStatus(response.status) || isStatusErrorResponse(response.status))
            }
    }

    private fun animatableProcessingLabels(): Set<String> {
        return setOf(
            getString(R.string.status_generate_pending),
            getString(R.string.status_continue_pending),
            getString(R.string.status_web_researching),
            displayStatusText("Pending Decision"),
            displayStatusText("Processing", "generate"),
            displayStatusText("Processing", "refine"),
            displayStatusText("Processing", "retry"),
            displayStatusText("Reviewing"),
            displayStatusText("Repairing")
        )
    }

    private fun processingAnimationBaseText(text: String): String? {
        val normalized = text.trim().trimEnd('.')
        return animatableProcessingLabels().firstOrNull { it == normalized }
    }

    private fun processingStatusVariants(baseText: String): List<String> {
        return listOf(
            baseText,
            "$baseText.",
            "$baseText..",
            "$baseText..."
        )
    }

    private fun shouldAnimateProcessingStatus(messages: List<ChatMessage>): Boolean {
        if (isMessageTextSelectionActive) return false
        if (!isProcessingAnimationActiveState()) return false
        val latestStatusMessage = messages.lastOrNull { it.kind == MessageKind.STATUS } ?: return false
        return processingAnimationBaseText(latestStatusMessage.body) != null
    }

    private fun isProcessingAnimationActiveState(): Boolean {
        if (screenState.pollingTaskId?.isNotBlank() == true) return true
        if (screenState.currentStatus == getString(R.string.status_sending)) return true
        if (processingAnimationBaseText(screenState.currentStatus) != null) return true
        if (processingAnimationBaseText(screenState.statusDetail.orEmpty()) != null) return true
        return false
    }

    private fun animateProcessingStatusBubble(messages: List<ChatMessage>): List<ChatMessage> {
        val targetIndex = messages.indexOfLast { it.kind == MessageKind.STATUS }
        if (targetIndex < 0) return messages
        val baseText = processingAnimationBaseText(messages[targetIndex].body) ?: return messages

        val variants = processingStatusVariants(baseText)
        val animatedBody = variants[processingStatusAnimationFrame % variants.size]
        return messages.mapIndexed { index, message ->
            if (index == targetIndex) message.copy(body = animatedBody) else message
        }
    }

    private fun syncProcessingStatusAnimation(messages: List<ChatMessage>) {
        if (!shouldAnimateProcessingStatus(messages)) {
            processingStatusAnimationJob?.cancel()
            processingStatusAnimationJob = null
            processingStatusAnimationFrame = 0
            return
        }
        if (processingStatusAnimationJob?.isActive == true) return

        processingStatusAnimationJob = lifecycleScope.launch {
            while (isActive && shouldAnimateProcessingStatus(screenState.messages)) {
                delay(PROCESSING_STATUS_ANIMATION_MS)
                processingStatusAnimationFrame = (processingStatusAnimationFrame + 1) % 4
                if (isMessageTextSelectionActive) {
                    continue
                }
                val visibleMessages = animateProcessingStatusBubble(
                    screenState.messages.filter { it.kind != MessageKind.LOG }
                )
                chatAdapter.submitList(visibleMessages)
            }
        }
    }

    private fun localizePlannerText(text: String): String {
        return text
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
            renderState()
        }
    }

    private fun appendOptimisticTaskMessage(taskId: String, message: ChatMessage, allowDuplicateContent: Boolean = false) {
        appendTaskTimelineMessage(taskId, message, allowDuplicateContent = allowDuplicateContent)
        if (screenState.selectedTaskId == taskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(taskId))
            renderState()
        }
    }

    private fun appendStatusTransitionMessage(taskId: String, response: StatusResponse) {
        if (!isCompactStatus(response.status)) return
        if (normalizeStatusKey(response.status) == "pending decision" && response.progress_mode.isNullOrBlank() && !isWebResearchInProgress(response)) {
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
                body = if (isWebResearchInProgress(response)) {
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
        return taskConversationMessages[taskId].orEmpty()
    }

    private fun appendTaskTimelineMessage(taskId: String, message: ChatMessage, allowDuplicateContent: Boolean = false) {
        val timeline = taskConversationMessages.getOrPut(taskId) { mutableListOf() }
        if (mergeProgressStatusMessage(timeline, message)) {
            persistTaskChats()
            return
        }
        if (timeline.lastOrNull()?.sameContentAs(message) == true) return
        val alreadyExists = timeline.any { it.sameContentAs(message) }
        if (!allowDuplicateContent && alreadyExists) return
        timeline += message.withUniqueId(taskId, timeline.size)
        persistTaskChats()
    }

    private fun mergeProgressStatusMessage(timeline: MutableList<ChatMessage>, message: ChatMessage): Boolean {
        if (message.kind != MessageKind.STATUS) return false
        val incomingKey = progressStatusDedupeKey(message.body) ?: return false
        val existingIndex = timeline.indexOfLast { it.kind == MessageKind.STATUS }
        if (existingIndex < 0) return false
        val existing = timeline[existingIndex]
        if (progressStatusDedupeKey(existing.body) != incomingKey) return false
        timeline[existingIndex] = existing.copy(
            body = processingAnimationBaseText(existing.body) ?: processingAnimationBaseText(message.body) ?: message.body,
            detail = message.detail ?: existing.detail,
            createdAt = message.createdAt ?: existing.createdAt
        )
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
        val timeline = buildTaskTimeline(taskId)
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
    private fun reenterTaskConversation(taskId: String) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return
        pendingInitialChatScrollTaskId = normalizedTaskId
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
            normalizeStatusKey(response.status),
            if (isWebResearchInProgress(response)) "web_research" else "",
            response.package_name,
            response.apk_url,
            response.build_success.toString(),
            response.build_attempts.toString()
        ).joinToString("|")
    }

    private fun isWebResearchInProgress(response: StatusResponse): Boolean {
        val statusKey = normalizeStatusKey(response.status)
        if (!shouldPoll(response.status)) return false
        if (statusKey in setOf("reviewing", "repairing")) return false

        val progressMode = response.progress_mode?.trim()?.lowercase().orEmpty()
        if (progressMode in setOf("refine", "retry", "repair", "runtime_repair")) return false
        if (progressMode in setOf("web_research", "research_then_build", "api_research")) return true

        val currentProgressText = listOf(
            response.status_display_text.orEmpty(),
            response.status_message.orEmpty(),
            response.latest_log.orEmpty()
        ).joinToString("\n").lowercase()
        if (currentProgressText.isBlank()) return false

        return listOf(
            "외부 정보 탐색",
            "웹 검색",
            "웹검색",
            "공개 api",
            "api 우선 탐색",
            "대표 api",
            "대표 웹 페이지",
            "웹 페이지 확보",
            "웹 데이터 구조 분석",
            "외부 정보 품질"
        ).any { marker -> currentProgressText.contains(marker.lowercase()) }
    }

    private fun ChatMessage.sameContentAs(other: ChatMessage): Boolean {
        if (kind != other.kind) return false
        val sameBody = hasSameMessageText(body, other.body)
        val sameDetail = hasSameMessageText(detail, other.detail)
        return when (kind) {
            MessageKind.USER -> {
                val hasImage = !imagePreviewBase64.isNullOrBlank()
                val otherHasImage = !other.imagePreviewBase64.isNullOrBlank()
                when {
                    hasImage != otherHasImage -> sameBody
                    hasImage -> sameBody && imagePreviewBase64 == other.imagePreviewBase64
                    else -> sameBody
                }
            }
            MessageKind.ASSISTANT,
            MessageKind.CONFIRMATION,
            MessageKind.LOG -> sameBody && sameDetail
            MessageKind.STATUS,
            MessageKind.BUILD_LOG -> sameBody && sameDetail && normalizeMessageTextForDedupe(title) == normalizeMessageTextForDedupe(other.title)
        }
    }

    private fun hasSameMessageText(left: String?, right: String?): Boolean {
        val normalizedLeft = normalizeMessageTextForDedupe(left)
        val normalizedRight = normalizeMessageTextForDedupe(right)
        if (normalizedLeft == normalizedRight) return true
        return compactMessageTextForDedupe(normalizedLeft) == compactMessageTextForDedupe(normalizedRight)
    }

    private fun normalizeMessageTextForDedupe(value: String?): String {
        return value.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun compactMessageTextForDedupe(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun currentTimestampString(): String {
        return serverTimestampFormat.format(Date())
    }

    private fun advanceTaskSelectionGeneration(): Long {
        taskSelectionGeneration += 1L
        return taskSelectionGeneration
    }

    private fun isTaskSelectionGenerationCurrent(generation: Long): Boolean {
        return taskSelectionGeneration == generation
    }

    private fun formatMessageTimestamp(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            displayTimestampFormat.format(serverTimestampFormat.parse(raw) ?: return raw)
        }.getOrElse { raw }
    }

    private fun formatMessageTimestampForBubble(value: String?): String? {
        val parsed = parseMessageTimestamp(value) ?: return null
        return bubbleTimestampFormat.format(parsed)
    }

    private fun parseMessageTimestamp(value: String?): Date? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null

        return runCatching { serverTimestampFormat.parse(raw) }.getOrNull()
            ?: runCatching { Date.from(Instant.parse(raw)) }.getOrNull()
            ?: runCatching { Date.from(OffsetDateTime.parse(raw).toInstant()) }.getOrNull()
            ?: runCatching {
                Date.from(LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant())
            }.getOrNull()
    }

    private fun buildLogPanelText(taskId: String?, messages: List<ChatMessage>): String {
        val timelineText = messages.mapNotNull { message ->
            if (message.kind == MessageKind.BUILD_LOG) return@mapNotNull null
            val label = when {
                !message.title.isNullOrBlank() -> message.title
                message.kind == MessageKind.USER -> getString(R.string.message_title_user)
                message.kind == MessageKind.ASSISTANT || message.kind == MessageKind.CONFIRMATION -> getString(R.string.message_title_assistant)
                message.kind == MessageKind.STATUS -> getString(R.string.message_title_status)
                else -> getString(R.string.message_title_log)
            }
            val bodyText = message.body.trim().ifBlank { return@mapNotNull null }
            val timestamp = formatMessageTimestamp(message.createdAt) ?: message.createdAt?.trim().orEmpty()
            val header = buildString {
                if (timestamp.isNotBlank()) {
                    append("[")
                    append(timestamp)
                    append("] ")
                }
                append(label)
                append(": ")
                append(bodyText)
            }
            val detailText = summarizeLogPanelDetail(message)
            if (detailText.isNullOrBlank()) {
                header
            } else {
                "$header\n$detailText"
            }
        }.joinToString("\n\n")

        val agentMessageText = taskId
            ?.let(::extractCodexAgentMessages)
            ?.map { "작업 엔진: $it" }
            ?.joinToString("\n\n")
            .orEmpty()

        return listOf(timelineText, agentMessageText)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun summarizeLogPanelDetail(message: ChatMessage): String? {
        val detailText = message.detail?.trim().orEmpty()
        if (detailText.isBlank()) return null
        val normalizedBody = message.body.trim()
        return when (message.kind) {
            MessageKind.LOG -> {
                detailText.takeIf { !hasSameMessageText(it, normalizedBody) }
            }
            MessageKind.STATUS -> detailText.takeIf { !hasSameMessageText(it, normalizedBody) }
            else -> null
        }
    }

    private fun extractCodexAgentMessages(taskId: String): List<String> {
        val sections = taskRawLogSections[taskId].orEmpty()
        val messages = linkedSetOf<String>()
        sections.forEach { section ->
            section.content
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") && it.endsWith("}") }
                .forEach { line ->
                    val parsed = runCatching { gson.fromJson(line, JsonObject::class.java) }.getOrNull()
                        ?: return@forEach
                    val eventType = firstString(parsed, "type")?.trim().orEmpty()
                    if (eventType != "item.completed") return@forEach
                    val item = parsed.get("item")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: return@forEach
                    val itemType = firstString(item, "type")?.trim().orEmpty()
                    if (itemType != "agent_message") return@forEach
                    val textCandidates = buildList {
                        add(firstString(item, "text")?.trim().orEmpty())
                        add(firstString(item, "message")?.trim().orEmpty())
                        add(firstString(item, "content")?.trim().orEmpty())
                        val payload = item.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
                        if (payload != null) {
                            add(firstString(payload, "text")?.trim().orEmpty())
                            add(firstString(payload, "message")?.trim().orEmpty())
                            add(firstString(payload, "content")?.trim().orEmpty())
                        }
                    }
                    textCandidates.firstOrNull { it.isNotBlank() }?.let { text ->
                        messages += text
                    }
                }
        }
        return messages.toList()
    }

    private fun ChatMessage.withUniqueId(taskId: String, position: Int): ChatMessage {
        return copy(
            id = "$taskId-$position-${kind.name.lowercase()}-${body.hashCode()}-${detail.hashCode()}",
            createdAt = createdAt ?: currentTimestampString()
        )
    }

    private fun showLocalSystemMessage(title: String, body: String, detail: String? = null, kind: MessageKind = MessageKind.STATUS) {
        val taskId = currentTaskId?.takeIf { it.isNotBlank() }
            ?: screenState.selectedTaskId?.takeIf { it.isNotBlank() }
        if (!taskId.isNullOrBlank()) {
            addTaskEvent(
                taskId,
                ChatMessage(
                    id = "local-system-$taskId-${System.currentTimeMillis()}",
                    kind = kind,
                    title = title,
                    body = body,
                    detail = detail,
                    createdAt = currentTimestampString()
                )
            )
            return
        }
        screenState = screenState.copy(
            messages = screenState.messages + ChatMessage(
                id = "local-system-${System.currentTimeMillis()}",
                kind = kind,
                title = title,
                body = body,
                detail = detail,
                createdAt = currentTimestampString()
            )
        )
        renderState()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun scrollMessagesToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) {
            recyclerMessages.scrollToPosition(count - 1)
        }
    }

    private fun logApiRequest(endpoint: String, taskId: String? = null, deviceId: String, extra: String? = null) {
        val suffix = extra?.let { " $it" }.orEmpty()
        Log.d(TAG, "API request endpoint=$endpoint task_id=${taskId ?: "-"} device_id=$deviceId$suffix")
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
                "API failure endpoint=$endpoint task_id=${taskId ?: "-"} device_id=$deviceId http=${throwable.code()} body=${rawBody ?: "<empty>"}",
                throwable
            )
        } else {
            Log.e(
                TAG,
                "API failure endpoint=$endpoint task_id=${taskId ?: "-"} device_id=$deviceId message=${throwable.message}",
                throwable
            )
        }
    }

    override fun onDestroy() {
        stopPolling()
        unregisterReceiver(crashReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_TASK_ID, screenState.selectedTaskId ?: currentTaskId)
        outState.putString(STATE_INPUT_PROMPT, inputPrompt.text?.toString().orEmpty())
        outState.putBoolean(STATE_SHOW_LOGS, showLogs)
    }

    private val messageSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            isMessageTextSelectionActive = true
            processingStatusAnimationJob?.cancel()
            processingStatusAnimationJob = null
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode?) {
            isMessageTextSelectionActive = false
            renderState()
        }
    }
}
