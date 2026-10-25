package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.memory.JpegMemoryBudget
import com.joe6355.astrophoto.processing.jpeg.v2.memory.PipelineMemoryTracker
import com.joe6355.astrophoto.processing.jpeg.v2.memory.RuntimeHeapSnapshot
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.FileBackedAdaptivePresetProcessor
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreStretchNoiseReductionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finalDeepSkyHasLessRelativeGrainWithStarAndForegroundIntact() = runBlocking {
        TemporaryPipelineFiles.create(temporary.root).use { files ->
            val store = ResultCandidateStore(files)
            val random = Random(20261025)
            val size = 96
            val input = store.createTemporaryWriter("input", size, size).use { writer ->
                repeat(size) { y -> writer.writeLinearRow(y, LongArray(size) { x ->
                    val broad = 20.0 * exp(-((x - 70.0) * (x - 70.0) + (y - 26.0) * (y - 26.0)) / 288.0)
                    val star = 250.0 * exp(-((x - 28.0) * (x - 28.0) + (y - 28.0) * (y - 28.0)) / 3.0)
                    val signal = (broad + star + random.nextGaussian() * 4.0).toFloat()
                    LinearRgb16.pack((80f + signal) / 65535f, (100f + signal) / 65535f, (120f + signal) / 65535f)
                }) }
                writer.finish()
            }
            val alpha = store.createFloatPlaneWriter("sky", size, size).use { writer ->
                repeat(size) { writer.writeRow(it, FloatArray(size) { _ -> if (it < 64) 1f else 0f }) }
                writer.finish()
            }
            val stars = listOf(DetectedStar(28f, 28f, 1f, .001f, .006f, 2f, 0f, 1f))
            val result = FileBackedAdaptivePresetProcessor(preservationBaseline = input, preservationStars = stars)
                .process(input, input, alpha, AstroProcessingProfile.DEEP_SKY, 5, stars, store,
                    JpegMemoryBudget(RuntimeHeapSnapshot(512L shl 20, 128L shl 20, 96L shl 20)),
                    PipelineMemoryTracker())
            fun relativeGrain(image: FileBackedImage): Float = FileBackedImageReader(image).use { reader ->
                fun l(x: Int, y: Int) = LinearRgb16.luminance(reader.linearRgbAt(x, y))
                val differences = mutableListOf<Float>()
                val brightness = mutableListOf<Float>()
                for (y in 8 until 54) for (x in 8 until 86) {
                    if (abs(x - 28) <= 12 && abs(y - 28) <= 12) continue
                    differences += abs(l(x, y) - l(x + 1, y) - l(x, y + 1) + l(x + 1, y + 1))
                    brightness += l(x, y)
                }
                differences.sorted()[differences.size / 2] / brightness.sorted()[brightness.size / 2]
            }
            assertTrue("The finished image must reduce grain at its own sky brightness",
                relativeGrain(result.image) < relativeGrain(input) * .85f)
            val retained = ReferenceStarRetentionValidator().validate(input, result.image, stars)
            assertEquals("The stellar source must survive the full pipeline", 1, retained.metrics.retainedReferenceStarCount)
            FileBackedImageReader(input).use { before ->
                FileBackedImageReader(result.image).use { after ->
                    for (y in 64 until size) for (x in 0 until size) {
                        assertEquals("Foreground changed at $x,$y", before.linearRgbAt(x, y), after.linearRgbAt(x, y))
                    }
                    assertTrue("Broad sky structure must remain visible",
                        LinearRgb16.luminance(after.linearRgbAt(70, 26)) >
                            LinearRgb16.luminance(after.linearRgbAt(70, 54)))
                }
            }
        }
    }
}
