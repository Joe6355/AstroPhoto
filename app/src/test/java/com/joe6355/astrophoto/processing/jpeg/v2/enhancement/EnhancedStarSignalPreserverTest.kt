package com.joe6355.astrophoto.processing.jpeg.v2.enhancement

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.*
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedStarSignalPreserverTest {
    @get:Rule val folder = TemporaryFolder()
    private val stars = List(4) { DetectedStar(20.3f + it * 35, 30.6f, 1000f, 8f, 180f, 2.4f, 0f, 1f) }
    private val validator = ReferenceStarRetentionValidator()

    @Test fun widenedSourceUsesSelectedStackAndLeavesOtherEnhancementExact() {
        val reference = render("reference-wide", sigma = 0.9)
        val baseline = render("baseline-wide", sigma = 1.15)
        val candidate = render("enhanced-wide", sigma = 1.15, firstSigma = 1.7, amplitude = 210)
        assertTrue(validator.validate(reference, baseline, stars).sources.first().retained)
        assertTrue(validator.validate(reference, candidate, stars).sources.first().failureReasons.contains("width_above_1_50x"))
        val baselineBytes = baseline.file.readBytes()
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(candidate, baseline, reference, stars, ResultCandidateStore(files))
            assertEquals(4, result.metrics.baselineRetainedSourceCount)
            assertEquals(1, result.metrics.protectedSourceCount)
            assertEquals(0, result.metrics.remainingLostSourceCount)
            assertTrue(validator.validate(reference, result.image, stars).sources.all { it.retained })
            assertArrayEquals(baselineBytes, baseline.file.readBytes())
            FileBackedImageReader(result.image).use { after ->
                FileBackedImageReader(reference).use { raw ->
                    assertNotEquals(raw.linearRgbAt(20, 31), after.linearRgbAt(20, 31))
                }
                FileBackedImageReader(candidate).use { before ->
                    for (y in 15..45) for (x in 45..140) {
                        assertEquals(before.linearRgbAt(x, y), after.linearRgbAt(x, y))
                    }
                }
            }
        }
    }

    @Test fun displacedSourceIsRestoredWithoutMovingTheImage() {
        val reference = render("reference-shift")
        val baseline = render("baseline-shift", firstShift = 0.15)
        val candidate = render("enhanced-shift", firstShift = 1.25, amplitude = 210)
        assertTrue(validator.validate(reference, candidate, stars).sources.first().failureReasons.contains("centroid_shift_above_0_75_px"))
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(candidate, baseline, reference, stars, ResultCandidateStore(files))
            val source = validator.validate(reference, result.image, stars).sources.first()
            assertTrue(source.retained)
            assertTrue(source.centroidShift <= 0.75f)
            assertEquals(1, result.metrics.protectedSourceCount)
            assertEquals(0, result.metrics.remainingLostSourceCount)
        }
    }

    @Test fun healthyFinishKeepsTheOriginalCandidateFile() {
        val reference = render("reference-healthy")
        val baseline = render("baseline-healthy")
        val candidate = render("enhanced-healthy", amplitude = 210)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(candidate, baseline, reference, stars, ResultCandidateStore(files))
            assertSame(candidate, result.image)
            assertEquals(0, result.metrics.protectedSourceCount)
            assertEquals(4, result.metrics.baselineRetainedSourceCount)
        }
    }

    @Test fun aNewSourceCannotBeErasedByAnAbsentBaselineSource() {
        val reference = render("reference-new")
        val baseline = render("baseline-new", missingFirst = true)
        val candidate = render("enhanced-new", amplitude = 210)
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(candidate, baseline, reference, stars, ResultCandidateStore(files))
            assertSame(candidate, result.image)
            assertEquals(3, result.metrics.baselineRetainedSourceCount)
            assertTrue(validator.validate(reference, result.image, stars).sources.first().retained)
        }
    }

    @Test fun existingReferenceElongationIsNotReportedAsANewEnhancementLoss() {
        val reference = render("reference-line", firstSigma = 2.0, firstSigmaY = 0.32)
        val baseline = render("baseline-line", firstSigma = 2.0, firstSigmaY = 0.32)
        val candidate = render("enhanced-line", firstSigma = 2.0, firstSigmaY = 0.32, amplitude = 210)
        assertTrue(validator.validate(reference, baseline, stars).sources.first().failureReasons.contains("line_like_ellipticity"))
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val result = EnhancedStarSignalPreserver().preserve(candidate, baseline, reference, stars, ResultCandidateStore(files))
            assertSame(candidate, result.image)
            assertEquals(3, result.metrics.baselineRetainedSourceCount)
            assertEquals(0, result.metrics.remainingLostSourceCount)
        }
    }

    @Test fun referenceEvidenceReachesTheFinalEnhancedReport() {
        val baseline = render("baseline-report")
        val alpha = FileBackedFloatPlaneWriter(File(folder.root, "alpha.f32"), 151, 65).use { writer ->
            repeat(65) { writer.writeRow(it, FloatArray(151) { 1f }) }
            writer.finish()
        }
        TemporaryPipelineFiles.create(folder.root).use { files ->
            val candidate = EnhancedGlobalToneProcessor().createCandidate(
                baseline, alpha, stars, ResultCandidateStore(files), referenceImage = baseline
            )
            assertEquals(4, candidate.validation.metrics.referenceBaselineRetainedSourceCount)
            assertEquals(0, candidate.validation.metrics.referenceRemainingLostSourceCount)
            assertEquals(4f, candidate.validation.metrics.asReportMetrics()["referenceBaselineRetainedSourceCount"])
            assertEquals(candidate.baselinePixelHashBefore, candidate.baselinePixelHashAfter)
        }
    }

    private fun render(
        name: String,
        sigma: Double = 1.1,
        firstSigma: Double = sigma,
        firstSigmaY: Double = firstSigma,
        firstShift: Double = 0.0,
        amplitude: Int = 180,
        missingFirst: Boolean = false
    ): FileBackedImage = FileBackedImageWriter(
        File(folder.root, "$name.rgb16"), 151, 65, FileBackedPixelFormat.LINEAR_RGB_16
    ).use { writer ->
        repeat(65) { y ->
            writer.writeRow(y, IntArray(151) { x ->
                val signal = stars.mapIndexed { index, star ->
                    val sx = if (index == 0) firstSigma else sigma
                    val sy = if (index == 0) firstSigmaY else sigma
                    val dx = x - star.x - if (index == 0) firstShift else 0.0
                    val dy = y - star.y.toDouble()
                    if (index == 0 && missingFirst) 0.0 else amplitude * exp(-(dx * dx / (sx * sx) + dy * dy / (sy * sy)) / 2)
                }.sum()
                val value = (8 + signal).roundToInt().coerceIn(0, 255)
                0xff000000.toInt() or (value shl 16) or (value shl 8) or value
            })
        }
        writer.finish()
    }
}
