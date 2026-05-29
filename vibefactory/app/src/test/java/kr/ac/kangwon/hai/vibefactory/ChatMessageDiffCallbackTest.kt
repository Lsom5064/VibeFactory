package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageDiffCallbackTest {
    @Test
    fun sameMessageIdIsSameItemEvenWhenBodyChanges() {
        val oldMessage = chatMessage(id = "m1", body = "빌드 진행 중")
        val newMessage = chatMessage(id = "m1", body = "빌드 진행 중.")

        assertTrue(ChatMessageDiffCallback.areItemsTheSame(oldMessage, newMessage))
        assertFalse(ChatMessageDiffCallback.areContentsTheSame(oldMessage, newMessage))
    }

    @Test
    fun differentMessageIdsAreDifferentItemsEvenWithSameBody() {
        val oldMessage = chatMessage(id = "m1", body = "APK 빌드가 완료되었어요.")
        val newMessage = chatMessage(id = "m2", body = "APK 빌드가 완료되었어요.")

        assertFalse(ChatMessageDiffCallback.areItemsTheSame(oldMessage, newMessage))
    }

    @Test
    fun unchangedMessageHasSameItemAndContent() {
        val oldMessage = chatMessage(id = "m1", body = "복사할 긴 메시지")
        val newMessage = oldMessage.copy()

        assertTrue(ChatMessageDiffCallback.areItemsTheSame(oldMessage, newMessage))
        assertTrue(ChatMessageDiffCallback.areContentsTheSame(oldMessage, newMessage))
    }

    private fun chatMessage(id: String, body: String): ChatMessage {
        return ChatMessage(
            id = id,
            kind = MessageKind.ASSISTANT,
            title = null,
            body = body,
            createdAt = "2026-05-29T00:00:00Z"
        )
    }
}
