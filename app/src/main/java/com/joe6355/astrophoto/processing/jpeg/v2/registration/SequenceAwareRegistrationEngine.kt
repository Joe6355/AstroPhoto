package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.hypot

class SequenceAwareRegistrationEngine(
    private val trackBuilder: TemporalFeatureTrackBuilder = TemporalFeatureTrackBuilder(),
    private val votingEstimator: SparseTranslationVotingEstimator = SparseTranslationVotingEstimator(),
    private val modelEstimator: StellarMotionModelEstimator = StellarMotionModelEstimator(),
    private val verifier: RegistrationSequenceVerifier = RegistrationSequenceVerifier(),
    private val registrar: StarSimilarityRegistrar = StarSimilarityRegistrar(),
    private val localRegistrar: ModelGuidedLocalRegistrar = ModelGuidedLocalRegistrar(),
    private val acceptancePolicy: SequenceSupportedFrameAcceptancePolicy =
        SequenceSupportedFrameAcceptancePolicy()
) {
    fun register(
        frames: List<TemporalFeatureFrame>,
        referenceFrameId: String,
        imageWidth: Int,
        imageHeight: Int,
        cancellationCheck: () -> Unit = {},
        onProgress: () -> Unit = {}
    ): SequenceAwareRegistrationDiagnostics {
        val ordered = frames.sortedBy { it.captureIndex }
        val reference = checkNotNull(ordered.firstOrNull { it.frameId == referenceFrameId })
        val tracks = trackBuilder.build(ordered, cancellationCheck)
        onProgress()
        val candidates = ordered.map { frame ->
            cancellationCheck()
            val candidate = SequenceRegistrationCandidate(
                frameId = frame.frameId,
                captureIndex = frame.captureIndex,
                isReference = frame.frameId == referenceFrameId,
                hypotheses = if (frame.frameId == referenceFrameId) {
                    listOf(identityHypothesis(reference.stars.size))
                } else {
                    votingEstimator.estimate(
                        reference,
                        frame,
                        imageWidth,
                        imageHeight,
                        tracks
                    )
                }
            )
            onProgress()
            candidate
        }
        val fitted = modelEstimator.fit(candidates, reference.captureIndex, tracks)
        onProgress()
        val globalVerification = verifier.verify(
            reference = reference,
            frames = ordered,
            selected = fitted.acceptedFrameHypotheses,
            zero = fitted.zeroFrameHypotheses,
            tracks = tracks
        )
        onProgress()
        val registrations = linkedMapOf<String, RegistrationResult>()
        val ranks = linkedMapOf<String, Int>()
        val rejectedReasons = linkedMapOf<String, String>()
        val localRegistrations = linkedMapOf<String, ModelGuidedRegistrationResult>()
        val frameVerifications = linkedMapOf<String, FrameRegistrationVerification>()
        val acceptancePaths = linkedMapOf<String, String>()
        val acceptanceReasons = linkedMapOf<String, String>()
        ordered.forEach { frame ->
            cancellationCheck()
            if (frame.frameId == referenceFrameId) {
                registrations[frame.frameId] = referenceIdentity(reference.stars.size)
                ranks[frame.frameId] = 0
                acceptancePaths[frame.frameId] = FrameAcceptanceDecision.PATH_REFERENCE
                acceptanceReasons[frame.frameId] = "reference_identity"
                return@forEach
            }
            val sparseSeed = fitted.acceptedFrameHypotheses[frame.frameId]
            val predicted = fitted.predictedTransform(frame.captureIndex)
            val retryEligible = sparseSeed == null &&
                reference.stars.size >= MIN_MODEL_GUIDED_STARS &&
                frame.stars.size >= MIN_MODEL_GUIDED_STARS
            val local = localRegistrar.register(
                reference = reference,
                candidate = frame,
                predicted = predicted,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                modelResidual = fitted.residual,
                modelConfidence = fitted.score,
                tracks = tracks,
                sparseSeed = sparseSeed,
                retry = retryEligible
            )
            val localHypothesis = local.toHypothesis()
            val preliminaryAgreement = sequenceAgreement(
                local.transform,
                predicted,
                local.searchRadius,
                imageWidth,
                imageHeight
            )
            val preliminaryVerification = verifier.verifyFrame(
                reference,
                frame,
                local.transform,
                predicted,
                tracks
            )
            val translation = if (local.rejectionReason == null) {
                registrar.registerAutomaticWithPrior(
                    referenceStars = reference.stars,
                    candidateStars = frame.stars,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    prior = localHypothesis,
                    sequenceAgreement = preliminaryAgreement,
                    verificationScore = preliminaryVerification.confidence
                )
            } else {
                RegistrationResult.rejected(
                    referenceStars = reference.stars.size,
                    detectedStars = frame.stars.size,
                    reason = local.rejectionReason,
                    matchedStars = local.matchedStars,
                    inlierStars = local.inlierStars,
                    residualError = local.residual,
                    confidence = local.confidence
                ).withTransform(local.transform).copy(registrationModel = "MODEL_GUIDED_REJECTED")
            }
            val automatic = registrar.registerAutomatic(
                referenceStars = reference.stars,
                candidateStars = frame.stars,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
            val rigid = automatic.takeIf {
                it.isReliable && it.rotationAllowed &&
                    transformDistanceAtImageCenter(
                        it.referenceToSourceTransform(),
                        local.transform,
                        imageWidth,
                        imageHeight
                    ) <= local.searchRadius.coerceAtLeast(MAX_LEGACY_LOCAL_REFINEMENT)
            }
            val legacy = rigid?.copy(
                rawDx = translation.dx,
                rawDy = translation.dy,
                rawRotationRadians = 0f,
                transformRetryUsed = true
            ) ?: translation
            val selectedTransform = legacy.referenceToSourceTransform().takeIf {
                legacy.rotationAllowed ||
                    transformDistanceAtImageCenter(
                        it,
                        local.transform,
                        imageWidth,
                        imageHeight
                    ) <= MAX_LEGACY_LOCAL_REFINEMENT
            } ?: local.transform
            val effectiveLocal = local.copy(
                transform = selectedTransform,
                correctionDx = selectedTransform.dx - predicted.dx,
                correctionDy = selectedTransform.dy - predicted.dy
            )
            val agreement = sequenceAgreement(
                selectedTransform,
                predicted,
                local.searchRadius,
                imageWidth,
                imageHeight
            )
            val frameVerification = verifier.verifyFrame(
                reference,
                frame,
                selectedTransform,
                predicted,
                tracks
            )
            val decision = acceptancePolicy.evaluate(
                FrameAcceptanceEvidence(
                    motionObservable = fitted.motionObservable,
                    sequenceModelScore = fitted.score,
                    sequenceAgreement = agreement,
                    usableSparseHypothesis = sparseSeed != null,
                    local = effectiveLocal,
                    legacyRegistration = legacy,
                    verification = frameVerification.selected,
                    identityVerification = frameVerification.identity,
                    sequenceIdentityVerification = globalVerification.identity
                )
            )
            val result = when (decision) {
                is FrameAcceptanceDecision.AcceptedStrongMatch -> legacy.copy(
                    isReliable = true,
                    rejectionReason = null,
                    transformRetryUsed = effectiveLocal.retryUsed,
                    transformSequenceScore = agreement,
                    transformSequenceDeviation = hypot(
                        effectiveLocal.correctionDx,
                        effectiveLocal.correctionDy
                    )
                )
                is FrameAcceptanceDecision.AcceptedSequenceSupported ->
                    sequenceSupportedRegistration(
                        referenceStars = reference.stars.size,
                        detectedStars = frame.stars.size,
                        local = effectiveLocal,
                        sequenceAgreement = agreement,
                        verificationConfidence = frameVerification.confidence
                    )
                is FrameAcceptanceDecision.AcceptedVerifiedIdentity ->
                    verifiedIdentityRegistration(
                        referenceStars = reference.stars.size,
                        detectedStars = frame.stars.size,
                        verification = frameVerification.identity
                    )
                is FrameAcceptanceDecision.ProvisionalStationary ->
                    sequenceSupportedRegistration(
                        referenceStars = reference.stars.size,
                        detectedStars = frame.stars.size,
                        local = effectiveLocal,
                        sequenceAgreement = agreement,
                        verificationConfidence = frameVerification.confidence
                    ).copy(
                        registrationModel = "STATIONARY_PROVISIONAL",
                        confidence = maxOf(
                            MIN_PROVISIONAL_CONFIDENCE,
                            effectiveLocal.confidence * 0.55f + agreement * 0.45f
                        ).coerceIn(0f, 1f)
                    )
                is FrameAcceptanceDecision.ProvisionalFullResolution ->
                    sequenceSupportedRegistration(
                        referenceStars = reference.stars.size,
                        detectedStars = frame.stars.size,
                        local = effectiveLocal,
                        sequenceAgreement = agreement,
                        verificationConfidence = frameVerification.confidence
                    ).copy(
                        registrationModel = "MODEL_SUPPORTED_PROVISIONAL",
                        confidence = maxOf(
                            MIN_PROVISIONAL_CONFIDENCE,
                            effectiveLocal.confidence * 0.35f +
                                agreement * 0.45f +
                                fitted.score * 0.20f
                        ).coerceIn(0f, 1f)
                    )
                is FrameAcceptanceDecision.Rejected -> legacy.withTransform(selectedTransform).copy(
                    isReliable = false,
                    rejectionReason = decision.reason,
                    registrationModel = "MODEL_GUIDED_REJECTED",
                    transformRetryUsed = effectiveLocal.retryUsed,
                    transformSequenceScore = agreement,
                    transformSequenceDeviation = hypot(
                        effectiveLocal.correctionDx,
                        effectiveLocal.correctionDy
                    ),
                    rawDx = predicted.dx,
                    rawDy = predicted.dy
                )
            }
            registrations[frame.frameId] = result
            localRegistrations[frame.frameId] = effectiveLocal
            if (reference.stars.isNotEmpty() && frame.stars.isNotEmpty()) {
                frameVerifications[frame.frameId] = frameVerification
            }
            acceptancePaths[frame.frameId] = decision.path
            acceptanceReasons[frame.frameId] = decision.reason
            val hypotheses = candidates.first { it.frameId == frame.frameId }.hypotheses
            ranks[frame.frameId] = hypotheses.indexOf(sparseSeed).let { if (it < 0) -1 else it + 1 }
            result.rejectionReason?.let { rejectedReasons[frame.frameId] = it }
            onProgress()
        }
        val acceptedFrameIds = registrations.filterValues { it.isReliable }.keys
        val rejectedFrameIds = registrations.filterValues { !it.isReliable }.keys
        val aggregation = VerificationMetricsAggregator.aggregate(
            perFrame = frameVerifications.mapValues { it.value.selected },
            acceptedFrameIds = acceptedFrameIds,
            rejectedFrameIds = rejectedFrameIds,
            referenceFrameId = referenceFrameId
        )
        val verification = globalVerification.copy(
            perFrame = frameVerifications.mapValues { it.value.selected },
            perFrameAccepted = frameVerifications.keys.associateWith {
                registrations[it]?.isReliable == true
            },
            perFrameComparisons = frameVerifications,
            aggregation = aggregation
        )
        val smoothness = (1f - fitted.residual / MAX_SEQUENCE_DEVIATION).coerceIn(0f, 1f)
        val priorAgreement = registrations.filterKeys { it != referenceFrameId }.values
            .filter { it.isReliable }
            .map { (1f - it.transformSequenceDeviation / MAX_SEQUENCE_DEVIATION).coerceIn(0f, 1f) }
            .average().takeIf { it.isFinite() }?.toFloat() ?: 0f
        return SequenceAwareRegistrationDiagnostics(
            trackAnalysis = tracks,
            model = fitted,
            verification = verification,
            registrations = registrations,
            hypothesisCountPerFrame = candidates.associate { it.frameId to it.hypotheses.size },
            selectedHypothesisRankPerFrame = ranks,
            rejectedReasons = rejectedReasons,
            sequenceSmoothnessScore = smoothness,
            sequencePriorAgreementScore = priorAgreement,
            referenceCaptureIndex = reference.captureIndex,
            analysisWidth = imageWidth,
            analysisHeight = imageHeight,
            modelGuidedRegistrations = localRegistrations,
            frameAcceptancePaths = acceptancePaths,
            frameAcceptanceReasons = acceptanceReasons
        )
    }

    private fun ModelGuidedRegistrationResult.toHypothesis() = TranslationHypothesis(
        dx = transform.dx,
        dy = transform.dy,
        support = matchedStars,
        weightedSupport = matchedStars * confidence,
        residual = residual,
        occupiedSectors = occupiedSectors,
        movingTrackSupport = movingTrackSupport,
        stationaryTrackSupport = stationaryTrackSupport
    )

    private fun sequenceAgreement(
        selected: ReferenceToSourceTransform,
        predicted: ReferenceToSourceTransform,
        searchRadius: Float,
        imageWidth: Int = 0,
        imageHeight: Int = 0
    ): Float = (1f - transformDistanceAtImageCenter(
        selected,
        predicted,
        imageWidth,
        imageHeight
    ) /
        searchRadius.coerceAtLeast(1f)).coerceIn(0f, 1f)

    private fun transformDistanceAtImageCenter(
        first: ReferenceToSourceTransform,
        second: ReferenceToSourceTransform,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val x = imageWidth * 0.5f
        val y = imageHeight * 0.5f
        val firstPoint = first.mapOutputToSource(x, y)
        val secondPoint = second.mapOutputToSource(x, y)
        return hypot(firstPoint.x - secondPoint.x, firstPoint.y - secondPoint.y)
    }

    private fun sequenceSupportedRegistration(
        referenceStars: Int,
        detectedStars: Int,
        local: ModelGuidedRegistrationResult,
        sequenceAgreement: Float,
        verificationConfidence: Float
    ) = RegistrationResult(
        dx = local.transform.dx,
        dy = local.transform.dy,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = detectedStars,
        matchedStars = local.matchedStars,
        inlierStars = local.inlierStars,
        residualError = local.residual,
        confidence = (
            local.confidence * 0.40f +
                sequenceAgreement * 0.30f +
                verificationConfidence * 0.30f
            ).coerceIn(0f, 1f),
        isReliable = true,
        rejectionReason = null,
        referenceStars = referenceStars,
        registrationModel = "SEQUENCE_SUPPORTED_LOCAL",
        scaleFixed = true,
        rotationAllowed = false,
        rotationRejectionReason = "model_guided_translation_only",
        occupiedDistributionCells = local.occupiedSectors,
        spatialDistributionScore = (local.occupiedSectors / 4f).coerceIn(0f, 1f),
        transformRetryUsed = local.retryUsed,
        transformSequenceScore = sequenceAgreement,
        transformSequenceDeviation = hypot(local.correctionDx, local.correctionDy),
        rawDx = local.predictedDx,
        rawDy = local.predictedDy,
        rawRotationRadians = 0f
    )

    private fun verifiedIdentityRegistration(
        referenceStars: Int,
        detectedStars: Int,
        verification: RegistrationVerificationMetrics
    ) = RegistrationResult(
        dx = 0f,
        dy = 0f,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = detectedStars,
        matchedStars = verification.reliableStarCount,
        inlierStars = verification.reliableStarCount,
        residualError = 0f,
        confidence = verification.score,
        isReliable = true,
        rejectionReason = null,
        referenceStars = referenceStars,
        registrationModel = "VERIFIED_IDENTITY",
        scaleFixed = true,
        rotationAllowed = false,
        rotationRejectionReason = "stationary_sequence_identity_verified",
        transformSequenceScore = verification.score,
        transformSequenceDeviation = 0f,
        rawDx = 0f,
        rawDy = 0f,
        rawRotationRadians = 0f
    )

    private fun referenceIdentity(stars: Int) = RegistrationResult(
        dx = 0f,
        dy = 0f,
        rotationRadians = 0f,
        scale = 1f,
        detectedStars = stars,
        matchedStars = stars,
        inlierStars = stars,
        residualError = 0f,
        confidence = 1f,
        isReliable = true,
        rejectionReason = null,
        referenceStars = stars,
        registrationModel = "REFERENCE_IDENTITY",
        scaleFixed = true,
        rotationAllowed = false,
        rotationRejectionReason = "reference_identity"
    )

    private fun identityHypothesis(stars: Int) = TranslationHypothesis(
        dx = 0f,
        dy = 0f,
        support = stars,
        weightedSupport = stars.toFloat(),
        residual = 0f,
        occupiedSectors = 9,
        movingTrackSupport = 0,
        stationaryTrackSupport = 0
    )

    companion object {
        private const val MAX_SEQUENCE_DEVIATION = 2.4f
        private const val MIN_MODEL_GUIDED_STARS = 2
        private const val MAX_LEGACY_LOCAL_REFINEMENT = 1.25f
        private const val MIN_PROVISIONAL_CONFIDENCE = 0.35f
    }
}

fun RegistrationResult.scaledToFullResolution(scaleX: Float, scaleY: Float): RegistrationResult {
    val scaled = referenceToSourceTransform().scaledToFullResolution(scaleX, scaleY)
    return withTransform(scaled).copy(
        scaleFixed = true,
        rawDx = rawDx * scaleX,
        rawDy = rawDy * scaleY,
        transformSequenceDeviation = transformSequenceDeviation * ((scaleX + scaleY) * 0.5f),
        neighborTransformDelta = neighborTransformDelta * ((scaleX + scaleY) * 0.5f)
    )
}

fun RegistrationResult.scaledToAnalysisResolution(scaleX: Float, scaleY: Float): RegistrationResult {
    val scaled = referenceToSourceTransform().scaledToAnalysisResolution(scaleX, scaleY)
    return withTransform(scaled).copy(
        rawDx = rawDx / scaleX,
        rawDy = rawDy / scaleY,
        transformSequenceDeviation = transformSequenceDeviation / ((scaleX + scaleY) * 0.5f),
        neighborTransformDelta = neighborTransformDelta / ((scaleX + scaleY) * 0.5f)
    )
}
