package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGlobalToneVisualFinishDiagnosticsTest {
    @Test
    fun generatesOnlyFixedVisualFinishGainsFromUnchangedBaseline() {
        val baseline = syntheticBaseline()
        val original = baseline.pixels.copyOf()
        val output = Files.createTempDirectory("global-tone-visual-finish")
        try {
            val bundle = ReplayGlobalToneVisualFinishRunner().run(
                baseline = baseline,
                effectiveSkyAlpha = AlphaMask.full(baseline.width, baseline.height),
                confirmedStars = listOf(
                    detectedStar(30f, 30f),
                    detectedStar(66f, 58f)
                ),
                outputRoot = output
            )

            assertEquals(
                ReplayToneVisualFinishPolicy.GAINS,
                bundle.results.map { it.candidate.gain }
            )
            assertTrue(bundle.results.all { it.candidate.anchors == bundle.anchors })
            assertTrue(bundle.baselineUnchanged)
            assertArrayEquals(original, baseline.pixels)
            assertTrue(Files.isRegularFile(output.resolve("full-resolution/baseline-recovered-stars.png")))
            assertTrue(Files.isRegularFile(output.resolve("full-resolution/gain-030.png")))
            assertTrue(Files.isRegularFile(output.resolve("full-resolution/gain-040.png")))
            assertTrue(Files.isRegularFile(output.resolve("full-resolution/gain-050.png")))
            assertTrue(Files.isRegularFile(output.resolve("four-way-baseline-gain030-gain040-gain050.png")))
            assertTrue(Files.isRegularFile(output.resolve("sky-comparison-zoom-3x.png")))
            assertTrue(Files.isRegularFile(output.resolve("sky-comparison-zoom-8x.png")))
            assertTrue(Files.isRegularFile(output.resolve("metrics-report.txt")))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun visualFinishDoesNotTreatSkyMadAloneAsHardRejection() {
        val baseline = syntheticBaseline()
        val bundle = ReplayGlobalToneVisualFinishRunner().run(
            baseline = baseline,
            effectiveSkyAlpha = AlphaMask.full(baseline.width, baseline.height),
            confirmedStars = listOf(
                detectedStar(30f, 30f),
                detectedStar(66f, 58f)
            )
        )

        bundle.results.forEach { result ->
            assertTrue(
                result.metrics.hardRejectionReasons.none {
                    it.contains("sky_mad", ignoreCase = true)
                }
            )
        }
    }

    private fun syntheticBaseline(): ArgbPixelImage {
        val width = 96
        val height = 96
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val value = 7 + (x * 3 + y * 5) % 4
            0xFF000000.toInt() or (value shl 16) or ((value + 1) shl 8) or (value + 2)
        }
        paintStar(pixels, width, 30, 30)
        paintStar(pixels, width, 66, 58)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun paintStar(pixels: IntArray, width: Int, centerX: Int, centerY: Int) {
        for (dy in -1..1) for (dx in -1..1) {
            val distance = kotlin.math.abs(dx) + kotlin.math.abs(dy)
            val value = when (distance) {
                0 -> 92
                1 -> 48
                else -> 24
            }
            pixels[(centerY + dy) * width + centerX + dx] =
                0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
        }
    }

    private fun detectedStar(x: Float, y: Float): DetectedStar =
        DetectedStar(
            x = x,
            y = y,
            flux = 1f,
            localBackground = 0.02f,
            localContrast = 0.20f,
            width = 1.6f,
            ellipticity = 0.12f,
            confidence = 0.90f
        )
}
