package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionFrameVerificationResult
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRefinementPolicy
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionRegistrationRefiner
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatchMatcher
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionTransformEvidence
import com.example.astrophoto.processing.jpeg.v2.registration.SubpixelPeakEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import com.example.astrophoto.processing.jpeg.v2.sampling.CachedArgbFrame
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.SamplingFidelityDiagnostic
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage11Test {
    @Test fun quadraticPeakFitRecoversPositiveNegativeAndMixedQuarterPixelOffsets() {
        val offsets = listOf(-0.75f, -0.50f, -0.25f, 0.25f, 0.50f, 0.75f)
        offsets.forEach { dx ->
            offsets.forEach { dy ->
                val fixture = parabolicSurface(dx, dy)
                val peak = SubpixelPeakEstimator().estimate(
                    fixture.scores,
                    fixture.side,
                    fixture.side,
                    fixture.peakIndex % fixture.side,
                    fixture.peakIndex / fixture.side
                )
                assertTrue("dx=$dx dy=$dy $peak", peak.accepted)
                assertEquals(CENTER + dx, peak.x, 0.001f)
                assertEquals(CENTER + dy, peak.y, 0.001f)
                assertTrue(peak.sharpness > 0f)
            }
        }
    }

    @Test fun flatMultiPeakAndEdgeClippedSurfacesAreRejected() {
        val estimator = SubpixelPeakEstimator()
        val flat = FloatArray(49) { 0.5f }
        assertFalse(estimator.estimate(flat, 7, 7, 3, 3).accepted)
        val edgeFixture = parabolicSurface(-3f, 0f)
        assertEquals("edge_clipped_peak", estimator.estimate(
            edgeFixture.scores, 7, 7, 0, 3
        ).rejectionReason)
        val multi = parabolicSurface(0f, 0f).scores
        multi[1 * 7 + 1] = multi[3 * 7 + 3]
        assertEquals("ambiguous_multi_peak_surface", estimator.estimate(multi, 7, 7, 3, 3).rejectionReason)
    }

    @Test fun matcherRecoversIntegerAxesMixedSignsAndSubpixelTranslations() {
        val corrections = listOf(
            2f to 0f,
            0f to -2f,
            1.5f to -1f,
            0.25f to -0.25f,
            0.50f to -0.50f,
            -0.75f to 0.75f
        )
        corrections.forEach { correction ->
            val fixture = fixture(
                initial = ReferenceToSourceTransform(-2f, 2f),
                correctionX = correction.first,
                correctionY = correction.second
            )
            val match = FullResolutionStarPatchMatcher().match(
                fixture.reference,
                fixture.candidate,
                fixture.patches.first(),
                fixture.initial,
                4f
            )
            assertTrue("correction=$correction match=$match", match.accepted)
            assertEquals(correction.first, match.correctionDx, 0.18f)
            assertEquals(correction.second, match.correctionDy, 0.18f)
        }
    }

    @Test fun normalizedMatchingToleratesBrightnessGradientAndJpegLikeNoise() {
        listOf(
            RenderOptions(brightness = 0.65f),
            RenderOptions(brightness = 1.15f, gradientX = 0.12f, gradientY = -0.08f),
            RenderOptions(brightness = 0.85f, noiseAmplitude = 0.018f)
        ).forEach { options ->
            val fixture = fixture(
                initial = ReferenceToSourceTransform(-2f, 2f),
                correctionX = 0.5f,
                correctionY = -0.25f,
                candidateOptions = options
            )
            val match = FullResolutionStarPatchMatcher().match(
                fixture.reference, fixture.candidate, fixture.patches[1], fixture.initial, 4f
            )
            assertTrue("options=$options match=$match", match.accepted)
            assertEquals(0.5f, match.correctionDx, 0.22f)
            assertEquals(-0.25f, match.correctionDy, 0.22f)
        }
    }

    @Test fun referenceIdentityIsExactAndNeverRefined() {
        val fixture = fixture(ReferenceToSourceTransform.Identity, 0f, 0f)
        val result = FullResolutionRegistrationRefiner().refine(
            "reference",
            true,
            fixture.reference,
            fixture.reference,
            ReferenceToSourceTransform(4f, -3f),
            fixture.patches,
            1f,
            1f,
            1f,
            0.8f
        )
        assertTrue(result.accepted)
        assertEquals(ReferenceToSourceTransform.Identity, result.refinedTransform)
        assertEquals(0f, result.correctionDx, 0f)
        assertEquals(0f, result.correctionDy, 0f)
    }

    @Test fun fullResolutionRefinerRecoversCorrectionAndBeatsTransformAlternatives() {
        val fixture = fixture(
            initial = ReferenceToSourceTransform(-4f, 3f),
            correctionX = 0.5f,
            correctionY = -0.25f
        )
        val result = refine(fixture)
        assertTrue(result.toString(), result.accepted)
        assertEquals(0.5f, result.correctionDx, 0.18f)
        assertEquals(-0.25f, result.correctionDy, 0.18f)
        assertTrue(result.acceptedPatchCount >= 3)
        assertTrue(result.spatialSectorCount >= 2)
        assertTrue(result.medianResidual <= 0.45f)
        assertTrue(result.percentile90Residual <= 0.75f)
        val evidence = result.verification
        assertTrue(evidence.refined.score >= evidence.initial.score - 0.01f)
        assertTrue(evidence.refined.score > evidence.identity.score)
        assertTrue(evidence.refined.score > evidence.inverse.score)
        assertTrue(evidence.refined.score > evidence.doubleApplied.score)
    }

    @Test fun stationaryBoundaryBuildingAndSaturatedPatchesAreRejectedWithReasons() {
        val base = patches()
        val filtered = listOf(
            base[0].copy(motionCluster = TemporalMotionCluster.STATIONARY_CAMERA_SPACE),
            base[1].copy(skyCoverage = 0.5f),
            base[2].copy(ellipticity = 0.95f)
        ) + base.drop(3)
        val trueTransform = ReferenceToSourceTransform(-3.5f, 2.75f)
        val reference = render(STARS + Star(80f, 60f, amplitude = 2f), ReferenceToSourceTransform.Identity)
        val candidate = render(STARS + Star(80f, 60f, amplitude = 2f), trueTransform)
        val result = FullResolutionRegistrationRefiner().refine(
            "candidate",
            false,
            reference,
            candidate,
            ReferenceToSourceTransform(-4f, 3f),
            filtered + FullResolutionStarPatch(
                80f, 60f, 1f, 1f, 1f, 0f, 4,
                TemporalMotionCluster.COHERENT_MOVING_SKY, 1f
            ),
            1f, 0.5f, 0.5f, 0.9f
        )
        val reasons = result.patchDiagnostics.mapNotNull { it.rejectionReason }.toSet()
        assertTrue(reasons.contains("stationary_camera_patch"))
        assertTrue(reasons.contains("sky_foreground_boundary_patch"))
        assertTrue(reasons.contains("line_or_building_edge_patch"))
        assertTrue(reasons.contains("saturated_patch"))
    }

    @Test fun robustAggregationRejectsOneWrongPatchAndKeepsTrueCorrection() {
        val initial = ReferenceToSourceTransform(-4f, 3f)
        val trueTransform = ReferenceToSourceTransform(-3.5f, 2.75f)
        val wrong = STARS.mapIndexed { index, star ->
            if (index == 0) star.copy(candidateOffsetX = 0.8f, candidateOffsetY = -0.7f) else star
        }
        val fixture = Fixture(
            reference = render(wrong, ReferenceToSourceTransform.Identity),
            candidate = render(wrong, trueTransform, useCandidateOffsets = true),
            initial = initial,
            patches = patches()
        )
        val result = refine(fixture)
        assertEquals(0.5f, result.correctionDx, 0.20f)
        assertEquals(-0.25f, result.correctionDy, 0.20f)
        assertTrue(result.patchDiagnostics.any { it.rejectionReason == "mad_outlier" })
    }

    @Test fun insufficientPatchesAndSpatialSectorsRejectFrame() {
        val fixture = fixture(ReferenceToSourceTransform(-4f, 3f), 0.5f, -0.25f)
        val tooFew = refine(fixture.copy(patches = fixture.patches.take(2)))
        assertFalse(tooFew.accepted)
        assertEquals("insufficient_full_resolution_patches", tooFew.rejectionReason)
        val strong = FullResolutionTransformEvidence(6, 0.95f, 1f, 1f, 0.1f, 0f, 0f)
        val verification = FullResolutionFrameVerificationResult(
            strong,
            strong.copy(score = 0.8f, centroidResidual = 0.4f),
            strong.copy(score = 0.5f, centroidResidual = 1.5f),
            strong.copy(score = 0.4f, centroidResidual = 2f),
            strong.copy(score = 0.45f, centroidResidual = 1.8f),
            1
        )
        val oneSector = FullResolutionRefinementPolicy().decide(
            -4f, 3f, 0.5f, -0.25f, 4, 3, 1, 0.1f, 0.2f, 0.9f, 4f, verification
        )
        assertEquals("insufficient_spatial_sectors", oneSector.rejectionReason)
    }

    @Test fun policyRejectsZeroModeJumpDistantCorrectionAndWeakFullResolutionEvidence() {
        val strong = FullResolutionTransformEvidence(6, 0.95f, 1f, 1f, 0.1f, 0f, 0f)
        val alternatives = FullResolutionFrameVerificationResult(
            refined = strong,
            initial = strong.copy(score = 0.80f, centroidResidual = 0.4f),
            identity = strong.copy(score = 0.50f, centroidResidual = 1.5f),
            inverse = strong.copy(score = 0.40f, centroidResidual = 2f),
            doubleApplied = strong.copy(score = 0.45f, centroidResidual = 1.8f),
            spatialSectorCount = 3
        )
        val policy = FullResolutionRefinementPolicy()
        val zeroJump = policy.decide(-3f, 0f, 2.8f, 0f, 6, 3, 3, 0.1f, 0.2f, 0.9f, 4f, alternatives)
        assertFalse(zeroJump.accepted)
        assertEquals("refinement_jump_to_zero_mode", zeroJump.rejectionReason)
        val distant = policy.decide(-8f, 0f, 5f, 0f, 6, 3, 3, 0.1f, 0.2f, 0.9f, 3f, alternatives)
        assertEquals("full_resolution_correction_out_of_bounds", distant.rejectionReason)
        val weak = alternatives.copy(refined = strong.copy(retention = 0.4f))
        val weakDecision = policy.decide(-8f, 0f, 0.2f, 0f, 6, 3, 3, 0.1f, 0.2f, 0.9f, 3f, weak)
        assertEquals("full_resolution_patch_retention_low", weakDecision.rejectionReason)
    }

    @Test fun samplerFidelityIsMeasuredWithoutChangingProductionKernel() {
        val fixture = fixture(ReferenceToSourceTransform.Identity, 0f, 0f)
        val diagnostic = SamplingFidelityDiagnostic().measure(fixture.reference, fixture.patches)
        assertEquals("BILINEAR", diagnostic.kernel)
        assertEquals(1f, diagnostic.identityContrastRatio, 0f)
        assertTrue(diagnostic.productionContrastRatio in 0f..1.05f)
        assertEquals(0f, diagnostic.alternativeContrastRatio, 0f)
    }

    @Test fun cacheMetadataCarriesRefinedFullResolutionTransformWithoutRescaling() {
        val transform = ReferenceToSourceTransform(-7.25f, 4.5f)
        val cached = CachedArgbFrame(File("bounded.argb"), 160, 120, transform)
        assertEquals(transform, cached.referenceToSourceTransform)
        assertEquals(-7.25f, cached.referenceToSourceTransform?.dx ?: 0f, 0f)
    }

    @Test fun productionOrderUsesFinalRefinedFramesWeightsAndSampler() {
        val source = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))
        val profile = source.substring(source.indexOf("suspend fun profileStack("), source.indexOf("suspend fun loadResultPreview("))
        val provisional = profile.indexOf("val provisionalAcceptedFrames")
        val refinement = profile.indexOf("prepareAndRefineFullResolutionFrames(")
        val weights = profile.indexOf("FrameWeightCalculator().calculate(")
        val integration = profile.indexOf("LinearWeightedIntegrator().integrate(")
        assertTrue(provisional in 0 until refinement)
        assertTrue(refinement in 0 until weights)
        assertTrue(weights in 0 until integration)
        assertTrue(profile.contains("acceptedProfileFrames += fullResolutionPreparation.acceptedFrames"))
        assertTrue(profile.contains("val cachedFrames = fullResolutionPreparation.cachedFrames"))
        assertTrue(profile.contains("transform = accepted.registration"))
        assertFalse(profile.substring(refinement, integration).contains("scaledToFullResolution("))
        assertTrue(source.contains("cached.copy(\n                    referenceToSourceTransform = centroidResult.refinedTransform"))
    }

    @Test fun productionRefinementUsesOriginalResolutionCacheNotUpscaledThumbnail() {
        val source = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))
        val helper = source.substring(
            source.indexOf("private suspend fun prepareAndRefineFullResolutionFrames("),
            source.indexOf("private fun logFullResolutionRefinement(")
        )
        val decode = helper.indexOf("decodeMedianFrame(accepted.frame, targetWidth, targetHeight)")
        val cache = helper.indexOf("ArgbFrameDiskCache.write(")
        val refine = helper.indexOf("refiner.refine(")
        assertTrue(decode in 0 until cache)
        assertTrue(cache in 0 until refine)
        assertTrue(helper.contains("FileBackedArgbPixelSource(referenceCached, 32)"))
        assertTrue(helper.contains("FileBackedArgbPixelSource(cached, 32)"))
        assertFalse(helper.contains("analysisWidth"))
        assertFalse(helper.contains("upscale"))
    }

    @Test fun reportsExposeAllStage11FieldsAndLegacyFieldsRemain() {
        val source = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt")
        )
        listOf(
            "fullResolutionRefinementEnabled",
            "provisionalAcceptedFrameCount",
            "finalAcceptedFrameCount",
            "fullResolutionRefinementRejectedCount",
            "fullResolutionInitialDxPerFrame",
            "fullResolutionRefinedDyPerFrame",
            "fullResolutionCorrectionDxPerFrame",
            "fullResolutionAttemptedPatchCountPerFrame",
            "fullResolutionAcceptedPatchCountPerFrame",
            "fullResolutionRejectedPatchCountPerFrame",
            "fullResolutionMedianPatchScorePerFrame",
            "fullResolutionMedianResidualPerFrame",
            "fullResolutionP90ResidualPerFrame",
            "fullResolutionSpatialSectorCountPerFrame",
            "fullResolutionRefinementConfidencePerFrame",
            "fullResolutionRefinementAcceptedPerFrame",
            "fullResolutionRefinementReasonPerFrame",
            "fullResolutionPatchRetentionPerFrame",
            "fullResolutionPatchContrastRatioPerFrame",
            "fullResolutionPatchCentroidResidualPerFrame",
            "fullResolutionPatchWidthGrowthPerFrame",
            "fullResolutionPatchSmearPerFrame",
            "samplingKernel",
            "samplingIdentityContrastRatio",
            "samplingProductionContrastRatio",
            "samplingAlternativeContrastRatio",
            "modelGuidedRegistrationEnabled",
            "acceptedFrameCount"
        ).forEach { field -> assertTrue(field, source.contains(field)) }
    }

    @Test fun finalQualityGateConstantsAndCanonicalTransformRemainUnchanged() {
        val transform = ReferenceToSourceTransform(-6.25f, 3.5f)
        val mapped = transform.mapOutputToSource(100f, 80f)
        assertEquals(93.75f, mapped.x, 0f)
        assertEquals(83.5f, mapped.y, 0f)
        val source = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/quality/ReferenceStarRetentionValidator.kt")
        )
        assertTrue(source.contains("MIN_RETENTION_RATIO = 0.90f"))
        assertTrue(source.contains("MIN_MEDIAN_CONTRAST_RATIO = 0.85f"))
        assertTrue(source.contains("MAX_LINE_LIKE_SMEAR_RATE = 0.10f"))
    }

    private fun refine(fixture: Fixture) = FullResolutionRegistrationRefiner().refine(
        frameId = "candidate",
        isReference = false,
        reference = fixture.reference,
        candidate = fixture.candidate,
        initialTransform = fixture.initial,
        patches = fixture.patches,
        analysisScaleUncertainty = 1f,
        stage10Residual = 0.6f,
        sequenceResidual = 0.4f,
        stage10Confidence = 0.9f
    )

    private fun fixture(
        initial: ReferenceToSourceTransform,
        correctionX: Float,
        correctionY: Float,
        candidateOptions: RenderOptions = RenderOptions()
    ): Fixture {
        val trueTransform = ReferenceToSourceTransform(initial.dx + correctionX, initial.dy + correctionY)
        return Fixture(
            reference = render(STARS, ReferenceToSourceTransform.Identity),
            candidate = render(STARS, trueTransform, candidateOptions),
            initial = initial,
            patches = patches()
        )
    }

    private fun render(
        stars: List<Star>,
        transform: ReferenceToSourceTransform,
        options: RenderOptions = RenderOptions(),
        useCandidateOffsets: Boolean = false
    ): IntArrayPixelSource {
        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            var luminance = BACKGROUND + options.gradientX * x / WIDTH + options.gradientY * y / HEIGHT
            stars.forEach { star ->
                val mapped = transform.mapOutputToSource(star.x, star.y)
                val centerX = mapped.x + if (useCandidateOffsets) star.candidateOffsetX else 0f
                val centerY = mapped.y + if (useCandidateOffsets) star.candidateOffsetY else 0f
                val dx = x - centerX
                val dy = y - centerY
                luminance += star.amplitude * options.brightness *
                    exp((-(dx * dx + dy * dy) / (2f * star.sigma * star.sigma)).toDouble()).toFloat()
            }
            if (options.noiseAmplitude > 0f) {
                val deterministic = (((x * 37 + y * 73) % 17) - 8) / 8f
                luminance += deterministic * options.noiseAmplitude
            }
            val value = (luminance.coerceIn(0f, 1f) * 255f).toInt()
            pixels[y * WIDTH + x] = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        return IntArrayPixelSource(WIDTH, HEIGHT, pixels)
    }

    private fun patches(): List<FullResolutionStarPatch> = STARS.map { star ->
        val sectorX = floor(star.x / WIDTH * 3f).toInt().coerceIn(0, 2)
        val sectorY = floor(star.y / HEIGHT * 3f).toInt().coerceIn(0, 2)
        FullResolutionStarPatch(
            x = star.x,
            y = star.y,
            confidence = 1f,
            localContrast = star.amplitude,
            width = star.sigma,
            ellipticity = 0.05f,
            sector = sectorY * 3 + sectorX,
            motionCluster = TemporalMotionCluster.COHERENT_MOVING_SKY,
            skyCoverage = 1f
        )
    }

    private fun parabolicSurface(dx: Float, dy: Float): SurfaceFixture {
        val side = 7
        val targetX = CENTER + dx
        val targetY = CENTER + dy
        val scores = FloatArray(side * side) { index ->
            val x = index % side
            val y = index / side
            0.95f - 0.07f * ((x - targetX) * (x - targetX) + (y - targetY) * (y - targetY))
        }
        val peakIndex = scores.indices.maxBy { scores[it] }
        return SurfaceFixture(scores, side, peakIndex)
    }

    private data class Fixture(
        val reference: IntArrayPixelSource,
        val candidate: IntArrayPixelSource,
        val initial: ReferenceToSourceTransform,
        val patches: List<FullResolutionStarPatch>
    )

    private data class RenderOptions(
        val brightness: Float = 1f,
        val gradientX: Float = 0f,
        val gradientY: Float = 0f,
        val noiseAmplitude: Float = 0f
    )

    private data class Star(
        val x: Float,
        val y: Float,
        val amplitude: Float = 0.55f,
        val sigma: Float = 1.15f,
        val candidateOffsetX: Float = 0f,
        val candidateOffsetY: Float = 0f
    )

    private data class SurfaceFixture(val scores: FloatArray, val side: Int, val peakIndex: Int)

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 120
        private const val BACKGROUND = 0.08f
        private const val CENTER = 3f
        private val STARS = listOf(
            Star(26.2f, 24.3f),
            Star(78.4f, 22.6f),
            Star(132.1f, 28.4f),
            Star(31.7f, 86.2f),
            Star(82.3f, 78.5f),
            Star(130.6f, 88.1f)
        )
    }
}
