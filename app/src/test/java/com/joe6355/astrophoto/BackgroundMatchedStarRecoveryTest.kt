package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.processing.jpeg.v2.composition.BackgroundMatchedStarSource
import com.joe6355.astrophoto.processing.jpeg.v2.enhancement.EnhancedStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.postprocessing.ProcessedStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundMatchedStarRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val stars = List(4) { DetectedStar(22f + it * 36, 26f, 1000f, 20f, 210f, 2.4f, 0f, 1f) }
    private val validator = ReferenceStarRetentionValidator()

    @Test fun processedRecoveryRestoresTheStarWithoutPastingADarkerSkyDisc() {
        val clean = render("clean", .004f)
        val processed = render("processed", .012f, missing = 1, amplitude = .24f)
        val originalClean = clean.file.readBytes()
        val originalProcessed = processed.file.readBytes()
        val alpha = alpha("processed-sky")
        assertFalse(validator.validate(clean, processed, stars).sources[1].retained)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ProcessedStarSignalPreserver().preserve(
                processed, clean, stars, ResultCandidateStore(files), clean, alpha
            )
            assertTrue(validator.validate(clean, result, stars).sources.all { it.retained })
            assertMatchedSkyAndUnchangedNeighbours(processed, result, stars[1])
        }
        assertArrayEquals(originalClean, clean.file.readBytes())
        assertArrayEquals(originalProcessed, processed.file.readBytes())
    }

    @Test fun enhancedRecoveryMatchesBrighterSkyAndStillRestoresSourcesOutsideTheSkyMask() {
        val baseline = render("baseline", .004f)
        val enhanced = render("enhanced", .012f, missing = 1, amplitude = .24f)
        val originalBaseline = baseline.file.readBytes()
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val sky = alpha("enhanced-sky")
            val result = EnhancedStarSignalPreserver().preserve(
                enhanced, baseline, baseline, stars, ResultCandidateStore(files), sky
            )
            assertEquals(1, result.metrics.protectedSourceCount)
            assertEquals(0, result.metrics.remainingLostSourceCount)
            assertTrue(validator.validate(baseline, result.image, stars).sources.all { it.retained })
            assertMatchedSkyAndUnchangedNeighbours(enhanced, result.image, stars[1])
        }
        // Enhanced already restores selected-stack support in the foreground; its sky matcher
        // must not prevent that separate protection when the mask contains no sky at all.
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(
                enhanced, baseline, baseline, stars, ResultCandidateStore(files), alpha("no-sky", false)
            )
            assertEquals(0, result.metrics.remainingLostSourceCount)
            assertTrue(validator.validate(baseline, result.image, stars).sources.all { it.retained })
        }
        assertArrayEquals(originalBaseline, baseline.file.readBytes())
    }

    @Test fun overlappingEdgePatchesAreOrderIndependentAndDoNotIntroduceHighlightClipping() {
        val catalogue = listOf(stars[0].copy(x = 4f, y = 4f), stars[0].copy(x = 12f, y = 6f))
        val baseline = render("bright-source", .004f, catalogue = catalogue, amplitude = .85f)
        val target = render("bright-target", .05f, catalogue = emptyList())
        val sky = alpha("edge-sky", maskedStripe = true)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val store = ResultCandidateStore(files)
            val first = BackgroundMatchedStarSource.restore(target, baseline, catalogue, sky, store)
            val second = BackgroundMatchedStarSource.restore(target, baseline, catalogue.reversed(), sky, store)
            FileBackedImageReader(first).use { output ->
                FileBackedImageReader(second).use { reversed ->
                    FileBackedImageReader(target).use { before ->
                        for (y in 0 until 64) for (x in 0 until 160) {
                            val color = output.linearRgbAt(x, y)
                            assertEquals("Patch order changed $x,$y", color, reversed.linearRgbAt(x, y))
                            assertTrue(LinearRgb16.red(color) < .994f)
                            assertTrue(LinearRgb16.green(color) < .994f)
                            assertTrue(LinearRgb16.blue(color) < .994f)
                            if (y >= 48 || x in 9..11) assertEquals(before.linearRgbAt(x, y), color)
                        }
                    }
                }
            }
        }
    }

    @Test fun faintColoredSourcesKeepTheirMeasuredWidthAndCentroidAtTheBlackFloor() {
        fun image(name: String, source: Boolean) = FileBackedImageWriter(
            File(folder.root, "$name.rgb16"), 160, 64, FileBackedPixelFormat.LINEAR_RGB_16
        ).use { writer ->
            repeat(64) { y -> writer.writeLinearRow(y, LongArray(160) { x ->
                val signal = if (!source) 0f else stars.sumOf { star ->
                    val dx = x - star.x
                    val dy = y - star.y
                    12.0 * exp(-(dx * dx + dy * dy) / (2 * 1.3 * 1.3))
                }.toInt().toFloat()
                // Each background pixel has displayed luminance zero, while the RGB
                // channel medians are (1,1,1), whose displayed luminance is one.
                fun channel(index: Int): Float {
                    val background = if (source) {
                        if ((x + y) % 3 == index) 0f else 1f
                    } else .2f
                    return SrgbTransfer.srgbToLinear((background + signal) / 255f)
                }
                LinearRgb16.pack(channel(0), channel(1), channel(2))
            }) }
            writer.finish()
        }
        val source = image("faint-source", true)
        val target = image("faint-target", false)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = BackgroundMatchedStarSource.restore(target, source, stars,
                alpha("faint-sky"), ResultCandidateStore(files))
            val measured = validator.validate(source, result, stars)
            assertEquals(4, measured.sources.size)
            measured.sources.forEach { star ->
                assertTrue(star.failureReasons.toString(), star.retained)
                assertEquals(star.beforeContrast, star.afterContrast, 0f)
                assertEquals(star.beforeWidth, star.afterWidth, .0001f)
                assertEquals(0f, star.centroidShift, .0001f)
            }
        }
    }

    @Test fun anIntrinsicallyElongatedBaselineKeepsItsPreviousExactCopy() {
        val centre = stars.first()
        val baseline = render("elongated-baseline", .004f, catalogue = listOf(centre),
            sigmaX = 2.0, sigmaY = .32)
        val target = render("elongated-target", .012f, catalogue = emptyList())
        val catalogue = listOf(centre)
        assertTrue(validator.validate(baseline, baseline, catalogue).sources.single()
            .failureReasons.contains("line_like_ellipticity"))
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = ProcessedStarSignalPreserver().preserve(target, baseline, catalogue,
                ResultCandidateStore(files), baseline, alpha("elongated-sky"))
            FileBackedImageReader(baseline).use { source ->
                FileBackedImageReader(result).use { output ->
                    for (y in 22..30) for (x in 18..26) {
                        assertEquals(source.linearRgbAt(x, y), output.linearRgbAt(x, y))
                    }
                }
            }
        }
    }

    @Test fun clippedMatchingCannotLoseASourceKeptByTheExactTransfer() {
        val baseline = render("clipping-baseline", .004f, amplitude = .8f)
        val target = render("clipping-target", .6f, catalogue = emptyList())
        val sky = alpha("clipping-sky")
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val store = ResultCandidateStore(files)
            val proposed = BackgroundMatchedStarSource.restore(target, baseline, stars, sky, store)
            assertTrue("The fixture must expose lost stellar signal after clipping",
                validator.validate(baseline, proposed, stars).sources.any { !it.retained })
            val result = ProcessedStarSignalPreserver().preserve(target, baseline, stars, store, baseline, sky)
            assertTrue(validator.validate(baseline, result, stars).sources.all { it.retained })
            FileBackedImageReader(baseline).use { source ->
                FileBackedImageReader(result).use { output ->
                    stars.forEach { star ->
                        assertEquals(source.linearRgbAt(star.x.toInt(), star.y.toInt()),
                            output.linearRgbAt(star.x.toInt(), star.y.toInt()))
                    }
                }
            }
        }
    }

    private fun assertMatchedSkyAndUnchangedNeighbours(
        before: FileBackedImage,
        result: FileBackedImage,
        restored: DetectedStar
    ) {
        FileBackedImageReader(before).use { original ->
            FileBackedImageReader(result).use { output ->
                val cx = restored.x.toInt()
                val cy = restored.y.toInt()
                // The old absolute patch copy makes this ring about three times darker.
                for (dy in -10..10) for (dx in -10..10) {
                    val r2 = dx * dx + dy * dy
                    if (r2 !in 49..100) continue
                    val first = original.linearRgbAt(cx + dx, cy + dy)
                    val second = output.linearRgbAt(cx + dx, cy + dy)
                    assertTrue("Dark or bright seam at $dx,$dy",
                        abs(LinearRgb16.luminance(second) - LinearRgb16.luminance(first)) <= 2f / 65535f)
                }
                for (y in 12..40) for (x in 8..36) {
                    assertEquals("A healthy neighbour changed", original.linearRgbAt(x, y), output.linearRgbAt(x, y))
                }
                for (y in 48 until 64) for (x in 0 until 160) {
                    assertEquals("Masked foreground changed", original.linearRgbAt(x, y), output.linearRgbAt(x, y))
                }
            }
        }
    }

    private fun alpha(name: String, sky: Boolean = true, maskedStripe: Boolean = false): FileBackedFloatPlane =
        FileBackedFloatPlaneWriter(File(folder.root, "$name.f32"), 160, 64).use { writer ->
            repeat(64) { y -> writer.writeRow(y, FloatArray(160) { x ->
                if (sky && y < 48 && !(maskedStripe && x in 9..11)) 1f else 0f
            }) }
            writer.finish()
        }

    private fun render(
        name: String,
        background: Float,
        missing: Int = -1,
        catalogue: List<DetectedStar> = stars,
        amplitude: Float = .08f,
        sigmaX: Double = 1.1,
        sigmaY: Double = 1.1
    ): FileBackedImage = FileBackedImageWriter(
        File(folder.root, "$name.rgb16"), 160, 64, FileBackedPixelFormat.LINEAR_RGB_16
    ).use { writer ->
        repeat(64) { y -> writer.writeLinearRow(y, LongArray(160) { x ->
            val signal = catalogue.mapIndexed { index, star ->
                val dx = x - star.x
                val dy = y - star.y
                if (index == missing) 0.0 else amplitude * exp(
                    -(dx * dx / (sigmaX * sigmaX) + dy * dy / (sigmaY * sigmaY)) / 2
                )
            }.sum().toFloat()
            LinearRgb16.pack(background + signal, background * 1.2f + signal, background * 1.5f + signal)
        }) }
        writer.finish()
    }
}
