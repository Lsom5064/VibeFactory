package kr.ac.kangwon.hai.vibefactory

internal data class InstalledPackageSnapshot(
    val versionCode: Long,
    val lastUpdateTime: Long
)

internal object GeneratedAppInstallPolicy {
    fun installationCompleted(
        before: InstalledPackageSnapshot?,
        after: InstalledPackageSnapshot?
    ): Boolean {
        after ?: return false
        before ?: return true
        return after.versionCode != before.versionCode ||
            after.lastUpdateTime > before.lastUpdateTime
    }
}
