package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Uses the same stellar selector as manual Median/Sigma, including short-series validity filtering. */
class StackFrameQualitySelectionTest {
    @Test
    fun selectsBestStarsAcrossEntireSeriesAndRestoresCaptureOrder() {
        val frames = (0 until 50).map { index ->
            analysis(index).copy(medianStarWidth = if (index < 20) 4f else 1.5f)
        }
        assertEquals((20 until 50).toList(), selected(frames, 30))
    }

    @Test
    fun hardInvalidCannotWinEvenWithExcellentStellarMetrics() {
        val good = analysis(3)
        val invalid = analysis(0).copy(reliableStarCount = 5000, medianStarContrast = 255f, clippingPercent = 80f)
        val black = analysis(1).copy(backgroundLevel = 0f, reliableStarCount = 0)
        val unreadable = FrameAnalysis.invalid("2", "2.jpg")
        assertEquals(listOf(3), selected(listOf(invalid, black, unreadable, good), 30))
    }

    @Test
    fun equalQualityUsesTemporalCenterThenCaptureIndexDeterministically() {
        val frames = (0 until 6).map(::analysis)
        assertEquals(listOf(1, 2, 3), selected(frames, 3))
        assertEquals(selected(frames, 3), selected(frames.reversed(), 3))
    }

    @Test
    fun roundCompactStarsBeatNoisyElongatedStars() {
        val noisy = analysis(0).copy(medianStarWidth = 3.5f, medianStarEllipticity = 0.7f, backgroundNoise = 30f)
        val compact = analysis(1).copy(medianStarWidth = 1.5f, medianStarEllipticity = 0.1f, backgroundNoise = 1f)
        assertEquals(listOf(1), selected(listOf(noisy, compact), 1))
        assertFalse(noisy.hardInvalidReason != null)
    }

    private fun selected(frames: List<FrameAnalysis>, maximum: Int): List<Int> =
        ReferenceFrameSelector().selectForIntegration(
            frames, frames.associate { it.id to it.id.toInt() }, maximum
        ).analyses.map { it.id.toInt() }

    private fun analysis(index: Int) = FrameAnalysis(
        id = index.toString(), fileName = "$index.jpg", width = 800, height = 600,
        stars = emptyList(), reliableStarCount = 20, medianStarContrast = 20f,
        medianStarWidth = 2f, medianStarEllipticity = 0.2f, backgroundNoise = 2f,
        clippingPercent = 0f, exposureSuitability = 0.8f, decodeValid = true,
        alignmentSuitability = 0.8f, skyMaskConfidence = 0.9f, skyMaskUsedFallback = false,
        backgroundLevel = 20f
    )
}
