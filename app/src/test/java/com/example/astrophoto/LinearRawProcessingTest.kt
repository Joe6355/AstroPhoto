package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearRawProcessingTest {
    @Test
    fun blackLevelIsRemovedBeforeWhiteBalance() {
        val frame = rawFrame(4, 4) { _, _ -> 64 }

        val output = developAstroRaw(frame)

        assertTrue(output.pixels.all { it == 0f })
    }

    @Test
    fun neutralBayerFieldDevelopsToNeutralLinearRgb() {
        val frame = rawFrame(6, 6) { _, _ -> 512 }

        val output = developAstroRaw(frame)

        for (index in output.pixels.indices step 3) {
            assertEquals(output.pixels[index], output.pixels[index + 1], 0.0001f)
            assertEquals(output.pixels[index + 1], output.pixels[index + 2], 0.0001f)
        }
    }

    @Test
    fun twoByTwoBinningPreservesChannelIdentity() {
        val frame = rawFrame(8, 8) { x, y ->
            when (cfaForTest(x, y)) {
                0 -> 900
                1 -> 500
                else -> 200
            }
        }

        val output = developAstroRaw(frame, maxOutputPixels = 4)

        assertEquals(2, output.width)
        assertEquals(2, output.height)
        assertTrue(output.channelAt(0, 0, 0) > output.channelAt(0, 0, 1))
        assertTrue(output.channelAt(0, 0, 1) > output.channelAt(0, 0, 2))
    }

    @Test
    fun colorMatrixIsAppliedAfterDemosaic() {
        val frame = rawFrame(
            4,
            4,
            transform = listOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f)
        ) { x, y ->
            when (cfaForTest(x, y)) {
                0 -> 900
                1 -> 500
                else -> 200
            }
        }

        val output = developAstroRaw(frame)

        assertTrue(output.channelAt(1, 1, 2) > output.channelAt(1, 1, 0))
    }

    @Test
    fun postRawSensitivityBoostIsAppliedInLinearSpace() {
        val normal = developAstroRaw(rawFrame(4, 4) { _, _ -> 256 })
        val boosted = developAstroRaw(
            rawFrame(4, 4, postRawSensitivityBoost = 200) { _, _ -> 256 }
        )

        assertEquals(normal.channelAt(1, 1, 0) * 2f, boosted.channelAt(1, 1, 0), 0.0001f)
    }

    @Test
    fun toneMappingIsDeterministicOpaqueAndKeepsBrightPoint() {
        val pixels = FloatArray(8 * 8 * 3) { 0.01f }
        val star = (4 * 8 + 4) * 3
        pixels[star] = 0.8f
        pixels[star + 1] = 0.8f
        pixels[star + 2] = 0.8f
        val image = LinearRgbImage(8, 8, pixels)

        val first = toneMapLinearToArgb(image)
        val second = toneMapLinearToArgb(image)

        assertTrue(first.pixels.contentEquals(second.pixels))
        assertTrue(first.pixels.all { it ushr 24 == 255 })
        assertTrue(pixelLuminance(first.pixelAt(4, 4)) > pixelLuminance(first.pixelAt(0, 0)))
    }

    private fun rawFrame(
        width: Int,
        height: Int,
        transform: List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        postRawSensitivityBoost: Int = 100,
        value: (Int, Int) -> Int
    ): AstroRawFrame {
        val metadata = AstroRawMetadata(
            width = width,
            height = height,
            sampleShift = 6,
            cfaArrangement = 0,
            whiteLevel = 1023,
            blackLevels = listOf(64f, 64f, 64f, 64f),
            colorGains = listOf(1f, 1f, 1f, 1f),
            colorTransform = transform,
            exposureTimeNs = 1_000_000_000L,
            sensitivityIso = 800,
            postRawSensitivityBoost = postRawSensitivityBoost,
            sensorOrientation = 0
        )
        val samples = ShortArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            samples[y * width + x] = (value(x, y) shl 6).toShort()
        }
        return AstroRawFrame(metadata, samples)
    }

    private fun cfaForTest(x: Int, y: Int): Int = when ((y and 1) * 2 + (x and 1)) {
        0 -> 0
        3 -> 2
        else -> 1
    }
}
