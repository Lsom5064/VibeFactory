package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskInputQueueManagerTest {
    @Test
    fun dequeuesInputsInOrderAndClearsEmptyQueue() {
        val manager = newManager()

        manager.enqueue("task-1", QueuedTaskInput(prompt = "first", imagePreview = null, attachment = null))
        manager.enqueue("task-1", QueuedTaskInput(prompt = "second", imagePreview = null, attachment = null))

        assertEquals("first", manager.dequeueNext("task-1")?.prompt)
        assertEquals("second", manager.dequeueNext("task-1")?.prompt)
        assertNull(manager.dequeueNext("task-1"))
    }

    @Test
    fun queueIsActiveForSelectedPollingTask() {
        val manager = newManager()

        assertTrue(
            manager.isQueueActive(
                taskId = "task-1",
                state = queueState(
                    selectedTaskId = "task-1",
                    currentTaskId = "task-1",
                    pollingTaskId = "task-1",
                    isPollingActive = true,
                    inputMode = InputMode.READ_ONLY
                )
            )
        )
    }

    @Test
    fun queueIsInactiveForUnrelatedTask() {
        val manager = newManager()

        assertFalse(
            manager.isQueueActive(
                taskId = "task-2",
                state = queueState(
                    selectedTaskId = "task-1",
                    currentTaskId = "task-1",
                    pollingTaskId = "task-1",
                    isPollingActive = true,
                    inputMode = InputMode.READ_ONLY
                )
            )
        )
    }

    @Test
    fun queueIsActiveForReadOnlyProcessingStatus() {
        val manager = newManager()

        assertTrue(
            manager.isQueueActive(
                taskId = "task-1",
                state = queueState(
                    selectedTaskId = "task-1",
                    currentTaskId = "task-1",
                    pollingTaskId = null,
                    isPollingActive = false,
                    inputMode = InputMode.READ_ONLY,
                    currentStatus = "생성 중"
                )
            )
        )
    }

    @Test
    fun queueIsInactiveForNonReadOnlyIdleTask() {
        val manager = newManager()

        assertFalse(
            manager.isQueueActive(
                taskId = "task-1",
                state = queueState(
                    selectedTaskId = "task-1",
                    currentTaskId = "task-1",
                    pollingTaskId = null,
                    isPollingActive = false,
                    inputMode = InputMode.CHAT,
                    currentStatus = "대화 가능"
                )
            )
        )
    }

    private fun newManager(): TaskInputQueueManager {
        return TaskInputQueueManager(
            isProcessingStatus = { value -> value == "생성 중" || value == "생각 중" },
            loadingTaskStatus = "작업 불러오는 중"
        )
    }

    private fun queueState(
        selectedTaskId: String?,
        currentTaskId: String?,
        pollingTaskId: String?,
        isPollingActive: Boolean,
        inputMode: InputMode,
        currentStatus: String = "대기 중",
        statusDetail: String? = null
    ): TaskInputQueueState {
        return TaskInputQueueState(
            selectedTaskId = selectedTaskId,
            currentTaskId = currentTaskId,
            pollingTaskId = pollingTaskId,
            isPollingActive = isPollingActive,
            inputMode = inputMode,
            currentStatus = currentStatus,
            statusDetail = statusDetail
        )
    }
}
