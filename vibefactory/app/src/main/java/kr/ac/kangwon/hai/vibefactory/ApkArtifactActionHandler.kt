package kr.ac.kangwon.hai.vibefactory

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException

internal data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
)

internal object ApkArtifactActionHandler {
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 750L
    private const val MANAGED_APK_PREFIX = "generated_app_"
    private val downloadFileMutex = Mutex()

    fun localApkFile(
        context: Context,
        taskId: String,
        url: String?,
        artifactPath: String?,
        downloadedPath: String?
    ): File? {
        return downloadedPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?: cachedDownloadedApkFile(context, taskId, url, artifactPath)
    }

    fun cachedDownloadedApkFile(
        context: Context,
        taskId: String,
        url: String?,
        artifactPath: String?
    ): File? {
        return artifactDownloadCacheFile(context, taskId, url, artifactPath).takeIf { it.exists() }
    }

    fun artifactDownloadCacheFile(
        context: Context,
        taskId: String,
        @Suppress("UNUSED_PARAMETER") url: String?,
        @Suppress("UNUSED_PARAMETER") artifactPath: String?
    ): File {
        val cacheDir = context.externalCacheDir ?: throw IOException("external cache unavailable")
        return File(cacheDir, transientApkFileName(taskId))
    }

    fun clearManagedDownloads(context: Context, keepFile: File? = null): Int {
        val cacheDir = context.externalCacheDir ?: return 0
        val keepPath = keepFile
            ?.takeIf { it.exists() }
            ?.canonicalPath
        var deletedCount = 0
        cacheDir.listFiles().orEmpty().forEach { file ->
            if (!isManagedApkFileName(file.name)) return@forEach
            if (keepPath != null && runCatching { file.canonicalPath }.getOrNull() == keepPath) {
                return@forEach
            }
            if (file.delete()) deletedCount += 1
        }
        return deletedCount
    }

    suspend fun clearManagedDownloadsSafely(context: Context, keepFile: File? = null): Int {
        return downloadFileMutex.withLock {
            clearManagedDownloads(context, keepFile)
        }
    }

    fun deleteTransientDownload(file: File?): Boolean {
        val target = file ?: return true
        if (!isManagedApkFileName(target.name)) return false
        val temp = File(target.parentFile, "${target.name}.tmp")
        val targetDeleted = !target.exists() || target.delete()
        val tempDeleted = !temp.exists() || temp.delete()
        return targetDeleted && tempDeleted
    }

    suspend fun downloadToCache(
        context: Context,
        apiService: VibeApiService,
        taskId: String,
        url: String?,
        artifactPath: String?,
        deviceId: String,
        phoneNumber: String?,
        onProgress: suspend (ApkDownloadProgress) -> Unit = {},
        onRetry: suspend (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> }
    ): File {
        return downloadFileMutex.withLock {
            downloadToCacheLocked(
                context = context,
                apiService = apiService,
                taskId = taskId,
                url = url,
                artifactPath = artifactPath,
                deviceId = deviceId,
                phoneNumber = phoneNumber,
                onProgress = onProgress,
                onRetry = onRetry
            )
        }
    }

    private suspend fun downloadToCacheLocked(
        context: Context,
        apiService: VibeApiService,
        taskId: String,
        url: String?,
        artifactPath: String?,
        deviceId: String,
        phoneNumber: String?,
        onProgress: suspend (ApkDownloadProgress) -> Unit,
        onRetry: suspend (attempt: Int, maxAttempts: Int) -> Unit
    ): File {
        val normalizedTaskId = taskId.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing task_id for download")
        val normalizedArtifactPath = artifactPath?.trim()?.takeIf { it.isNotBlank() }
        val target = artifactDownloadCacheFile(context, normalizedTaskId, url, normalizedArtifactPath)
        val temp = File(target.parentFile ?: throw IOException("cache parent unavailable"), "${target.name}.tmp")

        clearManagedDownloads(context)
        return try {
            repeat(MAX_DOWNLOAD_ATTEMPTS) { attemptIndex ->
                try {
                    downloadAttempt(
                        apiService = apiService,
                        taskId = normalizedTaskId,
                        artifactPath = normalizedArtifactPath,
                        deviceId = deviceId,
                        phoneNumber = phoneNumber,
                        temp = temp,
                        onProgress = onProgress
                    )
                    replaceDownloadedFile(temp, target)
                    return target
                } catch (error: IOException) {
                    if (error is NonRetryableDownloadException || attemptIndex == MAX_DOWNLOAD_ATTEMPTS - 1) {
                        throw error
                    }
                    onRetry(attemptIndex + 2, MAX_DOWNLOAD_ATTEMPTS)
                    delay(RETRY_DELAY_MS * (attemptIndex + 1))
                }
            }
            throw IOException("download failed")
        } catch (error: Exception) {
            deleteTransientDownload(target)
            throw error
        }
    }

    private suspend fun downloadAttempt(
        apiService: VibeApiService,
        taskId: String,
        artifactPath: String?,
        deviceId: String,
        phoneNumber: String?,
        temp: File,
        onProgress: suspend (ApkDownloadProgress) -> Unit
    ) {
        val existingBytes = temp.length().coerceAtLeast(0L)
        val requestedRange = existingBytes.takeIf { it > 0L }?.let { "bytes=$it-" }
        val response = apiService.downloadApk(
            taskId,
            deviceId,
            null,
            phoneNumber,
            artifactPath,
            requestedRange
        )
        if (!response.isSuccessful) {
            val rawBody = response.errorBody()?.string()?.trim().orEmpty()
            val suffix = rawBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            val error = "server response ${response.code()}$suffix"
            if (response.code() == 408 || response.code() == 425 || response.code() == 429 || response.code() >= 500) {
                throw IOException(error)
            }
            if (response.code() == 416 && existingBytes > 0L) {
                temp.delete()
                throw IOException(error)
            }
            throw NonRetryableDownloadException(error)
        }

        val body = response.body() ?: throw IOException("empty response")
        val append = requestedRange != null && response.code() == 206
        val startingBytes = if (append) existingBytes else 0L
        val totalBytes = response.headers()["Content-Range"]
            ?.substringAfterLast('/', missingDelimiterValue = "")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: body.contentLength()
                .takeIf { it > 0L }
                ?.let { startingBytes + it }

        body.use { responseBody ->
            responseBody.byteStream().use { input ->
                FileOutputStream(temp, append).use { output ->
                    var downloadedBytes = startingBytes
                    onProgress(ApkDownloadProgress(downloadedBytes, totalBytes))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(ApkDownloadProgress(downloadedBytes, totalBytes))
                    }
                }
            }
        }

        val actualBytes = temp.length()
        if (totalBytes != null && actualBytes != totalBytes) {
            throw EOFException("incomplete download: $actualBytes of $totalBytes bytes")
        }
        if (actualBytes <= 0L) throw EOFException("empty download")
    }

    private fun replaceDownloadedFile(temp: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("failed to replace cached APK")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private class NonRetryableDownloadException(message: String) : IOException(message)

    internal fun transientApkFileName(taskId: String): String {
        val normalizedTaskId = taskId
            .trim()
            .ifBlank { "latest" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "latest" }
        return "$MANAGED_APK_PREFIX$normalizedTaskId.apk"
    }

    internal fun isManagedApkFileName(fileName: String): Boolean {
        if (!fileName.startsWith(MANAGED_APK_PREFIX)) return false
        return fileName.endsWith(".apk") || fileName.endsWith(".apk.tmp")
    }

    fun installApk(activity: Activity, file: File): Boolean {
        if (!file.exists()) return false
        if (needsInstallPermission(activity)) {
            return requestInstallPermission(activity)
        }
        return launchApkInstaller(activity, file)
    }

    fun needsInstallPermission(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
    }

    fun requestInstallPermission(activity: Activity): Boolean {
        return runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    fun launchApkInstaller(activity: Activity, file: File): Boolean {
        if (!file.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
        }.getOrNull() ?: return false
        val readFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(readFlags)
            this.clipData = clipData
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        if (runCatching { activity.startActivity(installIntent) }.isSuccess) {
            return true
        }

        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(readFlags)
                this.clipData = clipData
            }
            activity.startActivity(intent)
        }.isSuccess
    }
}
