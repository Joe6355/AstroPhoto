package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegResultStatisticsTest {
    @Test
    fun calculatesUserVisibleResultStatistics() {
        val analyses = listOf(analysis("a", 2f, 0f), analysis("b", 4f, 6f))
        val registrations = listOf(registration(0.8f), registration(0.6f))

        val result = JpegResultStatisticsCalculator.calculate(
            "exposureTimeNs: 5000000000",
            inputFrames = 3,
            analyses = analyses,
            registrations = registrations
        )

        assertEquals(2, result.usedFrames)
        assertEquals(1, result.droppedFrames)
        assertEquals(10_000_000_000L, result.totalExposureNs)
        assertEquals(3f, result.medianFwhm ?: 0f, 0f)
        assertEquals(0.7f, result.meanAlignmentConfidence ?: 0f, 0.0001f)
        assertEquals(1, result.clippedFrames)
    }

    private fun analysis(id: String, width: Float, clipping: Float) = FrameAnalysis(
        id, "$id.jpg", 10, 10, emptyList(), 3, 10f, width, 0.1f, 2f,
        clipping, 0.8f, true, 0.8f, 0.8f, false, 20f
    )

    private fun registration(confidence: Float) = RegistrationResult(
        0f, 0f, 0f, 1f, 5, 5, 5, 0f, confidence, true, null
    )
}
