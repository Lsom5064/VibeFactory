package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceImageSupportTest {
    @Test
    fun sampleSizeKeepsDecodedBitmapNearRequestedDimension() {
        assertEquals(4, calculateBitmapSampleSize(width = 8000, height = 6000, maxDimension = 1600))
        assertEquals(2, calculateBitmapSampleSize(width = 4032, height = 3024, maxDimension = 1600))
    }

    @Test
    fun sampleSizeDoesNotUpscaleSmallOrInvalidImages() {
        assertEquals(1, calculateBitmapSampleSize(width = 1200, height = 800, maxDimension = 1600))
        assertEquals(1, calculateBitmapSampleSize(width = 0, height = 800, maxDimension = 1600))
        assertEquals(1, calculateBitmapSampleSize(width = 1200, height = 800, maxDimension = 0))
    }
}
