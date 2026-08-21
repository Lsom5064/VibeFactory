package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskRestorePolicyTest {
    @Test
    fun removeMissingTaskClearsOnlyMatchingReferences() {
        val recovered = TaskRestorePolicy.removeMissingTask(
            missingTaskId = " missing-task ",
            selection = TaskRestoreSelection(
                pendingTaskId = "missing-task",
                currentTaskId = "active-task",
                selectedTaskId = " missing-task ",
                lastSelectedTaskId = "missing-task"
            )
        )

        assertNull(recovered.pendingTaskId)
        assertEquals("active-task", recovered.currentTaskId)
        assertNull(recovered.selectedTaskId)
        assertNull(recovered.lastSelectedTaskId)
    }

    @Test
    fun removeMissingTaskPreservesSelectionForBlankMissingId() {
        val selection = TaskRestoreSelection(
            pendingTaskId = "pending-task",
            currentTaskId = "active-task",
            selectedTaskId = "selected-task",
            lastSelectedTaskId = "last-task"
        )

        assertEquals(selection, TaskRestorePolicy.removeMissingTask("  ", selection))
    }
}
