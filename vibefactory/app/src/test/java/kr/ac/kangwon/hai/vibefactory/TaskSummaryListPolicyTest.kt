package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSummaryListPolicyTest {
    @Test
    fun upsertAddsMissingCurrentTaskToTop() {
        val result = TaskSummaryListPolicy.upsert(
            listOf(summary("old", "2026-06-08T01:00:00Z")),
            summary("current", "2026-06-08T02:00:00Z")
        )

        assertEquals(listOf("current", "old"), result.map { it.taskId })
    }

    @Test
    fun upsertReplacesExistingTaskWithoutDuplicating() {
        val result = TaskSummaryListPolicy.upsert(
            listOf(summary("current", "2026-06-08T01:00:00Z", status = "Running")),
            summary("current", "2026-06-08T02:00:00Z", status = "Success")
        )

        assertEquals(1, result.size)
        assertEquals("Success", result.single().status)
    }

    private fun summary(
        taskId: String,
        updatedAt: String,
        status: String = "Queued"
    ): TaskSummary {
        return TaskSummary(
            taskId = taskId,
            title = taskId,
            appName = null,
            packageName = null,
            subtitle = taskId,
            status = status,
            updatedAt = updatedAt,
            hasApk = false
        )
    }
}
