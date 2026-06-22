package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskSummaryListPolicyTest {
    @Test
    fun visibleTaskIdOrNullReturnsNullForHiddenTask() {
        val result = TaskSummaryListPolicy.visibleTaskIdOrNull(
            rawTaskId = " task-1 ",
            hiddenTaskIds = setOf("task-1")
        )

        assertNull(result)
    }

    @Test
    fun upsertVisibleDoesNotInsertHiddenStatusSummary() {
        val result = TaskSummaryListPolicy.upsertVisible(
            list = emptyList(),
            summary = taskSummary("task-1"),
            hiddenTaskIds = setOf("task-1")
        )

        assertEquals(emptyList<TaskSummary>(), result)
    }

    @Test
    fun upsertVisibleRemovesStaleHiddenSummary() {
        val result = TaskSummaryListPolicy.upsertVisible(
            list = listOf(taskSummary("task-1"), taskSummary("task-2")),
            summary = taskSummary("task-1", status = "Success"),
            hiddenTaskIds = setOf("task-1")
        )

        assertEquals(listOf("task-2"), result.map { it.taskId })
    }

    @Test
    fun upsertVisibleUpdatesVisibleSummary() {
        val result = TaskSummaryListPolicy.upsertVisible(
            list = listOf(taskSummary("task-1", status = "Building")),
            summary = taskSummary("task-1", status = "Success"),
            hiddenTaskIds = emptySet()
        )

        assertEquals(listOf("task-1"), result.map { it.taskId })
        assertEquals("Success", result.first().status)
    }

    private fun taskSummary(taskId: String, status: String = "Building"): TaskSummary {
        return TaskSummary(
            taskId = taskId,
            title = "Task $taskId",
            appName = null,
            packageName = null,
            subtitle = "Subtitle",
            status = status,
            updatedAt = "2026-06-22T00:00:00Z",
            hasApk = false
        )
    }
}
