package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSanityTest {
    private val baseline = metrics(stars = 10, contrast = 80f, black = 2f, white = 1f, range = 180)

    @Test
    fun validOutputPasses() {
        assertTrue(evaluateProfileSanity(baseline, baseline) is ProfileSanityResult.Passed)
    }

    @Test
    fun blackAndWhiteCollapseFail() {
        assertFailed("black", baseline.copy(blackPercent = 99f))
        assertFailed("white", baseline.copy(whitePercent = 99f))
    }

    @Test
    fun uniformCollapseFailsWhenBaselineHadRange() {
        assertFailed("uniform", baseline.copy(dynamicRange = 1))
    }

    @Test
    fun extremeStarLossFails() {
        assertFailed("stars", baseline.copy(stars = 2))
    }

    @Test
    fun extremeContrastLossFails() {
        assertFailed("contrast", baseline.copy(averageStarContrast = 10f))
    }

    @Test
    fun extremeBackgroundClippingFails() {
        assertFailed("clipping", baseline.copy(blackPercent = 85f))
    }

    @Test
    fun dimensionChangeAndInvalidChannelsFail() {
        assertFailed("dimensions", baseline.copy(width = 31))
        assertFailed("dimensions", baseline.copy(width = 0))
        assertFailed("channels", baseline.copy(channelsValid = false))
    }

    @Test
    fun realSyntheticImageProducesFiniteMetrics() {
        val image = SyntheticImageTestData.stars(
            48, 48, 20,
            listOf(Triple(10, 10, 100), Triple(20, 30, 140), Triple(34, 18, 180))
        )
        val result = analyzeProfileImage(image, AstroRoi.Full, StarDetectionSensitivity.HIGH)
        assertEquals(48, result.width)
        assertTrue(result.background in 0..255)
        assertTrue(result.blackPercent.isFinite() && result.whitePercent.isFinite())
    }

    @Test
    fun extremeBackgroundLiftFromDarkStackFails() {
        val darkStack = baseline.copy(background = 6, lowPercentile = 4, highPercentile = 80)
        assertFailed(
            "brightness",
            darkStack.copy(background = 125, lowPercentile = 90, highPercentile = 190)
        )
    }

    @Test
    fun washedOutFlatGreyFailsWithoutRequiringWhiteCollapse() {
        val varied = baseline.copy(background = 50, flatGrayPercent = 12f)
        assertFailed(
            "flat grey",
            varied.copy(background = 70, flatGrayPercent = 90f, dynamicRange = 35)
        )
    }

    @Test
    fun medianStarContrastCollapseFails() {
        val result = evaluateProfileSanity(
            baseline.copy(averageStarContrast = 80f, medianStarContrast = 70f),
            baseline.copy(averageStarContrast = 70f, medianStarContrast = 15f)
        ) as ProfileSanityResult.Failed
        assertTrue(result.reason.contains("median star contrast"))
    }

    @Test
    fun severeLargeScaleBandingRegressionFails() {
        val result = evaluateProfileSanity(
            baseline.copy(largeScaleBanding = 1f),
            baseline.copy(largeScaleBanding = 18f)
        ) as ProfileSanityResult.Failed
        assertTrue(result.reason.contains("banding"))
    }

    @Test
    fun measuredBandingMetricDetectsBroadAlternatingBands() {
        val pixels = IntArray(64 * 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            val value = if ((x / 8) % 2 == 0) 35 else 105
            pixels[y * 64 + x] = gray(value)
        }
        val metrics = analyzeProfileImage(
            ArgbPixelImage(64, 64, pixels),
            AstroRoi.Full,
            StarDetectionSensitivity.MEDIUM
        )
        assertTrue(metrics.largeScaleBanding >= ProfileSanityThresholds.MIN_SEVERE_BANDING)
    }

    private fun assertFailed(expected: String, after: ProfileSanityMetrics) {
        val result = evaluateProfileSanity(baseline, after) as ProfileSanityResult.Failed
        assertTrue(result.reason.contains(expected))
    }

    private fun metrics(
        stars: Int,
        contrast: Float,
        black: Float,
        white: Float,
        range: Int
    ) = ProfileSanityMetrics(32, 32, stars, contrast, black, white, 20, range, true)

    private fun gray(value: Int) =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value
}
