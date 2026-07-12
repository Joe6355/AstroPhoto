package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredStarsTest {
    @Test
    fun fallbackUsesOnlySafeIntermediatePixels() {
        val intermediate = starField()
        val snapshot = intermediate.pixels.copyOf()
        val result = recoverStarsFromIntermediate(
            intermediate,
            AstroRoi.Full,
            StarDetectionSensitivity.HIGH,
            "aggressive output collapsed"
        ) as RecoveredStarsResult.Recovered
        assertArrayEquals(snapshot, intermediate.pixels)
        assertEquals("aggressive output collapsed", result.originalFailure)
        assertEquals(intermediate.width, result.image.width)
        assertTrue(result.image.pixels.all { it ushr 24 and 0xFF == 255 })
    }

    @Test
    fun fallbackDoesNotPaintNewStarCoordinates() {
        val intermediate = starField()
        val result = recoverStarsFromIntermediate(
            intermediate, AstroRoi.Full, StarDetectionSensitivity.HIGH, "failure"
        ) as RecoveredStarsResult.Recovered
        intermediate.pixels.indices.filter { pixelLuminance(intermediate.pixels[it]) == 20 }
            .forEach { index ->
                assertEquals(20, pixelLuminance(result.image.pixels[index]))
            }
    }

    @Test
    fun collapsedIntermediateCannotBeSavedAsRecovered() {
        val black = SyntheticImageTestData.uniform(32, 32, 0)
        val result = recoverStarsFromIntermediate(
            black, AstroRoi.Full, StarDetectionSensitivity.HIGH, "failure"
        )
        assertTrue(result is RecoveredStarsResult.Failed)
    }

    @Test
    fun fallbackIsDeterministic() {
        val image = starField()
        val first = recoverStarsFromIntermediate(
            image, AstroRoi.Full, StarDetectionSensitivity.HIGH, "failure"
        )
        val second = recoverStarsFromIntermediate(
            image, AstroRoi.Full, StarDetectionSensitivity.HIGH, "failure"
        )
        assertTrue(first is RecoveredStarsResult.Recovered)
        assertTrue(second is RecoveredStarsResult.Recovered)
        first as RecoveredStarsResult.Recovered
        second as RecoveredStarsResult.Recovered
        assertArrayEquals(first.image.pixels, second.image.pixels)
        assertEquals(first.sanity, second.sanity)
        assertEquals(first.originalFailure, second.originalFailure)
    }

    private fun starField() = SyntheticImageTestData.stars(
        48, 48, 20,
        listOf(
            Triple(8, 8, 100), Triple(16, 15, 120), Triple(24, 28, 140),
            Triple(34, 12, 160), Triple(40, 34, 180), Triple(12, 38, 110)
        )
    )
}
