package kr.ac.kangwon.hai.vibefactory

internal enum class ChatMessageRefreshAction {
    SUBMIT,
    SKIP_FOR_TEXT_SELECTION,
    DEFER_FOR_RECYCLER_LAYOUT
}

internal fun chooseChatMessageRefreshAction(
    isTextSelectionActive: Boolean,
    isRecyclerComputingLayout: Boolean
): ChatMessageRefreshAction {
    return when {
        isTextSelectionActive -> ChatMessageRefreshAction.SKIP_FOR_TEXT_SELECTION
        isRecyclerComputingLayout -> ChatMessageRefreshAction.DEFER_FOR_RECYCLER_LAYOUT
        else -> ChatMessageRefreshAction.SUBMIT
    }
}
