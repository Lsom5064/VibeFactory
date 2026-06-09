package kr.ac.kangwon.hai.vibefactory

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.Locale

class LibraryActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        findViewById<ImageButton>(R.id.btnBackLibrary).setOnClickListener { finish() }
        bindSnapshot(loadSnapshot())
    }

    private fun loadSnapshot(): LibrarySnapshot? {
        val snapshotFile = File(getExternalFilesDir(null), SNAPSHOT_FILE_NAME)
        if (!snapshotFile.exists()) return null
        return runCatching {
            gson.fromJson(snapshotFile.readText(), LibrarySnapshot::class.java)
        }.getOrNull()
    }

    private fun bindSnapshot(snapshot: LibrarySnapshot?) {
        val appVersionsContainer = findViewById<LinearLayout>(R.id.libraryAppVersionsContainer)
        val appVersionsEmpty = findViewById<TextView>(R.id.libraryAppVersionsEmpty)
        val attachmentsContainer = findViewById<LinearLayout>(R.id.libraryAttachmentsContainer)
        val attachmentsEmpty = findViewById<TextView>(R.id.libraryAttachmentsEmpty)

        appVersionsContainer.removeAllViews()
        attachmentsContainer.removeAllViews()

        val appVersions = snapshot?.appVersions.orEmpty()
        appVersionsEmpty.visibility = if (appVersions.isEmpty()) TextView.VISIBLE else TextView.GONE
        appVersions
            .groupBy { it.title.trim().ifBlank { it.title } }
            .values
            .forEach { items ->
                appVersionsContainer.addView(createLibraryRow(items))
            }

        val attachments = snapshot?.attachments.orEmpty()
        attachmentsEmpty.visibility = if (attachments.isEmpty()) TextView.VISIBLE else TextView.GONE
        attachments.forEach { item ->
            attachmentsContainer.addView(createAttachmentRow(item))
        }
    }

    private fun createLibraryRow(items: List<LibraryItem>): LinearLayout {
        val sortedItems = items.sortedByDescending { it.createdAt.orEmpty() }
        val headerItem = sortedItems.firstOrNull() ?: return LinearLayout(this)
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_library_item)
            setPadding(dp(16), dp(16), dp(16), dp(18))

            val content = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

            addView(dropdownHeader(headerItem, content, sortedItems.size))
            addView(content.withTopMargin(14))

            sortedItems.forEachIndexed { index, item ->
                if (index > 0) content.addView(sectionDivider())
                content.addView(createVersionContent(item))
            }
        }
    }

    private fun createVersionContent(item: LibraryItem): LinearLayout {
        val detail = item.detail?.trim().orEmpty()
        val filePath = detail.substringAfter("APK:", detail).trim()
        val fileName = filePath.substringAfterLast('/').takeIf { it.isNotBlank() }
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL

            val versionName = item.versionName?.trim()?.takeIf { it.isNotBlank() }
            val sizeLabel = fileSizeLabel(item, filePath, fileName.orEmpty())
            if (!fileName.isNullOrBlank()) {
                addView(fileTile(fileName, sizeLabel))
            }

            addView(
                metaLine(getString(R.string.library_version_label), versionName ?: getString(R.string.token_usage_value_unavailable))
                    .withTopMargin(14)
            )
            item.createdAt?.trim()?.takeIf { it.isNotBlank() }?.let(::formatCreatedAt)?.let { timestamp ->
                addView(metaLine(getString(R.string.library_created_label), timestamp).withTopMargin(6))
            }
        }
    }

    private fun sectionDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(18)
            }
            setBackgroundColor(getColor(R.color.divider_soft))
        }
    }

    private fun createAttachmentRow(item: LibraryItem): LinearLayout {
        val fileName = item.title.substringAfterLast('/').takeIf { it.isNotBlank() } ?: item.title
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_library_file_tile)
            setPadding(dp(12), dp(12), dp(12), dp(12))

            addView(ImageView(this@LibraryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                setBackgroundResource(R.drawable.bg_artifact_icon)
                setImageResource(R.drawable.ic_library_attachment)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_inverse))
                setPadding(dp(9), dp(9), dp(9), dp(9))
            })

            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
                addView(textView(fileName, 15f, R.color.text_primary, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                item.createdAt?.trim()?.takeIf { it.isNotBlank() }?.let(::formatCreatedAt)?.let { timestamp ->
                    addView(textView(timestamp, 12f, R.color.text_secondary).withTopMargin(3))
                }
            })
        }
    }

    private fun dropdownHeader(item: LibraryItem, content: View, versionCount: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true

            addView(textView(item.title, 18f, R.color.text_primary, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            addView(statusChip("${versionCount}개 버전"))

            val chevron = textView("▾", 18f, R.color.text_secondary, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(10)
                }
            }
            addView(chevron)

            setOnClickListener {
                val willExpand = content.visibility != View.VISIBLE
                content.visibility = if (willExpand) View.VISIBLE else View.GONE
                chevron.text = if (willExpand) "▴" else "▾"
            }
        }
    }

    private fun textView(value: String, sizeSp: Float, colorRes: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            setTextColor(getColor(colorRes))
            textSize = sizeSp
            if (bold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun metaLine(label: String, value: String): TextView {
        return textView("$label : $value", 13f, R.color.text_secondary).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun statusChip(status: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_library_status_chip)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            text = status
            setTextColor(getColor(R.color.accent_primary_dark))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun formatCreatedAt(value: String): String {
        val dateTime = value.substringBefore('+').substringBefore('Z')
        val date = dateTime.substringBefore('T', dateTime).replace("-", ".")
        val time = dateTime.substringAfter('T', "").take(5)
        return if (time.isBlank()) date else "$date $time"
    }

    private fun fileTile(fileName: String, fileSizeLabel: String?): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_library_file_tile)
            setPadding(dp(14), dp(14), dp(14), dp(14))

            addView(ImageView(this@LibraryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setBackgroundResource(R.drawable.bg_artifact_icon)
                setImageResource(R.drawable.ic_artifact_file)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_inverse))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })

            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
                addView(textView(fileName, 15f, R.color.text_primary, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(textView(apkMetaLabel(fileSizeLabel), 12f, R.color.text_secondary).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
                })
            })
        }
    }

    private fun apkMetaLabel(fileSizeLabel: String?): String {
        return listOfNotNull(
            getString(R.string.library_file_type_apk),
            fileSizeLabel
        ).joinToString(" · ")
    }

    private fun fileSizeLabel(item: LibraryItem, filePath: String, fileName: String): String? {
        item.fileSizeLabel?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        item.fileSizeBytes?.takeIf { it > 0L }?.let { return formatFileSize(it) }
        fileSizeTextFromMetadata(item)?.let { return it }
        return fileSizeFromLocalCandidates(filePath, fileName)?.let(::formatFileSize)
    }

    private fun fileSizeTextFromMetadata(item: LibraryItem): String? {
        return listOfNotNull(item.detail, item.subtitle)
            .firstNotNullOfOrNull { value ->
                FILE_SIZE_PATTERN.find(value)?.value?.replace(Regex("\\s+"), " ")?.trim()
            }
    }

    private fun fileSizeFromLocalCandidates(filePath: String, fileName: String): Long? {
        val candidates = listOfNotNull(
            File(filePath),
            externalCacheDir?.let { File(it, fileName) },
            getExternalFilesDir(null)?.let { File(it, fileName) },
            cacheDir?.let { File(it, fileName) },
            filesDir?.let { File(it, fileName) }
        )
        return candidates.firstOrNull { it.exists() && it.isFile }?.length()?.takeIf { it > 0L }
    }

    private fun formatFileSize(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun <T : View> T.withTopMargin(marginDp: Int): T {
        val currentParams = layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        currentParams.topMargin = dp(marginDp)
        layoutParams = currentParams
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class LibrarySnapshot(
        val appVersions: List<LibraryItem> = emptyList(),
        val attachments: List<LibraryItem> = emptyList()
    )

    data class LibraryItem(
        val title: String,
        val subtitle: String? = null,
        val detail: String? = null,
        val createdAt: String? = null,
        val packageName: String? = null,
        @SerializedName(
            value = "versionName",
            alternate = ["version_name", "appVersion", "app_version"]
        )
        val versionName: String? = null,
        @SerializedName(
            value = "fileSizeBytes",
            alternate = ["file_size_bytes", "sizeBytes", "size_bytes", "apkSizeBytes", "apk_size_bytes"]
        )
        val fileSizeBytes: Long? = null,
        @SerializedName(
            value = "fileSizeLabel",
            alternate = ["file_size_label", "sizeLabel", "size_label", "apkSizeLabel", "apk_size_label"]
        )
        val fileSizeLabel: String? = null
    )

    companion object {
        private const val SNAPSHOT_FILE_NAME = "library_snapshot.json"
        private val FILE_SIZE_PATTERN = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:B|KB|MB|GB|bytes?)\\b", RegexOption.IGNORE_CASE)
    }
}
