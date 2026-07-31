package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressRenderPolicyTest {
    @Test
    fun rapidProgressUpdatesAreCoalesced() {
        val policy = DownloadProgressRenderPolicy(minimumRenderIntervalMs = 200L)

        assertTrue(policy.evaluate(1L, 100L, nowMs = 0L).shouldRender)
        assertFalse(policy.evaluate(10L, 100L, nowMs = 50L).shouldRender)
        assertTrue(policy.evaluate(20L, 100L, nowMs = 200L).shouldRender)
    }

    @Test
    fun completionAlwaysRendersImmediately() {
        val policy = DownloadProgressRenderPolicy(minimumRenderIntervalMs = 1_000L)
        policy.evaluate(90L, 100L, nowMs = 0L)

        val completed = policy.evaluate(100L, 100L, nowMs = 10L)

        assertTrue(completed.shouldRender)
        assertEquals(100, completed.percent)
    }

    @Test
    fun resetAllowsRetryToRenderFromZero() {
        val policy = DownloadProgressRenderPolicy(minimumRenderIntervalMs = 1_000L)
        policy.evaluate(50L, 100L, nowMs = 0L)
        policy.reset()

        val retry = policy.evaluate(0L, 100L, nowMs = 1L)

        assertTrue(retry.shouldRender)
        assertEquals(0, retry.percent)
    }
}
