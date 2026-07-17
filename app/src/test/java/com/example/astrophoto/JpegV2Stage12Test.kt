package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarCentroidDetector
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.example.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatchSelector
import com.example.astrophoto.processing.jpeg.v2.registration.LocalBackgroundEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.StellarCentroidFrameRefiner
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage12Test {
    @Test fun scenario01DetectorMeasuresOriginalResolutionX() = assertCentroid(72.20f, 61.35f)
    @Test fun scenario02DetectorMeasuresOriginalResolutionY() = assertCentroid(72.65f, 61.80f)
    @Test fun scenario03ReferenceCentroidIsRefinedIndependently() = assertCentroid(71.75f, 60.70f, 73f, 62f)
    @Test fun scenario04BackgroundMedianRejectsBrightCore() = assertBackground(0f, 0f)
    @Test fun scenario05BackgroundPlaneTracksPositiveGradient() = assertBackground(0.0005f, 0.0003f)
    @Test fun scenario06BackgroundPlaneTracksMixedGradient() = assertBackground(-0.0004f, 0.0006f)
    @Test fun scenario07OnePixelTranslationIsMeasured() = assertDisplacement(1f, 0f)
    @Test fun scenario08TwoPixelTranslationIsMeasured() = assertDisplacement(0f, -2f)
    @Test fun scenario09ThreePixelTranslationIsMeasured() = assertDisplacement(-3f, 3f)
    @Test fun scenario10PositivePointTwoTranslationIsMeasured() = assertDisplacement(0.20f, 0.20f)
    @Test fun scenario11NegativePointTwoTranslationIsMeasured() = assertDisplacement(-0.20f, -0.20f)
    @Test fun scenario12MixedPointThreeFiveTranslationIsMeasured() = assertDisplacement(0.35f, -0.35f)
    @Test fun scenario13BrightnessChangeKeepsCentroid() = assertDisplacement(0.45f, -0.25f, brightness = 0.70f)
    @Test fun scenario14GradientChangeKeepsCentroid() = assertDisplacement(0.45f, -0.25f, gradientX = 0.0007f)
    @Test fun scenario15JpegLikeQuantizationKeepsCentroid() = assertDisplacement(0.45f, -0.25f, quantize = 12)
    @Test fun scenario16DeterministicNoiseKeepsCentroid() = assertDisplacement(0.45f, -0.25f, noise = 0.008f)
    @Test fun scenario17FlatPatchIsRejected() {
        assertFalse(detector().detect(render(emptyList()), 72f, 61f, 3f).accepted)
    }
    @Test fun scenario18BoundaryPatchIsRejected() {
        assertEquals("centroid_near_invalid_coverage_edge", detector().detect(
            render(listOf(Star(3f, 3f))), 3f, 3f, 3f
        ).rejectionReason)
    }
    @Test fun scenario19SaturatedPeakIsRejected() {
        assertFalse(detector().detect(render(listOf(Star(72f, 61f, amplitude = 4f, sigma = 2f))), 72f, 61f, 3f).accepted)
    }
    @Test fun scenario20LineLikePeakIsRejected() {
        val line = (64..80).map { x -> Star(x.toFloat(), 61f, 0.25f, 0.65f) }
        assertFalse(detector().detect(render(line), 72f, 61f, 3f).accepted)
    }
    @Test fun scenario21MultipleComparablePeaksAreRejected() {
        val result = detector().detect(render(listOf(Star(70f, 61f), Star(74f, 61f))), 72f, 61f, 4f)
        assertFalse(result.accepted)
    }
    @Test fun scenario22CanonicalTransformSignIsPreserved() {
        val result = refineFixture()
        assertTrue(result.refinedTransform.dx < 0f)
        assertTrue(result.refinedTransform.dy > 0f)
    }
    @Test fun scenario23CentroidCorrectionRecoversPositiveX() = assertRefinementCorrection(0.50f, -0.25f)
    @Test fun scenario24CentroidCorrectionRecoversNegativeX() = assertRefinementCorrection(-0.35f, 0.30f)
    @Test fun scenario25CentroidCorrectionRecoversMixedAxes() = assertRefinementCorrection(0.25f, 0.35f)
    @Test fun scenario26RefinedTransformBeatsInitial() {
        val result = refineFixture()
        assertTrue(result.verification.refined.centroidResidual < result.verification.initial.centroidResidual)
    }
    @Test fun scenario27RefinedTransformBeatsZncc() {
        val result = refineFixture()
        assertTrue(result.verification.refined.centroidResidual < result.verification.zncc.centroidResidual)
    }
    @Test fun scenario28RefinedTransformBeatsIdentity() {
        val result = refineFixture()
        assertTrue(result.verification.refined.centroidResidual < result.verification.identity.centroidResidual)
    }
    @Test fun scenario29RefinedTransformBeatsInverse() {
        val result = refineFixture()
        assertTrue(result.verification.refined.centroidResidual < result.verification.inverse.centroidResidual)
    }
    @Test fun scenario30RefinedTransformBeatsDoubleApplication() {
        val result = refineFixture()
        assertTrue(result.verification.refined.centroidResidual < result.verification.doubleApplied.centroidResidual)
    }
    @Test fun scenario31RobustAggregationRejectsWrongStar() {
        val result = refineFixture(candidateOffset = mapOf(0 to (1.0f to -0.9f)))
        assertTrue(result.diagnostics.any { it.rejectionReason == "centroid_mad_outlier" })
        assertEquals(-3.5f, result.refinedTransform.dx, 0.20f)
    }
    @Test fun scenario32MultipleSpatialSectorsAreRequired() {
        val fixture = frameFixture(
            correctionX = 0.5f,
            correctionY = -0.25f,
            candidateOffset = mapOf(3 to (8f to 0f), 4 to (8f to 0f), 5 to (8f to 0f))
        )
        val oneSector = fixture.copy(patches = fixture.patches.mapIndexed { index, patch ->
            patch.copy(sector = if (index < 3) 0 else index - 2)
        })
        val result = refine(oneSector)
        assertEquals("insufficient_centroid_spatial_sectors", result.rejectionReason)
    }
    @Test fun scenario33InsufficientCentroidsRejectFrame() {
        val fixture = frameFixture(correctionX = 0.5f, correctionY = -0.25f)
        val result = refine(fixture.copy(patches = fixture.patches.take(2)))
        assertEquals("insufficient_stellar_centroid_matches", result.rejectionReason)
    }
    @Test fun scenario34StationaryCameraPatchIsExcluded() {
        val fixture = frameFixture(correctionX = 0.5f, correctionY = -0.25f)
        val result = refine(fixture.copy(patches = fixture.patches.mapIndexed { index, patch ->
            if (index == 0) patch.copy(motionCluster = TemporalMotionCluster.STATIONARY_CAMERA_SPACE) else patch
        }))
        assertTrue(result.diagnostics.any { it.rejectionReason == "stationary_camera_patch" })
    }
    @Test fun scenario35SkyForegroundBoundaryIsExcluded() {
        val fixture = frameFixture(correctionX = 0.5f, correctionY = -0.25f)
        val result = refine(fixture.copy(patches = fixture.patches.mapIndexed { index, patch ->
            if (index == 0) patch.copy(skyCoverage = 0.5f) else patch
        }))
        assertTrue(result.diagnostics.any { it.rejectionReason == "sky_foreground_boundary_patch" })
    }
    @Test fun scenario36ReferenceIdentityRemainsExact() {
        val fixture = frameFixture(0f, 0f)
        val result = StellarCentroidFrameRefiner().refine(
            "reference", true, fixture.reference, fixture.reference,
            ReferenceToSourceTransform(9f, -8f), ReferenceToSourceTransform(-4f, 3f), fixture.patches,
            1f, 1f, 1f, 0.8f
        )
        assertTrue(result.accepted)
        assertEquals(ReferenceToSourceTransform.Identity, result.refinedTransform)
    }
    @Test fun scenario37CentroidIsPrimaryAndZnccIsSecondary() {
        val result = refineFixture()
        assertTrue(result.centroidPrimaryUsed)
        assertTrue(result.znccSecondaryUsed)
    }
    @Test fun scenario38ZnccFallbackIsNeverImplicit() = assertFalse(refineFixture().znccFallbackUsed)
    @Test fun scenario39MedianResidualMeetsStage12Target() = assertTrue(refineFixture().medianResidual <= 0.35f)
    @Test fun scenario40P90ResidualMeetsStage12Target() = assertTrue(refineFixture().percentile90Residual <= 0.60f)
    @Test fun scenario41CentroidRetentionMeetsGate() = assertTrue(refineFixture().verification.refined.retention >= 0.90f)
    @Test fun scenario42CentroidContrastMeetsGate() = assertTrue(refineFixture().verification.refined.contrastRatio >= 0.85f)
    @Test fun scenario43CentroidSmearMeetsGate() = assertTrue(refineFixture().verification.refined.smear <= 0.10f)
    @Test fun scenario44ProductionUsesCentroidBeforeWeights() = assertProductionOrder()
    @Test fun scenario45ProductionUsesOriginalResolutionCache() {
        val source = jpegStackerSource()
        val helper = source.substring(source.indexOf("private suspend fun prepareAndRefineFullResolutionFrames("),
            source.indexOf("private fun logFullResolutionRefinement("))
        assertTrue(helper.indexOf("decodeMedianFrame(accepted.frame, targetWidth, targetHeight)") <
            helper.indexOf("centroidRefiner.refine("))
        assertTrue(helper.contains("FileBackedArgbPixelSource(referenceCached, 32)"))
    }
    @Test fun scenario46ProductionIntegratesOnlyCentroidTransform() {
        val source = jpegStackerSource()
        assertTrue(source.contains("referenceToSourceTransform = centroidResult.refinedTransform"))
        assertFalse(source.contains("referenceToSourceTransform = result.refinedTransform"))
    }
    @Test fun scenario47ReportsExposeStage12Fields() {
        val report = Files.readString(Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt"))
        listOf(
            "stellarCentroidRefinementEnabled", "stellarCentroidSchemaVersion", "centroidPrimaryUsedPerFrame",
            "znccSecondaryUsedPerFrame", "znccFallbackUsedPerFrame", "referenceCentroidCount",
            "referenceCentroidAcceptedCount", "referenceCentroidRejectedCount", "centroidAttemptedStarCountPerFrame",
            "centroidAcceptedStarCountPerFrame", "centroidRejectedStarCountPerFrame",
            "centroidSpatialSectorCountPerFrame", "centroidInitialDxPerFrame", "centroidInitialDyPerFrame",
            "centroidRefinedDxPerFrame", "centroidRefinedDyPerFrame", "centroidCorrectionDxPerFrame",
            "centroidCorrectionDyPerFrame", "centroidMedianResidualPerFrame", "centroidP90ResidualPerFrame",
            "centroidMedianSnrPerFrame", "centroidMedianFitResidualPerFrame", "centroidConfidencePerFrame",
            "centroidRefinementAcceptedPerFrame", "centroidRefinementReasonPerFrame", "centroidRetentionPerFrame",
            "centroidContrastRatioPerFrame", "centroidWidthGrowthPerFrame", "centroidEllipticityGrowthPerFrame",
            "centroidSmearPerFrame"
        ).forEach { assertTrue(it, report.contains(it)) }
    }
    @Test fun scenario48LegacyGatesSamplingAndDimensionsRemainUnchanged() {
        val gate = Files.readString(Path.of(
            "src/main/java/com/example/astrophoto/processing/jpeg/v2/quality/ReferenceStarRetentionValidator.kt"
        ))
        assertTrue(gate.contains("MIN_RETENTION_RATIO = 0.90f"))
        assertTrue(gate.contains("MIN_MEDIAN_CONTRAST_RATIO = 0.85f"))
        assertTrue(gate.contains("MAX_LINE_LIKE_SMEAR_RATE = 0.10f"))
        assertTrue(jpegStackerSource().contains("samplingKernel = sampling.kernel"))
    }

    @Test fun scenario49PhoneSizedReferenceSetFitsPatchBudget() {
        val patches = (0 until 44).map { index ->
            FullResolutionStarPatch(
                x = 20f + index,
                y = 30f + index,
                confidence = 0.9f,
                localContrast = 1f,
                width = 2f,
                ellipticity = 0.1f,
                sector = index % 9,
                motionCluster = TemporalMotionCluster.COHERENT_MOVING_SKY,
                skyCoverage = 1f
            )
        }
        val selection = FullResolutionStarPatchSelector().select(patches)
        assertEquals(44, selection.selected.size)
        assertTrue(selection.rejected.none { it.reason == "patch_budget_exceeded" })
    }

    private fun assertCentroid(x: Float, y: Float, predictionX: Float = x, predictionY: Float = y) {
        val detection = detector().detect(render(listOf(Star(x, y))), predictionX, predictionY, 4f)
        assertTrue(detection.toString(), detection.accepted)
        assertEquals(x, detection.measurement!!.x, 0.18f)
        assertEquals(y, detection.measurement.y, 0.18f)
    }

    private fun assertBackground(gradientX: Float, gradientY: Float) {
        val source = render(listOf(Star(72f, 61f)), gradientX = gradientX, gradientY = gradientY)
        val model = checkNotNull(LocalBackgroundEstimator().estimate(source, 72f, 61f))
        assertEquals(BACKGROUND + gradientX * 72f + gradientY * 61f, model.valueAt(72f, 61f), 0.012f)
        assertEquals(gradientX, model.slopeX, 0.0005f)
        assertEquals(gradientY, model.slopeY, 0.0005f)
    }

    private fun assertDisplacement(
        dx: Float,
        dy: Float,
        brightness: Float = 1f,
        gradientX: Float = 0f,
        quantize: Int = 1,
        noise: Float = 0f
    ) {
        val reference = detector().detect(render(listOf(Star(72.2f, 61.35f))), 72f, 61f, 3f).measurement!!
        val candidate = detector().detect(
            render(listOf(Star(72.2f + dx, 61.35f + dy)), brightness, gradientX, 0f, quantize, noise),
            72.2f + dx,
            61.35f + dy,
            3f
        ).measurement!!
        assertEquals(dx, candidate.x - reference.x, 0.22f)
        assertEquals(dy, candidate.y - reference.y, 0.22f)
    }

    private fun assertRefinementCorrection(dx: Float, dy: Float) {
        val result = refine(frameFixture(dx, dy))
        assertTrue(result.toString(), result.accepted)
        assertEquals(dx, result.correctionDx, 0.20f)
        assertEquals(dy, result.correctionDy, 0.20f)
    }

    private fun refineFixture(candidateOffset: Map<Int, Pair<Float, Float>> = emptyMap()) =
        refine(frameFixture(0.5f, -0.25f, candidateOffset))

    private fun refine(fixture: FrameFixture) = StellarCentroidFrameRefiner().refine(
        "candidate", false, fixture.reference, fixture.candidate, fixture.initial, fixture.zncc,
        fixture.patches, 1f, 0.5f, 0.4f, 0.9f
    )

    private fun frameFixture(
        correctionX: Float,
        correctionY: Float,
        candidateOffset: Map<Int, Pair<Float, Float>> = emptyMap()
    ): FrameFixture {
        val initial = ReferenceToSourceTransform(-4f, 3f)
        val trueTransform = ReferenceToSourceTransform(initial.dx + correctionX, initial.dy + correctionY)
        val candidateStars = STARS.mapIndexed { index, star ->
            val offset = candidateOffset[index] ?: (0f to 0f)
            star.copy(x = star.x + trueTransform.dx + offset.first, y = star.y + trueTransform.dy + offset.second)
        }
        return FrameFixture(
            render(STARS),
            render(candidateStars),
            initial,
            ReferenceToSourceTransform(-2.8f, 1.8f),
            STARS.mapIndexed { index, star -> FullResolutionStarPatch(
                star.x, star.y, 1f, star.amplitude, star.sigma * 2.35482f, 0f,
                index % 4, TemporalMotionCluster.COHERENT_MOVING_SKY, 1f
            ) }
        )
    }

    private fun render(
        stars: List<Star>,
        brightness: Float = 1f,
        gradientX: Float = 0f,
        gradientY: Float = 0f,
        quantize: Int = 1,
        noise: Float = 0f
    ): IntArrayPixelSource {
        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            var value = BACKGROUND + gradientX * x + gradientY * y
            stars.forEach { star ->
                val dx = x - star.x
                val dy = y - star.y
                value += star.amplitude * brightness *
                    exp((-(dx * dx + dy * dy) / (2f * star.sigma * star.sigma)).toDouble()).toFloat()
            }
            if (noise > 0f) value += ((((x * 37 + y * 73) % 17) - 8) / 8f) * noise
            var channel = (value.coerceIn(0f, 1f) * 255f).toInt()
            if (quantize > 1) channel = (channel / quantize) * quantize
            pixels[y * WIDTH + x] = 0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }
        return IntArrayPixelSource(WIDTH, HEIGHT, pixels)
    }

    private fun detector() = FullResolutionStarCentroidDetector()

    private fun assertProductionOrder() {
        val source = jpegStackerSource()
        val profile = source.substring(source.indexOf("suspend fun profileStack("), source.indexOf("suspend fun loadResultPreview("))
        val stage12 = profile.indexOf("prepareAndRefineFullResolutionFrames(")
        val weights = profile.indexOf("FrameWeightCalculator().calculate(")
        val integration = profile.indexOf("LinearWeightedIntegrator().integrate(")
        assertTrue(stage12 in 0 until weights)
        assertTrue(weights in 0 until integration)
    }

    private fun jpegStackerSource() = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))

    private data class Star(
        val x: Float,
        val y: Float,
        val amplitude: Float = 0.55f,
        val sigma: Float = 1.35f
    )

    private data class FrameFixture(
        val reference: IntArrayPixelSource,
        val candidate: IntArrayPixelSource,
        val initial: ReferenceToSourceTransform,
        val zncc: ReferenceToSourceTransform,
        val patches: List<FullResolutionStarPatch>
    )

    companion object {
        private const val WIDTH = 180
        private const val HEIGHT = 140
        private const val BACKGROUND = 0.08f
        private val STARS = listOf(
            Star(32.2f, 30.4f), Star(75.6f, 27.3f), Star(132.4f, 34.7f),
            Star(41.7f, 91.2f), Star(92.3f, 104.6f), Star(145.5f, 93.8f)
        )
    }
}
