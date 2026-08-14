package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseScrollPolicyTest {
    @Test
    fun `transient progress keeps explicit response scroll pending`() {
        assertFalse(
            ChatResponseScrollPolicy.shouldClearPendingScroll(
                pending = true,
                scrollNow = false,
                pinBottomForTransientUpdate = true
            )
        )
    }

    @Test
    fun `manual position preservation clears pending response scroll`() {
        assertTrue(
            ChatResponseScrollPolicy.shouldClearPendingScroll(
                pending = true,
                scrollNow = false,
                pinBottomForTransientUpdate = false
            )
        )
    }

    @Test
    fun `scroll being applied does not clear through cancellation path`() {
        assertFalse(
            ChatResponseScrollPolicy.shouldClearPendingScroll(
                pending = true,
                scrollNow = true,
                pinBottomForTransientUpdate = false
            )
        )
    }
}
