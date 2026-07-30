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
}
