package kr.ac.kangwon.hai.vibefactory

data class QueuedTaskInput(
    val prompt: String,
    val imagePreview: ChatImagePreview?,
    val attachment: SelectedAttachment?
)

data class TaskInputQueueState(
    val selectedTaskId: String?,
    val currentTaskId: String?,
    val pollingTaskId: String?,
    val isPollingActive: Boolean,
    val inputMode: InputMode,
    val currentStatus: String,
    val statusDetail: String?
)

class TaskInputQueueManager(
    private val isProcessingStatus: (String) -> Boolean,
    private val loadingTaskStatus: String
) {
    private val queuedInputsByTask = mutableMapOf<String, ArrayDeque<QueuedTaskInput>>()

    fun enqueue(taskId: String, input: QueuedTaskInput): Int {
        val queue = queuedInputsByTask.getOrPut(taskId) { ArrayDeque() }
        queue.addLast(input)
        return queue.size
    }

    fun dequeueNext(taskId: String): QueuedTaskInput? {
        val queue = queuedInputsByTask[taskId] ?: return null
        if (queue.isEmpty()) {
            queuedInputsByTask.remove(taskId)
            return null
        }
        val nextInput = queue.removeFirst()
        if (queue.isEmpty()) {
            queuedInputsByTask.remove(taskId)
        }
        return nextInput
    }

    fun isQueueActive(taskId: String, state: TaskInputQueueState): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return false
        if (state.selectedTaskId != normalizedTaskId && state.currentTaskId != normalizedTaskId) return false
        if (state.pollingTaskId == normalizedTaskId || state.isPollingActive) return true
        if (state.inputMode != InputMode.READ_ONLY) return false
        return isProcessingStatus(state.currentStatus) ||
            isProcessingStatus(state.statusDetail.orEmpty()) ||
            state.currentStatus == loadingTaskStatus
    }
}
