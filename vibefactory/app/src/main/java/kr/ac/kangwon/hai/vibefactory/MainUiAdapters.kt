package kr.ac.kangwon.hai.vibefactory

import android.content.Intent
import android.text.TextUtils
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TaskSummaryAdapter(
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

    class TaskViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        private val runtimeBadge: TextView = view.findViewById(R.id.taskRuntimeBadge)
        private val title: TextView = view.findViewById(R.id.taskTitle)
        private val subtitle: TextView = view.findViewById(R.id.taskSubtitle)
        private val status: TextView = view.findViewById(R.id.taskStatus)
        private val btnHideTask: ImageButton = view.findViewById(R.id.btnHideTask)

        fun bind(item: TaskSummary, selected: Boolean) {
            val context = itemView.context
            runtimeBadge.visibility = if (item.hasRuntimeError) View.VISIBLE else View.GONE
            title.text = item.title
            subtitle.text = listOfNotNull(item.subtitle, item.updatedAt).joinToString(" • ")
            status.text = if (item.hasApk) {
                context.getString(R.string.task_status_with_apk, item.status)
            } else {
                item.status
            }

            itemView.setBackgroundColor(
                when {
                    selected && item.hasRuntimeError -> ContextCompat.getColor(context, R.color.task_runtime_error_bg_selected)
                    selected -> ContextCompat.getColor(context, R.color.drawer_history_item_selected)
                    item.hasRuntimeError -> ContextCompat.getColor(context, R.color.task_runtime_error_bg)
                    else -> ContextCompat.getColor(context, android.R.color.transparent)
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

        private val onClick: (TaskSummary) -> Unit
            get() = (bindingAdapter as TaskSummaryAdapter).onClick

        private val onDelete: (TaskSummary) -> Unit
            get() = (bindingAdapter as TaskSummaryAdapter).onDelete
    }
}

class ChatMessageAdapter(
    private val messageSelectionActionModeCallback: ActionMode.Callback,
    private val formatMessageTimestamp: (String?) -> String?,
    private val isConfirmationHandled: (String) -> Boolean,
    private val onConfirmationAccept: (ChatMessage) -> Unit,
    private val onConfirmationDismiss: (ChatMessage) -> Unit,
    private val onArtifactDownload: (ChatMessage) -> Unit,
    private val onArtifactInstall: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, ChatMessageAdapter.ChatViewHolder>(ChatMessageDiffCallback) {
    companion object {
        private const val CHAT_MESSAGE_COLLAPSE_CHAR_THRESHOLD = 420
        private const val CHAT_MESSAGE_COLLAPSED_MAX_LINES = 14
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val block: LinearLayout = view.findViewById(R.id.messageBlock)
        private val container: LinearLayout = view.findViewById(R.id.messageBubble)
        private val artifactCard: LinearLayout = view.findViewById(R.id.messageArtifactCard)
        private val artifactMeta: TextView = view.findViewById(R.id.messageArtifactMeta)
        private val artifactName: TextView = view.findViewById(R.id.messageArtifactName)
        private val artifactAction: TextView = view.findViewById(R.id.messageArtifactAction)
        private val title: TextView = view.findViewById(R.id.messageTitle)
        private val body: TextView = view.findViewById(R.id.messageBody)
        private val loadingRow: LinearLayout = view.findViewById(R.id.messageLoadingRow)
        private val loadingText: TextView = view.findViewById(R.id.messageLoadingText)
        private val imageLabel: TextView = view.findViewById(R.id.messageImageLabel)
        private val imagePreview: ImageView = view.findViewById(R.id.messageImagePreview)
        private val expandToggle: TextView = view.findViewById(R.id.messageExpandToggle)
        private val detail: TextView = view.findViewById(R.id.messageDetail)
        private val confirmationActions: LinearLayout = view.findViewById(R.id.messageConfirmationActions)
        private val btnConfirmationAccept: Button = view.findViewById(R.id.btnConfirmationAccept)
        private val btnConfirmationDismiss: Button = view.findViewById(R.id.btnConfirmationDismiss)
        private val timestamp: TextView = view.findViewById(R.id.messageTimestamp)

        fun bind(item: ChatMessage) {
            val context = itemView.context
            val isArtifactCard = item.kind == MessageKind.STATUS && !item.artifactTaskId.isNullOrBlank()
            title.customSelectionActionModeCallback = messageSelectionActionModeCallback
            body.customSelectionActionModeCallback = messageSelectionActionModeCallback
            detail.customSelectionActionModeCallback = messageSelectionActionModeCallback
            title.text = ""
            title.visibility = View.GONE
            bindLoadingState(item, context)
            detail.text = item.detail
            detail.visibility = if (item.detail.isNullOrBlank()) View.GONE else View.VISIBLE
            val formattedTimestamp = formatMessageTimestamp(item.createdAt)
            timestamp.text = formattedTimestamp
            timestamp.visibility = if (formattedTimestamp.isNullOrBlank()) View.GONE else View.VISIBLE
            bindArtifactCard(item, context)
            bindExpandableChatMessage(item)
            bindImagePreview(item, context)
            bindConfirmationActions(item)
            bindArtifactActions(item, context)

            val blockParams = block.layoutParams as LinearLayout.LayoutParams
            val bubbleParams = container.layoutParams as LinearLayout.LayoutParams
            when (item.kind) {
                MessageKind.USER -> {
                    container.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                    blockParams.gravity = Gravity.END
                    blockParams.marginStart = dp(context, 44)
                    blockParams.marginEnd = 0
                    bubbleParams.gravity = Gravity.END
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(R.drawable.bg_message_user)
                    title.setTextColor(ContextCompat.getColor(context, R.color.user_bubble_meta))
                    body.setTextColor(ContextCompat.getColor(context, R.color.user_bubble_text))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.user_bubble_meta))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.user_bubble_meta))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.user_bubble_meta))
                }
                MessageKind.ASSISTANT,
                MessageKind.CONFIRMATION -> {
                    container.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    blockParams.gravity = Gravity.START
                    blockParams.marginStart = 0
                    blockParams.marginEnd = dp(context, 44)
                    bubbleParams.gravity = Gravity.START
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(R.drawable.bg_message_assistant)
                    title.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                MessageKind.BUILD_LOG -> {
                    container.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    blockParams.gravity = Gravity.START
                    blockParams.marginStart = 0
                    blockParams.marginEnd = dp(context, 56)
                    bubbleParams.gravity = Gravity.START
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(R.drawable.bg_message_status)
                    title.setTextColor(ContextCompat.getColor(context, R.color.accent_primary_dark))
                    body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                MessageKind.STATUS -> {
                    container.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    blockParams.gravity = Gravity.START
                    blockParams.marginStart = 0
                    blockParams.marginEnd = dp(context, 72)
                    bubbleParams.gravity = Gravity.START
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(
                        if (isArtifactCard) R.drawable.bg_message_assistant else R.drawable.bg_message_status
                    )
                    title.setTextColor(ContextCompat.getColor(context, R.color.accent_primary_dark))
                    body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                MessageKind.LOG -> {
                    container.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    blockParams.gravity = Gravity.START
                    blockParams.marginStart = 0
                    blockParams.marginEnd = dp(context, 72)
                    bubbleParams.gravity = Gravity.START
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(R.drawable.bg_message_log)
                    title.setTextColor(ContextCompat.getColor(context, R.color.accent_primary_dark))
                    body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
            }
            block.layoutParams = blockParams
            container.layoutParams = bubbleParams
        }

        private fun bindLoadingState(item: ChatMessage, context: android.content.Context) {
            if (item.isLoading) {
                body.visibility = View.GONE
                loadingRow.visibility = View.VISIBLE
                loadingText.text = item.body
            } else {
                loadingRow.visibility = View.GONE
                body.visibility = View.VISIBLE
                body.text = chatMessageBodyText(item, context)
            }
        }

        private fun chatMessageBodyText(item: ChatMessage, context: android.content.Context): CharSequence {
            return if (item.kind == MessageKind.ASSISTANT || item.kind == MessageKind.CONFIRMATION) {
                ChatMarkdownRenderer.render(context, item.body)
            } else {
                item.body
            }
        }

        private fun bindArtifactCard(item: ChatMessage, context: android.content.Context) {
            val isArtifactCard = item.kind == MessageKind.STATUS && !item.artifactTaskId.isNullOrBlank()
            if (!isArtifactCard) {
                artifactCard.visibility = View.GONE
                artifactMeta.text = ""
                artifactName.text = ""
                artifactAction.text = ""
                title.visibility = View.GONE
                body.visibility = if (item.isLoading) View.GONE else View.VISIBLE
                body.maxLines = Int.MAX_VALUE
                body.ellipsize = null
                detail.visibility = if (item.detail.isNullOrBlank()) View.GONE else View.VISIBLE
                return
            }

            artifactCard.visibility = View.VISIBLE
            artifactName.text = artifactDisplayTitle(item)
            artifactMeta.text = artifactDisplayMeta(item, context)
            artifactAction.visibility = View.VISIBLE
            artifactAction.text = when {
                item.artifactDownloading -> context.getString(R.string.download_apk_in_progress)
                item.artifactCanInstall -> context.getString(R.string.install_apk)
                else -> context.getString(R.string.artifact_download_action)
            }
            imageLabel.visibility = View.GONE
            imagePreview.visibility = View.GONE
            imagePreview.setImageDrawable(null)
            title.visibility = View.GONE
            title.text = ""
            body.visibility = View.GONE
            body.maxLines = 1
            body.ellipsize = TextUtils.TruncateAt.END
            detail.visibility = View.GONE
            timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        private fun artifactDisplayTitle(item: ChatMessage): String {
            val revision = artifactRevisionLabel(item)
            val rawName = item.body
                .trim()
                .removeSuffix(".apk")
                .removeSuffix(".APK")
                .replace(Regex("""[-\s]+v\d+$""", RegexOption.IGNORE_CASE), "")
                .ifBlank { "APK" }
            return "$rawName $revision"
        }

        private fun artifactDisplayMeta(item: ChatMessage, context: android.content.Context): String {
            val revision = artifactRevisionLabel(item)
            val versionNumber = revision.removePrefix("v").toIntOrNull() ?: 1
            val versionKind = if (versionNumber <= 1) "최초 생성" else "수정본"
            val existingParts = item.detail
                .orEmpty()
                .split("·")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { it.equals("apk", ignoreCase = true) }
                .filterNot { it.matches(Regex("""v\d+""", RegexOption.IGNORE_CASE)) }
                .filterNot { it == "최초 생성" || it == "수정본" }
            return listOf(revision, versionKind, context.getString(R.string.library_file_type_apk))
                .plus(existingParts)
                .joinToString(" · ")
        }

        private fun artifactRevisionLabel(item: ChatMessage): String {
            item.artifactRevisionLabel
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { label ->
                    label.removePrefix("build-").toIntOrNull()?.let { return "v$it" }
                    if (label.matches(Regex("""v\d+""", RegexOption.IGNORE_CASE))) return label.lowercase()
                }
            Regex("""(?:^|/)rev_0*(\d+)(?:/|$)""")
                .find(item.artifactApkPath.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return "v$it" }
            item.artifactBuildAttempt?.takeIf { it > 0 }?.let { return "v$it" }
            return "v1"
        }

        private fun bindExpandableChatMessage(item: ChatMessage) {
            val supportsExpansion = item.kind == MessageKind.USER ||
                item.kind == MessageKind.ASSISTANT ||
                item.kind == MessageKind.CONFIRMATION
            val isLongChatMessage = supportsExpansion && item.body.length > CHAT_MESSAGE_COLLAPSE_CHAR_THRESHOLD
            if (!isLongChatMessage) {
                body.maxLines = Int.MAX_VALUE
                body.ellipsize = null
                expandToggle.visibility = View.GONE
                expandToggle.setOnClickListener(null)
                return
            }

            body.maxLines = CHAT_MESSAGE_COLLAPSED_MAX_LINES
            body.ellipsize = TextUtils.TruncateAt.END
            expandToggle.visibility = View.VISIBLE
            expandToggle.text = "전체보기"
            expandToggle.contentDescription = "전체보기"
            expandToggle.setOnClickListener {
                val context = itemView.context
                context.startActivity(
                    Intent(context, FullMessageActivity::class.java)
                        .putExtra(FullMessageActivity.EXTRA_BODY, item.body)
                )
            }
        }

        private fun bindImagePreview(item: ChatMessage, context: android.content.Context) {
            val hasImagePreview = !item.imagePreviewBase64.isNullOrBlank()
            if (!hasImagePreview) {
                imageLabel.visibility = View.GONE
                imagePreview.visibility = View.GONE
                imagePreview.setImageDrawable(null)
                return
            }

            imageLabel.text = item.imagePreviewName
                ?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.reference_image_selected, it) }
                ?: context.getString(R.string.reference_image_attached_message)
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

            val handled = isConfirmationHandled(item.id)
            confirmationActions.visibility = View.VISIBLE
            btnConfirmationAccept.isEnabled = !handled
            btnConfirmationDismiss.isEnabled = !handled
            btnConfirmationAccept.alpha = if (handled) 0.5f else 1.0f
            btnConfirmationDismiss.alpha = if (handled) 0.5f else 1.0f
            btnConfirmationAccept.setOnClickListener {
                if (!isConfirmationHandled(item.id)) {
                    onConfirmationAccept(item)
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
                }
            }
            btnConfirmationDismiss.setOnClickListener {
                if (!isConfirmationHandled(item.id)) {
                    onConfirmationDismiss(item)
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
                }
            }
        }

        private fun bindArtifactActions(item: ChatMessage, context: android.content.Context) {
            val isArtifactCard = item.kind == MessageKind.STATUS && !item.artifactTaskId.isNullOrBlank()
            if (!isArtifactCard) {
                artifactCard.setOnClickListener(null)
                artifactAction.setOnClickListener(null)
                artifactCard.isClickable = false
                artifactAction.isClickable = false
                artifactCard.isEnabled = true
                artifactAction.isEnabled = true
                artifactCard.alpha = 1.0f
                artifactAction.alpha = 1.0f
                return
            }

            if (item.artifactDownloading) {
                artifactCard.setOnClickListener(null)
                artifactAction.setOnClickListener(null)
                artifactCard.isClickable = false
                artifactAction.isClickable = false
                artifactCard.isEnabled = false
                artifactAction.isEnabled = false
                artifactCard.alpha = 0.72f
                artifactAction.alpha = 1.0f
                return
            }

            val clickListener = View.OnClickListener {
                if (item.artifactCanInstall) {
                    onArtifactInstall(item)
                } else {
                    onArtifactDownload(item)
                }
            }
            artifactCard.isEnabled = true
            artifactAction.isEnabled = true
            artifactCard.alpha = 1.0f
            artifactAction.alpha = 1.0f
            artifactCard.isClickable = true
            artifactAction.isClickable = true
            artifactCard.setOnClickListener(clickListener)
            artifactAction.setOnClickListener(clickListener)
        }
    }
}

internal object ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }
}

private fun dp(context: android.content.Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
}
