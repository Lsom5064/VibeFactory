package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageRefreshGateTest {
    @Test
    fun skipsAdapterRefreshWhileTextSelectionIsActiveEvenIfUpdatesArrive() {
        assertEquals(
            ChatMessageRefreshAction.SKIP_FOR_TEXT_SELECTION,
            chooseChatMessageRefreshAction(
                isTextSelectionActive = true,
                isRecyclerComputingLayout = false
            )
        )
    }

    @Test
    fun textSelectionTakesPriorityOverRecyclerLayoutDeferral() {
        assertEquals(
            ChatMessageRefreshAction.SKIP_FOR_TEXT_SELECTION,
            chooseChatMessageRefreshAction(
                isTextSelectionActive = true,
                isRecyclerComputingLayout = true
            )
        )
    }

    @Test
    fun defersRefreshAfterSelectionEndsIfRecyclerViewIsStillComputingLayout() {
        assertEquals(
            ChatMessageRefreshAction.DEFER_FOR_RECYCLER_LAYOUT,
            chooseChatMessageRefreshAction(
                isTextSelectionActive = false,
                isRecyclerComputingLayout = true
            )
        )
    }

    @Test
    fun submitsRefreshOnlyWhenSelectionAndRecyclerLayoutAreIdle() {
        assertEquals(
            ChatMessageRefreshAction.SUBMIT,
            chooseChatMessageRefreshAction(
                isTextSelectionActive = false,
                isRecyclerComputingLayout = false
            )
        )
    }
}
