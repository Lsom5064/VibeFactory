package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentModelTest {
    @Test
    fun chipLabelsUseSingleAttachmentNumberAndKind() {
        assertEquals("[Image #1] screen.png x", buildAttachmentChipLabel(SelectedAttachmentKind.IMAGE, "screen.png"))
        assertEquals("[PDF #1] spec.pdf x", buildAttachmentChipLabel(SelectedAttachmentKind.PDF, "spec.pdf"))
        assertEquals("[Text #1] notes.txt x", buildAttachmentChipLabel(SelectedAttachmentKind.TEXT, "notes.txt"))
    }

    @Test
    fun selectedAttachmentBuildsApiPayload() {
        val attachment = SelectedAttachment(
            kind = SelectedAttachmentKind.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            base64 = "abc"
        )

        assertEquals(
            AttachmentPayload(type = "pdf", mime_type = "application/pdf", name = "spec.pdf", base64 = "abc"),
            attachment.toPayload()
        )
        assertNull(attachment.toChatImagePreview())
    }

    @Test
    fun imageAttachmentKeepsChatPreviewCompatibility() {
        val attachment = SelectedAttachment(
            kind = SelectedAttachmentKind.IMAGE,
            displayName = "screen.jpg",
            mimeType = "image/jpeg",
            base64 = "abc"
        )

        assertEquals(ChatImagePreview(displayName = "screen.jpg", base64 = "abc"), attachment.toChatImagePreview())
    }
}
