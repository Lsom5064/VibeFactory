package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsagePresentationTest {
    @Test
    fun missingWindowDoesNotBecomeZeroPercent() {
        assertNull(normalizeRemainingPercent(null))
        assertNull(normalizeRemainingPercent(TokenUsageWindowDto(remaining_percent = null)))
    }

    @Test
    fun reportedPercentIsClampedToDisplayRange() {
        assertEquals(0, normalizeRemainingPercent(TokenUsageWindowDto(remaining_percent = -7)))
        assertEquals(63, normalizeRemainingPercent(TokenUsageWindowDto(remaining_percent = 63)))
        assertEquals(100, normalizeRemainingPercent(TokenUsageWindowDto(remaining_percent = 130)))
    }
}
