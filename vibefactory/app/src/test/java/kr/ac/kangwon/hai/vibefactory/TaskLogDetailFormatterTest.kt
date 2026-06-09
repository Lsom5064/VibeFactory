package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogDetailFormatterTest {
    @Test
    fun putsAgentMessagesOnlyInAgentSection() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task-123456789",
            summary = null,
            currentStatus = "생성 중",
            displayedAppName = "스탑워치",
            messages = listOf(
                message(
                    body = "스탑워치를 만들게요.",
                    eventType = "assistant_message"
                ),
                message(
                    body = "정적 분석은 통과했습니다. 이제 테스트를 실행합니다.",
                    eventType = "agent_message"
                )
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { "오후 2:34" }
        )

        assertEquals(1, payload.agentItems.size)
        assertEquals("정적 분석은 통과했습니다. 이제 테스트를 실행합니다.", payload.agentItems.single().body)
        assertFalse(payload.progressItems.any { it.body.contains("정적 분석") })
    }

    @Test
    fun hidesNoisyProgressFromVisibleProgress() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "생성 중",
            displayedAppName = "앱",
            messages = listOf(
                status("작업 진행", "작업"),
                status("작업 완료", "작업"),
                status("설치 파일 생성", "빌드")
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { "오후 2:34" }
        )

        assertEquals(listOf("설치 파일 생성"), payload.progressItems.map { it.body })
    }

    @Test
    fun ordersProgressAndAgentItemsLatestFirst() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "생성 중",
            displayedAppName = "앱",
            messages = listOf(
                status("첫 진행", "상태", createdAt = "2026-06-09 14:31"),
                message("첫 메모", "agent_message", createdAt = "2026-06-09 14:32"),
                status("두 번째 진행", "빌드", createdAt = "2026-06-09 14:33"),
                message("마지막 메모", "agent_message", createdAt = "2026-06-09 14:34"),
                status("마지막 진행", "테스트", createdAt = "2026-06-09 14:35")
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { it }
        )

        assertEquals(listOf("마지막 진행", "두 번째 진행", "첫 진행"), payload.progressItems.map { it.body })
        assertEquals(listOf("마지막 메모", "첫 메모"), payload.agentItems.map { it.body })
    }

    @Test
    fun formatsIsoTimestampWithoutLeakingRawIso() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "완료",
            displayedAppName = "앱",
            messages = listOf(
                status("설치 파일 준비가 완료되었어요.", "빌드", createdAt = "2026-06-09T05:34:44+00:00")
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { it }
        )

        assertTrue(payload.lastUpdated.isNotBlank())
        assertFalse(payload.lastUpdated.contains("T"))
    }

    @Test
    fun formatsSavedServerTimestampWithoutDateNoise() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "완료",
            displayedAppName = "앱",
            messages = listOf(
                status("다운로드 완료", "상태", createdAt = "2022-09-28 13:29")
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { it }
        )

        assertTrue(payload.lastUpdated.isNotBlank())
        assertFalse(payload.lastUpdated.contains("2022-09-28"))
    }

    @Test
    fun payloadDoesNotExposeRemovedVisibleSectionsOrCopyText() {
        val fieldNames = TaskLogDetailPayload::class.java.declaredFields.map { it.name }.toSet()

        assertFalse(fieldNames.contains("recentItems"))
        assertFalse(fieldNames.contains("fileItems"))
        assertFalse(fieldNames.contains("rawLogItems"))
        assertFalse(fieldNames.contains("copyText"))
    }

    @Test
    fun ignoresFileAndRawLogsAsVisibleSections() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "완료",
            displayedAppName = "앱",
            messages = listOf(
                ChatMessage(
                    id = "file",
                    kind = MessageKind.LOG,
                    title = "파일",
                    body = "파일 수정: app/src/main.kt",
                    eventType = "file_change"
                )
            ),
            rawLogContents = listOf("raw log line"),
            formatTimestamp = { "오후 2:34" }
        )

        assertTrue(payload.progressItems.isEmpty())
        assertTrue(payload.agentItems.isEmpty())
    }

    @Test
    fun ordersRawFallbackAgentMessagesLatestFirst() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "완료",
            displayedAppName = "앱",
            messages = emptyList(),
            rawLogContents = listOf(
                """{"type":"item.completed","item":{"type":"agent_message","text":"첫 원본 메모"}}""",
                """{"type":"item.completed","item":{"type":"agent_message","text":"마지막 원본 메모"}}"""
            ),
            formatTimestamp = { "오후 2:34" }
        )

        assertEquals(listOf("마지막 원본 메모", "첫 원본 메모"), payload.agentItems.map { it.body })
    }

    @Test
    fun extractsLatestApkActionFromArtifactMessages() {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = "task",
            summary = null,
            currentStatus = "완료",
            displayedAppName = "앱",
            messages = listOf(
                artifact("이전 APK", url = "https://example.test/old.apk"),
                artifact(
                    body = "최신 APK",
                    url = "https://example.test/latest.apk",
                    artifactPath = "revisions/rev_0002/app.apk",
                    downloadedPath = "/tmp/latest.apk"
                )
            ),
            rawLogContents = emptyList(),
            formatTimestamp = { "오후 2:34" }
        )

        assertEquals("최신 APK", payload.apkAction?.title)
        assertEquals("https://example.test/latest.apk", payload.apkAction?.apkUrl)
        assertEquals("revisions/rev_0002/app.apk", payload.apkAction?.artifactPath)
        assertEquals("/tmp/latest.apk", payload.apkAction?.downloadedPath)
        assertTrue(payload.progressItems.isEmpty())
    }

    private fun message(
        body: String,
        eventType: String,
        createdAt: String = "2026-06-09 14:34:44"
    ): ChatMessage {
        return ChatMessage(
            id = body.hashCode().toString(),
            kind = MessageKind.ASSISTANT,
            title = "AI",
            body = body,
            createdAt = createdAt,
            eventType = eventType
        )
    }

    private fun status(
        body: String,
        title: String,
        createdAt: String = "2026-06-09 14:34:44"
    ): ChatMessage {
        return ChatMessage(
            id = body.hashCode().toString(),
            kind = MessageKind.STATUS,
            title = title,
            body = body,
            createdAt = createdAt
        )
    }

    private fun artifact(
        body: String,
        url: String,
        artifactPath: String? = null,
        downloadedPath: String? = null
    ): ChatMessage {
        return ChatMessage(
            id = body.hashCode().toString(),
            kind = MessageKind.STATUS,
            title = null,
            body = body,
            detail = "v1 · apk",
            createdAt = "2026-06-09 14:34:44",
            artifactTaskId = "task",
            artifactApkUrl = url,
            artifactApkPath = artifactPath,
            artifactDownloadedPath = downloadedPath
        )
    }
}
