package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineCursorPolicyTest {
    @Test
    fun startsAfterProcessedEventForLegacyFullTimelineResponse() {
        val eventIds = listOf("event-1", "event-2", "event-3")

        assertEquals(
            2,
            TimelineCursorPolicy.firstUnprocessedIndex(
                eventIds = eventIds,
                processedEventId = "event-2",
                maxEvents = 120
            )
        )
        assertEquals(
            3,
            TimelineCursorPolicy.firstUnprocessedIndex(
                eventIds = eventIds,
                processedEventId = "event-3",
                maxEvents = 120
            )
        )
    }

    @Test
    fun fallsBackToLatestEventWhenServerDoesNotReturnCursor() {
        assertEquals(
            "event-3",
            TimelineCursorPolicy.nextCursor(
                serverCursor = null,
                eventIds = listOf("event-1", "event-2", "event-3")
            )
        )
        assertEquals(
            "server-cursor",
            TimelineCursorPolicy.nextCursor(
                serverCursor = "server-cursor",
                eventIds = listOf("event-1", "event-2", "event-3")
            )
        )
    }

    @Test
    fun restoresLatestServerCursorFromPersistedTimelineMessages() {
        val older = "1".repeat(32)
        val latest = "a".repeat(32)

        assertEquals(
            latest,
            TimelineCursorPolicy.restoredCursor(
                taskId = "task-1",
                messageIds = listOf(
                    "timeline-task-1-$older",
                    "local-task-1-message",
                    "artifact-task-1-$latest"
                )
            )
        )
    }

    @Test
    fun ignoresSyntheticMessageSuffixesWhenRestoringCursor() {
        assertEquals(
            null,
            TimelineCursorPolicy.restoredCursor(
                taskId = "task-1",
                messageIds = listOf(
                    "timeline-task-1-123456789",
                    "artifact-task-1-latest-7"
                )
            )
        )
    }
}
