package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineEventTypeBackfillTest {
    @Test
    fun backfillsPersistedAssistantMessageFromSourceEventType() {
        val timeline = mutableListOf(
            ChatMessage(
                id = "old-assistant",
                kind = MessageKind.ASSISTANT,
                title = "AI",
                body = "분석은 불필요 import 하나만 지적했고, 테스트는 Flutter fake time에서 실패했습니다."
            )
        )

        val changed = ChatTimelineEventTypeBackfill.backfill(
            timeline,
            listOf(
                ChatTimelineEventTypeBackfill.SourceEvent(
                    kind = "assistant",
                    body = "분석은 불필요 import 하나만 지적했고, 테스트는 Flutter fake time에서 실패했습니다.",
                    eventType = "agent_message"
                )
            )
        )

        assertTrue(changed)
        assertEquals("agent_message", timeline.single().eventType)
    }

    @Test
    fun keepsExistingEventType() {
        val timeline = mutableListOf(
            ChatMessage(
                id = "assistant",
                kind = MessageKind.ASSISTANT,
                title = "AI",
                body = "네, 말씀해 주세요.",
                eventType = "assistant_message"
            )
        )

        ChatTimelineEventTypeBackfill.backfill(
            timeline,
            listOf(
                ChatTimelineEventTypeBackfill.SourceEvent(
                    kind = "assistant",
                    body = "네, 말씀해 주세요.",
                    eventType = "agent_message"
                )
            )
        )

        assertEquals("assistant_message", timeline.single().eventType)
    }
}
