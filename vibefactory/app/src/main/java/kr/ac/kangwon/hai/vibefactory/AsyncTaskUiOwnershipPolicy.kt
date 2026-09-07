package kr.ac.kangwon.hai.vibefactory

internal object AsyncTaskUiOwnershipPolicy {
    fun requestStillOwnsVisibleSurface(
        sourceTaskId: String?,
        selectedTaskId: String?,
        requestSelectionGeneration: Long,
        currentSelectionGeneration: Long
    ): Boolean {
        if (requestSelectionGeneration != currentSelectionGeneration) return false
        return normalizedTaskId(sourceTaskId) == normalizedTaskId(selectedTaskId)
    }

    fun visibleTaskId(selectedTaskId: String?, currentTaskId: String?): String? {
        return normalizedTaskId(selectedTaskId) ?: normalizedTaskId(currentTaskId)
    }

    fun targetChangesVisibleTask(targetTaskId: String?, selectedTaskId: String?): Boolean {
        return normalizedTaskId(targetTaskId) != normalizedTaskId(selectedTaskId)
    }

    private fun normalizedTaskId(taskId: String?): String? {
        return taskId?.trim()?.takeIf { it.isNotBlank() }
    }
}
