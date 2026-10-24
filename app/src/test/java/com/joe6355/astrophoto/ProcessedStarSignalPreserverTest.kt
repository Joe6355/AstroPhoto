package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.ProcessedStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

class ProcessedStarSignalPreserverTest {
    @get:Rule val folder = TemporaryFolder()
    private val stars = List(4) { DetectedStar(20f + it * 40, 20f, 1000f, 20f, 210f, 2.4f, 0f, 1f) }

    @Test fun lostSourceComesFromCleanStackWhileEnhancedNeighboursStayExact() {
        val clean = render("clean", 1f)
        val processed = render("processed", 1.3f, missing = 1)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ProcessedStarSignalPreserver().preserve(processed, clean, stars, ResultCandidateStore(files))
            assertTrue(ReferenceStarRetentionValidator().validate(clean, result, stars).accepted)
            FileBackedImageReader(clean).use { baseline ->
                FileBackedImageReader(processed).use { before ->
                    FileBackedImageReader(result).use { after ->
                        assertEquals(baseline.linearRgbAt(60, 20), after.linearRgbAt(60, 20))
                        for (y in 10..30) for (x in 10..30) {
                            assertEquals(before.linearRgbAt(x, y), after.linearRgbAt(x, y))
                        }
                    }
                }
            }
        }
    }

    @Test fun healthyProcessedSignalIsReturnedWithoutAnotherImageCopy() {
        val clean = render("clean-healthy", 1f)
        val processed = render("processed-healthy", 1.2f)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            assertSame(processed, ProcessedStarSignalPreserver().preserve(processed, clean, stars, ResultCandidateStore(files)))
        }
    }

    @Test fun sourceAbsentFromCleanBaselineCannotEraseNewProcessedSignal() {
        val clean = render("clean-absent", 1f, missing = 1)
        val processed = render("processed-new", 1.2f)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ProcessedStarSignalPreserver().preserve(processed, clean, stars, ResultCandidateStore(files))
            FileBackedImageReader(processed).use { before ->
                FileBackedImageReader(result).use { after ->
                    for (y in 10..30) for (x in 50..70) assertEquals(before.linearRgbAt(x, y), after.linearRgbAt(x, y))
                }
            }
        }
    }

    @Test fun sourcesNearTheOriginalRetentionLimitCannotSlipPastCleanRelativeChecks() {
        fun display(name: String, amplitude: Int) = FileBackedImageWriter(File(folder.root, "$name.rgb16"),
            161, 41, FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            repeat(41) { y ->
                writer.writeRow(y, IntArray(161) { x ->
                    val value = (20 + amplitude * exp(-((x-20)*(x-20) + (y-20)*(y-20)) / (2 * 1.35 * 1.35)))
                        .roundToInt().coerceIn(0, 255)
                    0xff000000.toInt() or (value shl 16) or (value shl 8) or value
                })
            }
            writer.finish()
        }
        val original = display("original-limit", 200)
        val clean = display("clean-limit", 75)
        val processed = display("processed-limit", 66)
        val catalog = listOf(stars.first())
        val validator = ReferenceStarRetentionValidator()
        assertTrue(validator.validate(original, clean, catalog).sources.single().retained)
        assertTrue(validator.validate(clean, processed, catalog).sources.single().retained)
        assertFalse(validator.validate(original, processed, catalog).sources.single().retained)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ProcessedStarSignalPreserver().preserve(processed, clean, catalog, ResultCandidateStore(files), original)
            assertTrue(validator.validate(original, result, catalog).sources.single().retained)
        }
    }

    private fun render(name: String, gain: Float, missing: Int = -1): FileBackedImage {
        val width = 161
        val height = 41
        return FileBackedImageWriter(File(folder.root, "$name.rgb16"), width, height,
            FileBackedPixelFormat.LINEAR_RGB_16).use { writer ->
            repeat(height) { y ->
                writer.writeLinearRow(y, LongArray(width) { x ->
                    val signal = stars.mapIndexed { index, star ->
                        if (index == missing) 0.0 else .08 * gain * exp(-((x-star.x)*(x-star.x) +
                            (y-star.y)*(y-star.y)).toDouble() / (2 * 1.35 * 1.35))
                    }.sum().toFloat()
                    LinearRgb16.pack(.002f + signal, .002f + signal, .002f + signal)
                })
            }
            writer.finish()
        }
    }
}
