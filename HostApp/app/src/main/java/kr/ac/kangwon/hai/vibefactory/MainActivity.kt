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
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE_URL = "http://192.168.0.84:8000"
        private const val POLL_INTERVAL_MS = 3000L
        private const val PREFS_NAME = "vibefactory_prefs"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_PHONE_NUMBER = "phone_number"
        private const val PREF_LAST_TASK_ID = "last_task_id"
        private const val PREF_TASK_TIMELINES = "task_timelines"
        private const val PREF_TASK_STATUS_BUBBLES = "task_status_bubbles"
        private const val PREF_PENDING_RUNTIME_ERRORS = "pending_runtime_errors"
        private const val PREF_HIDDEN_TASK_IDS = "hidden_task_ids"
        private const val PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS = "notified_build_success_task_ids"
        private const val TAG = "VibeFactoryHost"
        private const val STATE_SELECTED_TASK_ID = "selected_task_id"
        private const val STATE_INPUT_PROMPT = "input_prompt"
        private const val STATE_SHOW_LOGS = "show_logs"
        private const val PROCESSING_STATUS_ANIMATION_MS = 700L
        private const val REQUEST_PHONE_NUMBER_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val ASSISTANT_MESSAGE_COLLAPSE_CHAR_THRESHOLD = 220
        private const val ASSISTANT_MESSAGE_COLLAPSED_MAX_LINES = 8
        private const val BUILD_NOTIFICATION_CHANNEL_ID = "build_complete"
        private const val MAX_REFERENCE_IMAGE_BYTES = 5_000_000
    }

    private val gson: Gson = GsonBuilder().create()
    private val serverTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val displayTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var apiService: VibeApiService
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var chatCard: View
    private lateinit var recyclerTasks: RecyclerView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var inputPrompt: EditText
    private lateinit var inputPhoneGate: EditText
    private lateinit var btnAttachReferenceImage: ImageButton
    private lateinit var selectedReferenceImagePreview: ImageView
    private lateinit var btnSend: Button
    private lateinit var btnNewChat: Button
    private lateinit var btnSavePhoneGate: Button
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var downloadArea: LinearLayout
    private lateinit var btnDownloadManual: Button
    private lateinit var btnInstallLatest: Button
    private lateinit var composerBar: LinearLayout
    private lateinit var logPanel: LinearLayout
    private lateinit var logPanelScroll: ScrollView
    private lateinit var logPanelTitle: TextView
    private lateinit var logPanelBody: TextView
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

    private val taskAdapter = TaskSummaryAdapter(
        onClick = { summary -> selectTask(summary.taskId, autoInstallOnSuccess = false) },
        onDelete = { summary -> confirmHideTaskFromChatList(summary) }
    )
    private val chatAdapter = ChatMessageAdapter()

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
    private val taskCurrentStatusMessages = mutableMapOf<String, ChatMessage>()
    private val taskLastStatusKeys = mutableMapOf<String, String>()
    private val hiddenTaskIds = mutableSetOf<String>()
    private var taskSummaryById: Map<String, TaskSummary> = emptyMap()
    private var pendingTaskSelectionKey: String? = null
    private var showLogs: Boolean = false
    private var isDownloadingApk: Boolean = false
    private var skipNextResumeRestore: Boolean = false
    private var restoreTaskJob: Job? = null
    private var taskSyncJob: Job? = null
    private var taskSelectionGeneration: Long = 0L
    private val expandedAssistantMessageIds = mutableSetOf<String>()
    private val handledConfirmationMessageIds = mutableSetOf<String>()
    private var pendingInitialChatScrollTaskId: String? = null
    private val notifiedBuildSuccessTaskIds = mutableSetOf<String>()
    private var isMessageTextSelectionActive = false
    private var selectedReferenceImage: ReferenceImageAttachment? = null

    private data class ReferenceImageAttachment(
        val displayName: String,
        val base64: String
    ) {
        fun toChatPreview(): ChatImagePreview {
            return ChatImagePreview(displayName = displayName, base64 = base64)
        }
    }

    private data class ChatImagePreview(
        val displayName: String,
        val base64: String
    )

    private data class UserIdentity(
        val phoneNumber: String?
    )

    private val pickReferenceImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleReferenceImageSelected(uri)
            }
        }

    private val crashReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "kr.ac.kangwon.hai.action.CRASH_REPORT") return

            val rawTaskId = intent.getStringExtra("task_id")
            lastCrashPackage = intent.getStringExtra("package_name")
            lastStackTrace = intent.getStringExtra("stack_trace")
            val pkg = lastCrashPackage.orEmpty()
            val stack = lastStackTrace.orEmpty()
            val taskId = resolveCrashTaskId(rawTaskId, pkg)
            lastCrashTaskId = taskId ?: rawTaskId

            if (!taskId.isNullOrBlank()) {
                handleRuntimeError(taskId, pkg.ifBlank { "알 수 없는 앱" }, stack)
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
        deviceId = getOrCreateDeviceId()
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
        loadPersistedRuntimeErrors()
        reconcilePersistedRuntimeErrors()
        renderState()

        registerReceiver(crashReceiver, IntentFilter("kr.ac.kangwon.hai.action.CRASH_REPORT"), RECEIVER_EXPORTED)
        pendingTaskSelectionKey = savedInstanceState?.getString(STATE_SELECTED_TASK_ID)
            ?: intent?.getStringExtra(STATE_SELECTED_TASK_ID)
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
        selectedReferenceImagePreview = findViewById(R.id.selectedReferenceImagePreview)
        btnSend = findViewById(R.id.btnSend)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnSavePhoneGate = findViewById(R.id.btnSavePhoneGate)
        btnOpenDrawer = findViewById(R.id.btnOpenDrawer)
        downloadArea = findViewById(R.id.downloadArea)
        btnDownloadManual = findViewById(R.id.btnDownloadManual)
        btnInstallLatest = findViewById(R.id.btnInstallLatest)
        composerBar = findViewById(R.id.composerBar)
        logPanel = findViewById(R.id.logPanel)
        logPanelScroll = findViewById(R.id.logPanelScroll)
        logPanelTitle = findViewById(R.id.logPanelTitle)
        logPanelBody = findViewById(R.id.logPanelBody)
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
                leftMargin = dp(12) + systemBars.left
                rightMargin = dp(12) + systemBars.right
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
            chatCard.requestLayout()

            downloadArea.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = dp(12) + systemBars.left
                rightMargin = dp(12) + systemBars.right
                bottomMargin = dp(10)
            }

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
        loadPersistedRuntimeErrors()
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
        val taskId = currentTaskId?.takeIf { it.isNotBlank() }
            ?: screenState.selectedTaskId?.takeIf { it.isNotBlank() }
            ?: pendingTaskSelectionKey?.takeIf { it.isNotBlank() }
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        restoreTaskJob = lifecycleScope.launch {
            if (taskId.isNullOrBlank()) {
                fetchTaskList(
                    autoSelectPendingTask = true,
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
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        recyclerTasks.layoutManager = LinearLayoutManager(this)
        recyclerTasks.adapter = taskAdapter

        recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerMessages.adapter = chatAdapter
    }

    private fun setupNetwork() {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        apiService = retrofit.create(VibeApiService::class.java)
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

        btnSavePhoneGate.setOnClickListener {
            savePhoneNumberFromGate()
        }

        btnSend.setOnClickListener {
            hideKeyboardAndClearInputFocus()
            submitMessage()
        }

        btnAttachReferenceImage.setOnClickListener {
            pickReferenceImageLauncher.launch("image/*")
        }

        inputPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (showLogs) {
                    showLogs = false
                    switchShowLogs.isChecked = false
                }
            }
        }

        btnDownloadManual.setOnClickListener {
            if (isDownloadingApk) return@setOnClickListener
            latestApkUrl?.let { downloadAndInstall(it) }
        }

        btnInstallLatest.setOnClickListener {
            latestDownloadedApkFile?.let { installApk(it) }
        }

        switchShowLogs.setOnCheckedChangeListener { _, isChecked ->
            showLogs = isChecked
            renderState()
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
                val resolvedTaskId = when {
                    !pendingKey.isNullOrBlank() -> resolveExactTaskIdCandidate(pendingKey, summaries)
                    currentTaskId?.isNotBlank() == true -> null
                    screenState.selectedTaskId?.isNotBlank() == true -> null
                    else -> summaries.firstOrNull()?.taskId
                }
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
        selectedReferenceImage = null
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
        taskConversationMessages.remove(normalizedTaskId)
        taskCurrentStatusMessages.remove(normalizedTaskId)
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
        val attachedImagePreview = selectedReferenceImage?.toChatPreview()
        inputPrompt.setText("")
        if (attachedImagePreview != null) {
            clearSelectedReferenceImage()
        }

        when (screenState.inputMode) {
            InputMode.NEW_GENERATE -> startAppSynthesis(prompt, attachedImagePreview)
            InputMode.CONTINUE_CLARIFICATION -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    continueClarification(taskId, prompt)
                }
            }
            InputMode.REFINE_EXISTING -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    dispatchLatestTaskFeedback(taskId, prompt, attachedImagePreview)
                }
            }
            InputMode.RETRY_FAILED -> {
                val taskId = currentTaskId
                if (!taskId.isNullOrBlank()) {
                    dispatchLatestTaskFeedback(taskId, prompt, attachedImagePreview)
                }
            }
            InputMode.READ_ONLY -> {
                Toast.makeText(this, R.string.read_only_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentReferenceImageName(): String? = selectedReferenceImage?.displayName

    private fun currentReferenceImageBase64(): String? = selectedReferenceImage?.base64

    private fun clearSelectedReferenceImage() {
        selectedReferenceImage = null
        renderState()
    }

    private fun dispatchLatestTaskFeedback(
        taskId: String,
        feedback: String,
        imagePreview: ChatImagePreview? = null
    ) {
        val apiTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        val pendingRuntimeError = pendingRuntimeErrors[apiTaskId]
        val referenceImagePreview = if (screenState.inputMode == InputMode.REFINE_EXISTING) {
            imagePreview ?: selectedReferenceImage?.toChatPreview()
        } else {
            null
        }
        appendOptimisticTaskMessage(
            apiTaskId,
            ChatMessage(
                id = "feedback-user-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.USER,
                title = getString(R.string.message_title_user),
                body = feedback,
                createdAt = currentTimestampString(),
                imagePreviewBase64 = referenceImagePreview?.base64,
                imagePreviewName = referenceImagePreview?.displayName
            )
        )
        lifecycleScope.launch {
            try {
                logTaskIdForApi("/feedback/route", apiTaskId)
                logApiRequest("/feedback/route", taskId = apiTaskId, deviceId = deviceId)
                val route = apiService.routeFeedback(
                    FeedbackRouteRequest(
                        task_id = apiTaskId,
                        user_message = feedback,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber,
                        runtime_error = pendingRuntimeError?.let {
                            RuntimeErrorContextDto(
                                package_name = it.packageName,
                                stack_trace = it.stackTrace,
                                summary = it.summary,
                                awaiting_user_confirmation = it.awaitingUserConfirmation
                            )
                        }
                    )
                )
                Log.d(TAG, "Feedback dispatch route task_id=$apiTaskId action=${route.action} endpoint=${route.target_endpoint} status=${route.current_status}")

                when (route.action.trim()) {
                    "repair_runtime" -> {
                        if (pendingRuntimeError != null && pendingRuntimeError.awaitingUserConfirmation) {
                            appendRouteConfirmationCard(apiTaskId, route, feedback)
                            renderState()
                        } else {
                            addTaskEvent(
                                apiTaskId,
                                ChatMessage(
                                    id = "runtime-repair-duplicate-$apiTaskId-${System.currentTimeMillis()}",
                                    kind = MessageKind.STATUS,
                                    title = getString(R.string.message_title_status),
                                    body = getString(R.string.runtime_repair_already_requested)
                                )
                            )
                            renderState()
                        }
                    }
                    "refine", "retry", "continue_generate" -> {
                        appendRouteConfirmationCard(apiTaskId, route, feedback)
                        renderState()
                    }
                    "ask_confirmation" -> {
                        appendBackendConfirmationCard(
                            apiTaskId,
                            route,
                            originalMessage = feedback,
                            fallbackAction = inferConfirmationActionForCurrentMode(apiTaskId)
                        )
                        renderState()
                    }
                    else -> {
                        if (route.assistant_message.isNotBlank()) {
                            appendOptimisticTaskMessage(
                                apiTaskId,
                                ChatMessage(
                                    id = "feedback-route-$apiTaskId-${System.currentTimeMillis()}",
                                    kind = MessageKind.ASSISTANT,
                                    title = getString(R.string.message_title_assistant),
                                    body = route.assistant_message
                                )
                            )
                        }
                        renderState()
                    }
                }
            } catch (e: Exception) {
                logApiFailure("/feedback/route", taskId = apiTaskId, deviceId = deviceId, throwable = e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.feedback_route_failed, userVisibleErrorMessage(e)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun appendRouteConfirmationCard(taskId: String, route: FeedbackRouteResponse, originalMessage: String) {
        val action = route.action.trim()
        val body = route.assistant_message.ifBlank {
            when (action) {
                "refine" -> getString(R.string.confirmation_refine_preview)
                "retry" -> getString(R.string.confirmation_retry_preview)
                "repair_runtime" -> getString(R.string.confirmation_repair_preview)
                "continue_generate" -> getString(R.string.confirmation_continue_preview)
                else -> getString(R.string.confirmation_continue_preview)
            }
        }
        appendOptimisticTaskMessage(
            taskId,
            ChatMessage(
                id = "confirm-$action-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.CONFIRMATION,
                title = getString(R.string.confirmation_title),
                body = body,
                detail = route.reason,
                confirmAction = action,
                confirmTaskId = taskId,
                confirmPayload = originalMessage
            )
        )
    }

    private fun appendBackendConfirmationCard(
        taskId: String,
        route: FeedbackRouteResponse,
        originalMessage: String,
        fallbackAction: String,
    ) {
        appendOptimisticTaskMessage(
            taskId,
            ChatMessage(
                id = "confirm-backend-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.CONFIRMATION,
                title = getString(R.string.confirmation_title),
                body = route.assistant_message.ifBlank { getString(R.string.confirmation_continue_preview) },
                detail = route.reason,
                confirmAction = fallbackAction,
                confirmTaskId = taskId,
                confirmPayload = originalMessage
            )
        )
    }

    private fun inferConfirmationActionForCurrentMode(taskId: String): String {
        val pendingRuntimeError = pendingRuntimeErrors[taskId]
        if (pendingRuntimeError != null && pendingRuntimeError.awaitingUserConfirmation) {
            return "repair_runtime"
        }
        return when (screenState.inputMode) {
            InputMode.REFINE_EXISTING -> "refine"
            InputMode.RETRY_FAILED -> "retry"
            InputMode.CONTINUE_CLARIFICATION -> "continue_generate"
            InputMode.NEW_GENERATE -> "generate_confirm"
            InputMode.READ_ONLY -> "route_confirm"
        }
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
                    beginTaskRegeneration(
                        taskId = taskId,
                        status = getString(R.string.status_retrying_progress),
                        detail = message.body
                    )
                    sendCrashToServer(taskId, pendingRuntimeError.packageName, pendingRuntimeError.stackTrace)
                }
            }
            "continue_generate" -> continueClarification(taskId, payload, appendUserMessage = false)
            "generate_confirm" -> continueClarification(taskId, payload)
            "route_confirm" -> dispatchLatestTaskFeedback(taskId, payload.ifBlank { "네, 진행해줘" })
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
        lifecycleScope.launch {
            try {
                apiService.recordInteractionEvent(
                    InteractionEventRequest(
                        task_id = taskId,
                        event_type = eventType,
                        action = selectedAction,
                        message_id = message.id,
                        message_type = "confirmation",
                        content = message.body,
                        payload = mapOf(
                            "title" to message.title,
                            "detail" to message.detail,
                            "confirm_payload" to selectedPayload
                        ),
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber
                    )
                )
            } catch (e: Exception) {
                logApiFailure("/interaction/event", taskId = taskId, deviceId = deviceId, throwable = e)
            }
        }
    }

    private fun startAppSynthesis(prompt: String, imagePreview: ChatImagePreview? = null) {
        val deviceInfo = collectDeviceInfo()
        val referenceImagePreview = imagePreview ?: selectedReferenceImage?.toChatPreview()
        val referenceImageName = referenceImagePreview?.displayName
        val referenceImageBase64 = referenceImagePreview?.base64
        appendLocalUserMessage(prompt, referenceImagePreview)
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
                        prompt = prompt,
                        device_info = deviceInfo,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber,
                        reference_image_name = referenceImageName,
                        reference_image_base64 = referenceImageBase64
                    )
                )
                clearSelectedReferenceImage()
                moveLocalConversationToTask(response.task_id)
                currentTaskId = response.task_id
                if (shouldPoll(response.status.orEmpty())) {
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
                refreshCurrentTaskAfterFollowup(response.task_id, autoInstallOnSuccess = true)
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

    private fun continueClarification(taskId: String, prompt: String, appendUserMessage: Boolean = true) {
        val apiTaskId = resolveApiTaskId(taskId, "/generate/continue") ?: return
        if (appendUserMessage) {
            appendOptimisticTaskMessage(
                apiTaskId,
                ChatMessage(
                    id = "continue-user-$apiTaskId-${System.currentTimeMillis()}",
                    kind = MessageKind.USER,
                    title = getString(R.string.message_title_user),
                    body = prompt
                )
            )
        }
        addTaskEvent(
            apiTaskId,
            ChatMessage(
                id = "continue-$apiTaskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = getString(R.string.status_continue_pending)
            )
        )
        lifecycleScope.launch {
            try {
                setComposerEnabled(false)
                beginTaskRegeneration(
                    taskId = apiTaskId,
                    status = getString(R.string.status_sending),
                    detail = getString(R.string.status_continue_pending)
                )

                logTaskSelection(apiTaskId, apiTaskId)
                logTaskIdForApi("/generate/continue", apiTaskId)
                logApiRequest("/generate/continue", taskId = apiTaskId, deviceId = deviceId)
                val response = apiService.continueGenerate(
                    ContinueRequest(
                        task_id = apiTaskId,
                        user_message = prompt,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber
                    )
                )
                applyGenerateDecisionResponse(response)
                refreshCurrentTaskAfterFollowup(apiTaskId, autoInstallOnSuccess = true)
                loadTaskList(autoSelectPendingTask = false)
            } catch (e: Exception) {
                setComposerEnabled(true)
                logApiFailure("/generate/continue", taskId = apiTaskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "continue-error-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.continue_failed, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.continue_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
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
            currentStatus = getString(R.string.status_loading_task),
            statusDetail = getString(R.string.status_loading_task_detail, resolvedTaskId),
            canDownload = false,
            canInstall = latestDownloadedTaskId == resolvedTaskId && latestDownloadedApkFile?.exists() == true
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

    private fun refreshCurrentTaskAfterFollowup(taskId: String, autoInstallOnSuccess: Boolean) {
        val resolvedTaskId = resolveApiTaskId(taskId, "/status/{task_id}") ?: return
        if (resolvedTaskId.isBlank()) return
        stopPolling()
        restoreTaskJob?.cancel()
        taskSyncJob?.cancel()
        val selectionGeneration = advanceTaskSelectionGeneration()
        currentTaskId = resolvedTaskId
        pendingTaskSelectionKey = null
        persistLastSelectedTaskId(resolvedTaskId)

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

        appendImageReferenceMessages(
            taskId,
            response.image_reference_summary,
            response.image_conflict_note
        )

        if (summary.isNotBlank()) {
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

        if (response.tool == "answer_question" && message.isNotBlank()) {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-answer-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.ASSISTANT,
                    title = getString(R.string.message_title_assistant),
                    body = message
                )
            )
            setComposerEnabled(true)
            return
        }

        if (response.tool == "ask_confirmation") {
            appendOptimisticTaskMessage(
                taskId,
                ChatMessage(
                    id = "decision-confirm-$taskId-${System.currentTimeMillis()}",
                    kind = MessageKind.CONFIRMATION,
                    title = getString(R.string.confirmation_title),
                    body = message.ifBlank { questions.firstOrNull().orEmpty() },
                    detail = response.reason,
                    confirmAction = "generate_confirm",
                    confirmTaskId = taskId,
                    confirmPayload = "네, 이 내용으로 앱 생성을 시작해줘"
                )
            )
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
    }

    private fun startRefinement(taskId: String, feedback: String) {
        val apiTaskId = resolveApiTaskId(taskId, "/refine/plan") ?: return
        val referenceImageName = currentReferenceImageName()
        val referenceImageBase64 = currentReferenceImageBase64()
        lifecycleScope.launch {
            try {
                setComposerEnabled(false)
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "refine-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.STATUS,
                        title = getString(R.string.message_title_status),
                        body = getString(R.string.status_refining_progress),
                        detail = feedback
                    )
                )
                beginTaskRegeneration(
                    taskId = apiTaskId,
                    status = getString(R.string.status_refining_progress),
                    detail = feedback
                )

                logTaskSelection(apiTaskId, apiTaskId)
                val refineRequest = RefineRequest(
                    task_id = apiTaskId,
                    feedback = feedback,
                    device_id = deviceId,
                    user_id = null,
                    phone_number = userIdentity.phoneNumber,
                    reference_image_name = referenceImageName,
                    reference_image_base64 = referenceImageBase64
                )
                var assistantMessage: String? = null
                try {
                    logTaskIdForApi("/refine/plan", apiTaskId)
                    logApiRequest("/refine/plan", taskId = apiTaskId, deviceId = deviceId)
                    val planResponse = apiService.planRefinement(refineRequest)
                    assistantMessage = extractRefinePlanAssistantMessage(planResponse)
                    val summary = extractRefinePlanSummary(planResponse)
                    appendImageReferenceMessages(
                        apiTaskId,
                        extractRefinePlanImageReferenceSummary(planResponse),
                        extractRefinePlanImageConflictNote(planResponse)
                    )
                    if (!summary.isNullOrBlank()) {
                        Log.d(TAG, "Refine plan summary task_id=$apiTaskId summary=$summary")
                    }
                } catch (e: HttpException) {
                    Log.w(TAG, "Refine plan preview failed task_id=$apiTaskId; continuing with /refine", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Refine plan preview parse failed task_id=$apiTaskId; continuing with /refine", e)
                }
                logTaskIdForApi("/refine", apiTaskId)
                logApiRequest("/refine", taskId = apiTaskId, deviceId = deviceId)
                val refineResponse = apiService.refineApp(refineRequest)
                clearSelectedReferenceImage()
                Log.d(TAG, "Refine accepted task_id=${refineResponse.task_id} status=${refineResponse.status ?: "-"}")
                appendImageReferenceMessages(
                    apiTaskId,
                    refineResponse.image_reference_summary,
                    refineResponse.image_conflict_note
                )
                refreshCurrentTaskAfterFollowup(apiTaskId, autoInstallOnSuccess = true)
            } catch (e: Exception) {
                setComposerEnabled(true)
                logApiFailure("/refine", taskId = apiTaskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "refine-status-error-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.STATUS,
                        title = getString(R.string.message_title_status),
                        body = getString(R.string.status_error),
                        detail = getString(R.string.refine_failed, userVisibleErrorMessage(e))
                    )
                )
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "refine-error-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.refine_failed, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.refine_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
    }

    private fun startRetry(taskId: String, feedback: String) {
        val apiTaskId = resolveApiTaskId(taskId, "/retry") ?: return
        lifecycleScope.launch {
            try {
                setComposerEnabled(false)
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "retry-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.STATUS,
                        title = getString(R.string.message_title_status),
                        body = getString(R.string.status_retry_reviewing),
                        detail = feedback
                    )
                )
                beginTaskRegeneration(
                    taskId = apiTaskId,
                    status = getString(R.string.status_retry_reviewing),
                    detail = feedback
                )

                logTaskSelection(apiTaskId, apiTaskId)
                logTaskIdForApi("/retry", apiTaskId)
                logApiRequest("/retry", taskId = apiTaskId, deviceId = deviceId)
                val retryResponse = apiService.retryApp(
                    RetryRequest(
                        task_id = apiTaskId,
                        feedback = feedback,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber
                    )
                )
                Log.d(TAG, "Retry accepted task_id=${retryResponse.task_id} status=${retryResponse.status ?: "-"}")
                appendImageReferenceMessages(
                    apiTaskId,
                    retryResponse.image_reference_summary,
                    retryResponse.image_conflict_note
                )
                refreshCurrentTaskAfterFollowup(apiTaskId, autoInstallOnSuccess = true)
            } catch (e: Exception) {
                setComposerEnabled(true)
                logApiFailure("/retry", taskId = apiTaskId, deviceId = deviceId, throwable = e)
                addTaskEvent(
                    apiTaskId,
                    ChatMessage(
                        id = "retry-error-$apiTaskId-${System.currentTimeMillis()}",
                        kind = MessageKind.LOG,
                        title = getString(R.string.message_title_log),
                        body = getString(R.string.retry_failed, userVisibleErrorMessage(e))
                    )
                )
                screenState = screenState.copy(
                    currentStatus = getString(R.string.status_error),
                    statusDetail = getString(R.string.retry_failed, userVisibleErrorMessage(e))
                )
                renderState()
            }
        }
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
        val isClarifying = isClarificationStatus(normalizedStatus)
        val isFailedBuild = isRetryableStatus(normalizedStatus)
        val isRetryable = isRetryAllowed(response)
        val isPollingStatus = shouldPoll(normalizedStatus)
        val isErrorResponse = isStatusErrorResponse(normalizedStatus)
        val allowArtifactActions = isSuccess && !isPollingStatus
        val progressMode = response.progress_mode.takeIf { it.isNotBlank() }
        latestApkUrl = resolveApkUrl(taskId, response, isSuccess)
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
        val messages = try {
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
            resolveStatusDisplayText(normalizedStatus, response.status_display_text, progressMode)
        }
        val statusDetailText = when {
            !response.status_message.isNullOrBlank() -> response.status_message
            !response.latest_log.isNullOrBlank() -> response.latest_log
            !response.log.isNullOrBlank() -> response.log
            isErrorResponse -> resolveStatusDisplayText(normalizedStatus, response.status_display_text, progressMode)
            else -> getString(R.string.status_no_detail)
        }

        screenState = screenState.copy(
            selectedTaskId = taskId,
            displayedAppName = taskDisplayName(response.generated_app_name)
                ?: taskDisplayName(response.app_name)
                ?: taskSummaryById[taskId]?.appName,
            messages = messages,
            inputMode = inputMode,
            currentStatus = currentStatusText,
            statusDetail = statusDetailText,
            canDownload = allowArtifactActions && latestApkUrl != null,
            canInstall = allowArtifactActions && latestDownloadedTaskId == taskId && latestDownloadedApkFile?.exists() == true
        )

        renderState()

        if (isSuccess && notifiedBuildSuccessTaskIds.add(taskId)) {
            persistNotifiedBuildSuccessTaskIds()
            notifyBuildCompleted(taskId, response.app_name?.takeIf { it.isNotBlank() } ?: taskSummaryById[taskId]?.appName)
        }

        if (isSuccess && autoInstallOnSuccess && latestApkUrl != null) {
            downloadAndInstall(latestApkUrl!!)
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_build_success_title))
            .setContentText(getString(R.string.notification_build_success_body, resolvedName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(taskId.hashCode(), notification)
    }

    private fun loadNotifiedBuildSuccessTaskIds() {
        val storedTaskIds = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS, emptySet())
            .orEmpty()
        notifiedBuildSuccessTaskIds.clear()
        notifiedBuildSuccessTaskIds += storedTaskIds.filter { it.isNotBlank() }
    }

    private fun persistNotifiedBuildSuccessTaskIds() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS, notifiedBuildSuccessTaskIds.toSet())
            .apply()
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

    private fun sendCrashToServer(taskId: String, pkg: String, stackTrace: String) {
        val currentSummary = pendingRuntimeErrors[taskId]?.summary
            ?.takeIf { it.isNotBlank() }
            ?: "실행 중 오류"
        pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
            packageName = pkg,
            stackTrace = stackTrace,
            summary = currentSummary,
            awaitingUserConfirmation = false
        )
        persistPendingRuntimeErrors()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = CrashReportRequest(
                    task_id = taskId,
                    package_name = pkg,
                    stack_trace = stackTrace,
                    device_id = deviceId,
                    user_id = null,
                    phone_number = userIdentity.phoneNumber
                )
                val response = apiService.reportCrash(report)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val crashResponse = response.body()
                        Log.d(
                            TAG,
                            "Crash forwarded task_id=${crashResponse?.task_id ?: taskId} status=${crashResponse?.status ?: "-"} source=${crashResponse?.lookup_source ?: "-"}"
                        )
                        Toast.makeText(this@MainActivity, R.string.crash_repair_started, Toast.LENGTH_SHORT).show()
                        addTaskEvent(
                            taskId,
                            ChatMessage(
                                id = "crash-repair-$taskId-${System.currentTimeMillis()}",
                                kind = MessageKind.STATUS,
                                title = getString(R.string.message_title_status),
                                body = getString(R.string.runtime_repair_in_progress)
                            )
                        )
                        selectTask(taskId, autoInstallOnSuccess = true)
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.crash_send_failed, response.code()), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.network_error_template, userVisibleErrorMessage(e)), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun reportRuntimeErrorToServer(taskId: String, packageName: String, stackTrace: String) {
        lifecycleScope.launch {
            try {
                logTaskIdForApi("/runtime/error/report", taskId)
                logApiRequest("/runtime/error/report", taskId = taskId, deviceId = deviceId)
                apiService.reportRuntimeError(
                    RuntimeErrorReportRequest(
                        task_id = taskId,
                        package_name = packageName,
                        stack_trace = stackTrace,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber
                    )
                )
            } catch (e: Exception) {
                logApiFailure("/runtime/error/report", taskId = taskId, deviceId = deviceId, throwable = e)
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

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existing = prefs.getString(PREF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            Log.d(TAG, "Reusing stored device_id=$existing")
            return existing
        }

        val newDeviceId = UUID.randomUUID().toString()
        val saved = prefs.edit().putString(PREF_DEVICE_ID, newDeviceId).commit()
        if (!saved) {
            throw IllegalStateException("Failed to persist device_id")
        }
        val stored = prefs.getString(PREF_DEVICE_ID, null)
        if (stored.isNullOrBlank()) {
            throw IllegalStateException("Stored device_id is blank after commit")
        }
        Log.d(TAG, "Generated new device_id=$stored")
        return stored
    }

    private fun getOrCreateUserIdentity(): UserIdentity {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedPhoneNumber = prefs.getString(PREF_PHONE_NUMBER, null)?.trim()?.ifBlank { null }
        val phoneNumber = savedPhoneNumber ?: readPhoneNumberFromSim()
        if (savedPhoneNumber.isNullOrBlank() && !phoneNumber.isNullOrBlank()) {
            prefs.edit().putString(PREF_PHONE_NUMBER, phoneNumber).apply()
        }
        Log.d(TAG, "Loaded user_identity phone_number=${phoneNumber ?: "-"}")
        return UserIdentity(
            phoneNumber = phoneNumber
        )
    }

    private fun hasRequiredPhoneNumber(): Boolean {
        return !userIdentity.phoneNumber.isNullOrBlank()
    }

    private fun savePhoneNumberFromGate() {
        val normalizedPhoneNumber = inputPhoneGate.text?.toString()?.trim()?.ifBlank { null }
        if (normalizedPhoneNumber.isNullOrBlank()) {
            inputPhoneGate.error = getString(R.string.phone_gate_error)
            return
        }
        val updatedIdentity = userIdentity.copy(phoneNumber = normalizedPhoneNumber)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_PHONE_NUMBER, updatedIdentity.phoneNumber)
            .apply()
        userIdentity = updatedIdentity
        inputPhoneGate.setText(updatedIdentity.phoneNumber.orEmpty())
        renderState()
        loadTaskList(autoSelectPendingTask = true)
        Toast.makeText(this, R.string.phone_gate_saved, Toast.LENGTH_SHORT).show()
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
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PHONE_NUMBER_PERMISSION)
        } else {
            tryFillPhoneNumberFromSim()
        }
    }

    private fun tryFillPhoneNumberFromSim() {
        val phoneNumber = readPhoneNumberFromSim() ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_PHONE_NUMBER, phoneNumber)
            .apply()
        userIdentity = UserIdentity(phoneNumber = phoneNumber)
        inputPhoneGate.setText(phoneNumber)
        renderState()
        loadTaskList(autoSelectPendingTask = true)
    }

    private fun readPhoneNumberFromSim(): String? {
        val canReadPhoneNumber =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!canReadPhoneNumber) return null

        return try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            val raw = telephonyManager.line1Number?.trim().orEmpty()
            normalizePhoneNumber(raw)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_TASK_ID, taskId)
            .apply()
    }

    private fun getLastSelectedTaskId(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_LAST_TASK_ID, null)
    }

    private fun loadHiddenTaskIds() {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_HIDDEN_TASK_IDS, null)
            ?: return
        runCatching {
            val type = object : TypeToken<Set<String>>() {}.type
            val saved: Set<String> = gson.fromJson(json, type) ?: emptySet()
            hiddenTaskIds.clear()
            hiddenTaskIds += saved.map { it.trim() }.filter { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "Failed to load hidden task ids", it)
        }
    }

    private fun persistHiddenTaskIds() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_HIDDEN_TASK_IDS, gson.toJson(hiddenTaskIds))
            .apply()
    }

    private fun loadPersistedTaskChats() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timelineJson = prefs.getString(PREF_TASK_TIMELINES, null)
        val statusJson = prefs.getString(PREF_TASK_STATUS_BUBBLES, null)

        if (!timelineJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<Map<String, List<ChatMessage>>>() {}.type
                gson.fromJson<Map<String, List<ChatMessage>>>(timelineJson, type)
            }.onSuccess { stored ->
                taskConversationMessages.clear()
                stored.orEmpty().forEach { (taskId, messages) ->
                    taskConversationMessages[taskId] = messages.toMutableList()
                }
            }.onFailure {
                Log.w(TAG, "Failed to restore task timelines", it)
            }
        }

        if (!statusJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<Map<String, ChatMessage>>() {}.type
                gson.fromJson<Map<String, ChatMessage>>(statusJson, type)
            }.onSuccess { stored ->
                taskCurrentStatusMessages.clear()
                taskCurrentStatusMessages.putAll(stored.orEmpty())
                taskCurrentStatusMessages.forEach { (taskId, message) ->
                    appendTaskTimelineMessage(taskId, message, allowDuplicateContent = true)
                }
                taskCurrentStatusMessages.clear()
            }.onFailure {
                Log.w(TAG, "Failed to restore task status bubbles", it)
            }
        }
    }

    private fun persistTaskChats() {
        runCatching {
            val committed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_TASK_TIMELINES, gson.toJson(taskConversationMessages))
                .remove(PREF_TASK_STATUS_BUBBLES)
                .commit()
            if (!committed) {
                Log.w(TAG, "Failed to commit task chats")
            }
        }.onFailure {
            Log.e(TAG, "Failed to persist task chats", it)
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
            canDownload = false,
            canInstall = false
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

    private fun downloadAndInstall(url: String) {
        isDownloadingApk = true
        renderState()
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadTaskId = currentTaskId?.trim().takeUnless { it.isNullOrBlank() }
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

                val apkFile = File(externalCacheDir, "generated_app.apk")
                response.body()?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                latestDownloadedApkFile = apkFile
                latestDownloadedTaskId = downloadTaskId
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
                        canInstall = latestDownloadedTaskId == screenState.selectedTaskId
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
        inputPhoneGate.isEnabled = phoneGateVisible
        btnSavePhoneGate.isEnabled = true

        taskAdapter.submitList(
            screenState.taskList.map { it.copy(hasRuntimeError = it.taskId in runtimeErrorTaskIds) },
            screenState.selectedTaskId
        )
        val baseVisibleMessages = screenState.messages.filter { it.kind != MessageKind.LOG }
        val visibleMessages = if (shouldAnimateProcessingStatus(screenState.messages)) {
            animateProcessingStatusBubble(baseVisibleMessages)
        } else {
            baseVisibleMessages
        }
        val shouldAutoScrollNewMessage = shouldAutoScrollMessages(visibleMessages)
        val pinnedLogMessage = screenState.messages.lastOrNull { it.kind == MessageKind.LOG }
        if (!isMessageTextSelectionActive) {
            chatAdapter.submitList(visibleMessages)
        }
        emptyChatText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
        val pinnedLogText = pinnedLogMessage?.detail?.takeIf { it.isNotBlank() }
            ?: pinnedLogMessage?.body?.takeIf { it.isNotBlank() }
        logPanel.visibility = if (showLogs && !pinnedLogText.isNullOrBlank()) View.VISIBLE else View.GONE
        logPanelTitle.text = pinnedLogMessage?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.message_title_log)
        val previousLogText = logPanelBody.text?.toString().orEmpty()
        val nextLogText = pinnedLogText.orEmpty()
        val previousLogScrollY = logPanelScroll.scrollY
        val shouldRestoreLogScroll = showLogs &&
            previousLogText != nextLogText &&
            previousLogText.isNotBlank()
        if (previousLogText != nextLogText) {
            logPanelBody.text = nextLogText
        }
        if (showLogs && !pinnedLogText.isNullOrBlank()) {
            if (shouldRestoreLogScroll) {
                restoreLogPanelScrollAfterLayout(previousLogScrollY)
            }
        }
        downloadArea.visibility = if (screenState.canDownload || screenState.canInstall) View.VISIBLE else View.GONE
        btnDownloadManual.visibility = if (screenState.canDownload) View.VISIBLE else View.GONE
        btnInstallLatest.visibility = if (screenState.canInstall) View.VISIBLE else View.GONE
        btnDownloadManual.isEnabled = screenState.canDownload && !isDownloadingApk
        btnDownloadManual.alpha = if (btnDownloadManual.isEnabled) 1.0f else 0.6f
        btnDownloadManual.text = if (isDownloadingApk) getString(R.string.download_apk_in_progress) else getString(R.string.download_apk)
        btnInstallLatest.isEnabled = screenState.canInstall && !isDownloadingApk
        btnInstallLatest.alpha = if (btnInstallLatest.isEnabled) 1.0f else 0.6f

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
        bindInlineImagePreview(
            imageView = selectedReferenceImagePreview,
            imageBase64 = selectedReferenceImage?.base64,
            fallbackVisibility = View.GONE
        )

        val selectedTaskId = screenState.selectedTaskId
        if (visibleMessages.isNotEmpty() && !selectedTaskId.isNullOrBlank() && pendingInitialChatScrollTaskId == selectedTaskId) {
            recyclerMessages.post {
                recyclerMessages.scrollToPosition(visibleMessages.lastIndex)
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

    private fun handleReferenceImageSelected(uri: Uri) {
        lifecycleScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) { buildReferenceImageAttachment(uri) }
                if (attachment == null) {
                    Toast.makeText(this@MainActivity, R.string.reference_image_too_large, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                selectedReferenceImage = attachment
                renderState()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.reference_image_pick_failed, userVisibleErrorMessage(e)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buildReferenceImageAttachment(uri: Uri): ReferenceImageAttachment? {
        val displayName = queryDisplayName(uri) ?: "reference_image"
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_REFERENCE_IMAGE_BYTES) {
                    return null
                }
                output.write(buffer, 0, read)
            }
            val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
            return ReferenceImageAttachment(displayName = displayName, base64 = encoded)
        }
        return null
    }

    private fun bindInlineImagePreview(
        imageView: ImageView,
        imageBase64: String?,
        fallbackVisibility: Int
    ) {
        val encoded = imageBase64?.trim().orEmpty()
        if (encoded.isBlank()) {
            imageView.setImageDrawable(null)
            imageView.visibility = fallbackVisibility
            return
        }

        runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onSuccess { bitmap ->
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
            } else {
                imageView.setImageDrawable(null)
                imageView.visibility = fallbackVisibility
            }
        }.onFailure {
            imageView.setImageDrawable(null)
            imageView.visibility = fallbackVisibility
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
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
        screenState = screenState.copy(messages = emptyList())
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
            val title = appName
                ?: initialPrompt
                ?: extractConversationPreview(dto.conversation_state)
                ?: getString(R.string.untitled_task)
            TaskSummary(
                taskId = taskId,
                title = title,
                appName = appName,
                packageName = dto.package_name.ifBlank { null },
                subtitle = initialPrompt ?: dto.package_name.ifBlank { dto.created_at.ifBlank { taskId } },
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
        val obj = response.conversation_state?.takeIf { it.isJsonObject }?.asJsonObject ?: return
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
        if (latestSummary.isNotBlank() && !timelineContainsBody(taskId, latestSummary)) {
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

        stringList(obj, "latest_assistant_questions").forEachIndexed { index, question ->
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

    private fun timelineContainsBody(taskId: String, body: String): Boolean {
        return buildTaskTimeline(taskId).any { hasSameMessageText(it.body, body) }
    }

    private fun buildIncrementalMessages(taskId: String, response: StatusResponse, existingTimeline: List<ChatMessage>): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
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
        return listOf(resolveStatusDisplayText(response.status, response.status_display_text, response.progress_mode), appSuffix, attemptSuffix).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractConversationPreview(conversationState: JsonElement?): String? {
        val obj = conversationState?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return firstString(obj, "initial_user_prompt", "latest_summary")?.trim()?.takeIf { it.isNotBlank() }
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
        val apkUrl = response.apk_url
        return when {
            apkUrl.isNotBlank() && apkUrl.startsWith("http") -> apkUrl
            apkUrl.isNotBlank() -> "$BASE_URL$apkUrl"
            isSuccess && taskId.isNotBlank() -> "$BASE_URL/download/$taskId"
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
            "processing",
            "reviewing",
            "repairing"
        )
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

    private fun resolveCrashTaskId(rawTaskId: String?, packageName: String?): String? {
        val normalizedRawTaskId = rawTaskId?.trim().orEmpty()
        val normalizedPackageName = packageName?.trim().orEmpty()
        val summaries = screenState.taskList.ifEmpty { taskSummaryById.values.toList() }

        val resolvedTaskId = when {
            normalizedRawTaskId.isNotBlank() && taskSummaryById.containsKey(normalizedRawTaskId) -> normalizedRawTaskId
            normalizedRawTaskId.isNotBlank() && !matchesDisplayIdentifier(normalizedRawTaskId) -> normalizedRawTaskId
            normalizedPackageName.isNotBlank() -> {
                summaries.firstOrNull { it.packageName == normalizedPackageName }?.taskId
                    ?: summaries.firstOrNull {
                        val packageLeaf = it.packageName?.substringAfterLast('.').orEmpty()
                        packageLeaf.isNotBlank() && packageLeaf == normalizedRawTaskId
                    }?.taskId
            }
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

    private fun summarizeRuntimeError(stackTrace: String): String {
        return "실행 중 오류"
    }

    private fun shouldReenterRuntimeErrorTask(taskId: String): Boolean {
        if (taskId.isBlank()) return false
        if (screenState.selectedTaskId == taskId || currentTaskId == taskId) return true
        if (taskSummaryById.containsKey(taskId)) return true
        return taskConversationMessages[taskId].orEmpty().isNotEmpty()
    }

    private fun handleRuntimeError(taskId: String, packageName: String, stackTrace: String) {
        val existing = pendingRuntimeErrors[taskId]
        if (existing?.stackTrace == stackTrace && existing.awaitingUserConfirmation) {
            return
        }

        val shouldReenterConversation = shouldReenterRuntimeErrorTask(taskId)
        if (shouldReenterConversation) {
            currentTaskId = taskId
            persistLastSelectedTaskId(taskId)
        }
        runtimeErrorTaskIds += taskId
        pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
            packageName = packageName,
            stackTrace = stackTrace,
            summary = "",
            awaitingUserConfirmation = true
        )
        persistPendingRuntimeErrors()

        addTaskEvent(
            taskId = taskId,
            message = ChatMessage(
                id = "runtime-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.LOG,
                title = getString(R.string.message_title_runtime),
                body = getString(R.string.runtime_error_body, packageName.ifBlank { "알 수 없는 앱" }),
                detail = stackTrace.take(500)
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
        if (shouldReenterConversation) {
            reenterTaskConversation(taskId)
        } else {
            renderState()
        }
        loadTaskList(autoSelectPendingTask = false)
        reportRuntimeErrorToServer(taskId, packageName, stackTrace)
        requestRuntimeErrorSummary(taskId, packageName, stackTrace)
    }

    private fun requestRuntimeErrorSummary(taskId: String, packageName: String, stackTrace: String) {
        lifecycleScope.launch {
            try {
                logTaskIdForApi("/runtime/error/summary", taskId)
                logApiRequest("/runtime/error/summary", taskId = taskId, deviceId = deviceId)
                val response = apiService.summarizeRuntimeError(
                    RuntimeErrorSummaryRequest(
                        task_id = taskId,
                        package_name = packageName,
                        stack_trace = stackTrace,
                        device_id = deviceId,
                        user_id = null,
                        phone_number = userIdentity.phoneNumber
                    )
                )
                val summary = response.summary.trim().ifBlank { summarizeRuntimeError(stackTrace) }
                pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
                    packageName = packageName,
                    stackTrace = stackTrace,
                    summary = summary,
                    awaitingUserConfirmation = true
                )
                persistPendingRuntimeErrors()
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "runtime-assistant-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.ASSISTANT,
                        title = getString(R.string.message_title_assistant),
                        body = response.assistant_message.trim().ifBlank {
                            getString(R.string.runtime_error_detected, summary)
                        }
                    )
                )
                renderState()
            } catch (e: Exception) {
                logApiFailure("/runtime/error/summary", taskId = taskId, deviceId = deviceId, throwable = e)
                val fallbackSummary = summarizeRuntimeError(stackTrace)
                pendingRuntimeErrors[taskId] = RuntimeErrorRecord(
                    packageName = packageName,
                    stackTrace = stackTrace,
                    summary = fallbackSummary,
                    awaitingUserConfirmation = true
                )
                persistPendingRuntimeErrors()
                appendOptimisticTaskMessage(
                    taskId,
                    ChatMessage(
                        id = "runtime-assistant-fallback-$taskId-${System.currentTimeMillis()}",
                        kind = MessageKind.ASSISTANT,
                        title = getString(R.string.message_title_assistant),
                        body = if (fallbackSummary.isBlank()) {
                            getString(R.string.runtime_error_detected_generic)
                        } else {
                            getString(R.string.runtime_error_detected, fallbackSummary)
                        }
                    )
                )
                renderState()
            }
        }
    }

    private fun persistPendingRuntimeErrors() {
        runCatching {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_PENDING_RUNTIME_ERRORS, gson.toJson(pendingRuntimeErrors))
                .apply()
        }.onFailure {
            Log.e(TAG, "Failed to persist pending runtime errors", it)
        }
    }

    private fun loadPersistedRuntimeErrors() {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_PENDING_RUNTIME_ERRORS, null)
            ?: return
        runCatching {
            val type = object : TypeToken<Map<String, RuntimeErrorRecord>>() {}.type
            gson.fromJson<Map<String, RuntimeErrorRecord>>(json, type).orEmpty()
        }.onSuccess { stored ->
            stored.forEach { (taskId, record) ->
                if (record.awaitingUserConfirmation) {
                    handleRuntimeError(taskId, record.packageName, record.stackTrace)
                } else {
                    pendingRuntimeErrors[taskId] = record
                    runtimeErrorTaskIds += taskId
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to restore pending runtime errors", it)
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
        return response.full_log.takeIf { it.isNotBlank() }
            ?: response.log.takeIf { it.isNotBlank() }
    }

    private fun animatableProcessingLabels(): Set<String> {
        return setOf(
            getString(R.string.status_generate_pending),
            getString(R.string.status_continue_pending),
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

    private fun appendOptimisticTaskMessage(taskId: String, message: ChatMessage) {
        appendTaskTimelineMessage(taskId, message)
        if (screenState.selectedTaskId == taskId) {
            screenState = screenState.copy(messages = buildTaskTimeline(taskId))
            renderState()
        }
    }

    private fun appendStatusTransitionMessage(taskId: String, response: StatusResponse) {
        if (!isCompactStatus(response.status)) return
        if (normalizeStatusKey(response.status) == "pending decision" && response.progress_mode.isBlank()) {
            return
        }

        val statusKey = buildStatusTransitionKey(response)
        val progressMode = response.progress_mode.takeIf { it.isNotBlank() }
        if (taskLastStatusKeys[taskId] == statusKey) return
        taskLastStatusKeys[taskId] = statusKey
        appendTaskTimelineMessage(
            taskId,
            ChatMessage(
                id = "status-$taskId-${System.currentTimeMillis()}",
                kind = MessageKind.STATUS,
                title = getString(R.string.message_title_status),
                body = resolveStatusDisplayText(response.status, response.status_display_text, progressMode),
                detail = response.package_name.ifBlank { null },
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
            canDownload = latestApkUrl != null && currentTaskId == normalizedTaskId,
            canInstall = latestDownloadedTaskId == normalizedTaskId && latestDownloadedApkFile?.exists() == true
        )
        renderState()
    }

    private fun buildStatusTransitionKey(response: StatusResponse): String {
        return listOf(
            normalizeStatusKey(response.status),
            response.package_name,
            response.apk_url,
            response.build_success.toString(),
            response.build_attempts.toString()
        ).joinToString("|")
    }

    private fun ChatMessage.sameContentAs(other: ChatMessage): Boolean {
        if (kind != other.kind) return false
        val sameBody = hasSameMessageText(body, other.body)
        val sameDetail = hasSameMessageText(detail, other.detail)
        return when (kind) {
            MessageKind.USER -> {
                val bothHaveImages = !imagePreviewBase64.isNullOrBlank() && !other.imagePreviewBase64.isNullOrBlank()
                sameBody && (!bothHaveImages || imagePreviewBase64 == other.imagePreviewBase64)
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

    private inner class TaskSummaryAdapter(
        private val onClick: (TaskSummary) -> Unit,
        private val onDelete: (TaskSummary) -> Unit
    ) : RecyclerView.Adapter<TaskSummaryAdapter.TaskViewHolder>() {

        private var items: List<TaskSummary> = emptyList()
        private var selectedTaskId: String? = null

        fun submitList(newItems: List<TaskSummary>, selectedTaskId: String?) {
            items = newItems
            this.selectedTaskId = selectedTaskId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task_summary, parent, false)
            return TaskViewHolder(view)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            holder.bind(items[position], items[position].taskId == selectedTaskId)
        }

        override fun getItemCount(): Int = items.size

        private inner class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val runtimeBadge: TextView = view.findViewById(R.id.taskRuntimeBadge)
            private val title: TextView = view.findViewById(R.id.taskTitle)
            private val subtitle: TextView = view.findViewById(R.id.taskSubtitle)
            private val status: TextView = view.findViewById(R.id.taskStatus)
            private val btnHideTask: ImageButton = view.findViewById(R.id.btnHideTask)

            fun bind(item: TaskSummary, selected: Boolean) {
                runtimeBadge.visibility = if (item.hasRuntimeError) View.VISIBLE else View.GONE
                title.text = item.title
                subtitle.text = listOfNotNull(item.subtitle, item.updatedAt).joinToString(" • ")
                status.text = if (item.hasApk) {
                    getString(R.string.task_status_with_apk, item.status)
                } else {
                    item.status
                }

                itemView.setBackgroundColor(
                    when {
                        selected && item.hasRuntimeError -> ContextCompat.getColor(this@MainActivity, R.color.task_runtime_error_bg_selected)
                        selected -> ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray)
                        item.hasRuntimeError -> ContextCompat.getColor(this@MainActivity, R.color.task_runtime_error_bg)
                        else -> ContextCompat.getColor(this@MainActivity, android.R.color.transparent)
                    }
                )
                val rowClickListener = View.OnClickListener { onClick(item) }
                itemView.setOnClickListener(rowClickListener)
                runtimeBadge.setOnClickListener(rowClickListener)
                title.setOnClickListener(rowClickListener)
                subtitle.setOnClickListener(rowClickListener)
                status.setOnClickListener(rowClickListener)
                btnHideTask.setOnClickListener { onDelete(item) }
            }
        }
    }

    private inner class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.ChatViewHolder>() {

        private var items: List<ChatMessage> = emptyList()

        fun submitList(newItems: List<ChatMessage>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        private inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val container: LinearLayout = view.findViewById(R.id.messageBubble)
            private val title: TextView = view.findViewById(R.id.messageTitle)
            private val body: TextView = view.findViewById(R.id.messageBody)
            private val imageLabel: TextView = view.findViewById(R.id.messageImageLabel)
            private val imagePreview: ImageView = view.findViewById(R.id.messageImagePreview)
            private val expandToggle: TextView = view.findViewById(R.id.messageExpandToggle)
            private val detail: TextView = view.findViewById(R.id.messageDetail)
            private val confirmationActions: LinearLayout = view.findViewById(R.id.messageConfirmationActions)
            private val btnConfirmationAccept: Button = view.findViewById(R.id.btnConfirmationAccept)
            private val btnConfirmationDismiss: Button = view.findViewById(R.id.btnConfirmationDismiss)
            private val timestamp: TextView = view.findViewById(R.id.messageTimestamp)

            fun bind(item: ChatMessage) {
                title.customSelectionActionModeCallback = messageSelectionActionModeCallback
                body.customSelectionActionModeCallback = messageSelectionActionModeCallback
                detail.customSelectionActionModeCallback = messageSelectionActionModeCallback
                title.text = item.title ?: ""
                title.visibility = if (item.title.isNullOrBlank()) View.GONE else View.VISIBLE
                body.text = item.body
                detail.text = item.detail
                detail.visibility = if (item.detail.isNullOrBlank()) View.GONE else View.VISIBLE
                val formattedTimestamp = formatMessageTimestamp(item.createdAt)
                timestamp.text = formattedTimestamp
                timestamp.visibility = if (formattedTimestamp.isNullOrBlank()) View.GONE else View.VISIBLE
                bindExpandableAssistantMessage(item)
                bindImagePreview(item)
                bindConfirmationActions(item)

                val params = container.layoutParams as LinearLayout.LayoutParams
                when (item.kind) {
                    MessageKind.USER -> {
                        params.gravity = Gravity.END
                        params.marginStart = dp(44)
                        params.marginEnd = 0
                        container.setBackgroundResource(R.drawable.bg_message_user)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_panel_alt))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_inverse))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_panel_alt))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bg_panel_alt))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_inverse))
                    }
                    MessageKind.ASSISTANT -> {
                        params.gravity = Gravity.START
                        params.marginStart = 0
                        params.marginEnd = dp(44)
                        container.setBackgroundResource(R.drawable.bg_message_assistant)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                    MessageKind.CONFIRMATION -> {
                        params.gravity = Gravity.START
                        params.marginStart = 0
                        params.marginEnd = dp(44)
                        container.setBackgroundResource(R.drawable.bg_message_assistant)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                    MessageKind.BUILD_LOG -> {
                        params.gravity = Gravity.CENTER_HORIZONTAL
                        params.marginStart = dp(56)
                        params.marginEnd = dp(56)
                        container.setBackgroundResource(R.drawable.bg_message_status)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_dark))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                    MessageKind.STATUS -> {
                        params.gravity = Gravity.CENTER_HORIZONTAL
                        params.marginStart = dp(72)
                        params.marginEnd = dp(72)
                        container.setBackgroundResource(R.drawable.bg_message_status)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_dark))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                    MessageKind.LOG -> {
                        params.gravity = Gravity.CENTER_HORIZONTAL
                        params.marginStart = dp(72)
                        params.marginEnd = dp(72)
                        container.setBackgroundResource(R.drawable.bg_message_log)
                        title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_dark))
                        body.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        imageLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        detail.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        timestamp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                }
                container.layoutParams = params
            }

            private fun bindExpandableAssistantMessage(item: ChatMessage) {
                val isLongAssistantMessage =
                    item.kind == MessageKind.ASSISTANT && item.body.length > ASSISTANT_MESSAGE_COLLAPSE_CHAR_THRESHOLD
                if (!isLongAssistantMessage) {
                    body.maxLines = Int.MAX_VALUE
                    body.ellipsize = null
                    expandToggle.visibility = View.GONE
                    expandToggle.setOnClickListener(null)
                    return
                }

                val isExpanded = expandedAssistantMessageIds.contains(item.id)
                body.maxLines = if (isExpanded) Int.MAX_VALUE else ASSISTANT_MESSAGE_COLLAPSED_MAX_LINES
                body.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
                expandToggle.visibility = View.VISIBLE
                expandToggle.text = if (isExpanded) "줄이기" else "더보기"
                expandToggle.setOnClickListener {
                    if (expandedAssistantMessageIds.contains(item.id)) {
                        expandedAssistantMessageIds.remove(item.id)
                    } else {
                        expandedAssistantMessageIds.add(item.id)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                }
            }

            private fun bindImagePreview(item: ChatMessage) {
                val hasImagePreview = !item.imagePreviewBase64.isNullOrBlank()
                if (!hasImagePreview) {
                    imageLabel.visibility = View.GONE
                    imagePreview.visibility = View.GONE
                    imagePreview.setImageDrawable(null)
                    return
                }

                imageLabel.text = item.imagePreviewName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { getString(R.string.reference_image_selected, it) }
                    ?: getString(R.string.reference_image_attached_message)
                imageLabel.visibility = View.VISIBLE
                bindInlineImagePreview(
                    imageView = imagePreview,
                    imageBase64 = item.imagePreviewBase64,
                    fallbackVisibility = View.GONE
                )
            }

            private fun bindConfirmationActions(item: ChatMessage) {
                if (item.kind != MessageKind.CONFIRMATION) {
                    confirmationActions.visibility = View.GONE
                    btnConfirmationAccept.setOnClickListener(null)
                    btnConfirmationDismiss.setOnClickListener(null)
                    return
                }

                val handled = handledConfirmationMessageIds.contains(item.id)
                confirmationActions.visibility = View.VISIBLE
                btnConfirmationAccept.isEnabled = !handled
                btnConfirmationDismiss.isEnabled = !handled
                btnConfirmationAccept.alpha = if (handled) 0.5f else 1.0f
                btnConfirmationDismiss.alpha = if (handled) 0.5f else 1.0f
                btnConfirmationAccept.setOnClickListener {
                    if (!handledConfirmationMessageIds.contains(item.id)) {
                        handleConfirmationAccepted(item)
                        bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { notifyItemChanged(it) }
                    }
                }
                btnConfirmationDismiss.setOnClickListener {
                    if (!handledConfirmationMessageIds.contains(item.id)) {
                        handleConfirmationDismissed(item)
                        bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { notifyItemChanged(it) }
                    }
                }
            }
        }
    }
}
