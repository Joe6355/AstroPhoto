package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import kotlin.math.hypot

class SequenceAwareRegistrationEngine(
    private val trackBuilder: TemporalFeatureTrackBuilder = TemporalFeatureTrackBuilder(),
    private val votingEstimator: SparseTranslationVotingEstimator = SparseTranslationVotingEstimator(),
    private val modelEstimator: StellarMotionModelEstimator = StellarMotionModelEstimator(),
    private val verifier: RegistrationSequenceVerifier = RegistrationSequenceVerifier(),
    private val registrar: StarSimilarityRegistrar = StarSimilarityRegistrar()
) {
    fun register(
        frames: List<TemporalFeatureFrame>,
        referenceFrameId: String,
        imageWidth: Int,
        imageHeight: Int
    ): SequenceAwareRegistrationDiagnostics {
        val ordered = frames.sortedBy { it.captureIndex }
        val reference = checkNotNull(ordered.firstOrNull { it.frameId == referenceFrameId })
        val tracks = trackBuilder.build(ordered)
        val candidates = ordered.map { frame ->
            SequenceRegistrationCandidate(
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
        }
        val fitted = modelEstimator.fit(candidates, reference.captureIndex, tracks)
        val verification = verifier.verify(
            reference = reference,
            frames = ordered,
            selected = fitted.acceptedFrameHypotheses,
            zero = fitted.zeroFrameHypotheses,
            tracks = tracks
        )
        val registrations = linkedMapOf<String, RegistrationResult>()
        val ranks = linkedMapOf<String, Int>()
        val rejectedReasons = linkedMapOf<String, String>()
        ordered.forEach { frame ->
            if (frame.frameId == referenceFrameId) {
                registrations[frame.frameId] = referenceIdentity(reference.stars.size)
                ranks[frame.frameId] = 0
                return@forEach
            }
            val selected = fitted.acceptedFrameHypotheses[frame.frameId]
            if (selected == null) {
                val rejected = RegistrationResult.rejected(
                    referenceStars = reference.stars.size,
                    detectedStars = frame.stars.size,
                    reason = "No hypothesis supported by sequence model"
                ).copy(registrationModel = "SEQUENCE_TRANSLATION", scaleFixed = true)
                registrations[frame.frameId] = rejected
                rejectedReasons[frame.frameId] = checkNotNull(rejected.rejectionReason)
                ranks[frame.frameId] = -1
                return@forEach
            }
            val predicted = fitted.predictedTransform(frame.captureIndex)
            val deviation = hypot(selected.dx - predicted.dx, selected.dy - predicted.dy)
            val agreement = (1f - deviation / MAX_SEQUENCE_DEVIATION).coerceIn(0f, 1f)
            val frameVerification = verification.perFrame[frame.frameId]
            val frameVerificationAccepted = verification.perFrameAccepted[frame.frameId] == true
            val result = registrar.registerAutomaticWithPrior(
                referenceStars = reference.stars,
                candidateStars = frame.stars,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                prior = selected,
                sequenceAgreement = agreement,
                verificationScore = frameVerification?.score ?: 0f
            ).let { local ->
                if (!frameVerificationAccepted) {
                    local.copy(
                        isReliable = false,
                        rejectionReason = "Frame transform failed star-preservation verification"
                    )
                } else {
                    local
                }
            }
            registrations[frame.frameId] = result
            val hypotheses = candidates.first { it.frameId == frame.frameId }.hypotheses
            ranks[frame.frameId] = hypotheses.indexOf(selected).let { if (it < 0) -1 else it + 1 }
            result.rejectionReason?.let { rejectedReasons[frame.frameId] = it }
        }
        val smoothness = (1f - fitted.residual / MAX_SEQUENCE_DEVIATION).coerceIn(0f, 1f)
        val priorAgreement = registrations.filterKeys { it != referenceFrameId }.values
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
            analysisHeight = imageHeight
        )
    }

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
