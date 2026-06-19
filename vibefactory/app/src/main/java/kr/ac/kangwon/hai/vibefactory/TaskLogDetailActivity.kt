package kr.ac.kangwon.hai.vibefactory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TaskLogDetailActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()
    private val preferencesStore by lazy {
        HostPreferencesStore(this, gson, "TaskLogDetailActivity")
    }
    private val apiService by lazy {
        createVibeApiService(gson = gson)
    }
    private var apkAction: TaskLogApkAction? = null
    private var downloadedApkFile: File? = null
    private var isDownloadingApk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_log_detail)
        applyRootSystemBarPadding()

        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
            ?.let { runCatching { gson.fromJson(it, TaskLogDetailPayload::class.java) }.getOrNull() }
            ?: emptyPayload()

        findViewById<ImageButton>(R.id.btnBackTaskLog).setOnClickListener { finish() }

        bindPayload(payload)
    }

    private fun bindPayload(payload: TaskLogDetailPayload) {
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
            if (payload.taskId.isNotBlank()) append("작업 ID ${payload.taskId}\n")
            append("마지막 업데이트 ${payload.lastUpdated}")
        }
        bindApkAction(payload.apkAction)

        val sections = findViewById<LinearLayout>(R.id.taskLogSectionsContainer)
        sections.removeAllViews()
        sections.addView(sectionCard("진행 단계", payload.progressItems))
        sections.addView(sectionCard("작업 메모", payload.agentItems))
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
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.taskLogApkName).text = action.title
        findViewById<TextView>(R.id.taskLogApkMeta).text = action.meta
        findViewById<Button>(R.id.btnTaskLogApkDownload).apply {
            visibility = if (action.apkUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            isEnabled = !isDownloadingApk
            text = getString(if (isDownloadingApk) R.string.download_apk_in_progress else R.string.download_apk)
            setOnClickListener { downloadApk(action) }
        }
        findViewById<Button>(R.id.btnTaskLogApkInstall).apply {
            visibility = if (downloadedApkFile != null) View.VISIBLE else View.GONE
            isEnabled = !isDownloadingApk
            setOnClickListener {
                localApkFile(action)?.let(::installApk)
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
        bindApkAction(action)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApkArtifactActionHandler.downloadToCache(
                        context = this@TaskLogDetailActivity,
                        apiService = apiService,
                        taskId = action.taskId,
                        url = action.apkUrl,
                        artifactPath = action.artifactPath,
                        deviceId = preferencesStore.getOrCreateDeviceId(),
                        phoneNumber = preferencesStore.loadPhoneNumber()
                    )
                }
            }
            isDownloadingApk = false
            result.onSuccess { file ->
                val updatedAction = action.copy(downloadedPath = file.absolutePath)
                Toast.makeText(this@TaskLogDetailActivity, R.string.status_downloaded, Toast.LENGTH_SHORT).show()
                bindApkAction(updatedAction)
            }.onFailure { error ->
                Toast.makeText(
                    this@TaskLogDetailActivity,
                    getString(R.string.download_failed, userVisibleErrorMessage(error)),
                    Toast.LENGTH_SHORT
                ).show()
                bindApkAction(action)
            }
        }
    }

    private fun installApk(file: File) {
        if (!ApkArtifactActionHandler.installApk(this, file)) {
            Toast.makeText(this, R.string.task_log_apk_missing, Toast.LENGTH_SHORT).show()
        }
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
