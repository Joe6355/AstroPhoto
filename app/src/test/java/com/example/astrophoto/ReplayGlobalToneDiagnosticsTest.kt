package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGlobalToneDiagnosticsTest {
    private val anchors = ReplayToneAnchors(0.01, 0.04)
    private val mapper = ReplayGlobalToneMapper()

    @Test
    fun exactIecSrgbTransferUsesSpecifiedPiecewiseBoundaries() {
        assertEquals(0.04045 / 12.92, ReplayIecSrgbTransfer.decode(0.04045), 1e-15)
        assertEquals(
            ((0.04046 + 0.055) / 1.055).pow(2.4),
            ReplayIecSrgbTransfer.decode(0.04046),
            1e-15
        )
        assertEquals(12.92 * 0.0031308, ReplayIecSrgbTransfer.encode(0.0031308), 1e-15)
        assertEquals(
            1.055 * 0.0031309.pow(1.0 / 2.4) - 0.055,
            ReplayIecSrgbTransfer.encode(0.0031309),
            1e-15
        )
        listOf(0, 1, 10, 64, 128, 250, 255).forEach { encoded ->
            assertEquals(encoded, ReplayIecSrgbTransfer.encode8(ReplayIecSrgbTransfer.decode8(encoded)))
        }
    }

    @Test
    fun zeroAndNearZeroPixelsRemainStable() {
        val black = 0xFF000000.toInt()
        val result = mapper.transformEncodedColor(black, anchors, 0.75)

        assertEquals(black, result.color)
        assertFalse(result.scaleLimited)
    }

    @Test
    fun smoothToeHasContinuousValueAndDerivative() {
        val step = 1e-7
        listOf(anchors.toeStart, anchors.toeEnd).forEach { edge ->
            val leftValue = mapper.toneLuminance(edge - step, anchors, 0.75)
            val edgeValue = mapper.toneLuminance(edge, anchors, 0.75)
            val rightValue = mapper.toneLuminance(edge + step, anchors, 0.75)
            val leftDerivative = (edgeValue - leftValue) / step
            val rightDerivative = (rightValue - edgeValue) / step

            assertTrue(abs(leftValue - edgeValue) < 2e-7)
            assertTrue(abs(rightValue - edgeValue) < 2e-7)
            assertEquals(leftDerivative, rightDerivative, 2e-4)
        }
    }

    @Test
    fun mappingIsSpatiallyInvariantAndDoesNotMutateBaseline() {
        val repeated = 0xFF18243A.toInt()
        val baseline = ArgbPixelImage(3, 2, intArrayOf(
            repeated, 0xFF000000.toInt(), repeated,
            repeated, 0xFFFFFFFF.toInt(), repeated
        ))
        val original = baseline.pixels.copyOf()

        val candidate = mapper.apply(baseline, anchors, 0.50)

        assertEquals(candidate.image.pixels[0], candidate.image.pixels[2])
        assertEquals(candidate.image.pixels[0], candidate.image.pixels[3])
        assertEquals(candidate.image.pixels[0], candidate.image.pixels[5])
        assertArrayEquals(original, baseline.pixels)
    }

    @Test
    fun sharedScalePreventsLinearChannelOverflowAndReportsLimiting() {
        val saturatedRed = 0xFFFF1010.toInt()
        val result = mapper.transformEncodedColor(saturatedRed, anchors, 0.75)

        assertTrue(result.scaleLimited)
        assertTrue(result.maximumLinearChannel <= 1.0 + 1e-12)
        assertEquals(255, result.color ushr 16 and 0xFF)
    }

    @Test
    fun scaleLimitedFlagsMatchIndependentFormula() {
        val colors = intArrayOf(
            0xFF000000.toInt(),
            0xFF101820.toInt(),
            0xFF808080.toInt(),
            0xFFFF1010.toInt(),
            0xFFFFFFFF.toInt()
        )
        val baseline = ArgbPixelImage(colors.size, 1, colors.copyOf())
        val candidate = mapper.apply(baseline, anchors, 0.75)
        val expected = colors.map { independentlyScaleLimited(it, anchors, 0.75) }.toBooleanArray()

        assertTrue(expected.contentEquals(candidate.scaleLimited))
    }

    @Test
    fun fixedStarSupportIsReusedForEveryMeasurement() {
        val width = 15
        val height = 15
        val pixels = IntArray(width * height) { 0xFF080808.toInt() }
        pixels[7 * width + 7] = 0xFF707070.toInt()
        val baseline = ArgbPixelImage(width, height, pixels)
        val star = DetectedStar(7f, 7f, 1f, 0.2f, 0.2f, 1.5f, 0.1f, 0.9f)
        val supports = ReplayFixedStarSupportFactory.create(width, height, listOf(star))
        val candidate = mapper.apply(baseline, anchors, 0.50)

        val before = ReplayFixedStarMeasurer.measure(baseline, supports)
        val after = ReplayFixedStarMeasurer.measure(candidate.image, supports)

        assertEquals(1, supports.size)
        assertTrue(supports.single().measurementIndices.isNotEmpty())
        assertTrue(after.single().contrast >= before.single().contrast)
    }

    private fun independentlyScaleLimited(
        color: Int,
        anchors: ReplayToneAnchors,
        gain: Double
    ): Boolean {
        val red = ReplayIecSrgbTransfer.decode8(color ushr 16 and 0xFF)
        val green = ReplayIecSrgbTransfer.decode8(color ushr 8 and 0xFF)
        val blue = ReplayIecSrgbTransfer.decode8(color and 0xFF)
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        if (luminance <= ReplayGlobalToneMapper.EPSILON) return false
        val t = ((luminance - anchors.toeStart) / (anchors.toeEnd - anchors.toeStart))
            .coerceIn(0.0, 1.0)
        val toeWeight = t * t * (3.0 - 2.0 * t)
        val prime = luminance + toeWeight * gain * luminance * (1.0 - luminance).pow(4.0)
        val requestedScale = prime / max(luminance, ReplayGlobalToneMapper.EPSILON)
        val gamutScale = 1.0 / max(max(red, green), max(blue, ReplayGlobalToneMapper.EPSILON))
        return gamutScale + 1e-12 < requestedScale
    }
}
