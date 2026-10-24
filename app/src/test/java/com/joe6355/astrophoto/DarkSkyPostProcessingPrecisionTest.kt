package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedSkyStatistics
import com.joe6355.astrophoto.processing.jpeg.v2.quality.FileBackedResultQualityAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.quality.BackgroundQualityComparator
import com.joe6355.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DarkSkyPostProcessingPrecisionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun darkSkyMedianAndLowTailRetainLinear16Precision() {
        val values = floatArrayOf(.00013f, .000145f, .00016f, .000175f)
        val image = image("dark") { x, _ -> gray(values[x % values.size]) }
        val stats = statistics(image, alpha())
        val expectedMedian = LinearRgb16.luminance(gray(values[1]))
        val expectedLow = LinearRgb16.luminance(gray(values[0]))
        assertEquals(expectedMedian, stats.luminanceMedian, .51f / 65535f)
        assertEquals(expectedLow, stats.lowPercentile, .51f / 65535f)
        assertTrue("A nonzero sky floor must not be reported as black", stats.lowPercentile > 0f)
    }

    @Test fun darkSkyGradientResolvesDifferencesBelowOneCoarseBin() {
        val left = .00065f
        val right = .00095f
        val image = image("gradient") { x, _ -> gray(if (x < WIDTH / 2) left else right) }
        val stats = statistics(image, alpha())
        val expected = LinearRgb16.luminance(gray(right)) - LinearRgb16.luminance(gray(left))
        assertEquals(expected, stats.gradientDirectionX, 1.5f / 65535f)
        assertEquals(0f, stats.gradientDirectionY, 1.5f / 65535f)
    }

    @Test fun qualityDoesNotInventLargeBandsBetweenAdjacentDarkDisplayCodes() {
        val image = image("one-code") { x, _ ->
            val level = if (x < WIDTH / 2) 6 else 7
            LinearRgb16.fromArgb(0xff000000.toInt() or (level shl 16) or (level shl 8) or level)
        }
        val quality = FileBackedResultQualityAnalyzer().analyze(image, image, alpha())
        assertTrue("One output-code step is not a strong band: ${quality.banding}",
            quality.banding.combinedScore < .01f)
    }

    @Test fun uniformDarkSkyDoesNotGenerateAQuantizedGradientCorrection() = runBlocking {
        val image = image("uniform") { _, _ -> LinearRgb16.pack(.0008f, .0008f, .0010f) }
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = FileBackedAdaptivePresetProcessor().process(image, image, alpha(),
                AstroProcessingProfile.DEEP_SKY, 4, emptyList(), ResultCandidateStore(files),
                JpegMemoryBudget(RuntimeHeapSnapshot(512L shl 20, 128L shl 20, 96L shl 20)),
                PipelineMemoryTracker())
            assertTrue("A spatially uniform background has no gradient to subtract: ${result.diagnostics.gradient}",
                result.diagnostics.gradient.maximumCorrection <= 1.5f / 65535f)
        }
    }

    @Test fun realBandsStillFailProcessedBackgroundQualityChecks() {
        fun code(level: Int) = LinearRgb16.fromArgb(0xff000000.toInt() or
            (level shl 16) or (level shl 8) or level)
        val baseline = image("flat-banding-baseline") { _, _ -> code(6) }
        val striped = image("real-bands") { x, _ -> code(if (x / 8 % 2 == 0) 4 else 18) }
        val sky = alpha()
        val analyzer = FileBackedResultQualityAnalyzer()
        val clean = analyzer.analyze(baseline, baseline, sky)
        val processed = analyzer.analyze(striped, baseline, sky)
        val decision = BackgroundQualityComparator().compare(clean, processed,
            ExistingPresetParameterMapper.parametersFor(AstroProcessingProfile.DEEP_SKY, 7))
        assertTrue(processed.banding.toString(), processed.banding.combinedScore > .5f)
        assertTrue(decision.toString(), "banding_increased_excessively" in decision.hardFailureReasons)
    }

    @Test fun positiveSkyFloorSurvivesStretchAndBackgroundMatching() = runBlocking {
        val input = image("shadow-floor") { x, _ -> gray(if (x < WIDTH / 10) .0004f else .001f) }
        val sky = alpha()
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = FileBackedAdaptivePresetProcessor().process(input, input, sky,
                AstroProcessingProfile.DEEP_SKY, 4, emptyList(), ResultCandidateStore(files),
                JpegMemoryBudget(RuntimeHeapSnapshot(512L shl 20, 128L shl 20, 96L shl 20)),
                PipelineMemoryTracker())
            val after = statistics(result.image, sky)
            assertTrue("Processing must retain the positive sky floor above the existing crushed-black limit: $after",
                after.lowPercentile > 1f / 4095f)
        }
    }

    private fun statistics(image: FileBackedImage, alpha: FileBackedFloatPlane) =
        FileBackedImageReader(image).use { reader ->
            FileBackedFloatPlaneReader(alpha).use { mask -> FileBackedSkyStatistics().calculate(reader, mask) }
        }

    private fun image(name: String, pixel: (Int, Int) -> Long) =
        FileBackedImageWriter(File(folder.root, "$name.rgb16"), WIDTH, HEIGHT,
            FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            repeat(HEIGHT) { y -> writer.writeLinearRow(y, LongArray(WIDTH) { x -> pixel(x, y) }) }
            writer.finish()
        }

    private fun alpha() = FileBackedFloatPlaneWriter(folder.newFile().also { it.delete() }, WIDTH, HEIGHT).use { writer ->
        repeat(HEIGHT) { writer.writeRow(it, FloatArray(WIDTH) { 1f }) }
        writer.finish()
    }

    private fun gray(value: Float) = LinearRgb16.pack(value, value, value)

    companion object { private const val WIDTH = 96; private const val HEIGHT = 72 }
}
