package kr.ac.kangwon.hai.vibefactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object ApkArtifactActionHandler {
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
        url: String?,
        artifactPath: String?
    ): File {
        val cacheDir = context.externalCacheDir ?: throw IOException("external cache unavailable")
        val normalizedTaskId = taskId.trim().ifBlank { "latest" }
        val artifactIdentity = artifactPath?.trim()?.takeIf { it.isNotBlank() }
            ?: url?.trim()?.takeIf { it.isNotBlank() }
            ?: "latest"
        val artifactKey = Integer.toUnsignedString("$normalizedTaskId|$artifactIdentity".hashCode(), 36)
        return File(cacheDir, "generated_app_${normalizedTaskId}_$artifactKey.apk")
    }

    suspend fun downloadToCache(
        context: Context,
        apiService: VibeApiService,
        taskId: String,
        url: String?,
        artifactPath: String?,
        deviceId: String,
        phoneNumber: String?
    ): File {
        val normalizedTaskId = taskId.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing task_id for download")
        val normalizedArtifactPath = artifactPath?.trim()?.takeIf { it.isNotBlank() }
        val response = apiService.downloadApk(
            normalizedTaskId,
            deviceId,
            null,
            phoneNumber,
            normalizedArtifactPath
        )
        if (!response.isSuccessful) {
            val rawBody = response.errorBody()?.string()?.trim().orEmpty()
            val suffix = rawBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            throw IOException("server response ${response.code()}$suffix")
        }

        val target = artifactDownloadCacheFile(context, normalizedTaskId, url, normalizedArtifactPath)
        val temp = File(target.parentFile ?: throw IOException("cache parent unavailable"), "${target.name}.tmp")
        response.body()?.byteStream()?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("empty response")

        if (target.exists() && !target.delete()) {
            throw IOException("failed to replace cached APK")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun installApk(activity: Activity, file: File): Boolean {
        if (!file.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return true
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return true
    }
}
