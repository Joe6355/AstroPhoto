package com.example.astrophoto.processing.jpeg.v2.registration

import kotlin.math.abs
import kotlin.math.hypot

data class StellarCentroidRefinementDecision(
    val accepted: Boolean,
    val rejectionReason: String?
)

/** Stage 12 frame gate. Downstream clean-stack quality thresholds remain unchanged and authoritative. */
class StellarCentroidRefinementPolicy {
    fun searchRadius(
        analysisScaleUncertainty: Float,
        stage10Residual: Float,
        sequenceResidual: Float,
        stage10Confidence: Float
    ): Float = (
        BASE_SEARCH_RADIUS +
            analysisScaleUncertainty.coerceIn(0f, 1f) * 0.35f +
            stage10Residual.coerceIn(0f, 3f) * 0.12f +
            sequenceResidual.coerceIn(0f, 3f) * 0.10f +
            (1f - stage10Confidence.coerceIn(0f, 1f)) * 0.35f
        ).coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)

    fun decide(
        initialDx: Float,
        initialDy: Float,
        correctionDx: Float,
        correctionDy: Float,
        acceptedMatchCount: Int,
        availableSectorCount: Int,
        acceptedSectorCount: Int,
        medianResidual: Float,
        percentile90Residual: Float,
        searchRadius: Float,
        znccTransformDifference: Float,
        verification: FullResolutionFrameVerificationResult
    ): StellarCentroidRefinementDecision {
        fun reject(reason: String) = StellarCentroidRefinementDecision(false, reason)
        if (acceptedMatchCount < MIN_ACCEPTED_MATCHES) return reject("insufficient_stellar_centroid_matches")
        if (availableSectorCount >= 2 && acceptedSectorCount < 2) {
            return reject("insufficient_centroid_spatial_sectors")
        }
        if (abs(correctionDx) > searchRadius || abs(correctionDy) > searchRadius) {
            return reject("centroid_correction_out_of_bounds")
        }
        val initialMagnitude = hypot(initialDx, initialDy)
        val correctionMagnitude = hypot(correctionDx, correctionDy)
        val refinedMagnitude = hypot(initialDx + correctionDx, initialDy + correctionDy)
        if (initialMagnitude > 1.5f && correctionMagnitude > initialMagnitude * 0.50f &&
            refinedMagnitude < maxOf(0.50f, initialMagnitude * 0.35f)
        ) return reject("centroid_refinement_jump_to_zero_mode")
        if (!medianResidual.isFinite() || !percentile90Residual.isFinite()) {
            return reject("centroid_residual_not_finite")
        }
        val refined = verification.refined
        if (refined.validPatchCount < MIN_ACCEPTED_MATCHES) return reject("insufficient_centroid_verification_matches")
        if (refined.retention < MIN_RETENTION) return reject("centroid_retention_low")
        if (refined.contrastRatio < MIN_CONTRAST_RATIO) return reject("centroid_contrast_low")
        if (refined.widthGrowth > MAX_WIDTH_GROWTH) return reject("centroid_width_growth_high")
        if (refined.ellipticityGrowth > MAX_ELLIPTICITY_GROWTH) return reject("centroid_ellipticity_growth_high")
        if (refined.smear > MAX_SMEAR) return reject("centroid_smear_high")
        if (!beats(refined, verification.initial, correctionMagnitude, MIN_INITIAL_ADVANTAGE)) {
            return reject("centroid_transform_does_not_improve_initial")
        }
        if (verification.zncc.validPatchCount > 0 && !beats(
                refined,
                verification.zncc,
                znccTransformDifference,
                MIN_ZNCC_ADVANTAGE
            )
        ) return reject("centroid_transform_does_not_improve_zncc")
        if (!beatsAlternative(refined, verification.identity)) return reject("centroid_identity_transform_competes")
        if (!beatsAlternative(refined, verification.inverse)) return reject("centroid_inverse_transform_competes")
        if (!beatsAlternative(refined, verification.doubleApplied)) return reject("centroid_double_transform_competes")
        return StellarCentroidRefinementDecision(true, null)
    }

    private fun beats(
        refined: FullResolutionTransformEvidence,
        alternative: FullResolutionTransformEvidence,
        materialDistance: Float,
        advantage: Float
    ): Boolean {
        if (alternative.validPatchCount == 0) return true
        return refined.centroidResidual <= alternative.centroidResidual + NON_REGRESSION_TOLERANCE
    }

    private fun beatsAlternative(
        refined: FullResolutionTransformEvidence,
        alternative: FullResolutionTransformEvidence
    ): Boolean = alternative.validPatchCount == 0 ||
        refined.centroidResidual + MIN_ALTERNATIVE_ADVANTAGE <= alternative.centroidResidual

    companion object {
        const val TARGET_MEDIAN_RESIDUAL = 0.35f
        const val TARGET_P90_RESIDUAL = 0.60f
        private const val BASE_SEARCH_RADIUS = 4.0f
        private const val MIN_SEARCH_RADIUS = 4.0f
        private const val MAX_SEARCH_RADIUS = 6.0f
        private const val MIN_ACCEPTED_MATCHES = 3
        private const val MIN_RETENTION = 0.90f
        private const val MIN_CONTRAST_RATIO = 0.85f
        private const val MAX_WIDTH_GROWTH = 0.45f
        private const val MAX_ELLIPTICITY_GROWTH = 0.25f
        private const val MAX_SMEAR = 0.10f
        private const val MATERIAL_TRANSFORM_DIFFERENCE = 0.08f
        private const val NON_REGRESSION_TOLERANCE = 0.015f
        private const val MIN_INITIAL_ADVANTAGE = 0.04f
        private const val MIN_ZNCC_ADVANTAGE = 0.03f
        private const val MIN_ALTERNATIVE_ADVANTAGE = 0.08f
    }
}
