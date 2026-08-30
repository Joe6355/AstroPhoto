package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesReliabilityTest {
    @Test
    fun shortExposureGetsEnoughCameraAndStorageOverhead() {
        assertEquals(30_100L, seriesFrameTimeoutMillis(100_000_000L))
    }

    @Test
    fun longExposureTimeoutIncludesHalfExposureOverhead() {
        assertEquals(180_000L, seriesFrameTimeoutMillis(120_000_000_000L))
    }

    @Test
    fun malformedExposureCannotCreateUnboundedTimeout() {
        assertTrue(seriesFrameTimeoutMillis(Long.MAX_VALUE) <= 15 * 60_000L)
        assertEquals(30_000L, seriesFrameTimeoutMillis(-1L))
    }
}
