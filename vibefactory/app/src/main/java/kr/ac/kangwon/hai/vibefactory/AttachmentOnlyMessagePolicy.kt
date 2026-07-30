package kr.ac.kangwon.hai.vibefactory

object AttachmentOnlyMessagePolicy {
    fun canonicalUserBody(
        normalizedBody: String,
        hasImages: Boolean,
        normalizedSyntheticPrompts: Set<String>
    ): String {
        if (!hasImages) return normalizedBody
        return normalizedBody.takeUnless { it in normalizedSyntheticPrompts }.orEmpty()
    }

    fun imageSelectionsEquivalent(
        first: List<ChatImagePreview>,
        second: List<ChatImagePreview>
    ): Boolean {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty()
        val firstSources = first.map { it.base64.ifBlank { it.remoteUrl.orEmpty() } }
        val secondSources = second.map { it.base64.ifBlank { it.remoteUrl.orEmpty() } }
        if (firstSources == secondSources) return true
        return normalizedNames(first) == normalizedNames(second)
    }

    fun imageIdentity(previews: List<ChatImagePreview>): String {
        return normalizedNames(previews).joinToString("|")
    }

    fun mergeLocalWithServerEcho(local: ChatMessage, server: ChatMessage): ChatMessage {
        val serverPreviews = server.allImagePreviews()
        val localPreviews = local.allImagePreviews()
        val mergedPreviews = serverPreviews.ifEmpty { localPreviews }
        val keepsLegacyLocalPreview = serverPreviews.isEmpty() && mergedPreviews.isNotEmpty()
        return server.copy(
            id = local.id,
            body = server.body.ifBlank { local.body },
            detail = server.detail ?: local.detail,
            createdAt = local.createdAt ?: server.createdAt,
            imagePreviewBase64 = if (keepsLegacyLocalPreview) {
                local.imagePreviewBase64
            } else {
                null
            },
            imagePreviewName = mergedPreviews.firstOrNull()?.displayName,
            imagePreviews = mergedPreviews
        )
    }

    private fun normalizedNames(previews: List<ChatImagePreview>): List<String> {
        return previews.map { preview ->
            preview.displayName.trim().lowercase().ifBlank { "attached-image" }
        }
    }
}
