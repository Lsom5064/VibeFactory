package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineVisibilityTest {
    @Test
    fun showsServerBuildProgressStatus() {
        val message = statusMessage(
            id = "timeline-task-event-1",
            title = "빌드",
            body = "Flutter 코드를 분석하고 있어요."
        )

        assertTrue(
            ChatTimelineVisibility.shouldShowStatusMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    @Test
    fun showsServerFileAndCommandProgressBodies() {
        val fileMessage = statusMessage(title = "파일", body = "앱 파일 수정 완료")
        val commandMessage = statusMessage(title = "점검", body = "앱 코드 점검")

        assertTrue(
            ChatTimelineVisibility.shouldShowStatusMessage(
                fileMessage,
                ChatTimelineVisibility.normalizeBody(fileMessage.body),
                emptySet()
            )
        )
        assertTrue(
            ChatTimelineVisibility.shouldShowStatusMessage(
                commandMessage,
                ChatTimelineVisibility.normalizeBody(commandMessage.body),
                emptySet()
            )
        )
    }

    @Test
    fun hidesServerProgressWhenTimelineIsOff() {
        val fileMessage = statusMessage(body = "파일 수정 완료: lib/main.dart")

        assertFalse(
            ChatTimelineVisibility.shouldShowStatusMessage(
                message = fileMessage,
                normalizedBody = ChatTimelineVisibility.normalizeBody(fileMessage.body),
                visibleStatusBodies = emptySet(),
                showProgressTimeline = false
            )
        )
    }

    @Test
    fun hidesUnknownStatusMessage() {
        val message = statusMessage(body = "내부 메타데이터가 기록되었습니다.")

        assertFalse(
            ChatTimelineVisibility.shouldShowStatusMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = setOf("생각 중")
            )
        )
    }

    @Test
    fun showsAiAssistantTimelineMessagesInMainChatWhenEventTypeIsAssistantMessage() {
        val message = ChatMessage(
            id = "timeline-task-item-1",
            kind = MessageKind.ASSISTANT,
            title = "AI",
            body = "스탑워치는 요청한 기능을 실행할 수 있게 만든 앱이에요.",
            eventType = "assistant_message"
        )

        assertTrue(
            ChatTimelineVisibility.shouldShowMainChatMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    @Test
    fun hidesAiAgentTimelineMessagesFromMainChat() {
        val message = ChatMessage(
            id = "recent-task-progress",
            kind = MessageKind.ASSISTANT,
            title = "AI",
            body = "`project`가 symlink라 상대 경로 로그 위치가 빗나갔습니다. 절대 경로로 로그를 지정해서 같은 명령을 다시 실행합니다.",
            eventType = "agent_message"
        )

        assertFalse(
            ChatTimelineVisibility.shouldShowMainChatMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    @Test
    fun hidesExplicitEngineAssistantMessagesFromMainChat() {
        val message = ChatMessage(
            id = "engine-task-progress",
            kind = MessageKind.ASSISTANT,
            title = "작업 엔진",
            body = "`flutter test`를 실행합니다."
        )

        assertFalse(
            ChatTimelineVisibility.shouldShowMainChatMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    @Test
    fun showsUserFacingAssistantSummaryInMainChat() {
        val message = ChatMessage(
            id = "recent-task-summary",
            kind = MessageKind.ASSISTANT,
            title = "AI",
            body = "간단한 메모장을 만들게요."
        )

        assertTrue(
            ChatTimelineVisibility.shouldShowMainChatMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    @Test
    fun hidesLogsFromMainChat() {
        val message = ChatMessage(
            id = "log-task",
            kind = MessageKind.LOG,
            title = "로그",
            body = "작업 엔진 메시지",
            detail = "flutter test"
        )

        assertFalse(
            ChatTimelineVisibility.shouldShowMainChatMessage(
                message = message,
                normalizedBody = ChatTimelineVisibility.normalizeBody(message.body),
                visibleStatusBodies = emptySet()
            )
        )
    }

    private fun statusMessage(
        id: String = "status-1",
        title: String = "상태",
        body: String
    ): ChatMessage {
        return ChatMessage(
            id = id,
            kind = MessageKind.STATUS,
            title = title,
            body = body
        )
    }
}
