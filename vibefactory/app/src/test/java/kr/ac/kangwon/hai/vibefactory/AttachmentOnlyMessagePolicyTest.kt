package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOnlyMessagePolicyTest {
    @Test
    fun syntheticPromptIsHiddenWhenImagesExist() {
        val prompt = "첨부한 이미지를 참고해서 앱 수정을 진행해줘."

        assertEquals(
            "",
            AttachmentOnlyMessagePolicy.canonicalUserBody(
                normalizedBody = prompt,
                hasImages = true,
                normalizedSyntheticPrompts = setOf(prompt)
            )
        )
        assertEquals(
            prompt,
            AttachmentOnlyMessagePolicy.canonicalUserBody(
                normalizedBody = prompt,
                hasImages = false,
                normalizedSyntheticPrompts = setOf(prompt)
            )
        )
    }

    @Test
    fun localAndRemotePreviewsWithSameNameAreEquivalent() {
        val local = listOf(ChatImagePreview(displayName = "meal.jpg", base64 = "base64-data"))
        val remote = listOf(ChatImagePreview(displayName = "meal.jpg", remoteUrl = "https://example.test/image"))

        assertTrue(AttachmentOnlyMessagePolicy.imageSelectionsEquivalent(local, remote))
    }

    @Test
    fun serverEchoReplacesPersistedBase64WithoutChangingBubbleId() {
        val local = ChatMessage(
            id = "local-task-1",
            kind = MessageKind.USER,
            title = "나",
            body = "",
            createdAt = "2026-07-30 10:00:00",
            imagePreviewBase64 = "base64-data",
            imagePreviewName = "meal.jpg",
            imagePreviews = listOf(
                ChatImagePreview(displayName = "meal.jpg", base64 = "base64-data")
            )
        )
        val server = ChatMessage(
            id = "timeline-task-1-event-1",
            kind = MessageKind.USER,
            title = "나",
            body = "",
            createdAt = "2026-07-30T01:00:00Z",
            imagePreviews = listOf(
                ChatImagePreview(
                    displayName = "meal.jpg",
                    remoteUrl = "https://example.test/image"
                )
            )
        )

        val merged = AttachmentOnlyMessagePolicy.mergeLocalWithServerEcho(local, server)

        assertEquals(local.id, merged.id)
        assertEquals(local.createdAt, merged.createdAt)
        assertEquals(null, merged.imagePreviewBase64)
        assertEquals("", merged.imagePreviews.single().base64)
        assertEquals("https://example.test/image", merged.imagePreviews.single().remoteUrl)
    }
}
