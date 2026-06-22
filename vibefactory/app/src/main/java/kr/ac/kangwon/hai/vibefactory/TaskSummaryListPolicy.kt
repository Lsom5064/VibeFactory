package kr.ac.kangwon.hai.vibefactory

object TaskSummaryListPolicy {
    fun visibleTaskIdOrNull(rawTaskId: String?, hiddenTaskIds: Set<String>): String? {
        val taskId = rawTaskId?.trim().orEmpty()
        return taskId.takeIf { it.isNotBlank() && it !in hiddenTaskIds }
    }

    fun upsert(list: List<TaskSummary>, summary: TaskSummary): List<TaskSummary> {
        return (list.filterNot { it.taskId == summary.taskId } + summary)
            .sortedByDescending { it.updatedAt.orEmpty() }
    }

    fun upsertVisible(
        list: List<TaskSummary>,
        summary: TaskSummary,
        hiddenTaskIds: Set<String>
    ): List<TaskSummary> {
        if (summary.taskId in hiddenTaskIds) {
            return list.filterNot { it.taskId == summary.taskId }
        }
        return upsert(list, summary)
    }
}
