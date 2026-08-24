package kr.ac.kangwon.hai.vibefactory

object ChatMessageTextPolicy {
    fun areSameContent(
        first: ChatMessage,
        second: ChatMessage,
        normalizedSyntheticPrompts: Set<String>
    ): Boolean {
        if (first.kind != second.kind) return false
        if (PromptReviewMessagePolicy.areEquivalent(first, second)) return true
        val firstArtifactKey = TaskProgressTimelinePolicy.artifactDedupeKey(first)
        val secondArtifactKey = TaskProgressTimelinePolicy.artifactDedupeKey(second)
        if (firstArtifactKey != null || secondArtifactKey != null) {
            return firstArtifactKey != null && firstArtifactKey == secondArtifactKey
        }

        val firstImages = first.allImagePreviews()
        val secondImages = second.allImagePreviews()
        val sameBody = if (first.kind == MessageKind.USER) {
            val firstBody = AttachmentOnlyMessagePolicy.canonicalUserBody(
                normalizedBody = normalize(first.body),
                hasImages = firstImages.isNotEmpty(),
                normalizedSyntheticPrompts = normalizedSyntheticPrompts
            )
            val secondBody = AttachmentOnlyMessagePolicy.canonicalUserBody(
                normalizedBody = normalize(second.body),
                hasImages = secondImages.isNotEmpty(),
                normalizedSyntheticPrompts = normalizedSyntheticPrompts
            )
            sameText(firstBody, secondBody)
        } else {
            sameText(first.body, second.body)
        }
        val sameDetail = sameText(first.detail, second.detail)
        return when (first.kind) {
            MessageKind.USER -> when {
                firstImages.isNotEmpty() != secondImages.isNotEmpty() -> sameBody
                firstImages.isNotEmpty() -> sameBody && AttachmentOnlyMessagePolicy.imageSelectionsEquivalent(
                    firstImages,
                    secondImages
                )
                else -> sameBody
            }
            MessageKind.ASSISTANT,
            MessageKind.CONFIRMATION,
            MessageKind.LOG -> sameBody && sameDetail
            MessageKind.DATE_SEPARATOR -> sameBody
            MessageKind.STATUS,
            MessageKind.BUILD_LOG -> sameBody && sameDetail && normalize(first.title) == normalize(second.title)
        }
    }

    fun sameText(left: String?, right: String?): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft == normalizedRight) return true
        val compactLeft = compact(normalizedLeft)
        val compactRight = compact(normalizedRight)
        if (compactLeft == compactRight) return true
        return stripInlineListMarkers(compactLeft) == stripInlineListMarkers(compactRight)
    }

    fun normalize(value: String?): String {
        return stripMarkdown(value.orEmpty())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    fun isPrebuildConfirmationHeader(value: String?): Boolean {
        val normalized = compact(normalize(value))
        return normalized == "앱 생성을 시작하기 전에 몇 가지만 확인할게요." ||
            normalized == "수정을 시작하기 전에 몇 가지만 확인할게요." ||
            PromptReviewMessagePolicy.isStandaloneReadyMessage(value)
    }

    fun isHiddenOperationalBuildMessage(value: String?): Boolean {
        return compact(normalize(value)) in HIDDEN_OPERATIONAL_BUILD_MESSAGES
    }

    private fun stripMarkdown(value: String): String {
        return value
            .replace(Regex("""\*\*(.*?)\*\*""")) { match -> match.groupValues[1] }
            .replace(Regex("""`([^`]*)`""")) { match -> match.groupValues[1] }
            .lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("""^[-*•]\s+"""), "")
                    .replace(Regex("""^\d+[.)]\s+"""), "")
            }
            .joinToString("\n")
    }

    private fun stripInlineListMarkers(value: String): String {
        return value
            .replace(Regex("""(^|\s)(?:[-*•]|\d+[.)])\s+"""), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val HIDDEN_OPERATIONAL_BUILD_MESSAGES = setOf(
        "APK build completed",
        "APK 빌드가 완료되었어요.",
        "APK 생성이 완료되었어요.",
        "앱 생성이 완료되었어요.",
        "앱 생성이 완료되었어요",
        "앱 생성 작업을 진행하고 있습니다.",
        "앱 생성 작업을 진행하고 있습니다"
    )
}
