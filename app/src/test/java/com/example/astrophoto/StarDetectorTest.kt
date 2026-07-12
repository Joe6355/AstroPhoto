package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarDetectorTest {
    private val detector = StarDetector()

    @Test
    fun emptyBlackImageHasNoStars() {
        assertTrue(detector.detect(SyntheticImageTestData.uniform(value = 0)).stars.isEmpty())
    }

    @Test
    fun uniformGreyImageHasNoStars() {
        assertTrue(detector.detect(SyntheticImageTestData.uniform(value = 80)).stars.isEmpty())
    }

    @Test
    fun oneIsolatedStarIsDetectedAtItsCentroid() {
        val result = detector.detect(starImage(Triple(20, 21, 180)))
        assertEquals(listOf(20 to 21), result.stars.map { it.x to it.y })
    }

    @Test
    fun multipleSeparatedStarsAreDetected() {
        val points = listOf(Triple(10, 10, 100), Triple(20, 30, 180), Triple(34, 15, 140))
        assertEquals(3, detector.detect(starImage(*points.toTypedArray())).stars.size)
    }

    @Test
    fun dimStarAboveThresholdIsDetected() {
        assertEquals(1, detector.detect(starImage(Triple(20, 20, 40))).stars.size)
    }

    @Test
    fun pixelBelowThresholdIsIgnored() {
        assertTrue(detector.detect(starImage(Triple(20, 20, 30))).stars.isEmpty())
    }

    @Test
    fun connectedBrightPixelsProduceOneCentroid() {
        val image = starImage(
            Triple(20, 20, 180), Triple(21, 20, 180),
            Triple(20, 21, 180), Triple(21, 21, 180)
        )
        val result = detector.detect(image)
        assertEquals(1, result.stars.size)
        assertEquals(21 to 21, result.stars.single().let { it.x to it.y })
    }

    @Test
    fun twoSeparatedConnectedGroupsRemainSeparate() {
        val image = starImage(
            Triple(12, 12, 150), Triple(13, 12, 150),
            Triple(30, 30, 170), Triple(31, 30, 170)
        )
        assertEquals(2, detector.detect(image).stars.size)
    }

    @Test
    fun largeBrightBlobIsRejected() {
        val points = mutableListOf<Triple<Int, Int, Int>>()
        for (y in 17..23) for (x in 17..23) points += Triple(x, y, 180)
        assertTrue(detector.detect(starImage(*points.toTypedArray())).stars.isEmpty())
    }

    @Test
    fun isolatedHotPixelMatchesCurrentStarIntent() {
        assertEquals(1, detector.detect(starImage(Triple(20, 20, 255))).stars.size)
    }

    @Test
    fun roiExcludesOutsidePixels() {
        val result = detector.detect(
            starImage(Triple(10, 20, 180), Triple(30, 20, 200)),
            AstroRoi(0f, 0f, 0.5f, 1f)
        )
        assertEquals(listOf(10 to 20), result.stars.map { it.x to it.y })
    }

    @Test
    fun brightnessOrderingIsDescending() {
        val stars = detector.detect(
            starImage(Triple(10, 10, 100), Triple(20, 20, 220), Triple(30, 30, 150))
        ).stars
        assertEquals(listOf(220, 150, 100), stars.map { it.brightness })
    }

    @Test
    fun imageBorderStarIsIgnoredByCurrentMarginRule() {
        assertTrue(detector.detect(starImage(Triple(0, 0, 220))).stars.isEmpty())
    }

    @Test
    fun deterministicNoiseProducesDeterministicResult() {
        val image = SyntheticImageTestData.noisy(starImage(Triple(20, 20, 180)), 99, 3)
        assertEquals(detector.detect(image), detector.detect(image))
    }

    @Test
    fun tinyImagesDoNotReadOutsideBounds() {
        assertTrue(detector.detect(SyntheticImageTestData.uniform(1, 1, 20)).stars.isEmpty())
        assertTrue(detector.detect(SyntheticImageTestData.uniform(5, 3, 20)).stars.isEmpty())
    }

    @Test
    fun maxStarsLimitsOutput() {
        val image = starImage(
            Triple(8, 8, 100), Triple(16, 16, 120), Triple(24, 24, 140), Triple(32, 32, 160)
        )
        assertEquals(2, detector.detect(image, maxStars = 2).stars.size)
    }

    @Test
    fun detectionDoesNotModifyInput() {
        val image = starImage(Triple(20, 20, 180))
        val snapshot = image.pixels.copyOf()
        detector.detect(image)
        assertArrayEquals(snapshot, image.pixels)
    }

    @Test
    fun confidenceReflectsDetectedStarCount() {
        val one = detector.detect(starImage(Triple(20, 20, 180)))
        val four = detector.detect(
            starImage(Triple(8, 8, 100), Triple(16, 16, 120), Triple(24, 24, 140), Triple(32, 32, 160))
        )
        assertTrue(four.confidence > one.confidence)
    }

    private fun starImage(vararg points: Triple<Int, Int, Int>) =
        SyntheticImageTestData.stars(width = 41, height = 41, points = points.toList())
}
