package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskProgressTimelinePolicyTest {
    @Test
    fun movesArtifactsToEndOfTheirUserTurnOnly() {
        val result = TaskProgressTimelinePolicy.moveArtifactsToEndOfUserTurns(
            listOf(
                message("user-1", MessageKind.USER),
                message("assistant-summary", MessageKind.ASSISTANT),
                artifact("artifact-v1"),
                message("progress-1", MessageKind.STATUS),
                message("progress-2", MessageKind.STATUS),
                message("user-2", MessageKind.USER),
                artifact("artifact-v2"),
                message("progress-3", MessageKind.STATUS)
            )
        )

        assertEquals(
            listOf(
                "user-1",
                "assistant-summary",
                "progress-1",
                "progress-2",
                "artifact-v1",
                "user-2",
                "progress-3",
                "artifact-v2"
            ),
            result.map { it.id }
        )
    }

    @Test
    fun keepsTimelineUnchangedWhenThereAreNoArtifacts() {
        val messages = listOf(
            message("user-1", MessageKind.USER),
            message("status-1", MessageKind.STATUS),
            message("assistant-1", MessageKind.ASSISTANT)
        )

        assertEquals(messages, TaskProgressTimelinePolicy.moveArtifactsToEndOfUserTurns(messages))
    }

    @Test
    fun keepsRefineArtifactAtEndOfRefineTurnNotGlobalBottom() {
        val result = TaskProgressTimelinePolicy.moveArtifactsToEndOfUserTurns(
            listOf(
                message("user-create", MessageKind.USER),
                message("assistant-create", MessageKind.ASSISTANT),
                artifact("artifact-v1"),
                message("build-status-v1", MessageKind.STATUS),
                message("user-question", MessageKind.USER),
                message("assistant-answer", MessageKind.ASSISTANT),
                message("user-refine-dark-mode", MessageKind.USER),
                message("assistant-refine", MessageKind.ASSISTANT),
                artifact("artifact-v2"),
                message("build-status-v2", MessageKind.STATUS),
                message("user-after-v2", MessageKind.USER),
                message("assistant-after-v2", MessageKind.ASSISTANT)
            )
        )

        assertEquals(
            listOf(
                "user-create",
                "assistant-create",
                "build-status-v1",
                "artifact-v1",
                "user-question",
                "assistant-answer",
                "user-refine-dark-mode",
                "assistant-refine",
                "build-status-v2",
                "artifact-v2",
                "user-after-v2",
                "assistant-after-v2"
            ),
            result.map { it.id }
        )
    }

    @Test
    fun doesNotReorderArtifactsBeforeFirstUserTurn() {
        val messages = listOf(
            artifact("artifact-before-user"),
            message("status-before-user", MessageKind.STATUS),
            message("user-1", MessageKind.USER),
            artifact("artifact-v1"),
            message("status-v1", MessageKind.STATUS)
        )

        val result = TaskProgressTimelinePolicy.moveArtifactsToEndOfUserTurns(messages)

        assertEquals(
            listOf(
                "artifact-before-user",
                "status-before-user",
                "user-1",
                "status-v1",
                "artifact-v1"
            ),
            result.map { it.id }
        )
    }

    @Test
    fun keepsLatestDuplicateArtifactForSameApkIdentity() {
        val result = TaskProgressTimelinePolicy.keepLatestDuplicateArtifacts(
            listOf(
                message("user-create", MessageKind.USER),
                artifact("artifact-v2-old", artifactPath = "rev_0002/app.apk"),
                message("user-refine", MessageKind.USER),
                message("assistant-refine", MessageKind.ASSISTANT),
                artifact("artifact-v2-new", artifactPath = "rev_0002/app.apk")
            )
        )

        assertEquals(
            listOf(
                "user-create",
                "user-refine",
                "assistant-refine",
                "artifact-v2-new"
            ),
            result.map { it.id }
        )
    }

    private fun message(id: String, kind: MessageKind): ChatMessage {
        return ChatMessage(
            id = id,
            kind = kind,
            title = null,
            body = id
        )
    }

    private fun artifact(id: String, artifactPath: String? = null): ChatMessage {
        return ChatMessage(
            id = id,
            kind = MessageKind.STATUS,
            title = null,
            body = id,
            artifactTaskId = "task-1",
            artifactApkUrl = "https://example.test/download/task-1",
            artifactApkPath = artifactPath
        )
    }
}
