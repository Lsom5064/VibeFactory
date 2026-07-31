package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptReviewMessagePolicyTest {
    @Test
    fun `optimistic and server prompt review messages are equivalent`() {
        val prompt = "# 앱 생성 프롬프트\n\n## 앱 이름\n컬러카드"
        val optimistic = ChatMessage(
            id = "decision",
            kind = MessageKind.CONFIRMATION,
            title = "확인",
            body = prompt,
            detail = PromptReviewMessagePolicy.READY_MESSAGE,
            confirmAction = "submit_initial_prompt",
            confirmPayload = prompt,
            promptReviewText = prompt
        )
        val server = ChatMessage(
            id = "timeline",
            kind = MessageKind.CONFIRMATION,
            title = "확인",
            body = prompt,
            confirmAction = "submit_initial_prompt",
            confirmPayload = prompt,
            promptReviewText = prompt
        )

        assertTrue(PromptReviewMessagePolicy.areEquivalent(optimistic, server))
    }

    @Test
    fun `different prepared prompts remain distinct`() {
        val first = ChatMessage(
            id = "first",
            kind = MessageKind.CONFIRMATION,
            title = "확인",
            body = "첫 번째 프롬프트",
            confirmAction = "submit_initial_prompt"
        )
        val second = first.copy(id = "second", body = "두 번째 프롬프트")

        assertFalse(PromptReviewMessagePolicy.areEquivalent(first, second))
    }
}
