package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.example.astrophoto.processing.jpeg.v2.registration.FrameAcceptanceDecision
import com.example.astrophoto.processing.jpeg.v2.registration.FrameAcceptanceEvidence
import com.example.astrophoto.processing.jpeg.v2.registration.ExpectedSequenceMotionModel
import com.example.astrophoto.processing.jpeg.v2.registration.ModelGuidedLocalRegistrar
import com.example.astrophoto.processing.jpeg.v2.registration.ModelGuidedRegistrationResult
import com.example.astrophoto.processing.jpeg.v2.registration.OrderedRegistration
import com.example.astrophoto.processing.jpeg.v2.registration.RegistrationVerificationMetrics
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceSupportedFrameAcceptancePolicy
import com.example.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureTrack
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalTrackAnalysis
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalTrackObservation
import com.example.astrophoto.processing.jpeg.v2.registration.TranslationHypothesis
import com.example.astrophoto.processing.jpeg.v2.registration.TransformSequenceValidator
import com.example.astrophoto.processing.jpeg.v2.registration.VerificationMetricsAggregator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegV2Stage10Test {
    private val registrar = ModelGuidedLocalRegistrar()
    private val policy = SequenceSupportedFrameAcceptancePolicy()

    @Test fun modelGuidedSearchCentersOnPredictedTransformAndRecoversCorrection() {
        val fixture = localFixture(correctionX = 0.35f, correctionY = -0.25f)
        val result = register(fixture)
        assertEquals(PREDICTED_DX, result.predictedDx, 0f)
        assertEquals(PREDICTED_DY, result.predictedDy, 0f)
        assertEquals(0.35f, result.correctionDx, 0.08f)
        assertEquals(-0.25f, result.correctionDy, 0.08f)
        assertEquals(PREDICTED_DX + 0.35f, result.transform.dx, 0.08f)
        assertEquals(PREDICTED_DY - 0.25f, result.transform.dy, 0.08f)
        assertEquals(null, result.rejectionReason)
    }

    @Test fun searchRadiusGrowsWithResidualAndStaysBounded() {
        val precise = registrar.searchRadius(modelResidual = 0.05f, modelConfidence = 0.9f, retry = false)
        val uncertain = registrar.searchRadius(modelResidual = 1.5f, modelConfidence = 0.55f, retry = false)
        val retry = registrar.searchRadius(modelResidual = 20f, modelConfidence = 0f, retry = true)
        assertTrue(uncertain > precise)
        assertTrue(precise >= 1.25f)
        assertTrue(uncertain <= 4.5f)
        assertEquals(6f, retry, 0f)
    }

    @Test fun localMatchingIsOneToOneNearPrediction() {
        val fixture = localFixture().let { value ->
            value.copy(candidate = value.candidate.copy(stars = value.candidate.stars + value.candidate.stars.first()))
        }
        val result = register(fixture)
        assertTrue(result.matchedStars <= fixture.reference.stars.size)
        assertTrue(result.inlierStars >= 4)
        assertEquals(null, result.rejectionReason)
    }

    @Test fun movingTracksArePreferredAndStationaryEvidenceIsPenalized() {
        val movingFixture = localFixture(trackCluster = TemporalMotionCluster.COHERENT_MOVING_SKY)
        val stationaryFixture = localFixture(trackCluster = TemporalMotionCluster.STATIONARY_CAMERA_SPACE)
        val moving = register(movingFixture)
        val stationary = register(stationaryFixture)
        assertTrue(moving.movingTrackSupport >= 4)
        assertEquals(0, moving.stationaryTrackSupport)
        assertTrue(stationary.stationaryTrackSupport >= 4)
        assertTrue(moving.confidence > stationary.confidence)
    }

    @Test fun robustRefinementRejectsOneLocalOutlier() {
        val fixture = localFixture().let { value ->
            val corrupted = value.candidate.stars.mapIndexed { index, star ->
                if (index == 0) star.copy(x = star.x + 1.8f, y = star.y - 1.6f) else star
            }
            value.copy(candidate = value.candidate.copy(stars = corrupted))
        }
        val result = register(fixture)
        assertEquals(0f, result.correctionDx, 0.12f)
        assertEquals(0f, result.correctionDy, 0.12f)
        assertTrue(result.inlierStars >= 5)
    }

    @Test fun localRefinementCannotJumpToZeroMode() {
        val reference = TemporalFeatureFrame("reference", 9, gridStars())
        val candidate = TemporalFeatureFrame("candidate", 14, gridStars())
        val predicted = ReferenceToSourceTransform(5f, 0f)
        val result = registrar.register(
            reference,
            candidate,
            predicted,
            WIDTH,
            HEIGHT,
            modelResidual = 2f,
            modelConfidence = 0.8f,
            tracks = TemporalTrackAnalysis(emptyList(), true, 1f, 0f),
            sparseSeed = null,
            retry = true
        )
        assertTrue(result.rejectionReason != null)
        assertEquals(predicted, result.transform)
        assertNotEquals(ReferenceToSourceTransform.Identity, result.transform)
    }

    @Test fun localSearchCannotJumpToDistantPeak() {
        val fixture = localFixture().let { value ->
            value.copy(candidate = value.candidate.copy(stars = value.reference.stars.map {
                it.copy(x = it.x + 15f, y = it.y - 12f)
            }))
        }
        val result = register(fixture)
        assertTrue(result.rejectionReason != null)
        assertEquals(ReferenceToSourceTransform(PREDICTED_DX, PREDICTED_DY), result.transform)
    }

    @Test fun sequenceSupportedPathAcceptsLowLegacyInliersWithStrongEvidence() {
        val decision = policy.evaluate(
            evidence(
                local = localResult(matched = 3, inliers = 3),
                legacy = registration(reliable = false, matched = 3, inliers = 3),
                verification = metrics(retention = 1f, contrast = 0.95f, smear = 0f),
                sparse = false
            )
        )
        assertTrue(decision is FrameAcceptanceDecision.AcceptedSequenceSupported)
    }

    @Test fun frame012And013LikeEvidencePassesDocumentedFramePolicy() {
        listOf(0.837f, 1.064f).forEach { contrast ->
            val decision = policy.evaluate(
                evidence(
                    local = localResult(matched = 3, inliers = 3),
                    legacy = registration(reliable = false, matched = 3, inliers = 3),
                    verification = metrics(retention = 1f, contrast = contrast, smear = 0f),
                    sparse = false
                )
            )
            assertTrue("contrast=$contrast decision=$decision", decision is FrameAcceptanceDecision.AcceptedSequenceSupported)
        }
    }

    @Test fun modelSupportedFramesReachFullResolutionDespiteWeakThumbnailVerification() {
        val lowRetention = policy.evaluate(
            evidence(verification = metrics(retention = 0.70f, contrast = 1f, smear = 0f))
        )
        val highSmear = policy.evaluate(
            evidence(verification = metrics(retention = 1f, contrast = 1f, smear = 0.20f))
        )
        val poorAgreement = policy.evaluate(evidence(agreement = 0.30f))
        val noVerifiedStars = policy.evaluate(
            evidence(verification = metrics(retention = 0f, contrast = 0f, smear = 1f, stars = 0))
        )
        assertTrue(lowRetention is FrameAcceptanceDecision.ProvisionalFullResolution)
        assertTrue(highSmear is FrameAcceptanceDecision.ProvisionalFullResolution)
        assertTrue(poorAgreement is FrameAcceptanceDecision.Rejected)
        assertTrue(noVerifiedStars is FrameAcceptanceDecision.Rejected)
    }

    @Test fun traditionalStrongMatchPathAndLegacyThresholdsRemainUnchanged() {
        val decision = policy.evaluate(
            evidence(
                legacy = registration(reliable = true, matched = 6, inliers = 6),
                sparse = true,
                verification = metrics(retention = 0.75f, contrast = 0.70f, smear = 0.05f)
            )
        )
        assertTrue(decision is FrameAcceptanceDecision.AcceptedStrongMatch)
        assertEquals(4, StarSimilarityRegistrar.MIN_MATCHED_STARS)
        assertEquals(4, StarSimilarityRegistrar.MIN_INLIER_STARS)
        assertEquals(0.32f, StarSimilarityRegistrar.MIN_INLIER_COVERAGE, 0f)
    }

    @Test fun retryIsSingleBoundedAttemptAndInvalidAnalysisIsNotMarkedRetried() {
        val validRetry = register(localFixture(), sparseSeed = null, retry = true)
        val invalid = registrar.register(
            localFixture().reference,
            TemporalFeatureFrame("invalid", 12, emptyList()),
            ReferenceToSourceTransform(PREDICTED_DX, PREDICTED_DY),
            WIDTH,
            HEIGHT,
            modelResidual = 0.2f,
            modelConfidence = 0.8f,
            tracks = TemporalTrackAnalysis(emptyList(), true, -1f, 1f),
            sparseSeed = null,
            retry = false
        )
        assertTrue(validRetry.retryUsed)
        assertTrue(validRetry.searchRadius <= 6f)
        assertFalse(invalid.retryUsed)
        assertTrue(invalid.rejectionReason != null)
    }

    @Test fun aggregationIgnoresMissingSamplesAndReferenceIdentity() {
        val aggregation = VerificationMetricsAggregator.aggregate(
            perFrame = mapOf(
                "reference" to metrics(smear = 1f),
                "accepted" to metrics(retention = 0.9f, contrast = 0.8f, smear = 0.05f)
            ),
            acceptedFrameIds = setOf("reference", "accepted", "missingAccepted"),
            rejectedFrameIds = setOf("missingRejected"),
            referenceFrameId = "reference"
        )
        assertEquals(1, aggregation.sampleCount)
        assertEquals(1, aggregation.acceptedSampleCount)
        assertEquals(0, aggregation.rejectedSampleCount)
        assertEquals(0.05f, checkNotNull(aggregation.acceptedMean).smearRate, 0f)
    }

    @Test fun oneAndTwoSampleAggregationUsesActualRates() {
        val one = aggregateAccepted(listOf(metrics(retention = 0.8f, contrast = 0.9f, smear = 0.02f)))
        assertEquals(0.02f, checkNotNull(one.acceptedMean).smearRate, 0f)
        val two = aggregateAccepted(
            listOf(
                metrics(retention = 0.8f, contrast = 0.7f, smear = 0.02f),
                metrics(retention = 1f, contrast = 1.1f, smear = 0.08f)
            )
        )
        assertEquals(0.9f, checkNotNull(two.acceptedMean).referenceRetention, 0.0001f)
        assertEquals(0.9f, checkNotNull(two.acceptedMean).contrastRatio, 0.0001f)
        assertEquals(0.05f, checkNotNull(two.acceptedMean).smearRate, 0.0001f)
    }

    @Test fun manySampleAggregationSeparatesAcceptedAndRejected() {
        val perFrame = (1..10).associate { index ->
            "f$index" to metrics(smear = index / 100f)
        }
        val aggregation = VerificationMetricsAggregator.aggregate(
            perFrame,
            acceptedFrameIds = (1..7).map { "f$it" }.toSet(),
            rejectedFrameIds = (8..10).map { "f$it" }.toSet(),
            referenceFrameId = "reference"
        )
        assertEquals(10, aggregation.sampleCount)
        assertEquals(7, aggregation.acceptedSampleCount)
        assertEquals(3, aggregation.rejectedSampleCount)
        assertEquals(0.04f, checkNotNull(aggregation.acceptedMean).smearRate, 0.0001f)
    }

    @Test fun engineUsesSequenceSupportedPathAndKeepsInvalidFrameOutOfAcceptedSet() {
        val frames = mixedMotionSequence(includeInvalid = true)
        val result = SequenceAwareRegistrationEngine().register(
            frames,
            referenceFrameId = "f3",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        assertTrue(result.model.motionObservable)
        assertEquals(0f, result.registrations.getValue("f3").dx, 0f)
        assertTrue(result.registrations.getValue("f3").isReliable)
        assertFalse(result.registrations.getValue("invalid").isReliable)
        assertFalse(result.modelGuidedRegistrations.getValue("invalid").retryUsed)
        assertTrue(result.registrations.count { it.value.isReliable } > 2)
        assertTrue(result.toString(), result.frameAcceptancePaths.values.any {
            it == FrameAcceptanceDecision.PATH_SEQUENCE_SUPPORTED
        })
    }

    @Test fun downstreamSequenceValidatorPreservesPolicyBackedLowInlierFrames() {
        val frames = mixedMotionSequence()
        val result = SequenceAwareRegistrationEngine().register(
            frames,
            referenceFrameId = "f3",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        val sequenceSupportedIds = result.frameAcceptancePaths.filterValues {
            it == FrameAcceptanceDecision.PATH_SEQUENCE_SUPPORTED
        }.keys
        assertTrue(sequenceSupportedIds.isNotEmpty())

        val captureIndexes = frames.associate { it.frameId to it.captureIndex }
        val validation = TransformSequenceValidator().validate(
            values = result.registrations.mapNotNull { (frameId, registration) ->
                if (!registration.isReliable) return@mapNotNull null
                OrderedRegistration(
                    frameId = frameId,
                    captureIndex = captureIndexes.getValue(frameId),
                    isReference = frameId == "f3",
                    registration = registration
                )
            },
            expectedMotionModel = ExpectedSequenceMotionModel(
                velocityX = result.model.velocityX,
                velocityY = result.model.velocityY,
                referenceIndex = result.model.referenceIndex,
                residual = result.model.residual,
                motionObservable = result.model.motionObservable,
                verificationScore = result.verification.selectedModel.score
            )
        )

        assertTrue(validation.registrations.count { it.registration.isReliable } > 2)
        assertTrue(sequenceSupportedIds.all { frameId ->
            validation.registrations.single { it.frameId == frameId }.registration.isReliable
        })
    }

    @Test fun sparseFailureGetsAtMostOneModelGuidedRetryAndNeverIdentityFallback() {
        val frames = mixedMotionSequence(includeSparseFailure = true)
        val result = SequenceAwareRegistrationEngine().register(
            frames,
            referenceFrameId = "f3",
            imageWidth = WIDTH,
            imageHeight = HEIGHT
        )
        val local = result.modelGuidedRegistrations.getValue("sparseFailure")
        val registration = result.registrations.getValue("sparseFailure")
        assertTrue(local.retryUsed)
        assertFalse(registration.isReliable)
        assertNotEquals("REFERENCE_IDENTITY", registration.registrationModel)
    }

    @Test fun productionCachesAndIntegratesOnlyFinalReliableRegistrations() {
        val source = Files.readString(Path.of("src/main/java/com/example/astrophoto/JpegStacker.kt"))
        val profile = source.substring(
            source.indexOf("suspend fun profileStack("),
            source.indexOf("suspend fun loadResultPreview(")
        )
        assertTrue(profile.contains("if (!registration.isReliable) return@mapNotNull null"))
        assertTrue(profile.contains("acceptedProfileFrames.removeAll { !it.registration.isReliable }"))
        assertTrue(profile.contains("val cachedFrames = fullResolutionPreparation.cachedFrames"))
        assertTrue(profile.contains("acceptedProfileFrames += fullResolutionPreparation.acceptedFrames"))
        assertTrue(profile.contains("frames = cachedFrames.map"))
        assertTrue(profile.contains("modelGuidedRegistrations"))
        assertFalse(profile.contains("ManualStackingSource") && profile.contains("registerAutomaticWithPrior("))
    }

    @Test fun finalQualityGateAndStage9ContractRemainUnchanged() {
        assertEquals(0.90f, ReferenceStarRetentionValidator.MIN_RETENTION_RATIO, 0f)
        assertEquals(0.85f, ReferenceStarRetentionValidator.MIN_MEDIAN_CONTRAST_RATIO, 0f)
        assertEquals(0.10f, ReferenceStarRetentionValidator.MAX_LINE_LIKE_SMEAR_RATE, 0f)
        val transform = ReferenceToSourceTransform(-6f, 3f)
        val mapped = transform.mapOutputToSource(100f, 100f)
        assertEquals(94f, mapped.x, 0f)
        assertEquals(103f, mapped.y, 0f)
    }

    @Test fun reportsExposeModelGuidedAndCorrectAggregationFields() {
        val source = Files.readString(
            Path.of("src/main/java/com/example/astrophoto/processing/jpeg/v2/diagnostics/ProcessingReport.kt")
        )
        listOf(
            "modelGuidedRegistrationEnabled",
            "modelGuidedSearchRadiusPerFrame",
            "modelGuidedCorrectionDxPerFrame",
            "modelGuidedRetryUsedPerFrame",
            "frameAcceptancePathPerFrame",
            "frameVerificationSampleCountPerFrame",
            "verificationSampleCount",
            "acceptedVerificationSampleCount",
            "rejectedVerificationSampleCount",
            "acceptedVerificationMeanRetention",
            "acceptedVerificationMeanContrastRatio",
            "acceptedVerificationMeanSmearRate"
        ).forEach { assertTrue(it, source.contains(it)) }
    }

    private fun register(
        fixture: LocalFixture,
        sparseSeed: TranslationHypothesis? = hypothesis(PREDICTED_DX, PREDICTED_DY),
        retry: Boolean = false
    ): ModelGuidedRegistrationResult = registrar.register(
        reference = fixture.reference,
        candidate = fixture.candidate,
        predicted = ReferenceToSourceTransform(PREDICTED_DX, PREDICTED_DY),
        imageWidth = WIDTH,
        imageHeight = HEIGHT,
        modelResidual = 0.2f,
        modelConfidence = 0.8f,
        tracks = fixture.tracks,
        sparseSeed = sparseSeed,
        retry = retry
    )

    private fun localFixture(
        correctionX: Float = 0f,
        correctionY: Float = 0f,
        trackCluster: TemporalMotionCluster? = null
    ): LocalFixture {
        val referenceStars = gridStars()
        val candidateStars = referenceStars.map {
            it.copy(x = it.x + PREDICTED_DX + correctionX, y = it.y + PREDICTED_DY + correctionY)
        }
        val reference = TemporalFeatureFrame("reference", 9, referenceStars)
        val candidate = TemporalFeatureFrame("candidate", 12, candidateStars)
        val tracks = if (trackCluster == null) {
            TemporalTrackAnalysis(emptyList(), true, -1f, 1f)
        } else {
            tracked(reference, candidate, trackCluster)
        }
        return LocalFixture(reference, candidate, tracks)
    }

    private fun tracked(
        reference: TemporalFeatureFrame,
        candidate: TemporalFeatureFrame,
        cluster: TemporalMotionCluster
    ): TemporalTrackAnalysis = TemporalTrackAnalysis(
        tracks = reference.stars.indices.map { index ->
            TemporalFeatureTrack(
                observations = listOf(
                    TemporalTrackObservation(reference.frameId, reference.captureIndex, reference.stars[index]),
                    TemporalTrackObservation(candidate.frameId, candidate.captureIndex, candidate.stars[index])
                ),
                presenceRatio = 1f,
                velocityX = (candidate.stars[index].x - reference.stars[index].x) / 3f,
                velocityY = (candidate.stars[index].y - reference.stars[index].y) / 3f,
                fitResidual = 0f,
                cluster = cluster
            )
        },
        motionObservable = cluster == TemporalMotionCluster.COHERENT_MOVING_SKY,
        coherentVelocityX = -1f,
        coherentVelocityY = 1f
    )

    private fun evidence(
        local: ModelGuidedRegistrationResult = localResult(),
        legacy: RegistrationResult = registration(reliable = false, matched = 3, inliers = 3),
        verification: RegistrationVerificationMetrics = metrics(),
        sparse: Boolean = false,
        agreement: Float = 0.9f
    ) = FrameAcceptanceEvidence(
        motionObservable = true,
        sequenceModelScore = 0.75f,
        sequenceAgreement = agreement,
        usableSparseHypothesis = sparse,
        local = local,
        legacyRegistration = legacy,
        verification = verification
    )

    private fun localResult(matched: Int = 4, inliers: Int = 4) = ModelGuidedRegistrationResult(
        transform = ReferenceToSourceTransform(PREDICTED_DX, PREDICTED_DY),
        predictedDx = PREDICTED_DX,
        predictedDy = PREDICTED_DY,
        correctionDx = 0f,
        correctionDy = 0f,
        matchedStars = matched,
        inlierStars = inliers,
        residual = 0.2f,
        confidence = 0.8f,
        searchRadius = 2f,
        occupiedSectors = 3,
        movingTrackSupport = inliers,
        stationaryTrackSupport = 0,
        usedSparseSeed = false,
        usedSequencePriorOnly = true,
        retryUsed = true,
        rejectionReason = null
    )

    private fun metrics(
        retention: Float = 1f,
        contrast: Float = 0.95f,
        smear: Float = 0f,
        stars: Int = 6,
        score: Float = 0.9f
    ) = RegistrationVerificationMetrics(
        referenceRetention = retention,
        contrastRatio = contrast,
        widthGrowth = 0.05f,
        smearRate = smear,
        reliableStarCount = stars,
        backgroundNoise = 0.01f,
        stationaryArtifactStreakEvidence = 0f,
        score = score
    )

    private fun aggregateAccepted(values: List<RegistrationVerificationMetrics>) =
        VerificationMetricsAggregator.aggregate(
            perFrame = values.mapIndexed { index, metrics -> "f$index" to metrics }.toMap(),
            acceptedFrameIds = values.indices.map { "f$it" }.toSet(),
            rejectedFrameIds = emptySet(),
            referenceFrameId = "reference"
        )

    private fun mixedMotionSequence(
        includeInvalid: Boolean = false,
        includeSparseFailure: Boolean = false
    ): List<TemporalFeatureFrame> {
        val moving = listOf(star(18f, 16f), star(96f, 16f), star(18f, 72f), star(96f, 72f))
        val stationary = (0 until 16).map { index ->
            star(30f + (index % 4) * 14f, 25f + (index / 4) * 12f, contrast = 0.65f)
        }
        val frames = (0..7).map { capture ->
            val delta = capture - 3
            TemporalFeatureFrame(
                frameId = "f$capture",
                captureIndex = capture,
                stars = moving.mapIndexed { index, star ->
                    star.copy(
                        x = star.x + VELOCITY_X * delta +
                            if (capture == 7 && index == 0) 1f else 0f,
                        y = star.y + VELOCITY_Y * delta
                    )
                } + stationary
            )
        }.toMutableList()
        if (includeInvalid) frames += TemporalFeatureFrame("invalid", 8, emptyList())
        if (includeSparseFailure) {
            frames += TemporalFeatureFrame(
                "sparseFailure",
                9,
                moving.take(2).map {
                    it.copy(x = it.x + VELOCITY_X * 6, y = it.y + VELOCITY_Y * 6)
                }
            )
        }
        return frames
    }

    private fun registration(
        reliable: Boolean,
        matched: Int,
        inliers: Int
    ) = RegistrationResult(
        dx = PREDICTED_DX,
        dy = PREDICTED_DY,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = 12,
        matchedStars = matched,
        inlierStars = inliers,
        residualError = 0.2f,
        confidence = if (reliable) 0.9f else 0.4f,
        isReliable = reliable,
        rejectionReason = if (reliable) null else "Insufficient inlier stars",
        referenceStars = 12,
        registrationModel = "SEQUENCE_TRANSLATION",
        scaleFixed = true
    )

    private fun hypothesis(dx: Float, dy: Float) = TranslationHypothesis(
        dx,
        dy,
        support = 6,
        weightedSupport = 10f,
        residual = 0.1f,
        occupiedSectors = 4,
        movingTrackSupport = 4,
        stationaryTrackSupport = 0
    )

    private fun gridStars() = listOf(
        star(12f, 12f), star(55f, 12f), star(98f, 12f),
        star(12f, 70f), star(55f, 70f), star(98f, 70f)
    )

    private fun star(x: Float, y: Float, contrast: Float = 1f) = DetectedStar(
        x = x,
        y = y,
        flux = 100f,
        localBackground = 0.05f,
        localContrast = contrast,
        width = 1f,
        ellipticity = 0.05f,
        confidence = 1f
    )

    private data class LocalFixture(
        val reference: TemporalFeatureFrame,
        val candidate: TemporalFeatureFrame,
        val tracks: TemporalTrackAnalysis
    )

    companion object {
        private const val WIDTH = 120
        private const val HEIGHT = 90
        private const val PREDICTED_DX = -2f
        private const val PREDICTED_DY = 2f
        private const val VELOCITY_X = -1f
        private const val VELOCITY_Y = 1.1f
    }
}
