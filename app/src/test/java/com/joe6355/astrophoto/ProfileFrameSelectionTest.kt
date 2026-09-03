package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFrameSelectionTest {
    @Test
    fun shortSeriesKeepsEveryValidFrameInCaptureOrder() {
        val analyses = listOf(analysis("f3", 8), analysis("f1", 12), analysis("f2", 10))
        val indices = mapOf("f1" to 1, "f2" to 2, "f3" to 3)

        val selected = ReferenceFrameSelector().selectForIntegration(analyses, indices, 30)

        assertEquals(listOf("f1", "f2", "f3"), selected.analyses.map { it.id })
        assertEquals(0, selected.droppedCount)
    }

    @Test
    fun longSeriesKeepsOnlyBestFrames() {
        val analyses = (0 until 50).map { index ->
            analysis("f$index", if (index < 20) 40 - index else 2)
        }
        val indices = analyses.associate { it.id to it.id.removePrefix("f").toInt() }

        val selected = ReferenceFrameSelector().selectForIntegration(analyses, indices, 10)
        val selectedIndices = selected.analyses.map { indices.getValue(it.id) }

        assertEquals(10, selected.analyses.size)
        assertTrue("highest-quality frame must be retained", 0 in selectedIndices)
        assertEquals((0 until 10).toList(), selectedIndices)
        assertEquals(40, selected.droppedCount)
    }

    @Test
    fun equalQualityUsesTemporalThenCaptureIndexTieBreak() {
        val analyses = (0 until 9).map { analysis("f$it", 10) }
        val indices = analyses.associate { it.id to it.id.removePrefix("f").toInt() }

        val selected = ReferenceFrameSelector().selectForIntegration(analyses, indices, 3)

        assertEquals(listOf("f3", "f4", "f5"), selected.analyses.map { it.id })
    }

    @Test
    fun criticallyClippedFrameIsNeverSelected() {
        val clipped = analysis("clipped", 100).copy(clippingPercent = 60f)
        val valid = analysis("valid", 2)

        val selected = ReferenceFrameSelector().selectForIntegration(
            listOf(clipped, valid),
            mapOf("clipped" to 0, "valid" to 1),
            30
        )

        assertEquals(listOf("valid"), selected.analyses.map { it.id })
        assertEquals(1, selected.droppedCount)
    }

    @Test
    fun invalidFramesAreNotSelected() {
        val valid = analysis("valid", 10)
        val invalid = FrameAnalysis.invalid("invalid", "invalid.jpg")

        val selected = ReferenceFrameSelector().selectForIntegration(
            listOf(invalid, valid),
            mapOf("invalid" to 1, "valid" to 2),
            30
        )

        assertEquals(listOf("valid"), selected.analyses.map { it.id })
        assertEquals(1, selected.droppedCount)
    }

    private fun analysis(id: String, stars: Int) = FrameAnalysis(
        id = id,
        fileName = "$id.jpg",
        width = 960,
        height = 720,
        stars = emptyList(),
        reliableStarCount = stars,
        medianStarContrast = stars.toFloat(),
        medianStarWidth = 2f,
        medianStarEllipticity = 0.1f,
        backgroundNoise = 3f,
        clippingPercent = 0f,
        exposureSuitability = 0.9f,
        decodeValid = true,
        alignmentSuitability = 0.9f,
        skyMaskConfidence = 0.9f,
        skyMaskUsedFallback = false
    )
}
