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
    fun `prompt review uses a single view action while normal confirmation remains visible`() {
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

        assertFalse(PromptReviewMessagePolicy.shouldShowConfirmationActions(promptReview))
        assertTrue(PromptReviewMessagePolicy.canEdit(promptReview, handled = false))
        assertFalse(PromptReviewMessagePolicy.canEdit(promptReview, handled = true))
        assertFalse(PromptReviewMessagePolicy.canEdit(promptReview.copy(promptReviewSubmitted = true), handled = false))
        assertTrue(PromptReviewMessagePolicy.shouldShowConfirmationActions(normalConfirmation))
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
        assertTrue(submitted.promptReviewSubmitted)
    }

    @Test fun `explicitly finished review never restores a stale confirmation action`() {
        assertFalse(PromptReviewMessagePolicy.shouldRestoreReview(false, "status_only", "submit_initial_prompt", false))
        assertFalse(PromptReviewMessagePolicy.shouldRestoreReview(false, "prompt_review_bubble", "submit_initial_prompt", true))
        assertTrue(PromptReviewMessagePolicy.shouldRestoreReview(true, "prompt_review_bubble", "submit_initial_prompt", true))
    }

    @Test fun `legacy review responses without an explicit flag remain supported`() {
        assertTrue(PromptReviewMessagePolicy.shouldRestoreReview(null, "prompt_review_bubble", "", false))
        assertTrue(PromptReviewMessagePolicy.shouldRestoreReview(null, "", "submit_initial_prompt", false))
        assertFalse(PromptReviewMessagePolicy.shouldRestoreReview(null, "status_only", "", false))
    }

    private fun review(id: String, prompt: String, task: String = "task") = ChatMessage(
        id = id, kind = MessageKind.CONFIRMATION, title = "확인", body = prompt,
        confirmAction = "submit_initial_prompt", promptReviewTaskId = task, promptReviewText = prompt)

    private val notice = ChatMessage("notice", MessageKind.USER, "나", "전송 안내")

    @Test fun `existing duplicate submitted cards collapse before the original send notice`() {
        val first = review("original", "최종 내용").copy(createdAt = "2026-10-19 12:47:05")
        val duplicate = review("restored", "최종 내용")
        val fixed = PromptReviewMessagePolicy.reconcileSubmittedReviews(
            listOf(first, notice, duplicate), "task", "최종 내용", "전송한 내용")
        assertEquals(listOf("original", "notice"), fixed.map { it.id })
        assertEquals(first.createdAt, fixed.first().createdAt)
        assertTrue(fixed.first().promptReviewSubmitted)
        assertEquals(fixed, PromptReviewMessagePolicy.reconcileSubmittedReviews(fixed, "task", "최종 내용", "전송한 내용"))
    }

    @Test fun `editing preserves the original card and removes a later copy of the edited prompt`() {
        val fixed = PromptReviewMessagePolicy.reconcileSubmittedReviews(
            listOf(review("original", "초안"), notice, review("copy", "최종 내용")),
            "task", "최종 내용", "전송한 내용", "original")
        assertEquals(listOf("original", "notice"), fixed.map { it.id })
        assertEquals("최종 내용", fixed.first().body)
        assertEquals("최종 내용", fixed.first().confirmPayload)
    }

    @Test fun `reconciliation preserves genuinely different drafts and other tasks`() {
        val old = review("old", "이전 명세")
        val anotherTask = review("other", "최종 내용", "other-task")
        val fixed = PromptReviewMessagePolicy.reconcileSubmittedReviews(
            listOf(old, review("selected", "초안"), notice, anotherTask),
            "task", "최종 내용", "전송한 내용", "selected")
        assertEquals(4, fixed.size)
        assertEquals(old, fixed.first())
        assertEquals(anotherTask, fixed.last())
    }

    @Test fun `a later submission does not overwrite an earlier different submission`() {
        val old = review("old", "이전 전송").copy(promptReviewSubmitted = true)
        val first = PromptReviewMessagePolicy.reconcileSubmittedReviews(
            listOf(old, review("current", "새 초안"), notice), "task", "새 전송", "전송한 내용", "current")
        val restored = PromptReviewMessagePolicy.reconcileSubmittedReviews(first, "task", "새 전송", "전송한 내용")
        assertEquals(old, restored.first())
        assertEquals("새 전송", restored[1].body)
        assertTrue(restored[1].promptReviewSubmitted)
    }

}
