package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StackFrameQualitySelectionTest {
    @Test
    fun selectsHighestQualityAcrossEntireSeriesAndRestoresCaptureOrder() {
        val candidates = (0 until 50).map { index ->
            StackFrameQualityCandidate(
                captureIndex = index,
                statusRank = if (index >= 20) 4 else 2,
                sharpness = index.toDouble(),
                clippedPercent = 0.0,
                brightness = 40.0
            )
        }

        val selected = selectBestStackFrameIndices(candidates, 30)

        assertEquals((20 until 50).toList(), selected)
    }

    @Test
    fun statusOutranksMisleadingSharpnessNoise() {
        val selected = selectBestStackFrameIndices(
            listOf(
                StackFrameQualityCandidate(0, 1, 10_000.0, 20.0, 250.0),
                StackFrameQualityCandidate(1, 4, 20.0, 0.0, 60.0),
                StackFrameQualityCandidate(2, 4, 15.0, 0.0, 55.0)
            ),
            maxFrames = 2
        )

        assertEquals(listOf(1, 2), selected)
        assertTrue(0 !in selected)
    }
}
