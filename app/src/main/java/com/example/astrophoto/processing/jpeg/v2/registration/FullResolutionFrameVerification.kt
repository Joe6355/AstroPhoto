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
    val smear: Float,
    val ellipticityGrowth: Float = 0f
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
    val spatialSectorCount: Int,
    val zncc: FullResolutionTransformEvidence = FullResolutionTransformEvidence.Empty
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

    /** Stage 12 verification uses measured stellar centroids; ZNCC is only a named alternative. */
    fun verifyCentroids(
        matches: List<StellarCentroidMatch>,
        initial: ReferenceToSourceTransform,
        zncc: ReferenceToSourceTransform,
        refined: ReferenceToSourceTransform
    ): FullResolutionFrameVerificationResult = FullResolutionFrameVerificationResult(
        refined = centroidEvidence(matches, refined),
        initial = centroidEvidence(matches, initial),
        identity = centroidEvidence(matches, ReferenceToSourceTransform.Identity),
        inverse = centroidEvidence(matches, initial.inverse().asReferenceToSourceTransform()),
        doubleApplied = centroidEvidence(matches, initial.appliedTwice()),
        spatialSectorCount = matches.map { it.patch.sector }.distinct().size,
        zncc = centroidEvidence(matches, zncc)
    )

    private fun centroidEvidence(
        matches: List<StellarCentroidMatch>,
        transform: ReferenceToSourceTransform
    ): FullResolutionTransformEvidence {
        val accepted = matches.filter { it.accepted }
        if (accepted.isEmpty()) return FullResolutionTransformEvidence.Empty
        val residuals = accepted.map { match ->
            kotlin.math.hypot(match.dx - transform.dx, match.dy - transform.dy)
        }
        val contrastRatios = accepted.map { match ->
            val referenceContrast = (match.reference.peak - match.reference.background).coerceAtLeast(0.0001f)
            ((match.candidate.peak - match.candidate.background) / referenceContrast).coerceIn(0f, 4f)
        }
        val widthGrowth = accepted.map { match ->
            val referenceWidth = (match.reference.fwhmX + match.reference.fwhmY) * 0.5f
            val candidateWidth = (match.candidate.fwhmX + match.candidate.fwhmY) * 0.5f
            (candidateWidth / referenceWidth.coerceAtLeast(0.1f) - 1f).coerceIn(-1f, 4f)
        }
        val ellipticityGrowth = accepted.map { match ->
            match.candidate.ellipticity - match.reference.ellipticity
        }
        val centroidResidual = median(residuals)
        val confidence = median(accepted.map { it.confidence })
        return FullResolutionTransformEvidence(
            validPatchCount = accepted.size,
            score = (confidence / (1f + centroidResidual)).coerceIn(0f, 1f),
            retention = accepted.count { it.candidate.confidence >= MIN_CENTROID_CONFIDENCE }.toFloat() /
                accepted.size,
            contrastRatio = median(contrastRatios),
            centroidResidual = centroidResidual,
            widthGrowth = median(widthGrowth),
            smear = accepted.count { match ->
                match.candidate.ellipticity >= MAX_STELLAR_ELLIPTICITY ||
                    match.candidate.ellipticity - match.reference.ellipticity > MAX_ELLIPTICITY_GROWTH
            }.toFloat() / accepted.size,
            ellipticityGrowth = median(ellipticityGrowth)
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
        private const val MIN_CENTROID_CONFIDENCE = 0.20f
        private const val MAX_STELLAR_ELLIPTICITY = 0.82f
        private const val MAX_ELLIPTICITY_GROWTH = 0.25f
    }
}
