package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStatusUpdatePolicyTest {
    @Test
    fun staleResponseFromAnotherTaskDoesNotTargetVisibleChat() {
        assertFalse(
            TaskStatusUpdatePolicy.targetsVisibleTask(
                taskId = "task-a",
                selectedTaskId = "task-b"
            )
        )
    }

    @Test
    fun responseForSelectedTaskTargetsVisibleChat() {
        assertTrue(
            TaskStatusUpdatePolicy.targetsVisibleTask(
                taskId = "task-b",
                selectedTaskId = "task-b"
            )
        )
    }

    @Test
    fun responseDoesNotSelectTaskWhenNoChatIsOpen() {
        assertFalse(TaskStatusUpdatePolicy.targetsVisibleTask("task-a", null))
    }
}
