package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientApkFilePolicyTest {
    @Test
    fun `open action launches an installed artifact without downloading it again`() {
        assertTrue(ApkArtifactActionHandler.shouldLaunchInstalledArtifact(packageInstalled = true))
        assertFalse(ApkArtifactActionHandler.shouldLaunchInstalledArtifact(packageInstalled = false))
    }

    @Test
    fun `one stable file name is used for a task artifact`() {
        assertEquals(
            "generated_app_abc123.apk",
            ApkArtifactActionHandler.transientApkFileName("abc123")
        )
    }

    @Test
    fun `different revisions cannot reuse a stale cached APK`() {
        val firstRevision = ApkArtifactActionHandler.transientApkFileName(
            "abc123",
            "/download/abc123",
            "revisions/rev_0001/project/build/app/outputs/flutter-apk/app-release.apk"
        )
        val secondRevision = ApkArtifactActionHandler.transientApkFileName(
            "abc123",
            "/download/abc123",
            "revisions/rev_0002/project/build/app/outputs/flutter-apk/app-release.apk"
        )

        assertTrue(firstRevision.startsWith("generated_app_abc123-"))
        assertTrue(firstRevision.endsWith(".apk"))
        assertFalse(firstRevision == secondRevision)
    }

    @Test
    fun `installed artifact identity includes task and revision`() {
        assertEquals(
            "task-1|revisions/rev_0002/app-release.apk",
            ApkArtifactActionHandler.artifactIdentity(
                "task-1",
                "/download/task-1",
                "revisions/rev_0002/app-release.apk"
            )
        )
        assertFalse(
            ApkArtifactActionHandler.artifactIdentity(
                "task-1",
                "/download/task-1",
                "revisions/rev_0001/app-release.apk"
            ) ==
                ApkArtifactActionHandler.artifactIdentity(
                    "task-1",
                    "/download/task-1",
                    "revisions/rev_0002/app-release.apk"
                )
        )
    }

    @Test
    fun `artifact path takes precedence over a shared download URL`() {
        assertFalse(
            ApkArtifactActionHandler.artifactsMatch(
                targetTaskId = "task-1",
                targetUrl = "/download/task-1",
                targetArtifactPath = "revisions/rev_0001/app-release.apk",
                candidateTaskId = "task-1",
                candidateUrl = "/download/task-1",
                candidateArtifactPath = "revisions/rev_0002/app-release.apk"
            )
        )
        assertTrue(
            ApkArtifactActionHandler.artifactsMatch(
                targetTaskId = "task-1",
                targetUrl = "/download/task-1",
                targetArtifactPath = "revisions/rev_0001/app-release.apk",
                candidateTaskId = "task-1",
                candidateUrl = "/download/task-1",
                candidateArtifactPath = "revisions/rev_0001/app-release.apk"
            )
        )
    }

    @Test
    fun `unsafe task id characters are removed from file name`() {
        assertEquals(
            "generated_app_task_with_spaces.apk",
            ApkArtifactActionHandler.transientApkFileName("task with spaces")
        )
    }

    @Test
    fun `only generated APK and partial files are managed`() {
        assertTrue(ApkArtifactActionHandler.isManagedApkFileName("generated_app_task.apk"))
        assertTrue(ApkArtifactActionHandler.isManagedApkFileName("generated_app_task.apk.tmp"))
        assertFalse(ApkArtifactActionHandler.isManagedApkFileName("reference.jpg"))
        assertFalse(ApkArtifactActionHandler.isManagedApkFileName("app-release.apk"))
    }
}
