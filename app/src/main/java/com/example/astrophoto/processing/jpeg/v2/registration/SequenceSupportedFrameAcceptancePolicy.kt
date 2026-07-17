package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult

sealed interface FrameAcceptanceDecision {
    val path: String
    val reason: String

    data class AcceptedStrongMatch(
        override val reason: String = "legacy_sparse_and_local_evidence_passed"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_STRONG_SPARSE
    }

    data class AcceptedSequenceSupported(
        override val reason: String = "sequence_prior_local_and_verification_evidence_passed"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_SEQUENCE_SUPPORTED
    }

    data class ProvisionalFullResolution(
        override val reason: String = "sequence_model_supports_full_resolution_verification"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_MODEL_SUPPORTED_PROVISIONAL
    }

    data class Rejected(override val reason: String) : FrameAcceptanceDecision {
        override val path: String = PATH_REJECTED
    }

    companion object {
        const val PATH_REFERENCE = "REFERENCE_IDENTITY"
        const val PATH_STRONG_SPARSE = "STRONG_SPARSE"
        const val PATH_SEQUENCE_SUPPORTED = "SEQUENCE_SUPPORTED"
        const val PATH_MODEL_SUPPORTED_PROVISIONAL = "MODEL_SUPPORTED_PROVISIONAL"
        const val PATH_REJECTED = "REJECTED"
    }
}

data class FrameAcceptanceEvidence(
    val motionObservable: Boolean,
    val sequenceModelScore: Float,
    val sequenceAgreement: Float,
    val usableSparseHypothesis: Boolean,
    val local: ModelGuidedRegistrationResult,
    val legacyRegistration: RegistrationResult,
    val verification: RegistrationVerificationMetrics
)

/** Centralized single-frame thresholds. Final clean-stack thresholds live elsewhere unchanged. */
class SequenceSupportedFrameAcceptancePolicy {
    fun evaluate(evidence: FrameAcceptanceEvidence): FrameAcceptanceDecision {
        if (
            evidence.usableSparseHypothesis &&
            evidence.legacyRegistration.isReliable &&
            strongVerificationAccepted(evidence.verification)
        ) {
            return FrameAcceptanceDecision.AcceptedStrongMatch()
        }
        val rejection = when {
            !evidence.motionObservable -> "sequence_motion_not_observable"
            evidence.sequenceModelScore < MIN_SEQUENCE_MODEL_SCORE -> "sequence_model_confidence_too_low"
            evidence.local.rejectionReason != null -> evidence.local.rejectionReason
            evidence.local.matchedStars < MIN_LOCAL_CORRESPONDENCES -> "insufficient_local_correspondences"
            evidence.local.inlierStars < MIN_LOCAL_INLIERS -> "insufficient_local_inliers"
            evidence.local.residual > MAX_LOCAL_RESIDUAL -> "local_residual_too_high"
            evidence.local.confidence < MIN_LOCAL_CONFIDENCE -> "local_confidence_too_low"
            evidence.sequenceAgreement < MIN_SEQUENCE_AGREEMENT -> "sequence_agreement_too_low"
            evidence.verification.reliableStarCount < MIN_SEQUENCE_VERIFICATION_STARS ->
                "insufficient_verification_stars"
            evidence.verification.referenceRetention < MIN_SEQUENCE_RETENTION ->
                "frame_verification_retention_too_low"
            evidence.verification.contrastRatio < MIN_SEQUENCE_CONTRAST_RATIO ->
                "frame_verification_contrast_too_low"
            evidence.verification.widthGrowth > MAX_SEQUENCE_WIDTH_GROWTH ->
                "frame_verification_width_growth_too_high"
            evidence.verification.smearRate > MAX_SEQUENCE_SMEAR_RATE ->
                "frame_verification_smear_too_high"
            evidence.verification.score < MIN_SEQUENCE_VERIFICATION_CONFIDENCE ->
                "frame_verification_confidence_too_low"
            else -> null
        }
        return when {
            rejection == null -> FrameAcceptanceDecision.AcceptedSequenceSupported()
            supportsFullResolutionVerification(evidence) ->
                FrameAcceptanceDecision.ProvisionalFullResolution(rejection)
            else -> FrameAcceptanceDecision.Rejected(rejection)
        }
    }

    private fun supportsFullResolutionVerification(evidence: FrameAcceptanceEvidence): Boolean =
        evidence.motionObservable &&
            evidence.sequenceModelScore >= MIN_SEQUENCE_MODEL_SCORE &&
            evidence.sequenceAgreement >= MIN_SEQUENCE_AGREEMENT &&
            evidence.verification.reliableStarCount >= MIN_PROVISIONAL_VERIFICATION_STARS &&
            evidence.legacyRegistration.referenceStars >= MIN_ANALYSIS_STARS &&
            evidence.legacyRegistration.detectedStars >= MIN_ANALYSIS_STARS &&
            evidence.local.transform.dx.isFinite() &&
            evidence.local.transform.dy.isFinite()

    fun strongVerificationAccepted(metrics: RegistrationVerificationMetrics): Boolean =
        metrics.reliableStarCount >= MIN_STRONG_VERIFICATION_STARS &&
            metrics.referenceRetention >= MIN_STRONG_RETENTION &&
            metrics.contrastRatio >= MIN_STRONG_CONTRAST_RATIO &&
            metrics.smearRate <= MAX_STRONG_SMEAR_RATE &&
            metrics.score >= MIN_STRONG_VERIFICATION_CONFIDENCE

    companion object {
        const val MIN_STRONG_VERIFICATION_STARS = 4
        const val MIN_STRONG_RETENTION = 0.40f
        const val MIN_STRONG_CONTRAST_RATIO = 0.35f
        const val MAX_STRONG_SMEAR_RATE = 0.50f
        const val MIN_STRONG_VERIFICATION_CONFIDENCE = 0.45f

        const val MIN_SEQUENCE_MODEL_SCORE = 0.50f
        const val MIN_LOCAL_CORRESPONDENCES = 2
        const val MIN_LOCAL_INLIERS = 2
        const val MAX_LOCAL_RESIDUAL = 1.25f
        const val MIN_LOCAL_CONFIDENCE = 0.42f
        const val MIN_SEQUENCE_AGREEMENT = 0.55f
        const val MIN_SEQUENCE_VERIFICATION_STARS = 4
        const val MIN_SEQUENCE_RETENTION = 0.80f
        const val MIN_SEQUENCE_CONTRAST_RATIO = 0.78f
        const val MAX_SEQUENCE_WIDTH_GROWTH = 0.65f
        const val MAX_SEQUENCE_SMEAR_RATE = 0.10f
        const val MIN_SEQUENCE_VERIFICATION_CONFIDENCE = 0.70f
        const val MIN_PROVISIONAL_VERIFICATION_STARS = 1
        const val MIN_ANALYSIS_STARS = 1
    }
}
