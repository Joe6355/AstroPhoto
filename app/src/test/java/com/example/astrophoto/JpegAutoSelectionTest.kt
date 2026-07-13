package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Test

class JpegAutoSelectionTest {
    @Test
    fun sharpnessBaselineIsNotPulledUpBySingleNoisyOutlier() {
        assertEquals(
            107.5,
            robustSharpnessBaseline(listOf(100.0, 110.0, 105.0, 10_000.0)),
            0.0
        )
    }

    @Test
    fun sharpnessBaselineIgnoresInvalidMetricsAndInputOrder() {
        val values = listOf(Double.NaN, -1.0, 30.0, 10.0, Double.POSITIVE_INFINITY, 20.0)

        assertEquals(20.0, robustSharpnessBaseline(values), 0.0)
        assertEquals(20.0, robustSharpnessBaseline(values.reversed()), 0.0)
    }

    @Test
    fun emptySharpnessBaselineIsSafe() {
        assertEquals(0.0, robustSharpnessBaseline(emptyList()), 0.0)
    }

    @Test
    fun darkThresholdAdaptsToAnEntireLowLightSeries() {
        assertEquals(
            6.0,
            adaptiveDarkBrightnessThreshold(
                absoluteThreshold = 25.0,
                blackThreshold = 5.0,
                brightnessValues = listOf(9.0, 10.0, 11.0, 12.0)
            ),
            0.0
        )
    }

    @Test
    fun darkThresholdKeepsAbsoluteLimitForBrightSeries() {
        assertEquals(
            25.0,
            adaptiveDarkBrightnessThreshold(
                absoluteThreshold = 25.0,
                blackThreshold = 5.0,
                brightnessValues = listOf(70.0, 80.0, 90.0)
            ),
            0.0
        )
    }
}
