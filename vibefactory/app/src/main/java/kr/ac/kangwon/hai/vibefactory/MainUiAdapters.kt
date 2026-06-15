package kr.ac.kangwon.hai.vibefactory

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
        private const val ASSISTANT_MESSAGE_COLLAPSE_CHAR_THRESHOLD = 220
        private const val ASSISTANT_MESSAGE_COLLAPSED_MAX_LINES = 8
    }

    private val expandedAssistantMessageIds = mutableSetOf<String>()

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
            val context = itemView.context
            val isArtifactCard = item.kind == MessageKind.STATUS && !item.artifactTaskId.isNullOrBlank()
            title.customSelectionActionModeCallback = messageSelectionActionModeCallback
            body.customSelectionActionModeCallback = messageSelectionActionModeCallback
            detail.customSelectionActionModeCallback = messageSelectionActionModeCallback
            title.text = item.title ?: ""
            title.visibility = View.GONE
            body.text = item.body
            detail.text = item.detail
            detail.visibility = if (item.detail.isNullOrBlank()) View.GONE else View.VISIBLE
            val formattedTimestamp = formatMessageTimestamp(item.createdAt)
            timestamp.text = formattedTimestamp
            timestamp.visibility = if (formattedTimestamp.isNullOrBlank()) View.GONE else View.VISIBLE
            bindExpandableAssistantMessage(item)
            bindImagePreview(item, context)
            bindConfirmationActions(item)
            bindArtifactActions(item, context)

            val blockParams = block.layoutParams as LinearLayout.LayoutParams
            val bubbleParams = container.layoutParams as LinearLayout.LayoutParams
            when (item.kind) {
                MessageKind.USER -> {
                    blockParams.gravity = Gravity.END
                    blockParams.marginStart = dp(context, 44)
                    blockParams.marginEnd = 0
                    bubbleParams.gravity = Gravity.END
                    bubbleParams.marginStart = 0
                    bubbleParams.marginEnd = 0
                    container.setBackgroundResource(R.drawable.bg_message_user)
                    title.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    imageLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    timestamp.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                MessageKind.ASSISTANT,
                MessageKind.CONFIRMATION -> {
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
            bindArtifactCard(item, context)
        }

        private fun bindArtifactCard(item: ChatMessage, context: android.content.Context) {
            val isArtifactCard = item.kind == MessageKind.STATUS && !item.artifactTaskId.isNullOrBlank()
            if (!isArtifactCard) {
                artifactCard.visibility = View.GONE
                artifactMeta.text = ""
                artifactName.text = ""
                title.visibility = if (item.title.isNullOrBlank()) View.GONE else View.VISIBLE
                body.visibility = View.VISIBLE
                body.maxLines = Int.MAX_VALUE
                body.ellipsize = null
                detail.visibility = if (item.detail.isNullOrBlank()) View.GONE else View.VISIBLE
                return
            }

            artifactCard.visibility = View.VISIBLE
            artifactName.text = item.body
            artifactMeta.text = item.detail ?: ""
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
            body.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
            expandToggle.visibility = View.VISIBLE
            expandToggle.text = if (isExpanded) "줄이기" else "더보기"
            expandToggle.setOnClickListener {
                if (expandedAssistantMessageIds.contains(item.id)) {
                    expandedAssistantMessageIds.remove(item.id)
                } else {
                    expandedAssistantMessageIds.add(item.id)
                }
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
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
            // Artifact actions are intentionally hidden in the current file-card layout.
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
