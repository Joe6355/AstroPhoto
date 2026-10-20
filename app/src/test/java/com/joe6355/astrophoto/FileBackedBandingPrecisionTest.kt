package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.EnhancedGlobalToneValidator
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.GlobalToneAnchors
import com.joe6355.astrophoto.processing.jpeg.v2.quality.FileBackedOutputBandingEstimator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.quality.qualityLinearLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBackedBandingPrecisionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oneOutputLevelCannotBecomeAMuchLargerLinearHistogramStep() {
        val baseline = image("baseline") { _, _ -> gray(6) }
        val candidate = image("single-level") { x, _ -> gray(if (x < SIZE / 2) 6 else 7) }
        val alpha = alpha("sky") { _, _ -> 1f }
        val coarse = FileBackedResultQualityAnalyzer().analyze(candidate, baseline, alpha).banding.combinedScore
        val precise = FileBackedOutputBandingEstimator().estimate(candidate, alpha, 0f).combinedScore
        assertTrue("The 256-bin quantizer reproduces the false strong-band threshold crossing", coarse > 0.05f)
        assertTrue(precise.toString(), precise < 0.01f)
        val validation = validate(baseline, candidate, alpha)
        assertFalse(validation.hardFailureReasons.toString(), "strong_banding_appeared" in validation.hardFailureReasons)
    }

    @Test fun realBandsRemainHardFailuresInEitherDirection() {
        val baseline = image("flat") { _, _ -> gray(6) }
        val alpha = alpha("sky") { _, _ -> 1f }
        for (vertical in listOf(false, true)) {
            val candidate = image("bands-$vertical") { x, y ->
                gray(if ((if (vertical) x else y) / 8 % 2 == 0) 4 else 18)
            }
            val measurement = FileBackedOutputBandingEstimator().estimate(candidate, alpha, 0f)
            assertTrue(measurement.toString(), measurement.combinedScore > 0.5f)
            assertTrue(validate(baseline, candidate, alpha).hardFailureReasons.contains("strong_banding_appeared"))
        }
    }

    @Test fun excludesForegroundAndLinesWithInsufficientSky() {
        val image = image("foreground") { x, y -> gray(if (x < SIZE / 2) 6 else if (y % 2 == 0) 0 else 64) }
        val sky = alpha("sky-half") { x, _ -> if (x < SIZE / 2) 1f else 0.5f }
        assertEquals(0f, FileBackedOutputBandingEstimator().estimate(image, sky, 0f).combinedScore, 0f)
        val sparse = alpha("sparse") { x, y -> if (x == 2 && y % 2 == 0) 1f else 0f }
        assertEquals(0f, FileBackedOutputBandingEstimator().estimate(image, sparse, 0f).combinedScore, 0f)
    }

    @Test fun boundedTwoPassHistogramsAgreeWithExactSortedMediansForMixedChroma() {
        val random = Random(6355)
        val pixels = IntArray(SIZE * SIZE) {
            0xff000000.toInt() or (random.nextInt(4, 30) shl 16) or
                (random.nextInt(4, 30) shl 8) or random.nextInt(4, 30)
        }
        val input = image("mixed") { x, y -> pixels[y * SIZE + x] }
        val sky = alpha("mixed-sky") { x, y -> if ((x + y) % 7 == 0) 0f else 1f }
        fun value(x: Int, y: Int) =
            Math.round(qualityLinearLuminance(pixels[y * SIZE + x]) * 65535f) / 65535f
        fun median(values: List<Float>) = values.sorted()[(values.size - 1) / 2]
        val rows = (0 until SIZE).map { y -> median((0 until SIZE).filter { x -> (x + y) % 7 != 0 }.map { x -> value(x, y) }) }
        val columns = (0 until SIZE).map { x -> median((0 until SIZE).filter { y -> (x + y) % 7 != 0 }.map { y -> value(x, y) }) }
        fun score(values: List<Float>): Double {
            val radius = (SIZE / 24).coerceIn(2, 48)
            val residual = values.indices.sumOf { i ->
                abs(values[i] - values.subList(maxOf(0, i - radius), minOf(SIZE, i + radius + 1)).average())
            }
            val abrupt = (1 until SIZE - 1).sumOf { i -> abs(values[i] - (values[i - 1] + values[i + 1]) * 0.5) }
            return (residual + abrupt * 0.75) / SIZE / 0.002
        }
        val expectedHorizontal = score(rows)
        val expectedVertical = score(columns)
        val actual = FileBackedOutputBandingEstimator().estimate(input, sky, 0f)
        assertEquals(expectedHorizontal, actual.horizontalScore.toDouble(), 0.00001)
        assertEquals(expectedVertical, actual.verticalScore.toDouble(), 0.00001)
        assertEquals(sqrt(expectedHorizontal * expectedHorizontal + expectedVertical * expectedVertical),
            actual.combinedScore.toDouble(), 0.00001)
    }

    private fun validate(baseline: FileBackedImage, candidate: FileBackedImage, alpha: FileBackedFloatPlane) =
        EnhancedGlobalToneValidator().validate(baseline, candidate, alpha, emptyList(),
            GlobalToneAnchors(0.0, 0.02), 0, 1.0, 0.0)

    private fun image(name: String, color: (Int, Int) -> Int) =
        FileBackedImageWriter(File(temporary.root, "$name.argb"), SIZE, SIZE).use { writer ->
            repeat(SIZE) { y -> writer.writeRow(y, IntArray(SIZE) { x -> color(x, y) }) }
            writer.finish()
        }

    private fun alpha(name: String, value: (Int, Int) -> Float) =
        FileBackedFloatPlaneWriter(File(temporary.root, "$name.f32"), SIZE, SIZE).use { writer ->
            repeat(SIZE) { y -> writer.writeRow(y, FloatArray(SIZE) { x -> value(x, y) }) }
            writer.finish()
        }

    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    companion object { private const val SIZE = 96 }
}
