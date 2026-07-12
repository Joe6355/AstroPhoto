package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroRoiTest {
    @Test
    fun fullImageUsesExclusiveRightAndBottomBounds() {
        assertEquals(PixelRect(0, 0, 100, 80), AstroRoi.Full.toPixelRect(100, 80))
    }

    @Test
    fun centeredRoiRoundsToExpectedPixels() {
        assertEquals(PixelRect(15, 12, 85, 68), AstroRoi.Center70.toPixelRect(100, 80))
    }

    @Test
    fun topPresetsKeepImageBoundaries() {
        assertEquals(PixelRect(0, 0, 40, 35), AstroRoi.Top70.toPixelRect(40, 50))
        assertEquals(PixelRect(0, 0, 40, 25), AstroRoi.Top50.toPixelRect(40, 50))
    }

    @Test
    fun onePixelImageIsSupported() {
        assertEquals(PixelRect(0, 0, 1, 1), AstroRoi.Full.toPixelRect(1, 1))
    }

    @Test
    fun negativeCoordinatesAreClamped() {
        assertEquals(PixelRect(0, 0, 5, 5), AstroRoi(-1f, -2f, 0.5f, 0.5f).toPixelRect(10, 10))
    }

    @Test
    fun coordinatesPastImageAreClamped() {
        assertEquals(PixelRect(9, 9, 10, 10), AstroRoi(2f, 3f, 4f, 5f).toPixelRect(10, 10))
    }

    @Test
    fun invertedCoordinatesProduceCurrentMinimumRoi() {
        val rect = AstroRoi(0.8f, 0.7f, 0.2f, 0.1f).toPixelRect(100, 100)
        assertEquals(PixelRect(80, 70, 85, 75), rect)
    }

    @Test
    fun zeroImageDimensionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AstroRoi.Full.toPixelRect(0, 10)
        }
    }

    @Test
    fun membershipIncludesLeftTopAndExcludesRightBottom() {
        val rect = PixelRect(2, 3, 7, 8)
        assertTrue(2 to 3 in rect)
        assertTrue(6 to 7 in rect)
        assertFalse(7 to 7 in rect)
        assertFalse(6 to 8 in rect)
    }

    @Test
    fun roiConversionDoesNotModifyImage() {
        val image = SyntheticImageTestData.texture(20, 20)
        val snapshot = image.pixels.copyOf()
        AstroRoi.Center70.toPixelRect(image.width, image.height)
        assertArrayEquals(snapshot, image.pixels)
    }

    @Test
    fun detectorIgnoresStarOutsideRoi() {
        val image = SyntheticImageTestData.stars(
            width = 50,
            height = 50,
            points = listOf(Triple(10, 25, 180), Triple(35, 25, 200))
        )
        val result = StarDetector().detect(image, AstroRoi(0f, 0f, 0.5f, 1f))
        assertEquals(listOf(10 to 25), result.stars.map { it.x to it.y })
    }
}
