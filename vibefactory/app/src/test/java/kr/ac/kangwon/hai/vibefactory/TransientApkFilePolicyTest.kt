package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientApkFilePolicyTest {
    @Test
    fun `one stable file name is used for every revision of a task`() {
        assertEquals(
            "generated_app_abc123.apk",
            ApkArtifactActionHandler.transientApkFileName("abc123")
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
