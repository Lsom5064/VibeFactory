package kr.ac.kangwon.hai.vibefactory

internal object PromptReviewMessagePolicy {
    const val READY_MESSAGE =
        "앱 생성 프롬프트를 준비했어요. 확인하거나 수정한 뒤 전송해 주세요."

    fun isStandaloneReadyMessage(value: String?): Boolean {
        return normalize(value) == normalize(READY_MESSAGE)
    }

    fun areEquivalent(first: ChatMessage, second: ChatMessage): Boolean {
        if (first.kind != MessageKind.CONFIRMATION || second.kind != MessageKind.CONFIRMATION) {
            return false
        }
        if (!isPromptReview(first) || !isPromptReview(second)) return false
        val firstPrompt = canonicalPrompt(first)
        val secondPrompt = canonicalPrompt(second)
        return firstPrompt.isNotBlank() && firstPrompt == secondPrompt
    }

    fun isPromptReview(message: ChatMessage): Boolean {
        return message.kind == MessageKind.CONFIRMATION &&
            (message.confirmAction == "submit_initial_prompt" ||
                !message.promptReviewText.isNullOrBlank()
            )
    }

    fun shouldShowConfirmationActions(message: ChatMessage): Boolean =
        message.kind == MessageKind.CONFIRMATION && !isPromptReview(message)

    fun canEdit(message: ChatMessage, handled: Boolean): Boolean =
        isPromptReview(message) && !handled && !message.promptReviewSubmitted

    fun shouldRestoreReview(
        awaitingReview: Boolean?, renderMode: String, confirmationAction: String,
        responseSuggestsReview: Boolean
    ): Boolean = awaitingReview ?: (renderMode == "prompt_review_bubble" ||
        confirmationAction == "submit_initial_prompt" || responseSuggestsReview)

    /** Keep the original card's slot and identity; discard only duplicate copies of this submission. */
    fun reconcileSubmittedReviews(
        messages: List<ChatMessage>, taskId: String, submittedPrompt: String,
        submittedDetail: String, preferredMessageId: String = ""
    ): List<ChatMessage> {
        val finalPrompt = normalize(submittedPrompt)
        if (finalPrompt.isBlank()) return messages
        val indices = messages.indices.filter { index ->
            val message = messages[index]
            isPromptReview(message) && (message.promptReviewTaskId ?: message.confirmTaskId) == taskId
        }
        if (indices.isEmpty()) return messages
        val selected = indices.firstOrNull { messages[it].id == preferredMessageId }
            ?: indices.firstOrNull { canonicalPrompt(messages[it]) == finalPrompt }
            ?: indices.last()
        return messages.mapIndexedNotNull { index, message ->
            when {
                index == selected -> withSubmittedPrompt(message, submittedPrompt, submittedDetail)
                index in indices && canonicalPrompt(message) == finalPrompt -> null
                else -> message
            }
        }
    }

    fun withSubmittedPrompt(
        message: ChatMessage,
        submittedPrompt: String,
        submittedDetail: String
    ): ChatMessage {
        val finalPrompt = submittedPrompt.trim()
        if (!isPromptReview(message) || finalPrompt.isBlank()) return message
        return message.copy(
            body = finalPrompt,
            detail = submittedDetail.trim().ifBlank { message.detail },
            confirmPayload = finalPrompt,
            promptReviewText = finalPrompt,
            promptReviewSubmitted = true
        )
    }

    private fun canonicalPrompt(message: ChatMessage): String {
        val prompt = message.promptReviewText?.takeIf { it.isNotBlank() }
            ?: message.confirmPayload?.takeIf { it.isNotBlank() }
            ?: message.body
        return normalize(prompt)
    }

    private fun normalize(value: String?): String {
        return value.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }
}
