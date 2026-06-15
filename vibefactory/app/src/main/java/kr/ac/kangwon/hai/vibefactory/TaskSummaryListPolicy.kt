package kr.ac.kangwon.hai.vibefactory

object TaskSummaryListPolicy {
    fun upsert(list: List<TaskSummary>, summary: TaskSummary): List<TaskSummary> {
        return (list.filterNot { it.taskId == summary.taskId } + summary)
            .sortedByDescending { it.updatedAt.orEmpty() }
    }
}
