package kr.ac.kangwon.hai.vibefactory

internal object UiRenderFingerprint {
    private const val SEED = 1_125_899_906_842_597L
    private const val MULTIPLIER = 31L
    private const val PAYLOAD_SAMPLE_LENGTH = 48

    fun taskList(
        tasks: List<TaskSummary>,
        selectedTaskId: String?,
        runtimeErrorTaskIds: Set<String>
    ): Long {
        var fingerprint = mix(SEED, selectedTaskId)
        tasks.forEach { task ->
            fingerprint = mix(fingerprint, task.taskId)
            fingerprint = mix(fingerprint, task.title)
            fingerprint = mix(fingerprint, task.appName)
            fingerprint = mix(fingerprint, task.status)
            fingerprint = mix(fingerprint, task.updatedAt)
            fingerprint = mix(fingerprint, task.hasApk)
            fingerprint = mix(fingerprint, task.taskId in runtimeErrorTaskIds)
        }
        return fingerprint
    }

    fun messages(taskId: String?, messages: List<ChatMessage>): Long {
        var fingerprint = mix(SEED, taskId)
        messages.forEach { message ->
            fingerprint = mix(fingerprint, message.id)
            fingerprint = mix(fingerprint, message.kind)
            fingerprint = mix(fingerprint, message.title)
            fingerprint = mix(fingerprint, message.body)
            fingerprint = mix(fingerprint, message.detail)
            fingerprint = mix(fingerprint, message.createdAt)
            fingerprint = mix(fingerprint, message.isLoading)
            fingerprint = mix(fingerprint, message.artifactDownloading)
            fingerprint = mix(fingerprint, message.artifactDownloadProgressPercent)
            fingerprint = mix(fingerprint, message.artifactDownloadProgressText)
            fingerprint = mix(fingerprint, message.artifactCanDownload)
            fingerprint = mix(fingerprint, message.artifactCanInstall)
            fingerprint = mix(fingerprint, message.artifactInstalled)
            fingerprint = mix(fingerprint, message.artifactPackageName)
            fingerprint = mix(fingerprint, message.artifactTaskId)
            fingerprint = mix(fingerprint, message.artifactRevisionLabel)
            fingerprint = mix(fingerprint, message.cancelTaskId)
            message.allImagePreviews().forEach { preview ->
                fingerprint = mix(fingerprint, preview.displayName)
                fingerprint = mix(fingerprint, binaryPayload(preview.base64))
                fingerprint = mix(fingerprint, preview.remoteUrl)
            }
        }
        return fingerprint
    }

    fun attachments(attachments: List<SelectedAttachment>): Long {
        var fingerprint = SEED
        attachments.forEach { attachment ->
            fingerprint = mix(fingerprint, attachment.kind)
            fingerprint = mix(fingerprint, attachment.displayName)
            fingerprint = mix(fingerprint, attachment.mimeType)
            fingerprint = mix(fingerprint, binaryPayload(attachment.base64))
        }
        return fingerprint
    }

    fun binaryPayload(payload: String): Long {
        if (payload.isEmpty()) return 0L
        var fingerprint = mix(SEED, payload.length)
        fingerprint = mix(fingerprint, payload.hashCode())
        fingerprint = mix(fingerprint, payload.take(PAYLOAD_SAMPLE_LENGTH))
        fingerprint = mix(fingerprint, payload.takeLast(PAYLOAD_SAMPLE_LENGTH))
        return fingerprint
    }

    private fun mix(current: Long, value: Any?): Long {
        return current * MULTIPLIER + (value?.hashCode()?.toLong() ?: 0L)
    }
}
