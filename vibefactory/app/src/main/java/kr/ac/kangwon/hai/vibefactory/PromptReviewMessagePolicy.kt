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

    fun shouldShowConfirmationActions(message: ChatMessage, handled: Boolean): Boolean {
        if (message.kind != MessageKind.CONFIRMATION) return false
        return !handled || !isPromptReview(message)
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
