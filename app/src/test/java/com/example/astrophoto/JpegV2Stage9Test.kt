package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.example.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.registration.CaptureSequenceFrame
import com.example.astrophoto.processing.jpeg.v2.registration.CaptureSequenceIndexResolver
import com.example.astrophoto.processing.jpeg.v2.registration.RegistrationSequenceVerifier
import com.example.astrophoto.processing.jpeg.v2.registration.SparseTranslationVotingEstimator
import com.example.astrophoto.processing.jpeg.v2.registration.StellarSequenceMotionModel
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalTrackAnalysis
import com.example.astrophoto.processing.jpeg.v2.registration.TranslationHypothesis
import com.example.astrophoto.processing.jpeg.v2.registration.scaledToAnalysisResolution
import com.example.astrophoto.processing.jpeg.v2.registration.scaledToFullResolution
import com.example.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage9Test {
    @Test fun canonicalTransformUsesReferenceToCandidateSignedDisplacement() {
        val transform = ReferenceToSourceTransform(dx = -6f, dy = 3f)
        val source = transform.mapOutputToSource(100f, 100f)
        assertEquals(94f, source.x, 0f)
        assertEquals(103f, source.y, 0f)
    }

    @Test fun explicitInverseRoundTripIncludesRotationScaleAndCenter() {
        val transform = ReferenceToSourceTransform(
            dx = -2.25f,
            dy = 3.5f,
            rotationRadians = 0.07f,
            scale = 1.03f,
            rotationCenterX = 20f,
            rotationCenterY = 15f
        )
        val source = transform.mapOutputToSource(12.5f, 8.25f)
        val output = transform.inverse().mapSourceToOutput(source.x, source.y)
        assertEquals(12.5f, output.x, 0.0001f)
        assertEquals(8.25f, output.y, 0.0001f)
        assertEquals(transform, transform.inverse().inverse())
    }

    @Test fun productionSamplerHandlesEveryTranslationSignWithoutAxisSwap() {
        val sampler = TransformedBitmapSampler()
        listOf(2f to 0f, -2f to 0f, 0f to 2f, 0f to -2f, -2f to 2f, 2f to -1f)
            .forEach { (dx, dy) ->
                val outputX = 4
                val outputY = 4
                val sourceX = (outputX + dx).toInt()
                val sourceY = (outputY + dy).toInt()
                val pixels = IntArray(9 * 9) { BLACK }
                pixels[sourceY * 9 + sourceX] = WHITE
                val sampled = sampler.sample(
                    IntArrayPixelSource(9, 9, pixels),
                    registration(dx, dy),
                    outputX.toFloat(),
                    outputY.toFloat()
                )
                assertEquals("dx=$dx dy=$dy", 1f, checkNotNull(sampled).red, 0f)
            }
    }

    @Test fun productionSamplerSupportsIdentitySubpixelAndEdges() {
        val source = IntArrayPixelSource(
            3,
            2,
            intArrayOf(gray(0), gray(100), gray(200), gray(0), gray(100), gray(200))
        )
        val sampler = TransformedBitmapSampler()
        assertEquals(100f / 255f, checkNotNull(sampler.sample(source, registration(0f, 0f), 1f, 0f)).red, 0.0001f)
        assertEquals(50f / 255f, checkNotNull(sampler.sample(source, registration(0.5f, 0f), 0f, 0f)).red, 0.0001f)
        assertEquals(200f / 255f, checkNotNull(sampler.sample(source, registration(0f, 0f), 2f, 1f)).red, 0.0001f)
        assertNull(sampler.sample(source, registration(-0.01f, 0f), 0f, 0f))
    }

    @Test fun voterLabelsCandidateMinusReferenceAsCanonicalHypothesis() {
        val transform = SparseTranslationVotingEstimator().canonicalHypothesis(
            star(100f, 100f),
            star(94f, 103f)
        )
        assertEquals(-6f, transform.dx, 0f)
        assertEquals(3f, transform.dy, 0f)
    }

    @Test fun middleReferenceUsesActualCaptureNumbersAndExactIdentity() {
        val model = model(referenceCapture = 9, velocityX = -2.6f, velocityY = 2.7f)
        assertEquals(ReferenceToSourceTransform.Identity, model.predictedTransform(9))
        val early = model.predictedTransform(1)
        val late = model.predictedTransform(30)
        assertTrue(early.dx > 0f && early.dy < 0f)
        assertTrue(late.dx < 0f && late.dy > 0f)
    }

    @Test fun captureResolverIgnoresReferenceFirstReorderingAndPreservesGaps() {
        val indices = CaptureSequenceIndexResolver.resolve(
            listOf(
                CaptureSequenceFrame("reference", "AstroSeries_009.jpg", 200L),
                CaptureSequenceFrame("later", "AstroSeries_030.jpeg", 300L),
                CaptureSequenceFrame("earlier", "AstroSeries_001.jpg", 100L)
            )
        )
        assertEquals(9, indices["reference"])
        assertEquals(30, indices["later"])
        assertEquals(1, indices["earlier"])
    }

    @Test fun captureResolverHasDeterministicChronologicalFallback() {
        val indices = CaptureSequenceIndexResolver.resolve(
            listOf(
                CaptureSequenceFrame("b", "second.jpg", 20L),
                CaptureSequenceFrame("a", "first.jpg", 10L)
            )
        )
        assertEquals(1, indices["a"])
        assertEquals(2, indices["b"])
    }

    @Test fun analysisFullScalingIsIndependentSignedAndRoundTrips() {
        val analysis = registration(-4.25f, 5f)
        val full = analysis.scaledToFullResolution(scaleX = 2f, scaleY = 3f)
        assertEquals(-8.5f, full.dx, 0f)
        assertEquals(15f, full.dy, 0f)
        val roundTrip = full.scaledToAnalysisResolution(scaleX = 2f, scaleY = 3f)
        assertEquals(analysis.dx, roundTrip.dx, 0.0001f)
        assertEquals(analysis.dy, roundTrip.dy, 0.0001f)
        assertEquals(
            ReferenceToSourceTransform.Identity,
            ReferenceToSourceTransform.Identity.scaledToFullResolution(2f, 3f)
        )
    }

    @Test fun verifierCanonicalMappingBeatsIdentityInverseAndDoubleApplication() {
        val fixture = verificationFixture()
        val verification = RegistrationSequenceVerifier().verify(
            fixture.reference,
            fixture.frames,
            fixture.selected,
            fixture.frames.associate { it.frameId to hypothesis(0f, 0f) },
            observableTracks()
        )
        assertTrue(verification.selectedModel.referenceRetention > 0.90f)
        assertTrue(verification.selectedModel.score > verification.identity.score)
        assertTrue(verification.selectedModel.score > verification.inverseModel.score)
        assertTrue(verification.selectedModel.score > verification.doubleAppliedModel.score)
    }

    @Test fun perFrameVerificationRejectsOnlyOutlierAndKeepsReferenceIdentity() {
        val fixture = verificationFixture(includeBadFrame = true)
        val verification = RegistrationSequenceVerifier().verify(
            fixture.reference,
            fixture.frames,
            fixture.selected,
            fixture.frames.associate { it.frameId to hypothesis(0f, 0f) },
            observableTracks()
        )
        assertTrue(verification.perFrameAccepted.getValue("reference"))
        assertTrue(verification.perFrameAccepted.getValue("good"))
        assertFalse(verification.perFrameAccepted.getValue("bad"))
        assertTrue(verification.selectedModel.referenceRetention > 0.90f)
    }

    @Test fun verifierAndProductionSamplerAgreeOnAlignedCoordinate() {
        val referenceStars = gridStars()
        val candidateStars = referenceStars.map { it.copy(x = it.x - 2f, y = it.y + 2f) }
        val metrics = RegistrationSequenceVerifier().measureCanonical(
            TemporalFeatureFrame("reference", 9, referenceStars),
            listOf(TemporalFeatureFrame("candidate", 10, candidateStars)),
            mapOf("candidate" to ReferenceToSourceTransform(-2f, 2f)),
            observableTracks(),
            requiredMatchingFrames = 1
        )
        val pixels = IntArray(9 * 9) { BLACK }.also { it[5 * 9 + 1] = WHITE }
        val sample = TransformedBitmapSampler().sample(
            IntArrayPixelSource(9, 9, pixels),
            registration(-2f, 2f),
            3f,
            3f
        )
        assertEquals(1f, metrics.referenceRetention, 0f)
        assertEquals(1f, checkNotNull(sample).red, 0f)
    }

    @Test fun productionIntegratorUsesTheSameReferenceToSourceDirection() = runBlocking {
        val pixels = IntArray(7 * 7) { BLACK }.also { it[5 * 7 + 1] = WHITE }
        val output = IntArray(7 * 7)
        LinearWeightedIntegrator().integrate(
            outputWidth = 7,
            outputHeight = 7,
            frames = listOf(WeightedIntegrationFrame("candidate", pixels, registration(-2f, 2f), 1f)),
            maximumWorkingMemoryBytes = 1024L * 1024L,
            openSource = { IntArrayPixelSource(7, 7, it) },
            allowRobustClipping = false,
            writeTile = { tile, tilePixels ->
                for (y in 0 until tile.height) for (x in 0 until tile.width) {
                    output[(tile.top + y) * 7 + tile.left + x] = tilePixels[y * tile.width + x]
                }
            }
        )
        assertEquals(WHITE, output[3 * 7 + 3])
        assertEquals(BLACK, output[5 * 7 + 1])
    }

    @Test fun failedRegistrationCannotMasqueradeAsReferenceIdentity() {
        val failed = RegistrationResult.rejected(8, 8, "bad transform")
        assertFalse(failed.isReliable)
        assertFalse(failed.registrationModel == "REFERENCE_IDENTITY")
    }

    @Test fun qualityGateThresholdsRemainUnchanged() {
        assertEquals(0.90f, ReferenceStarRetentionValidator.MIN_RETENTION_RATIO, 0f)
        assertEquals(0.85f, ReferenceStarRetentionValidator.MIN_MEDIAN_CONTRAST_RATIO, 0f)
        assertEquals(0.10f, ReferenceStarRetentionValidator.MAX_LINE_LIKE_SMEAR_RATE, 0f)
    }

    @Test fun productionProfileAndReportUseStage9Components() {
        val profile = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))
        assertTrue(profile.contains("profileCaptureIndices(cappedFrames)"))
        assertFalse(profile.contains("cappedFrames.indexOfFirst"))
        assertTrue(profile.contains("scaledToFullResolution(scaleX, scaleY)"))
        assertTrue(profile.contains("LinearWeightedIntegrator().integrate("))
        assertTrue(profile.contains("FileBackedArgbPixelSource(cached)"))
        val report = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt")
        )
        listOf(
            "transformContractVersion",
            "transformDirection",
            "referenceCaptureIndex",
            "analysisToFullScaleX",
            "canonicalTransformVerificationScore",
            "inverseTransformVerificationScore",
            "doubleTransformVerificationScore",
            "perFramePredictedDx",
            "perFrameVerificationSmearRate"
        ).forEach { assertTrue(it, report.contains(it)) }
    }

    private fun verificationFixture(includeBadFrame: Boolean = false): VerificationFixture {
        val referenceStars = gridStars()
        val reference = TemporalFeatureFrame("reference", 9, referenceStars)
        val good = TemporalFeatureFrame(
            "good",
            10,
            referenceStars.map { it.copy(x = it.x - 2f, y = it.y + 2f) }
        )
        val frames = mutableListOf(reference, good)
        val selected = linkedMapOf(
            "reference" to hypothesis(0f, 0f),
            "good" to hypothesis(-2f, 2f)
        )
        if (includeBadFrame) {
            frames += TemporalFeatureFrame(
                "bad",
                11,
                referenceStars.map { it.copy(x = it.x + 8f, y = it.y - 7f) }
            )
            selected["bad"] = hypothesis(-4f, 4f)
        }
        return VerificationFixture(reference, frames, selected)
    }

    private fun gridStars(): List<DetectedStar> = listOf(
        star(12f, 12f), star(26f, 12f), star(40f, 12f),
        star(12f, 28f), star(26f, 28f), star(40f, 28f)
    )

    private fun model(referenceCapture: Int, velocityX: Float, velocityY: Float) =
        StellarSequenceMotionModel(
            velocityX = velocityX,
            velocityY = velocityY,
            referenceIndex = referenceCapture,
            acceptedFrameHypotheses = emptyMap(),
            score = 1f,
            residual = 0f,
            motionObservable = true,
            rejectedFrameIds = emptyList(),
            competingZeroModelScore = 0f,
            nonZeroModelScore = 1f,
            selectedMotionModel = "NON_ZERO_STELLAR"
        )

    private fun observableTracks() = TemporalTrackAnalysis(emptyList(), true, -2f, 2f)

    private fun hypothesis(dx: Float, dy: Float) = TranslationHypothesis(
        dx = dx,
        dy = dy,
        support = 6,
        weightedSupport = 6f,
        residual = 0f,
        occupiedSectors = 6,
        movingTrackSupport = 6,
        stationaryTrackSupport = 0
    )

    private fun registration(dx: Float, dy: Float) = RegistrationResult(
        dx = dx,
        dy = dy,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = 6,
        matchedStars = 6,
        inlierStars = 6,
        residualError = 0f,
        confidence = 1f,
        isReliable = true,
        rejectionReason = null,
        registrationModel = "SEQUENCE_TRANSLATION",
        scaleFixed = true
    )

    private fun star(x: Float, y: Float) = DetectedStar(
        x = x,
        y = y,
        flux = 100f,
        localBackground = 0.05f,
        localContrast = 1f,
        width = 1f,
        ellipticity = 0.05f,
        confidence = 1f
    )

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    private data class VerificationFixture(
        val reference: TemporalFeatureFrame,
        val frames: List<TemporalFeatureFrame>,
        val selected: Map<String, TranslationHypothesis>
    )

    companion object {
        private const val WHITE = -1
        private const val BLACK = 0xFF000000.toInt()
    }
}
