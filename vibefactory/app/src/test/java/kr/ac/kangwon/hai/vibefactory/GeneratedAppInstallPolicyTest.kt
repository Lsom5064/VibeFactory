package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAppInstallPolicyTest {
    @Test
    fun `first install is complete when package appears`() {
        assertTrue(
            GeneratedAppInstallPolicy.installationCompleted(
                before = null,
                after = InstalledPackageSnapshot(versionCode = 1, lastUpdateTime = 100)
            )
        )
    }

    @Test
    fun `replacement with equal version code uses update time`() {
        assertTrue(
            GeneratedAppInstallPolicy.installationCompleted(
                before = InstalledPackageSnapshot(versionCode = 1_900_000_000, lastUpdateTime = 100),
                after = InstalledPackageSnapshot(versionCode = 1_900_000_000, lastUpdateTime = 200)
            )
        )
    }

    @Test
    fun `cancelled installer is not treated as completed`() {
        val unchanged = InstalledPackageSnapshot(versionCode = 10, lastUpdateTime = 100)

        assertFalse(
            GeneratedAppInstallPolicy.installationCompleted(
                before = unchanged,
                after = unchanged
            )
        )
        assertFalse(
            GeneratedAppInstallPolicy.installationCompleted(
                before = unchanged,
                after = null
            )
        )
    }
}
