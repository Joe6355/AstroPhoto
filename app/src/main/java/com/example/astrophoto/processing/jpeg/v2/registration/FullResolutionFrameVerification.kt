package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource

data class FullResolutionTransformEvidence(
    val validPatchCount: Int,
    val score: Float,
    val retention: Float,
    val contrastRatio: Float,
    val centroidResidual: Float,
    val widthGrowth: Float,
    val smear: Float
) {
    companion object {
        val Empty = FullResolutionTransformEvidence(0, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f)
        val ReferenceIdentity = FullResolutionTransformEvidence(1, 1f, 1f, 1f, 0f, 0f, 0f)
    }
}

data class FullResolutionFrameVerificationResult(
    val refined: FullResolutionTransformEvidence,
    val initial: FullResolutionTransformEvidence,
    val identity: FullResolutionTransformEvidence,
    val inverse: FullResolutionTransformEvidence,
    val doubleApplied: FullResolutionTransformEvidence,
    val spatialSectorCount: Int
)

class FullResolutionFrameVerification(
    private val matcher: FullResolutionStarPatchMatcher = FullResolutionStarPatchMatcher()
) {
    fun verify(
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        patches: List<FullResolutionStarPatch>,
        initial: ReferenceToSourceTransform,
        refined: ReferenceToSourceTransform
    ): FullResolutionFrameVerificationResult {
        val boundedPatches = patches.take(MAX_VERIFICATION_PATCHES)
        return FullResolutionFrameVerificationResult(
            refined = evidence(reference, candidate, boundedPatches, refined),
            initial = evidence(reference, candidate, boundedPatches, initial),
            identity = evidence(reference, candidate, boundedPatches, ReferenceToSourceTransform.Identity),
            inverse = evidence(reference, candidate, boundedPatches, initial.inverse().asReferenceToSourceTransform()),
            doubleApplied = evidence(reference, candidate, boundedPatches, initial.appliedTwice()),
            spatialSectorCount = boundedPatches.map { it.sector }.distinct().size
        )
    }

    private fun evidence(
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        patches: List<FullResolutionStarPatch>,
        transform: ReferenceToSourceTransform
    ): FullResolutionTransformEvidence {
        val comparisons = patches.map { matcher.compareAt(reference, candidate, it, transform) }
            .filter { it.valid }
        if (comparisons.isEmpty()) return FullResolutionTransformEvidence.Empty
        return FullResolutionTransformEvidence(
            validPatchCount = comparisons.size,
            score = median(comparisons.map { it.score }),
            retention = comparisons.count { it.retained }.toFloat() / comparisons.size,
            contrastRatio = median(comparisons.map { it.contrastRatio }),
            centroidResidual = median(comparisons.map { it.centroidResidual }),
            widthGrowth = median(comparisons.map { it.widthGrowth }),
            smear = comparisons.sumOf { it.smear.toDouble() }.toFloat() / comparisons.size
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    companion object {
        private const val MAX_VERIFICATION_PATCHES = 24
    }
}
