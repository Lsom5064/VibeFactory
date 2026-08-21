package kr.ac.kangwon.hai.vibefactory

data class TaskRestoreSelection(
    val pendingTaskId: String?,
    val currentTaskId: String?,
    val selectedTaskId: String?,
    val lastSelectedTaskId: String?
)

object TaskRestorePolicy {
    fun removeMissingTask(
        missingTaskId: String,
        selection: TaskRestoreSelection
    ): TaskRestoreSelection {
        val normalizedMissingTaskId = missingTaskId.trim()
        if (normalizedMissingTaskId.isBlank()) return selection

        fun keepUnlessMissing(candidate: String?): String? {
            return candidate?.takeUnless { it.trim() == normalizedMissingTaskId }
        }

        return TaskRestoreSelection(
            pendingTaskId = keepUnlessMissing(selection.pendingTaskId),
            currentTaskId = keepUnlessMissing(selection.currentTaskId),
            selectedTaskId = keepUnlessMissing(selection.selectedTaskId),
            lastSelectedTaskId = keepUnlessMissing(selection.lastSelectedTaskId)
        )
    }
}
