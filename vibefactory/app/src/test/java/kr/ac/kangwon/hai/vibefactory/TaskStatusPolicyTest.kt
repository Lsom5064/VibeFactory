package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStatusPolicyTest {
    @Test
    fun normalizeHandlesServerStatusVariants() {
        assertEquals("pending decision", TaskStatusPolicy.normalize(" Pending_Decision\nextra"))
        assertEquals("in progress", TaskStatusPolicy.normalize("in-progress"))
        assertEquals("", TaskStatusPolicy.normalize("  "))
    }

    @Test
    fun foregroundPollingRetainsClarificationWhileBackgroundMonitorStops() {
        assertTrue(TaskStatusPolicy.shouldPollConversation("clarification_needed"))
        assertFalse(TaskStatusPolicy.shouldMonitorBuildInBackground("clarification_needed"))
        assertTrue(TaskStatusPolicy.shouldMonitorBuildInBackground("pending_decision"))
        assertTrue(TaskStatusPolicy.shouldPollConversation("building"))
        assertTrue(TaskStatusPolicy.shouldMonitorBuildInBackground("building"))
        assertFalse(TaskStatusPolicy.shouldPollConversation("rejected"))
    }

    @Test
    fun terminalStatesAreClassifiedConsistently() {
        assertTrue(TaskStatusPolicy.isSuccess("SUCCESS"))
        assertTrue(TaskStatusPolicy.isCancelled("canceled"))
        assertTrue(TaskStatusPolicy.isRetryableFailure("failed"))
        assertTrue(TaskStatusPolicy.isResponseError("device_mismatch"))
    }
}
