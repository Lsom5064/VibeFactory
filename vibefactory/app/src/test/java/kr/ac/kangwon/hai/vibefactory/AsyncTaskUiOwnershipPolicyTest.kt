package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncTaskUiOwnershipPolicyTest {
    @Test
    fun initialRequestOwnsUnchangedNewChatSurface() {
        assertTrue(
            AsyncTaskUiOwnershipPolicy.requestStillOwnsVisibleSurface(
                sourceTaskId = null,
                selectedTaskId = null,
                requestSelectionGeneration = 3,
                currentSelectionGeneration = 3
            )
        )
    }

    @Test
    fun oldInitialRequestCannotTakeOverNewerNewChat() {
        assertFalse(
            AsyncTaskUiOwnershipPolicy.requestStillOwnsVisibleSurface(
                sourceTaskId = null,
                selectedTaskId = null,
                requestSelectionGeneration = 3,
                currentSelectionGeneration = 4
            )
        )
    }

    @Test
    fun responseCannotTakeOverAnotherSelectedTask() {
        assertFalse(
            AsyncTaskUiOwnershipPolicy.requestStillOwnsVisibleSurface(
                sourceTaskId = "task-a",
                selectedTaskId = "task-b",
                requestSelectionGeneration = 7,
                currentSelectionGeneration = 7
            )
        )
    }

    @Test
    fun visibleTaskAlwaysPrefersSelectedConversation() {
        assertEquals(
            "task-b",
            AsyncTaskUiOwnershipPolicy.visibleTaskId(
                selectedTaskId = " task-b ",
                currentTaskId = "task-a"
            )
        )
    }

    @Test
    fun resumingSameTaskDoesNotCountAsSelectionChange() {
        assertFalse(
            AsyncTaskUiOwnershipPolicy.targetChangesVisibleTask(
                targetTaskId = " task-a ",
                selectedTaskId = "task-a"
            )
        )
    }

    @Test
    fun restoringAnotherTaskCountsAsSelectionChange() {
        assertTrue(
            AsyncTaskUiOwnershipPolicy.targetChangesVisibleTask(
                targetTaskId = "task-a",
                selectedTaskId = "task-b"
            )
        )
    }
}
