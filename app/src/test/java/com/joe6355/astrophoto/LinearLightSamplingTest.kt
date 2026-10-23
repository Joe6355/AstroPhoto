package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.PersistentArtifactClassification
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectFootprintPixel
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.SensorDefectRegion
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.TileProcessingCoordinator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.MutableSampledLinearRgb
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinearLightSamplingTest {
    @Test fun fractionalSamplingAveragesLightInEachChannelWithAndWithoutSensorMask() = runBlocking {
        val pixels = IntArray(36) { BLACK }.apply {
            this[0] = 0xffff0000.toInt()
            this[1] = 0xff00ff00.toInt()
            this[6] = 0xff0000ff.toInt()
            this[7] = 0xffffffff.toInt()
        }
        for (masked in listOf(false, true)) {
            val result = integrate(IntArrayPixelSource(6, 6, pixels), 1, 1, .25f, .5f, masked)
            val color = result.linear.single()
            assertEquals("Red; mask=$masked", .5f, LinearRgb16.red(color), HALF_STEP)
            assertEquals("Green; mask=$masked", .25f, LinearRgb16.green(color), HALF_STEP)
            assertEquals("Blue; mask=$masked", .5f, LinearRgb16.blue(color), HALF_STEP)
            assertEquals(1f, result.coverage.single(), 0f)
        }
    }

    @Test fun fractionalTranslationConservesIsolatedStarFluxWithAndWithoutSensorMask() = runBlocking {
        val pixels = IntArray(49) { BLACK }.apply { this[3 * 7 + 3] = 0xffffffff.toInt() }
        for (masked in listOf(false, true)) {
            val result = integrate(IntArrayPixelSource(7, 7, pixels), 7, 7, .5f, .25f, masked)
            assertEquals("Integrated star light; mask=$masked", 1.0,
                result.linear.sumOf { LinearRgb16.red(it).toDouble() }, 4.0 / 65535)
            assertEquals(0f, result.coverage[6], 0f)
            assertEquals(1f, result.coverage[3 * 7 + 3], 0f)
        }
    }

    @Test fun rgb16IdentityKeepsDifferencesHiddenByArgb8AndNeverReadsArgb() = runBlocking {
        val first = LinearRgb16.pack(.0200f, .0110f, .0010f)
        val second = LinearRgb16.pack(.0201f, .0110f, .0010f)
        assertEquals(LinearRgb16.toArgb(first), LinearRgb16.toArgb(second))
        assertNotEquals(first, second)
        val pixels = longArrayOf(first, second, second, first)
        val result = integrate(linearSource(2, 2, pixels), 2, 2)
        assertArrayEquals(pixels, result.linear)
    }

    @Test fun rgb16FractionalSamplingDoesNotEncodeOrDecodeAgain() = runBlocking {
        val pixels = longArrayOf(
            LinearRgb16.pack(.0100f, .1000f, .0010f),
            LinearRgb16.pack(.0101f, .2000f, .0030f)
        )
        val result = integrate(linearSource(2, 1, pixels), 1, 1, .5f)
        val actual = result.linear.single()
        for (channel in listOf(LinearRgb16::red, LinearRgb16::green, LinearRgb16::blue)) {
            assertEquals((channel(pixels[0]) + channel(pixels[1])) / 2f, channel(actual), HALF_STEP)
        }
    }

    @Test fun integerSamplesKeepAllOriginalByteValues() = runBlocking {
        val pixels = IntArray(256) { BLACK or (it shl 16) or ((255 - it) shl 8) or it }
        val result = integrate(IntArrayPixelSource(256, 1, pixels), 256, 1)
        assertArrayEquals(LongArray(256) { LinearRgb16.fromArgb(pixels[it]) }, result.linear)
    }

    @Test fun robustAndArgbOutputPathsUseTheSameLinearInterpolation() = runBlocking {
        val source = IntArrayPixelSource(2, 1, intArrayOf(BLACK, 0xffffffff.toInt()))
        val result = integrate(source, 1, 1, .5f, frames = 8, argbOutput = true)
        assertEquals(0xffbcbcbc.toInt(), result.argb.single())
        assertEquals(1f, result.coverage.single(), 0f)
    }

    @Test fun linearSamplerKeepsCoverageEdgesAndLeavesDestinationUntouchedOnFailure() {
        val source = IntArrayPixelSource(2, 2, intArrayOf(BLACK, BLACK, BLACK, 0xffffffff.toInt()))
        val sampler = TransformedBitmapSampler()
        val sample = MutableSampledLinearRgb()
        assertTrue(sampler.sampleLinearAt(source, 1f, 1f, sample))
        assertEquals(1f, sample.red, 0f)
        assertTrue(sampler.sampleLinearAt(source, 1f, .5f, sample))
        assertEquals(.5f, sample.red, 0f)
        for ((x, y) in listOf(-.01f to 0f, 0f to -.01f, 1.01f to 0f, 0f to 1.01f,
            Float.NaN to 0f, 0f to Float.POSITIVE_INFINITY)) {
            assertFalse("Unexpected coverage at $x, $y", sampler.sampleLinearAt(source, x, y, sample))
            assertEquals(.5f, sample.red, 0f)
        }
    }

    @Test fun geometryPatchSamplingRetainsItsEncodedSrgbSemantics() {
        val source = IntArrayPixelSource(2, 1, intArrayOf(0xff404040.toInt(), 0xffc0c0c0.toInt()))
        val sampler = TransformedBitmapSampler()
        val linear = MutableSampledLinearRgb()
        assertTrue(sampler.sampleLinearAt(source, .5f, 0f, linear))
        val expectedLight = (SrgbTransfer.srgbToLinear(64f / 255) + SrgbTransfer.srgbToLinear(192f / 255)) / 2
        assertEquals(expectedLight, linear.red, .000001f)
        assertEquals(128f / 255, checkNotNull(sampler.sampleAt(source, .5f, 0f)).red, .000001f)
        assertEquals(128f / 255, checkNotNull(sampler.sampleLuminanceAt(source, .5f, 0f)), .000001f)
    }

    private data class Result(val linear: LongArray, val argb: IntArray, val coverage: FloatArray)

    private suspend fun integrate(
        source: ArgbPixelSource,
        width: Int,
        height: Int,
        dx: Float = 0f,
        dy: Float = 0f,
        masked: Boolean = false,
        frames: Int = 1,
        argbOutput: Boolean = false
    ): Result {
        val result = Result(LongArray(width * height), IntArray(width * height), FloatArray(width * height))
        val transform = RegistrationResult(dx, dy, 0f, 1f, 12, 12, 12, 0f, 1f, true, null)
        LinearWeightedIntegrator(tileCoordinator = TileProcessingCoordinator(32, 1)).integrate(
            outputWidth = width,
            outputHeight = height,
            frames = List(frames) { WeightedIntegrationFrame("$it", source, transform, 1f / frames) },
            maximumWorkingMemoryBytes = 64L * 1024 * 1024,
            openSource = { it },
            sensorDefectMask = if (masked) sensorMask(source.width, source.height) else null,
            writeLinearTile = if (argbOutput) null else { tile, values ->
                for (row in 0 until tile.height) values.copyInto(result.linear,
                    (tile.top + row) * width + tile.left, row * tile.width, (row + 1) * tile.width)
            },
            writeTile = if (!argbOutput) null else { tile, values ->
                for (row in 0 until tile.height) values.copyInto(result.argb,
                    (tile.top + row) * width + tile.left, row * tile.width, (row + 1) * tile.width)
            },
            writeCoverageTile = { tile, values ->
                for (row in 0 until tile.height) values.copyInto(result.coverage,
                    (tile.top + row) * width + tile.left, row * tile.width, (row + 1) * tile.width)
            }
        )
        return result
    }

    private fun linearSource(width: Int, height: Int, pixels: LongArray) = object : LinearRgbPixelSource {
        override val width = width
        override val height = height
        override fun linearRgbAt(x: Int, y: Int) = pixels[y * width + x]
        override fun argbAt(x: Int, y: Int): Int = error("Linear source was quantized through ARGB8")
    }

    private fun sensorMask(width: Int, height: Int) = SensorDefectMask(width, height, listOf(
        SensorDefectRegion("corner", (width - 1).toFloat(), (height - 1).toFloat(), 0f, 0f,
            listOf(SensorDefectFootprintPixel(width - 1, height - 1)), 10, 10, 0, 1f,
            PersistentArtifactClassification.SENSOR_DEFECT, "test")
    ), enabled = true)

    private companion object {
        const val BLACK = 0xff000000.toInt()
        const val HALF_STEP = .5001f / 65535
    }
}
