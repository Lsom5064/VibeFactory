package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTextPolicyTest {
    @Test
    fun markdownAndListMarkersDoNotCreateDuplicateMessages() {
        assertTrue(ChatMessageTextPolicy.sameText("**질문**\n- 첫째", "질문 첫째"))
    }

    @Test
    fun attachmentOnlySyntheticPromptMatchesBlankServerEcho() {
        val prompt = "첨부한 이미지를 참고해서 앱 수정을 진행해줘."
        val local = userMessage(prompt, listOf(ChatImagePreview("screen.png", "base64")))
        val remote = userMessage("", listOf(ChatImagePreview("screen.png", remoteUrl = "/image/1")))

        assertTrue(
            ChatMessageTextPolicy.areSameContent(
                local,
                remote,
                normalizedSyntheticPrompts = setOf(ChatMessageTextPolicy.normalize(prompt))
            )
        )
    }

    @Test
    fun differentImageSelectionsAreNotCollapsed() {
        val first = userMessage("수정해줘", listOf(ChatImagePreview("first.png", "first")))
        val second = userMessage("수정해줘", listOf(ChatImagePreview("second.png", "second")))

        assertFalse(ChatMessageTextPolicy.areSameContent(first, second, emptySet()))
    }

    @Test
    fun internalBuildMessagesAreHiddenByOnePolicy() {
        assertTrue(ChatMessageTextPolicy.isHiddenOperationalBuildMessage("APK build completed"))
        assertFalse(ChatMessageTextPolicy.isHiddenOperationalBuildMessage("앱을 설치할 수 있어요."))
    }

    private fun userMessage(body: String, images: List<ChatImagePreview>): ChatMessage {
        return ChatMessage(
            id = body + images.firstOrNull()?.displayName.orEmpty(),
            kind = MessageKind.USER,
            title = "나",
            body = body,
            imagePreviews = images
        )
    }
}
