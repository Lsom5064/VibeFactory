package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiRenderFingerprintTest {
    @Test
    fun identicalMessagesProduceStableFingerprint() {
        val messages = listOf(sampleMessage())

        assertEquals(
            UiRenderFingerprint.messages("task-1", messages),
            UiRenderFingerprint.messages("task-1", messages.map { it.copy() })
        )
    }

    @Test
    fun progressAndTaskChangesInvalidateMessageFingerprint() {
        val message = sampleMessage()
        val original = UiRenderFingerprint.messages("task-1", listOf(message))

        assertNotEquals(
            original,
            UiRenderFingerprint.messages(
                "task-1",
                listOf(message.copy(artifactDownloadProgressPercent = 51))
            )
        )
        assertNotEquals(original, UiRenderFingerprint.messages("task-2", listOf(message)))
    }

    @Test
    fun attachmentPayloadIdentityUsesSizeAndBothEdges() {
        val middle = "m".repeat(200)
        val first = "a".repeat(48) + middle + "z".repeat(48)
        val changedStart = "b" + first.drop(1)
        val changedEnd = first.dropLast(1) + "y"

        assertNotEquals(UiRenderFingerprint.binaryPayload(first), UiRenderFingerprint.binaryPayload(changedStart))
        assertNotEquals(UiRenderFingerprint.binaryPayload(first), UiRenderFingerprint.binaryPayload(changedEnd))
        assertNotEquals(UiRenderFingerprint.binaryPayload(first), UiRenderFingerprint.binaryPayload(first + "m"))
    }

    @Test
    fun taskRuntimeErrorStateInvalidatesTaskListFingerprint() {
        val task = TaskSummary(
            taskId = "task-1",
            title = "테스트 앱",
            appName = "테스트 앱",
            packageName = "com.example.test",
            subtitle = "",
            status = "Success",
            updatedAt = null,
            hasApk = true
        )

        assertNotEquals(
            UiRenderFingerprint.taskList(listOf(task), "task-1", emptySet()),
            UiRenderFingerprint.taskList(listOf(task), "task-1", setOf("task-1"))
        )
    }

    private fun sampleMessage(): ChatMessage {
        return ChatMessage(
            id = "message-1",
            kind = MessageKind.STATUS,
            title = "앱 생성 완료",
            body = "APK를 다운로드할 수 있어요.",
            artifactTaskId = "task-1",
            artifactCanDownload = true,
            artifactDownloadProgressPercent = 50,
            imagePreviews = listOf(
                ChatImagePreview(
                    displayName = "reference.jpg",
                    base64 = "a".repeat(120)
                )
            )
        )
    }
}
