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

    @Test
    fun evaluationUsesServerFlagsBeforeFallbackStatusRules() {
        val evaluation = TaskStatusPolicy.evaluate(
            StatusResponse(
                status = "Failed",
                retry_allowed = false,
                allowed_next_actions = listOf("retry")
            )
        )

        assertFalse(evaluation.isRetryable)
        assertFalse(evaluation.isResponseError)
        assertFalse(evaluation.isPolling)
    }

    @Test
    fun webResearchPendingDecisionKeepsPollingWithoutTreatingItAsClarification() {
        val evaluation = TaskStatusPolicy.evaluate(
            StatusResponse(
                status = "Pending Decision",
                progress_mode = "web_research",
                requires_user_input = true
            )
        )

        assertTrue(evaluation.isClarifying)
        assertTrue(evaluation.isPolling)
        assertTrue(TaskStatusPolicy.isWebResearchInProgress(
            StatusResponse(status = "Pending Decision", progress_mode = "web_research")
        ))
    }

    @Test
    fun serverRequestedInputStopsPollingEvenForActiveStatus() {
        val evaluation = TaskStatusPolicy.evaluate(
            StatusResponse(status = "Building", requires_user_input = true)
        )

        assertTrue(evaluation.isClarifying)
        assertFalse(evaluation.isPolling)
    }
}
