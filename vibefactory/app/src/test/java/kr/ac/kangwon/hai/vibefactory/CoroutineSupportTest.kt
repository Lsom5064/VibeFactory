package kr.ac.kangwon.hai.vibefactory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineSupportTest {
    @Test(expected = CancellationException::class)
    fun runSuspendCatchingDoesNotConsumeCancellation() {
        runBlocking {
            runSuspendCatching<Unit> {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun runSuspendCatchingReturnsRegularFailures() = runBlocking {
        val result = runSuspendCatching<Unit> {
            error("failed")
        }
        assertTrue(result.isFailure)
        assertEquals("failed", result.exceptionOrNull()?.message)
    }
}
