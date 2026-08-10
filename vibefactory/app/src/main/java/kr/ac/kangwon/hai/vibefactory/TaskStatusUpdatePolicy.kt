package kr.ac.kangwon.hai.vibefactory

internal object TaskStatusUpdatePolicy {
    fun targetsVisibleTask(taskId: String, selectedTaskId: String?): Boolean {
        val normalizedTaskId = taskId.trim()
        return normalizedTaskId.isNotBlank() && normalizedTaskId == selectedTaskId?.trim()
    }
}
