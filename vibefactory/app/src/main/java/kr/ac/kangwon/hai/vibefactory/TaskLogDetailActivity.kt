package kr.ac.kangwon.hai.vibefactory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TaskLogDetailActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()
    private val preferencesStore by lazy {
        HostPreferencesStore(this, gson, "TaskLogDetailActivity")
    }
    private val apiService by lazy {
        createVibeApiService(gson = gson)
    }
    private val downloadApiService by lazy {
        createDownloadVibeApiService(gson = gson)
    }
    private var apkAction: TaskLogApkAction? = null
    private var downloadedApkFile: File? = null
    private var isDownloadingApk = false
    private var downloadingApkArtifactIdentity: String? = null
    private var boundPayload: TaskLogDetailPayload? = null
    private var revisionOptions: List<TaskRevisionDto> = emptyList()
    private var selectedRevision: TaskRevisionDto? = null
    private var isBranchingRevision = false
    private var pendingInstallApkFile: File? = null
    private var pendingInstallPackageName: String? = null
    private var pendingInstallPreviousSnapshot: InstalledPackageSnapshot? = null
    private var pendingInstallArtifactIdentity: String? = null
    private var installPermissionRequested = false
    private var installerLaunched = false
    private var installResolutionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_log_detail)
        applyRootSystemBarPadding()

        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
            ?.let { runCatching { gson.fromJson(it, TaskLogDetailPayload::class.java) }.getOrNull() }
            ?: emptyPayload()

        findViewById<ImageButton>(R.id.btnBackTaskLog).setOnClickListener { finish() }

        bindPayload(payload)
        refreshAgentLogsIfNeeded(payload)
    }

    override fun onResume() {
        super.onResume()
        when {
            installerLaunched -> resolveReturnedInstallerIfNeeded()
            installPermissionRequested &&
                ApkArtifactActionHandler.needsInstallPermission(this) -> clearTransientInstallState()
            else -> retryPendingApkInstallIfReady()
        }
    }

    private fun bindPayload(payload: TaskLogDetailPayload) {
        boundPayload = payload
        findViewById<TextView>(R.id.taskLogTitle).text = payload.title
        findViewById<TextView>(R.id.taskLogAppName).text = payload.appName
        findViewById<TextView>(R.id.taskLogStatusBadge).apply {
            text = payload.status
            setTextColor(
                getColor(
                    when (payload.statusTone) {
                        "error" -> R.color.task_runtime_badge_text
                        "success" -> R.color.accent_primary_dark
                        else -> R.color.text_secondary
                    }
                )
            )
        }
        findViewById<TextView>(R.id.taskLogMeta).text = buildString {
            if (payload.taskId.isNotBlank()) append("작업 ID ${payload.taskId.take(8)}\n")
            append("마지막 업데이트 ${payload.lastUpdated}")
        }
        bindRevisionSelector(payload, emptyList())
        loadRevisions(payload)
        bindApkAction(payload.apkAction)

        bindLogSections(payload)
    }

    private fun bindLogSections(payload: TaskLogDetailPayload) {
        val sections = findViewById<LinearLayout>(R.id.taskLogSectionsContainer)
        sections.removeAllViews()
        sections.addView(sectionCard("진행 단계", payload.progressItems))
        sections.addView(sectionCard("작업 메모", payload.agentItems))
    }

    private fun refreshAgentLogsIfNeeded(payload: TaskLogDetailPayload) {
        val taskId = payload.taskId.trim()
        if (taskId.isBlank() || payload.agentItems.isNotEmpty()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    apiService.getStatus(
                        taskId = taskId,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber(),
                        includeLogs = true,
                        includeTimeline = false
                    )
                }
            }
            result.onSuccess { response ->
                val agentItems = TaskLogDetailFormatter.agentItemsFromStatus(response)
                val currentPayload = boundPayload
                    ?.takeIf { it.taskId == taskId }
                    ?: return@onSuccess
                if (agentItems.isEmpty() || agentItems == currentPayload.agentItems) return@onSuccess
                val updatedPayload = currentPayload.copy(agentItems = agentItems)
                boundPayload = updatedPayload
                bindLogSections(updatedPayload)
            }.onFailure { error ->
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    getString(R.string.polling_failed, userVisibleErrorMessage(error)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadRevisions(payload: TaskLogDetailPayload) {
        val taskId = payload.taskId.trim()
        if (taskId.isBlank()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    apiService.getTaskRevisions(
                        taskId = taskId,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }
            result.onSuccess { response ->
                revisionOptions = response.revisions
                bindRevisionSelector(payload, revisionOptions)
            }.onFailure { error ->
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    getString(R.string.task_log_revision_load_failed, userVisibleErrorMessage(error)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun bindRevisionSelector(payload: TaskLogDetailPayload, revisions: List<TaskRevisionDto>) {
        val selector = findViewById<LinearLayout>(R.id.taskLogRevisionSelector)
        val visibleRevisions = revisions.filter { it.revision_label.isNotBlank() || it.version_name.isNotBlank() }
        if (visibleRevisions.isEmpty()) {
            selectedRevision = null
            selector.visibility = View.GONE
            selector.setOnClickListener(null)
            bindBranchAction(payload, null)
            return
        }
        val selected = selectedRevision
            ?.let { previous -> visibleRevisions.firstOrNull { it.revision_label == previous.revision_label } }
            ?: visibleRevisions.firstOrNull { it.is_current }
            ?: visibleRevisions.last()
        selector.visibility = View.VISIBLE
        selectRevision(payload, selected)
        selector.setOnClickListener {
            showRevisionMenu(selector, payload, visibleRevisions)
        }
    }

    private fun showRevisionMenu(anchor: View, payload: TaskLogDetailPayload, revisions: List<TaskRevisionDto>) {
        val menu = PopupMenu(this, anchor)
        revisions.forEachIndexed { index, revision ->
            menu.menu.add(0, index, index, revisionMenuLabel(revision))
        }
        menu.setOnMenuItemClickListener { item ->
            val revision = revisions.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            selectRevision(payload, revision)
            true
        }
        menu.show()
    }

    private fun selectRevision(payload: TaskLogDetailPayload, revision: TaskRevisionDto) {
        selectedRevision = revision
        findViewById<TextView>(R.id.taskLogRevisionValue).text = revisionSelectorText(revision)
        bindApkAction(apkActionForRevision(payload, revision))
        bindBranchAction(payload, revision)
    }

    private fun bindBranchAction(payload: TaskLogDetailPayload, revision: TaskRevisionDto?) {
        findViewById<Button>(R.id.btnTaskLogBranchRevision).apply {
            visibility = if (revision?.can_branch == true) View.VISIBLE else View.GONE
            isEnabled = revision?.can_branch == true && !isBranchingRevision
            text = getString(
                if (isBranchingRevision) {
                    R.string.task_log_branch_revision_progress
                } else {
                    R.string.task_log_branch_revision
                }
            )
            setOnClickListener {
                revision?.takeIf { it.can_branch }?.let { confirmBranchRevision(payload, it) }
            }
        }
    }

    private fun confirmBranchRevision(payload: TaskLogDetailPayload, revision: TaskRevisionDto) {
        if (isBranchingRevision) return
        AlertDialog.Builder(this)
            .setTitle(R.string.task_log_branch_confirm_title)
            .setMessage(
                getString(
                    R.string.task_log_branch_confirm_message,
                    revisionDisplayLabel(revision).replace(" · 현재", "")
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.task_log_branch_confirm_positive) { _, _ ->
                branchRevision(payload, revision)
            }
            .show()
    }

    private fun branchRevision(payload: TaskLogDetailPayload, revision: TaskRevisionDto) {
        val taskId = payload.taskId.trim()
        val revisionLabel = revision.revision_label.trim()
        if (taskId.isBlank() || revisionLabel.isBlank() || isBranchingRevision) return
        isBranchingRevision = true
        bindBranchAction(payload, revision)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    apiService.branchTaskRevision(
                        taskId = taskId,
                        revisionLabel = revisionLabel,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        userId = null,
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }
            result.onSuccess { response ->
                val branchedTaskId = response.task_id.trim()
                if (branchedTaskId.isBlank()) {
                    isBranchingRevision = false
                    bindBranchAction(payload, revision)
                    Toast.makeText(
                        this@TaskLogDetailActivity,
                        getString(R.string.task_log_branch_failed, "새 작업 ID가 없습니다."),
                        Toast.LENGTH_LONG
                    ).show()
                    return@onSuccess
                }
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    R.string.task_log_branch_created,
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(
                    Intent(this@TaskLogDetailActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SELECTED_TASK_ID, branchedTaskId)
                        .putExtra(MainActivity.EXTRA_BRANCHED_TASK_CREATED, true)
                        .putExtra(
                            MainActivity.EXTRA_BRANCHED_TASK_APP_NAME,
                            response.generated_app_name?.takeIf { it.isNotBlank() }
                                ?: response.app_name?.takeIf { it.isNotBlank() }
                                ?: payload.appName
                        )
                        .putExtra(MainActivity.EXTRA_BRANCHED_TASK_PACKAGE_NAME, response.package_name)
                        .putExtra(
                            MainActivity.EXTRA_BRANCHED_TASK_STATUS,
                            response.status_display_text?.takeIf { it.isNotBlank() } ?: response.status
                        )
                        .putExtra(
                            MainActivity.EXTRA_BRANCHED_TASK_MESSAGE,
                            response.status_message?.takeIf { it.isNotBlank() }
                                ?: getString(
                                    R.string.task_log_branch_chat_message,
                                    revisionDisplayLabel(revision).replace(" · 현재", "")
                                )
                        )
                        .putExtra(
                            MainActivity.EXTRA_BRANCHED_TASK_VERSION,
                            revisionDisplayLabel(revision).replace(" · 현재", "")
                        )
                        .putExtra(MainActivity.EXTRA_BRANCHED_TASK_CREATED_AT, response.created_at)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }.onFailure { error ->
                isBranchingRevision = false
                bindBranchAction(payload, revision)
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    getString(R.string.task_log_branch_failed, userVisibleErrorMessage(error)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun apkActionForRevision(payload: TaskLogDetailPayload, revision: TaskRevisionDto): TaskLogApkAction? {
        if (!revision.has_apk || revision.apk_path.isNullOrBlank()) return null
        val taskId = revision.task_id.ifBlank { payload.taskId }
        val version = revision.version_name.ifBlank { revision.revision_label }
        return TaskLogApkAction(
            taskId = taskId,
            title = "${payload.appName} $version APK",
            meta = listOf(
                version,
                revisionSourceLabel(revision.source),
                formatRevisionTimestamp(revision.created_at),
                formatBytes(revision.apk_size_bytes)
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · "),
            apkUrl = revision.apk_url?.takeIf { it.isNotBlank() } ?: "${HostAppConfig.BASE_URL}/download/$taskId",
            artifactPath = revision.apk_path,
            downloadedPath = null
        )
    }

    private fun revisionDisplayLabel(revision: TaskRevisionDto): String {
        val version = revision.version_name.ifBlank { revision.revision_label.ifBlank { "버전" } }
        return if (revision.is_current) "$version · 현재" else version
    }

    private fun revisionMenuLabel(revision: TaskRevisionDto): String {
        val summary = revision.request_summary.trim().let { value ->
            if (value.length <= 42) value else value.take(41).trimEnd() + "…"
        }
        return listOf(
            revisionDisplayLabel(revision),
            revisionSourceLabel(revision.source),
            formatRevisionTimestamp(revision.created_at),
            summary.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ")
    }

    private fun revisionSelectorText(revision: TaskRevisionDto): String {
        val typeAndTime = listOf(
            revisionSourceLabel(revision.source),
            formatRevisionTimestamp(revision.created_at)
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val summary = revision.request_summary.trim().let { value ->
            if (value.length <= 180) value else value.take(179).trimEnd() + "…"
        }
        return listOf(
            revisionDisplayLabel(revision),
            typeAndTime.takeIf { it.isNotBlank() },
            summary.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString("\n")
    }

    private fun revisionSourceLabel(source: String): String {
        return when (source.trim().lowercase()) {
            "new_app" -> "최초 생성"
            "existing_app_modification", "existing_app", "modification" -> "수정 요청"
            "runtime_repair", "repair" -> "실행 오류 복구"
            "branched_revision", "branch" -> "분기 생성"
            "current" -> "현재 버전"
            else -> "앱 버전"
        }
    }

    private fun formatRevisionTimestamp(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        val zone = ZoneId.of("Asia/Seoul")
        val dateTime = runCatching { Instant.parse(raw).atZone(zone) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone) }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(zone)
            }.getOrNull()
            ?: return ""
        return DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN).format(dateTime)
    }

    private fun formatBytes(value: Long?): String {
        val bytes = value ?: return ""
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    private fun bindApkAction(action: TaskLogApkAction?) {
        apkAction = action
        val card = findViewById<LinearLayout>(R.id.taskLogApkCard)
        if (action == null) {
            downloadedApkFile = null
            card.visibility = View.GONE
            return
        }

        downloadedApkFile = localApkFile(action)
        val actionIsDownloading = isDownloadingApk &&
            downloadingApkArtifactIdentity == artifactIdentity(action)
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.taskLogApkName).text = action.title
        findViewById<TextView>(R.id.taskLogApkMeta).text = action.meta
        findViewById<Button>(R.id.btnTaskLogApkDownload).apply {
            visibility = if (action.apkUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            isEnabled = !isDownloadingApk
            text = getString(
                if (actionIsDownloading) R.string.download_apk_in_progress else R.string.download_apk
            )
            setOnClickListener { downloadApk(action) }
        }
        findViewById<Button>(R.id.btnTaskLogApkInstall).apply {
            visibility = if (downloadedApkFile != null) View.VISIBLE else View.GONE
            isEnabled = !isDownloadingApk
            setOnClickListener {
                localApkFile(action)?.let { file -> installApk(file, action) }
                    ?: Toast.makeText(
                        this@TaskLogDetailActivity,
                        R.string.task_log_apk_missing,
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    private fun downloadApk(action: TaskLogApkAction) {
        if (isDownloadingApk) return
        isDownloadingApk = true
        downloadingApkArtifactIdentity = artifactIdentity(action)
        bindApkAction(action)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    ApkArtifactActionHandler.downloadToCache(
                        context = this@TaskLogDetailActivity,
                        apiService = downloadApiService,
                        taskId = action.taskId,
                        url = action.apkUrl,
                        artifactPath = action.artifactPath,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }
            isDownloadingApk = false
            downloadingApkArtifactIdentity = null
            result.onSuccess { file ->
                val updatedAction = action.copy(downloadedPath = file.absolutePath)
                Toast.makeText(this@TaskLogDetailActivity, R.string.status_downloaded, Toast.LENGTH_SHORT).show()
                val currentAction = apkAction
                if (currentAction != null && artifactsMatch(currentAction, action)) {
                    bindApkAction(updatedAction)
                    installApk(file, updatedAction)
                } else {
                    bindApkAction(currentAction)
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    getString(R.string.download_failed, userVisibleErrorMessage(error)),
                    Toast.LENGTH_SHORT
                ).show()
                bindApkAction(apkAction)
            }
        }
    }

    private fun installApk(file: File, action: TaskLogApkAction? = apkAction) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.task_log_apk_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val packageName = ApkArtifactActionHandler.packageNameFromApk(this, file)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingInstallPackageName = packageName
        pendingInstallPreviousSnapshot =
            ApkArtifactActionHandler.installedPackageSnapshot(this, packageName)
        pendingInstallArtifactIdentity = action?.let(::artifactIdentity)
        if (ApkArtifactActionHandler.needsInstallPermission(this)) {
            pendingInstallApkFile = file
            installPermissionRequested = true
            Toast.makeText(this, R.string.install_permission_required, Toast.LENGTH_LONG).show()
            if (!ApkArtifactActionHandler.requestInstallPermission(this)) {
                clearTransientInstallState()
                Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        pendingInstallApkFile = file
        installPermissionRequested = false
        if (ApkArtifactActionHandler.launchApkInstaller(this, file)) {
            installerLaunched = true
        } else {
            clearTransientInstallState()
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun artifactIdentity(action: TaskLogApkAction): String {
        return ApkArtifactActionHandler.artifactIdentity(
            action.taskId,
            action.apkUrl,
            action.artifactPath
        )
    }

    private fun artifactsMatch(first: TaskLogApkAction, second: TaskLogApkAction): Boolean {
        return ApkArtifactActionHandler.artifactsMatch(
            targetTaskId = first.taskId,
            targetUrl = first.apkUrl,
            targetArtifactPath = first.artifactPath,
            candidateTaskId = second.taskId,
            candidateUrl = second.apkUrl,
            candidateArtifactPath = second.artifactPath
        )
    }

    private fun resolveReturnedInstallerIfNeeded() {
        if (!installerLaunched || pendingInstallApkFile == null) return
        installResolutionJob?.cancel()
        installResolutionJob = lifecycleScope.launch {
            repeat(10) {
                delay(500L)
                val packageName = pendingInstallPackageName ?: return@launch
                val currentSnapshot =
                    ApkArtifactActionHandler.installedPackageSnapshot(
                        this@TaskLogDetailActivity,
                        packageName
                    )
                if (
                    GeneratedAppInstallPolicy.installationCompleted(
                        pendingInstallPreviousSnapshot,
                        currentSnapshot
                    )
                ) {
                    val launched = ApkArtifactActionHandler.launchInstalledPackage(
                        this@TaskLogDetailActivity,
                        packageName
                    )
                    ApkArtifactActionHandler.recordInstalledArtifact(
                        this@TaskLogDetailActivity,
                        packageName,
                        pendingInstallArtifactIdentity
                    )
                    installResolutionJob = null
                    clearTransientInstallState()
                    if (!launched) {
                        Toast.makeText(
                            this@TaskLogDetailActivity,
                            R.string.generated_app_launch_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
            }
            installResolutionJob = null
            clearTransientInstallState()
        }
    }

    private fun retryPendingApkInstallIfReady() {
        val file = pendingInstallApkFile ?: return
        if (!file.exists()) {
            clearTransientInstallState()
            Toast.makeText(this, R.string.task_log_apk_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ApkArtifactActionHandler.needsInstallPermission(this)) {
            installApk(file)
        }
    }

    private fun clearTransientInstallState() {
        val file = pendingInstallApkFile
        installResolutionJob?.cancel()
        installResolutionJob = null
        pendingInstallApkFile = null
        pendingInstallPackageName = null
        pendingInstallPreviousSnapshot = null
        pendingInstallArtifactIdentity = null
        installPermissionRequested = false
        installerLaunched = false
        ApkArtifactActionHandler.deleteTransientDownload(file)
        val action = apkAction ?: return
        bindApkAction(action.copy(downloadedPath = null))
    }

    private fun localApkFile(action: TaskLogApkAction): File? {
        return ApkArtifactActionHandler.localApkFile(
            context = this,
            taskId = action.taskId,
            url = action.apkUrl,
            artifactPath = action.artifactPath,
            downloadedPath = action.downloadedPath
        )
    }

    private fun userVisibleErrorMessage(error: Throwable): String {
        return error.message?.trim()?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun sectionCard(title: String, items: List<TaskLogDetailItem>): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_surface_card)
            setPadding(dp(18), dp(16), dp(18), dp(16))

            val content = LinearLayout(this@TaskLogDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.VISIBLE
            }
            val chevron = textView("▴", 16f, R.color.text_secondary, bold = true)
            val header = LinearLayout(this@TaskLogDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                addView(textView(title, 18f, R.color.text_primary, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(countChip("${items.size}개"))
                addView(chevron.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(10) }
                })
                setOnClickListener {
                    val willExpand = content.visibility != View.VISIBLE
                    content.visibility = if (willExpand) View.VISIBLE else View.GONE
                    chevron.text = if (willExpand) "▴" else "▾"
                }
            }
            addView(header)
            addView(content.apply {
                setPadding(0, dp(14), 0, 0)
                if (items.isEmpty()) {
                    addView(emptyText())
                } else {
                    items.forEach { addView(itemRow(it)) }
                }
            })
        }
    }

    private fun itemRow(item: TaskLogDetailItem): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_surface_alt)
            foreground = selectableItemForeground()
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { copyItem(item) }

            val meta = listOf(item.time, item.label).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) addView(textView(meta, 12f, R.color.text_secondary, bold = true))
            addView(textView(item.body, 15f, R.color.text_primary).apply {
                if (meta.isNotBlank()) setPadding(0, dp(6), 0, 0)
            })
            item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                addView(textView(detail, 13f, R.color.text_secondary).apply {
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }
    }

    private fun copyItem(item: TaskLogDetailItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(item.label.ifBlank { "작업 로그" }, itemCopyText(item)))
        Toast.makeText(this, R.string.task_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun itemCopyText(item: TaskLogDetailItem): String {
        return buildString {
            val meta = listOf(item.time, item.label).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) appendLine(meta)
            appendLine(item.body)
            item.detail?.takeIf { it.isNotBlank() }?.let(::appendLine)
        }.trim()
    }

    private fun emptyText(): TextView {
        return textView(getString(R.string.task_log_section_empty), 14f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_surface_alt)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
    }

    private fun countChip(value: String): TextView {
        return TextView(this).apply {
            setBackgroundResource(R.drawable.bg_library_status_chip)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            text = value
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun textView(value: String, sizeSp: Float, colorRes: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            setTextColor(getColor(colorRes))
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun selectableItemForeground(): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return getDrawable(typedValue.resourceId)
    }

    private fun emptyPayload(): TaskLogDetailPayload {
        val title = getString(R.string.task_log_detail_title)
        return TaskLogDetailPayload(
            title = title,
            appName = title,
            taskId = "",
            status = getString(R.string.status_unknown),
            statusTone = "running",
            lastUpdated = getString(R.string.task_log_time_missing),
            progressItems = emptyList(),
            agentItems = emptyList(),
            apkAction = null
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_PAYLOAD = "extra_task_log_payload"
    }
}
