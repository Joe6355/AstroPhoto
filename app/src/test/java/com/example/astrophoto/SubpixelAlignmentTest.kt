package com.example.astrophoto

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubpixelAlignmentTest {
    private val reference = starField(96, 72)

    @Test
    fun recoversFractionalTranslation() {
        val candidate = shiftLinearImage(reference, SubpixelShift(-2.35f, 1.7f))

        val shift = findSubpixelAlignment(reference, candidate, maxShiftPixels = 6)

        assertEquals(2.35f, shift.dx, 0.2f)
        assertEquals(-1.7f, shift.dy, 0.2f)
        assertTrue(shift.correlation > 0.8f)
    }

    @Test
    fun identicalImageKeepsZeroShift() {
        assertEquals(SubpixelShift.Zero, findSubpixelAlignment(reference, reference, 6))
    }

    @Test
    fun featurelessFrameIsNotReportedAsReliableAlignment() {
        val flat = LinearRgbImage(96, 72, FloatArray(96 * 72 * 3) { 0.1f })

        assertTrue(!findSubpixelAlignment(reference, flat, 6).isReliable)
    }

    @Test
    fun alignedAverageRetainsSharperPeakThanUnalignedAverage() {
        val candidate = shiftLinearImage(reference, SubpixelShift(-1.5f, -1.25f))

        val aligned = averageAlignedLinearFrames(listOf(reference, candidate), align = true).image
        val unaligned = averageAlignedLinearFrames(listOf(reference, candidate), align = false).image

        assertTrue(maxChannel(aligned) > maxChannel(unaligned))
    }

    @Test
    fun commonCropRemovesEveryInterpolationBorder() {
        val cropped = cropLinearToCommonRegion(
            reference,
            listOf(SubpixelShift.Zero, SubpixelShift(2.25f, -1.5f), SubpixelShift(-3.2f, 4.1f))
        )

        assertEquals(89, cropped.width)
        assertEquals(65, cropped.height)
    }

    private fun starField(width: Int, height: Int): LinearRgbImage {
        val pixels = FloatArray(width * height * 3) { 0.005f }
        val stars = listOf(12f to 11f, 25f to 30f, 43f to 18f, 61f to 44f, 79f to 15f, 82f to 59f)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0.005f + x * 0.00003f + y * 0.00002f
            stars.forEachIndexed { index, (sx, sy) ->
                val distance = (x - sx) * (x - sx) + (y - sy) * (y - sy)
                value += (0.3f + index * 0.06f) * exp(-distance / 2.2f)
            }
            val pixel = (y * width + x) * 3
            pixels[pixel] = value
            pixels[pixel + 1] = value
            pixels[pixel + 2] = value
        }
        return LinearRgbImage(width, height, pixels)
    }

    private fun maxChannel(image: LinearRgbImage): Float = image.pixels.maxOrNull() ?: 0f
}
