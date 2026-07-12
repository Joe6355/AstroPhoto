package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkFrameValidationTest {
    @Test
    fun emptyDarkInputIsRejected() {
        val result = validateDarkFrames(emptyList(), listOf(shape(2, 2)))

        assertInvalid(result, "At least one Dark frame")
    }

    @Test
    fun validMatchingFramesAreAccepted() {
        val result = validateDarkFrames(
            darkFrames = listOf(shape(2, 2), shape(2, 2)),
            lightFrames = listOf(shape(2, 2), shape(2, 2))
        )

        assertEquals(DarkValidationResult.Valid(emptyList()), result)
    }

    @Test
    fun mismatchedDarkDimensionsAreRejected() {
        val result = validateDarkFrames(
            darkFrames = listOf(shape(2, 2), shape(2, 3)),
            lightFrames = listOf(shape(2, 2))
        )

        assertInvalid(result, "Dark frames must have equal dimensions")
    }

    @Test
    fun darkAndLightDimensionMismatchIsRejected() {
        val result = validateDarkFrames(
            darkFrames = listOf(shape(2, 2)),
            lightFrames = listOf(shape(3, 2))
        )

        assertInvalid(result, "must match Light frame dimensions")
    }

    @Test
    fun invalidDarkPixelArrayLengthIsRejected() {
        val result = validateDarkFrames(
            darkFrames = listOf(DecodedPixelFrameShape(2, 2, 3)),
            lightFrames = listOf(shape(2, 2))
        )

        assertInvalid(result, "Dark frame pixel count")
    }

    @Test
    fun inputsAreNotModified() {
        val darkFrames = mutableListOf(shape(2, 2), shape(2, 2))
        val lightFrames = mutableListOf(shape(2, 2))
        val darkSnapshot = darkFrames.toList()
        val lightSnapshot = lightFrames.toList()

        validateDarkFrames(darkFrames, lightFrames)

        assertEquals(darkSnapshot, darkFrames)
        assertEquals(lightSnapshot, lightFrames)
    }

    private fun shape(width: Int, height: Int) =
        DecodedPixelFrameShape(width, height, width * height)

    private fun assertInvalid(result: DarkValidationResult, message: String) {
        assertTrue(result is DarkValidationResult.Invalid)
        assertTrue((result as DarkValidationResult.Invalid).message.contains(message))
    }
}
