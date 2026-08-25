package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun `handled prompt review hides its action while normal confirmation remains visible`() {
        val promptReview = ChatMessage(
            id = "prompt-review",
            kind = MessageKind.CONFIRMATION,
            title = "확인",
            body = "프롬프트",
            confirmAction = "submit_initial_prompt",
            promptReviewText = "프롬프트"
        )
        val normalConfirmation = promptReview.copy(
            id = "normal-confirmation",
            confirmAction = "repair_runtime",
            promptReviewText = null
        )

        assertTrue(PromptReviewMessagePolicy.shouldShowConfirmationActions(promptReview, handled = false))
        assertFalse(PromptReviewMessagePolicy.shouldShowConfirmationActions(promptReview, handled = true))
        assertTrue(PromptReviewMessagePolicy.shouldShowConfirmationActions(normalConfirmation, handled = true))
    }

    @Test
    fun `submitted prompt replaces every expandable prompt review value`() {
        val original = ChatMessage(
            id = "prompt-review",
            kind = MessageKind.CONFIRMATION,
            title = "확인",
            body = "수정 전 프롬프트",
            detail = PromptReviewMessagePolicy.READY_MESSAGE,
            confirmAction = "submit_initial_prompt",
            confirmPayload = "수정 전 프롬프트",
            promptReviewText = "수정 전 프롬프트"
        )

        val submitted = PromptReviewMessagePolicy.withSubmittedPrompt(
            message = original,
            submittedPrompt = "  수정 후 최종 프롬프트  ",
            submittedDetail = "전송한 최종 프롬프트"
        )

        assertEquals("수정 후 최종 프롬프트", submitted.body)
        assertEquals("수정 후 최종 프롬프트", submitted.confirmPayload)
        assertEquals("수정 후 최종 프롬프트", submitted.promptReviewText)
        assertEquals("전송한 최종 프롬프트", submitted.detail)
    }
}
