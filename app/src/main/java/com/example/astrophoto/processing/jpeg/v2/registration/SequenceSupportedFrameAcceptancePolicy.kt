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

    data class AcceptedVerifiedIdentity(
        override val reason: String = "stationary_sequence_identity_verified"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_VERIFIED_IDENTITY
    }

    data class ProvisionalFullResolution(
        override val reason: String = "sequence_model_supports_full_resolution_verification"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_MODEL_SUPPORTED_PROVISIONAL
    }

    data class ProvisionalStationary(
        override val reason: String = "stationary_sequence_supports_full_resolution_verification"
    ) : FrameAcceptanceDecision {
        override val path: String = PATH_STATIONARY_PROVISIONAL
    }

    data class Rejected(override val reason: String) : FrameAcceptanceDecision {
        override val path: String = PATH_REJECTED
    }

    companion object {
        const val PATH_REFERENCE = "REFERENCE_IDENTITY"
        const val PATH_STRONG_SPARSE = "STRONG_SPARSE"
        const val PATH_SEQUENCE_SUPPORTED = "SEQUENCE_SUPPORTED"
        const val PATH_VERIFIED_IDENTITY = "VERIFIED_IDENTITY"
        const val PATH_MODEL_SUPPORTED_PROVISIONAL = "MODEL_SUPPORTED_PROVISIONAL"
        const val PATH_STATIONARY_PROVISIONAL = "STATIONARY_PROVISIONAL"
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
    val verification: RegistrationVerificationMetrics,
    val identityVerification: RegistrationVerificationMetrics,
    val sequenceIdentityVerification: RegistrationVerificationMetrics
)

/** Centralized single-frame thresholds. Final clean-stack thresholds live elsewhere unchanged. */
class SequenceSupportedFrameAcceptancePolicy {
    fun evaluate(evidence: FrameAcceptanceEvidence): FrameAcceptanceDecision {
        if (
            !evidence.motionObservable &&
            identityVerificationAccepted(evidence.sequenceIdentityVerification) &&
            identityVerificationAccepted(evidence.identityVerification)
        ) {
            return FrameAcceptanceDecision.AcceptedVerifiedIdentity()
        }
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
            supportsSparseStationaryFullResolutionVerification(evidence) ->
                FrameAcceptanceDecision.ProvisionalStationary(rejection)
            supportsStationaryFullResolutionVerification(evidence) ->
                FrameAcceptanceDecision.ProvisionalStationary(rejection)
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

    private fun supportsStationaryFullResolutionVerification(
        evidence: FrameAcceptanceEvidence
    ): Boolean =
        !evidence.motionObservable &&
            evidence.sequenceAgreement >= MIN_STATIONARY_SEQUENCE_AGREEMENT &&
            evidence.local.rejectionReason == null &&
            evidence.local.matchedStars >= MIN_STATIONARY_LOCAL_CORRESPONDENCES &&
            evidence.local.inlierStars >= MIN_STATIONARY_LOCAL_INLIERS &&
            evidence.local.residual <= MAX_STATIONARY_LOCAL_RESIDUAL &&
            evidence.local.confidence >= MIN_STATIONARY_LOCAL_CONFIDENCE &&
            kotlin.math.hypot(
                evidence.local.transform.dx,
                evidence.local.transform.dy
            ) <= MAX_STATIONARY_ANALYSIS_SHIFT &&
            evidence.legacyRegistration.referenceStars >= MIN_STATIONARY_ANALYSIS_STARS &&
            evidence.legacyRegistration.detectedStars >= MIN_STATIONARY_ANALYSIS_STARS

    private fun supportsSparseStationaryFullResolutionVerification(
        evidence: FrameAcceptanceEvidence
    ): Boolean =
        !evidence.motionObservable &&
            evidence.sequenceModelScore >= MIN_SPARSE_STATIONARY_MODEL_SCORE &&
            evidence.sequenceAgreement >= MIN_SPARSE_STATIONARY_SEQUENCE_AGREEMENT &&
            kotlin.math.hypot(
                evidence.local.transform.dx,
                evidence.local.transform.dy
            ) <= MAX_SPARSE_STATIONARY_ANALYSIS_SHIFT &&
            evidence.legacyRegistration.referenceStars >= MIN_STATIONARY_ANALYSIS_STARS &&
            evidence.legacyRegistration.detectedStars >= MIN_SPARSE_STATIONARY_DETECTED_STARS &&
            evidence.identityVerification.reliableStarCount >=
                MIN_SPARSE_STATIONARY_VERIFICATION_STARS &&
            evidence.identityVerification.referenceRetention >=
                MIN_SPARSE_STATIONARY_RETENTION &&
            evidence.identityVerification.smearRate <= MAX_IDENTITY_SMEAR_RATE

    fun strongVerificationAccepted(metrics: RegistrationVerificationMetrics): Boolean =
        metrics.reliableStarCount >= MIN_STRONG_VERIFICATION_STARS &&
            metrics.referenceRetention >= MIN_STRONG_RETENTION &&
            metrics.contrastRatio >= MIN_STRONG_CONTRAST_RATIO &&
            metrics.smearRate <= MAX_STRONG_SMEAR_RATE &&
            metrics.score >= MIN_STRONG_VERIFICATION_CONFIDENCE

    fun identityVerificationAccepted(metrics: RegistrationVerificationMetrics): Boolean =
        metrics.reliableStarCount >= MIN_IDENTITY_VERIFICATION_STARS &&
            metrics.referenceRetention >= MIN_IDENTITY_RETENTION &&
            metrics.contrastRatio >= MIN_IDENTITY_CONTRAST_RATIO &&
            metrics.widthGrowth <= MAX_IDENTITY_WIDTH_GROWTH &&
            metrics.smearRate <= MAX_IDENTITY_SMEAR_RATE &&
            metrics.score >= MIN_IDENTITY_VERIFICATION_CONFIDENCE

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

        const val MIN_IDENTITY_VERIFICATION_STARS = 4
        const val MIN_IDENTITY_RETENTION = 0.80f
        const val MIN_IDENTITY_CONTRAST_RATIO = 0.75f
        const val MAX_IDENTITY_WIDTH_GROWTH = 0.45f
        const val MAX_IDENTITY_SMEAR_RATE = 0.10f
        const val MIN_IDENTITY_VERIFICATION_CONFIDENCE = 0.75f

        const val MIN_STATIONARY_SEQUENCE_AGREEMENT = 0.80f
        const val MIN_STATIONARY_LOCAL_CORRESPONDENCES = 3
        const val MIN_STATIONARY_LOCAL_INLIERS = 3
        const val MAX_STATIONARY_LOCAL_RESIDUAL = 0.55f
        const val MIN_STATIONARY_LOCAL_CONFIDENCE = 0.55f
        const val MAX_STATIONARY_ANALYSIS_SHIFT = 0.90f
        const val MIN_STATIONARY_ANALYSIS_STARS = 4

        const val MIN_SPARSE_STATIONARY_MODEL_SCORE = 0.65f
        const val MIN_SPARSE_STATIONARY_SEQUENCE_AGREEMENT = 0.95f
        const val MAX_SPARSE_STATIONARY_ANALYSIS_SHIFT = 0.20f
        const val MIN_SPARSE_STATIONARY_DETECTED_STARS = 2
        const val MIN_SPARSE_STATIONARY_VERIFICATION_STARS = 1
        const val MIN_SPARSE_STATIONARY_RETENTION = 0.20f
    }
}
