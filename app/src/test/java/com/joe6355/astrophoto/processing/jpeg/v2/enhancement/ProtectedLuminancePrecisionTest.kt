package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.linearLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore
import com.joe6355.astrophoto.processing.jpeg.v2.storage.TemporaryPipelineFiles
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedLuminancePrecisionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeDarkSkyNoiseIsReducedWithoutChangingConfirmedStarOrBroadSignal() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val random = Random(20261019)
            val size = 96
            val baseline = store.createTemporaryWriter("dark", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val broad = 12.0 * exp(-((x - 70.0) * (x - 70.0) + (y - 68.0) * (y - 68.0)) / 200.0)
                    val star = 160.0 * exp(-((x - 24.0) * (x - 24.0) + (y - 24.0) * (y - 24.0)) / 3.0)
                    val noise = random.nextGaussian() * 2.0
                    val detail = (broad + star + noise).toFloat()
                    LinearRgb16.pack((80f + detail) / 65535f, (100f + detail) / 65535f, (120f + detail) / 65535f)
                }) }
                writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { 1f }) }; writer.finish()
            }
            val stars = listOf(DetectedStar(24f, 24f, 1f, .001f, .003f, 2f, 0f, 1f))
            val denoiser = ProtectedLuminanceDenoiser()
            val prepared = denoiser.prepareNoiseMap(baseline, alpha, stars, store)
            val result = denoiser.apply(baseline, alpha, stars, prepared, store)
            assertTrue(prepared.metrics.noiseEstimateSampleCount > 256)
            assertTrue(prepared.metrics.highFrequencyNoiseMad in (1f / 65535f)..(8f / 65535f))
            assertTrue(result.metrics.toString(), result.metrics.changedPixelCount > 0)
            FileBackedImageReader(baseline).use { before -> FileBackedImageReader(result.image).use { after ->
                val roughnessBefore = roughness(before, 38, 5, 85, 30)
                val roughnessAfter = roughness(after, 38, 5, 85, 30)
                assertTrue("$roughnessAfter vs $roughnessBefore", roughnessAfter < roughnessBefore * .9)
                for (y in 20..28) for (x in 20..28) {
                    if ((x - 24) * (x - 24) + (y - 24) * (y - 24) <= 16)
                        assertEquals(before.linearRgbAt(x, y), after.linearRgbAt(x, y))
                }
                val signalBefore = average(before, 64, 62, 76, 74) - average(before, 65, 5, 77, 17)
                val signalAfter = average(after, 64, 62, 76, 74) - average(after, 65, 5, 77, 17)
                assertEquals(signalBefore, signalAfter, abs(signalBefore) * .03)
                assertEquals(average(before, 38, 5, 85, 30), average(after, 38, 5, 85, 30), .3 / 65535.0)
            } }
        }
    }

    @Test fun brightForegroundCannotBleedAcrossFullSkyBoundary() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val size = 32
            val baseline = store.createTemporaryWriter("boundary", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val value = if (x < 16) .001f else .01f
                    LinearRgb16.pack(value, value, value)
                }) }; writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { x -> if (x < 16) 1f else 0f }) }; writer.finish()
            }
            val noise = store.createFloatPlaneWriter("all-noise", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { 1f }) }; writer.finish()
            }
            // A prepared map with no adaptive estimate also exercises the conservative legacy fallback.
            val prepared = PreparedLuminanceNoiseMap(noise, ProtectedLuminanceDenoiseMetrics.NONE.copy(eligiblePixelCount = size * size))
            val result = ProtectedLuminanceDenoiser().apply(baseline, alpha, emptyList(), prepared, store)
            assertEquals(fileBackedPixelHash(baseline), fileBackedPixelHash(result.image))
        }
    }

    @Test fun linearSkyGradientIsNotClassifiedAsHighFrequencyNoise() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val size = 48
            val baseline = store.createTemporaryWriter("gradient", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val value = (80 + 2 * x + y) / 65535f
                    LinearRgb16.pack(value, value, value)
                }) }; writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { 1f }) }; writer.finish()
            }
            val denoiser = ProtectedLuminanceDenoiser()
            val prepared = denoiser.prepareNoiseMap(baseline, alpha, emptyList(), store)
            assertEquals(0f, prepared.metrics.highFrequencyNoiseMad, 1f / 65535f)
            val result = denoiser.apply(baseline, alpha, emptyList(), prepared, store)
            assertEquals(fileBackedPixelHash(baseline), fileBackedPixelHash(result.image))
        }
    }

    @Test fun unlistedCompactPeakAndItsShapeSurviveDarkSkySmoothing() {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val size = 64
            val random = Random(731)
            val baseline = store.createTemporaryWriter("unlisted", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val peak = 100.0 * exp(-((x - 32.0) * (x - 32.0) + (y - 32.0) * (y - 32.0)) / 3.0)
                    val value = (100.0 + peak + random.nextGaussian() * 2.0).toFloat() / 65535f
                    LinearRgb16.pack(value, value, value)
                }) }; writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { 1f }) }; writer.finish()
            }
            val denoiser = ProtectedLuminanceDenoiser()
            val prepared = denoiser.prepareNoiseMap(baseline, alpha, emptyList(), store)
            val result = denoiser.apply(baseline, alpha, emptyList(), prepared, store)
            FileBackedImageReader(baseline).use { before -> FileBackedImageReader(result.image).use { after ->
                assertEquals(before.linearRgbAt(32, 32), after.linearRgbAt(32, 32))
                fun moment(image: FileBackedImageReader): Double {
                    var flux = 0.0
                    var moment = 0.0
                    for (dy in -4..4) for (dx in -4..4) {
                        val value = (linearLuminance(image.linearRgbAt(32 + dx, 32 + dy)) - 100f / 65535f).coerceAtLeast(0f)
                        flux += value
                        moment += value * (dx * dx + dy * dy)
                    }
                    return moment / flux
                }
                assertEquals(moment(before), moment(after), moment(before) * .05)
            } }
        }
    }

    private fun roughness(image: FileBackedImageReader, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val values = mutableListOf<Double>()
        for (y in y0 until y1) for (x in x0 until x1) {
            fun l(dx: Int, dy: Int) = linearLuminance(image.linearRgbAt(x + dx, y + dy)).toDouble()
            values += abs(l(0, 0) - l(1, 0) - l(0, 1) + l(1, 1))
        }
        return values.sorted()[values.size / 2]
    }

    private fun average(image: FileBackedImageReader, x0: Int, y0: Int, x1: Int, y1: Int): Double =
        (y0 until y1).sumOf { y -> (x0 until x1).sumOf { x -> linearLuminance(image.linearRgbAt(x, y)).toDouble() } } /
            ((x1 - x0) * (y1 - y0))
}
