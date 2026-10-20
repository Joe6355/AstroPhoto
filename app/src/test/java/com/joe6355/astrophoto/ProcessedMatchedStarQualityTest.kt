package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidateType
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.quality.AstroResultQualityGate
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ResultQualityAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageWriter
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedPixelFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.exp
import kotlin.math.roundToInt

class ProcessedMatchedStarQualityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gate = AstroResultQualityGate()
    private val validator = ReferenceStarRetentionValidator()
    private val profile = AstroProcessingProfile.DEEP_SKY
    private val stars = (0 until 8).map { index ->
        DetectedStar(18f + (index % 4) * 30f, 18f + (index / 4) * 38f, 500f, 12f, 100f, 2.2f, .05f, .95f)
    }
    private val clean by lazy { image() }
    private val cleanMetrics by lazy {
        ResultQualityAnalyzer().analyze(clean, clean, AlphaMask.full(clean.width, clean.height))
    }

    @Test fun expandedFaintPopulationDoesNotRejectBrighterMatchedStars() {
        val brighter = image(amplitude = 115.0, extraFaintStars = true)
        val matched = validator.validate(clean, brighter, stars)
        assertTrue(matched.accepted)
        assertTrue(matched.metrics.medianContrastRatio > 1f)
        // Reproduce the observed detector population change: 21/45 -> 24/34.
        val baseline = cleanMetrics.copy(reliableStarCount = 21, medianStarLocalContrast = 45f)
        val changedPopulation = baseline.copy(reliableStarCount = 24, medianStarLocalContrast = 34f)
        val reference = candidate(ResultCandidateType.REFERENCE, clean, baseline)
        val stack = candidate(ResultCandidateType.CLEAN_STACK, clean, baseline)
        val processed = candidate(ResultCandidateType.PROCESSED, brighter, changedPopulation)

        val withoutMatches = gate.evaluateProcessed(reference, stack, processed, profile, 15)
        assertTrue("median_star_contrast_drop_gt_15_percent" in withoutMatches.hardFailureReasons)
        val withMatches = gate.evaluateProcessed(reference, stack, processed, profile, 15, matched)
        assertTrue(withMatches.hardFailureReasons.toString(), withMatches.accepted)
    }

    @Test fun fileBackedRgb16UsesTheSameMatchedEvidence() {
        val brighter = image(amplitude = 115.0)
        fun stored(name: String, source: ArgbPixelImage, type: ResultCandidateType, metrics: ResultQualityMetrics): StoredResultCandidate {
            val writer = FileBackedImageWriter(
                temporary.root.resolve(name), source.width, source.height, FileBackedPixelFormat.LINEAR_RGB_16
            )
            for (y in 0 until source.height) writer.writeRow(y, source.pixels, y * source.width)
            return StoredResultCandidate(type, writer.finish(), metrics)
        }
        val baseline = cleanMetrics.copy(reliableStarCount = 21, medianStarLocalContrast = 45f)
        val stack = stored("clean.rgb16", clean, ResultCandidateType.CLEAN_STACK, baseline)
        val processed = stored("processed.rgb16", brighter, ResultCandidateType.PROCESSED,
            baseline.copy(reliableStarCount = 24, medianStarLocalContrast = 34f))
        val reference = stack.copy(type = ResultCandidateType.REFERENCE)
        val matched = validator.validate(stack.image, processed.image, stars)
        assertTrue(matched.accepted)
        assertFalse(gate.evaluateProcessed(reference, stack, processed, profile, 15).accepted)
        assertTrue(gate.evaluateProcessed(reference, stack, processed, profile, 15, matched).accepted)
    }

    @Test fun actualLostWeakenedBroadenedAndShiftedStarsStillRejectDespiteGoodGlobalMedians() {
        val damaged = listOf(
            image(omitted = setOf(0, 1)),
            image(amplitude = 45.0),
            image(sigma = 2.1),
            image(dx = 2.0)
        )
        val reference = candidate(ResultCandidateType.REFERENCE, clean, cleanMetrics)
        val stack = reference.copy(type = ResultCandidateType.CLEAN_STACK)
        for (output in damaged) {
            val matched = validator.validate(clean, output, stars)
            assertFalse(matched.metrics.toString(), matched.accepted)
            val candidate = candidate(ResultCandidateType.PROCESSED, output, cleanMetrics)
            val decision = gate.evaluateProcessed(reference, stack, candidate, profile, 15, matched)
            assertFalse(decision.toString(), decision.accepted)
            assertTrue(decision.hardFailureReasons.containsAll(matched.hardFailureReasons))
        }
    }

    @Test fun insufficientFixedStarsCannotAuthorizePopulationBypass() {
        val brighter = image(amplitude = 115.0)
        val insufficient = validator.validate(clean, brighter, stars.take(3))
        assertFalse(insufficient.accepted)
        val reference = candidate(ResultCandidateType.REFERENCE, clean, cleanMetrics)
        val processed = candidate(ResultCandidateType.PROCESSED, brighter, cleanMetrics)
        val decision = gate.evaluateProcessed(reference, reference.copy(type = ResultCandidateType.CLEAN_STACK),
            processed, profile, 15, insufficient)
        assertFalse(decision.accepted)
        assertTrue("insufficient_reference_stars_for_retention_validation" in decision.hardFailureReasons)
    }

    @Test fun moderateElongationStillRejectsWhenGlobalPopulationMediansHideIt() {
        val baseline = image(sigma = 1.05, ySigma = 1.0)
        val elongated = image(sigma = 1.25, ySigma = 1.0)
        val matched = validator.validate(baseline, elongated, stars)
        // This is below the retention validator's line-smear and 20% width limits.
        assertTrue(matched.metrics.toString(), matched.accepted)
        assertTrue(matched.sources.all { it.beforeEllipticity > 0f })
        val population = ResultQualityAnalyzer().analyze(baseline, baseline, AlphaMask.full(128, 96))
        val reference = candidate(ResultCandidateType.REFERENCE, baseline, population)
        val stack = reference.copy(type = ResultCandidateType.CLEAN_STACK)
        val processed = candidate(ResultCandidateType.PROCESSED, elongated, population)
        val decision = gate.evaluateProcessed(reference, stack, processed, profile, 15, matched)
        assertFalse("Matched shapes must retain the existing ellipticity guard", decision.accepted)
        assertTrue(decision.hardFailureReasons.any { "ellipticity" in it })
    }

    @Test fun matchedStarsDoNotOverrideClippingFakePointsOrForegroundChanges() {
        val matched = validator.validate(clean, clean, stars)
        assertTrue(matched.accepted)
        val reference = candidate(ResultCandidateType.REFERENCE, clean, cleanMetrics)
        val stack = reference.copy(type = ResultCandidateType.CLEAN_STACK)
        val badMetrics = listOf(
            cleanMetrics.copy(brightStarClippingPercent = 40f),
            cleanMetrics.copy(suspiciousPointCount = cleanMetrics.suspiciousPointCount + 5),
            cleanMetrics.copy(foregroundMaximumPixelDifference = 100, foregroundMeanPixelDifference = 20f)
        )
        for (metrics in badMetrics) {
            val processed = candidate(ResultCandidateType.PROCESSED, clean, metrics)
            assertFalse(gate.evaluateProcessed(reference, stack, processed, profile, 15, matched).accepted)
        }
    }

    private fun candidate(type: ResultCandidateType, image: ArgbPixelImage, metrics: ResultQualityMetrics) =
        ResultCandidate(type, image, metrics)

    private fun image(
        amplitude: Double = 100.0,
        sigma: Double = 1.0,
        ySigma: Double = sigma,
        dx: Double = 0.0,
        omitted: Set<Int> = emptySet(),
        extraFaintStars: Boolean = false
    ): ArgbPixelImage = ArgbPixelImage(128, 96, IntArray(128 * 96) { index ->
        val x = index % 128
        val y = index / 128
        var signal = 12.0
        for ((starIndex, star) in stars.withIndex()) if (starIndex !in omitted) {
            val rx = (x - star.x - dx) / sigma
            val ry = (y - star.y) / ySigma
            signal += amplitude * exp(-(rx * rx + ry * ry) / 2)
        }
        if (extraFaintStars) for (faint in 0 until 10) {
            val rx = x - (10 + faint % 5 * 24)
            val ry = y - (35 + faint / 5 * 42)
            signal += 15 * exp(-(rx * rx + ry * ry) / 2.0)
        }
        val channel = signal.roundToInt().coerceIn(0, 255)
        0xff000000.toInt() or (channel shl 16) or (channel shl 8) or channel
    })
}
