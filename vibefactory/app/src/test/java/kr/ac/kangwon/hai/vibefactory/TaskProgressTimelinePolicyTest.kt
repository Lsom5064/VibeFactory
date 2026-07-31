package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressTimelinePolicyTest {
    @Test
    fun identicalLoadingPollDoesNotChangeTimeline() {
        val existing = loadingMessage(createdAt = "2026-07-30T12:00:00Z")
        val timeline = mutableListOf(existing)

        val changed = TaskProgressTimelinePolicy.upsertSingleActiveLoadingMessage(
            timeline,
            loadingMessage(createdAt = "2026-07-30T12:00:03Z")
        )

        assertFalse(changed)
        assertEquals(listOf(existing), timeline)
    }

    @Test
    fun changedLoadingStateUpdatesInPlaceAndRemovesStaleLoadingMessages() {
        val timeline = mutableListOf(
            loadingMessage(id = "stale-loading"),
            loadingMessage(cancelTaskId = null)
        )

        val changed = TaskProgressTimelinePolicy.upsertSingleActiveLoadingMessage(
            timeline,
            loadingMessage(cancelTaskId = "task-1")
        )

        assertTrue(changed)
        assertEquals(1, timeline.size)
        assertEquals("current-build-stage-task-1", timeline.single().id)
        assertEquals("task-1", timeline.single().cancelTaskId)
    }

    private fun loadingMessage(
        id: String = "current-build-stage-task-1",
        createdAt: String = "2026-07-30T12:00:00Z",
        cancelTaskId: String? = "task-1"
    ): ChatMessage {
        return ChatMessage(
            id = id,
            kind = MessageKind.STATUS,
            title = "상태",
            body = "앱을 생성하고 있어요.",
            createdAt = createdAt,
            cancelTaskId = cancelTaskId,
            isLoading = true
        )
    }
}
