package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskInputQueueManagerTest {
    private val manager = TaskInputQueueManager(
        isProcessingStatus = { it == "processing" },
        loadingTaskStatus = "loading"
    )

    @Test
    fun staleCurrentTaskCannotReceiveVisibleChatsQueuedInput() {
        assertFalse(
            manager.isQueueActive(
                taskId = "task-a",
                state = state(selectedTaskId = "task-b", currentTaskId = "task-a")
            )
        )
    }

    @Test
    fun selectedProcessingTaskCanQueueInput() {
        assertTrue(
            manager.isQueueActive(
                taskId = "task-b",
                state = state(selectedTaskId = "task-b", currentTaskId = "task-a")
            )
        )
    }

    private fun state(selectedTaskId: String?, currentTaskId: String?): TaskInputQueueState {
        return TaskInputQueueState(
            selectedTaskId = selectedTaskId,
            currentTaskId = currentTaskId,
            pollingTaskId = selectedTaskId,
            isPollingActive = true,
            inputMode = InputMode.READ_ONLY,
            currentStatus = "processing",
            statusDetail = null
        )
    }
}
