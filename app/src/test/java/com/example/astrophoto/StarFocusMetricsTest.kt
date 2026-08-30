package com.example.astrophoto

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt

class StarFocusMetricsTest {
    @Test
    fun gaussianStarFieldProducesStableFocusMetric() {
        val metrics = measureStarFocus(gaussianStarField(sigma = 1.25f))

        assertNotNull(metrics)
        assertTrue(checkNotNull(metrics).measuredStars >= 2)
        assertTrue(metrics.medianFwhm in 1.5f..5f)
    }

    @Test
    fun broaderStarsProduceLargerFwhm() {
        val sharp = checkNotNull(measureStarFocus(gaussianStarField(sigma = 1.10f)))
        val blurred = checkNotNull(measureStarFocus(gaussianStarField(sigma = 2.00f)))

        assertTrue(blurred.medianFwhm > sharp.medianFwhm)
    }

    @Test
    fun uniformFrameHasNoStarFocusMetric() {
        val pixels = IntArray(96 * 72) { 0xFF181818.toInt() }

        assertTrue(measureStarFocus(ArgbPixelImage(96, 72, pixels)) == null)
    }

    private fun gaussianStarField(sigma: Float): ArgbPixelImage {
        val width = 128
        val height = 96
        val stars = listOf(24 to 24, 62 to 31, 101 to 25, 38 to 70, 88 to 66)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 18.0
            stars.forEach { (starX, starY) ->
                val dx = (x - starX).toDouble()
                val dy = (y - starY).toDouble()
                value += 190.0 * exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
            }
            val channel = value.roundToInt().coerceIn(0, 255)
            pixels[y * width + x] =
                0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }
        return ArgbPixelImage(width, height, pixels)
    }
}
