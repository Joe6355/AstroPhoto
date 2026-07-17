package com.example.astrophoto.processing.jpeg.v2.registration

import kotlin.math.abs
import kotlin.math.hypot

data class FullResolutionRefinementDecision(
    val accepted: Boolean,
    val rejectionReason: String?,
    val medianResidualLimit: Float,
    val percentile90ResidualLimit: Float
)

/** Final frame-level gate. This is separate from, and does not weaken, the clean-stack gate. */
class FullResolutionRefinementPolicy {
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
        acceptedPatchCount: Int,
        availableSectorCount: Int,
        acceptedSectorCount: Int,
        medianResidual: Float,
        percentile90Residual: Float,
        confidence: Float,
        searchRadius: Float,
        verification: FullResolutionFrameVerificationResult
    ): FullResolutionRefinementDecision {
        val medianLimit = TARGET_MEDIAN_RESIDUAL + (1f - confidence) * 0.10f
        val percentile90Limit = TARGET_P90_RESIDUAL + (1f - confidence) * 0.15f
        fun reject(reason: String) = FullResolutionRefinementDecision(false, reason, medianLimit, percentile90Limit)
        if (acceptedPatchCount < MIN_ACCEPTED_PATCHES) return reject("insufficient_full_resolution_patches")
        if (availableSectorCount >= 2 && acceptedSectorCount < 2) return reject("insufficient_spatial_sectors")
        val correctionMagnitude = hypot(correctionDx, correctionDy)
        if (abs(correctionDx) > searchRadius || abs(correctionDy) > searchRadius) {
            return reject("full_resolution_correction_out_of_bounds")
        }
        val initialMagnitude = hypot(initialDx, initialDy)
        val refinedMagnitude = hypot(initialDx + correctionDx, initialDy + correctionDy)
        if (
            initialMagnitude > 1.5f &&
            correctionMagnitude > initialMagnitude * 0.50f &&
            refinedMagnitude < maxOf(0.50f, initialMagnitude * 0.35f)
        ) {
            return reject("refinement_jump_to_zero_mode")
        }
        if (!medianResidual.isFinite() || medianResidual > medianLimit) return reject("median_residual_above_limit")
        if (!percentile90Residual.isFinite() || percentile90Residual > percentile90Limit) {
            return reject("p90_residual_above_limit")
        }
        val refined = verification.refined
        if (refined.validPatchCount < MIN_ACCEPTED_PATCHES) return reject("insufficient_verification_patches")
        if (refined.retention < MIN_PATCH_RETENTION) return reject("full_resolution_patch_retention_low")
        if (refined.contrastRatio < MIN_PATCH_CONTRAST_RATIO) return reject("full_resolution_patch_contrast_low")
        if (refined.centroidResidual > MAX_CENTROID_RESIDUAL) return reject("full_resolution_centroid_residual_high")
        if (refined.widthGrowth > MAX_WIDTH_GROWTH) return reject("full_resolution_width_growth_high")
        if (refined.smear > MAX_SMEAR) return reject("full_resolution_smear_high")

        if (!beatsInitial(refined, verification.initial, correctionMagnitude)) {
            return reject("refined_transform_does_not_improve_initial")
        }
        if (!beatsAlternative(refined, verification.identity)) return reject("identity_transform_competes")
        if (!beatsAlternative(refined, verification.inverse)) return reject("inverse_transform_competes")
        if (!beatsAlternative(refined, verification.doubleApplied)) return reject("double_transform_competes")
        return FullResolutionRefinementDecision(true, null, medianLimit, percentile90Limit)
    }

    private fun beatsInitial(
        refined: FullResolutionTransformEvidence,
        initial: FullResolutionTransformEvidence,
        correctionMagnitude: Float
    ): Boolean {
        if (initial.validPatchCount == 0) return true
        if (correctionMagnitude < MATERIAL_CORRECTION) {
            return refined.score >= initial.score - NON_REGRESSION_SCORE_TOLERANCE &&
                refined.centroidResidual <= initial.centroidResidual + NON_REGRESSION_CENTROID_TOLERANCE
        }
        return refined.score >= initial.score + MIN_SCORE_ADVANTAGE ||
            refined.centroidResidual + MIN_CENTROID_ADVANTAGE <= initial.centroidResidual
    }

    private fun beatsAlternative(
        refined: FullResolutionTransformEvidence,
        alternative: FullResolutionTransformEvidence
    ): Boolean {
        if (alternative.validPatchCount == 0) return true
        return refined.score >= alternative.score + MIN_ALTERNATIVE_SCORE_ADVANTAGE ||
            refined.centroidResidual + MIN_ALTERNATIVE_CENTROID_ADVANTAGE <= alternative.centroidResidual
    }

    companion object {
        const val TARGET_MEDIAN_RESIDUAL = 0.35f
        const val TARGET_P90_RESIDUAL = 0.60f
        private const val BASE_SEARCH_RADIUS = 2.5f
        private const val MIN_SEARCH_RADIUS = 2.5f
        private const val MAX_SEARCH_RADIUS = 4.0f
        private const val MIN_ACCEPTED_PATCHES = 3
        private const val MIN_PATCH_RETENTION = 0.72f
        private const val MIN_PATCH_CONTRAST_RATIO = 0.65f
        private const val MAX_CENTROID_RESIDUAL = 0.60f
        private const val MAX_WIDTH_GROWTH = 0.45f
        private const val MAX_SMEAR = 0.20f
        private const val MATERIAL_CORRECTION = 0.08f
        private const val MIN_SCORE_ADVANTAGE = 0.004f
        private const val MIN_CENTROID_ADVANTAGE = 0.04f
        private const val NON_REGRESSION_SCORE_TOLERANCE = 0.006f
        private const val NON_REGRESSION_CENTROID_TOLERANCE = 0.05f
        private const val MIN_ALTERNATIVE_SCORE_ADVANTAGE = 0.010f
        private const val MIN_ALTERNATIVE_CENTROID_ADVANTAGE = 0.10f
    }
}
