package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

data class FullResolutionPatchDiagnostic(
    val x: Float,
    val y: Float,
    val sector: Int,
    val correctionDx: Float,
    val correctionDy: Float,
    val score: Float,
    val peakSharpness: Float,
    val accepted: Boolean,
    val rejectionReason: String?
)

data class FullResolutionRefinementResult(
    val frameId: String,
    val initialTransform: ReferenceToSourceTransform,
    val refinedTransform: ReferenceToSourceTransform,
    val correctionDx: Float,
    val correctionDy: Float,
    val searchRadius: Float,
    val attemptedPatchCount: Int,
    val acceptedPatchCount: Int,
    val rejectedPatchCount: Int,
    val medianPatchScore: Float,
    val medianResidual: Float,
    val percentile90Residual: Float,
    val spatialSectorCount: Int,
    val confidence: Float,
    val verification: FullResolutionFrameVerificationResult,
    val accepted: Boolean,
    val rejectionReason: String?,
    val patchDiagnostics: List<FullResolutionPatchDiagnostic>
)

class FullResolutionRegistrationRefiner(
    private val matcher: FullResolutionStarPatchMatcher = FullResolutionStarPatchMatcher(),
    private val verifier: FullResolutionFrameVerification = FullResolutionFrameVerification(matcher),
    private val policy: FullResolutionRefinementPolicy = FullResolutionRefinementPolicy(),
    private val selector: FullResolutionStarPatchSelector = FullResolutionStarPatchSelector()
) {
    fun refine(
        frameId: String,
        isReference: Boolean,
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        initialTransform: ReferenceToSourceTransform,
        patches: List<FullResolutionStarPatch>,
        analysisScaleUncertainty: Float,
        stage10Residual: Float,
        sequenceResidual: Float,
        stage10Confidence: Float,
        cancellationCheck: () -> Unit = {}
    ): FullResolutionRefinementResult {
        if (isReference) return referenceIdentity(frameId)
        require(initialTransform.rotationRadians == 0f && initialTransform.scale == 1f) {
            "Full-resolution refinement is translation-only"
        }
        val searchRadius = policy.searchRadius(
            analysisScaleUncertainty,
            stage10Residual,
            sequenceResidual,
            stage10Confidence
        )
        val selection = selector.select(patches)
        val diagnostics = selection.rejected.map { rejected ->
            FullResolutionPatchDiagnostic(
                rejected.patch.x,
                rejected.patch.y,
                rejected.patch.sector,
                0f,
                0f,
                0f,
                0f,
                false,
                rejected.reason
            )
        }.toMutableList()
        val matched = selection.selected.map { patch ->
            cancellationCheck()
            val match = matcher.match(
                reference,
                candidate,
                patch,
                initialTransform,
                searchRadius,
                cancellationCheck
            )
            diagnostics += FullResolutionPatchDiagnostic(
                x = patch.x,
                y = patch.y,
                sector = patch.sector,
                correctionDx = match.correctionDx,
                correctionDy = match.correctionDy,
                score = match.score,
                peakSharpness = match.peakSharpness,
                accepted = match.accepted,
                rejectionReason = match.rejectionReason
            )
            WeightedPatch(patch, match, patchWeight(patch, match))
        }.filter { it.match.accepted }

        val aggregation = aggregate(matched)
        val inlierKeys = aggregation.inliers.map { patchKey(it.patch) }.toSet()
        for (index in diagnostics.indices) {
            val value = diagnostics[index]
            if (value.accepted && patchKey(value.x, value.y) !in inlierKeys) {
                diagnostics[index] = value.copy(accepted = false, rejectionReason = "mad_outlier")
            }
        }
        val refined = ReferenceToSourceTransform(
            dx = initialTransform.dx + aggregation.dx,
            dy = initialTransform.dy + aggregation.dy
        )
        val verificationPatches = aggregation.inliers.map { it.patch }
        val verification = verifier.verify(
            reference,
            candidate,
            verificationPatches,
            initialTransform,
            refined
        )
        val availableSectors = selection.selected.map { it.sector }.distinct().size
        val acceptedSectors = verificationPatches.map { it.sector }.distinct().size
        val decision = policy.decide(
            initialDx = initialTransform.dx,
            initialDy = initialTransform.dy,
            correctionDx = aggregation.dx,
            correctionDy = aggregation.dy,
            acceptedPatchCount = aggregation.inliers.size,
            availableSectorCount = availableSectors,
            acceptedSectorCount = acceptedSectors,
            medianResidual = aggregation.medianResidual,
            percentile90Residual = aggregation.percentile90Residual,
            confidence = aggregation.confidence,
            searchRadius = searchRadius,
            verification = verification
        )
        return FullResolutionRefinementResult(
            frameId = frameId,
            initialTransform = initialTransform,
            refinedTransform = refined,
            correctionDx = aggregation.dx,
            correctionDy = aggregation.dy,
            searchRadius = searchRadius,
            attemptedPatchCount = diagnostics.size,
            acceptedPatchCount = aggregation.inliers.size,
            rejectedPatchCount = diagnostics.count { !it.accepted },
            medianPatchScore = median(aggregation.inliers.map { it.match.score }),
            medianResidual = aggregation.medianResidual,
            percentile90Residual = aggregation.percentile90Residual,
            spatialSectorCount = acceptedSectors,
            confidence = aggregation.confidence,
            verification = verification,
            accepted = decision.accepted,
            rejectionReason = decision.rejectionReason,
            patchDiagnostics = diagnostics.sortedWith(compareBy({ it.y }, { it.x }, { it.rejectionReason.orEmpty() }))
        )
    }

    private fun aggregate(patches: List<WeightedPatch>): Aggregation {
        if (patches.isEmpty()) return Aggregation(0f, 0f, emptyList(), Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0f)
        val medianX = weightedMedian(patches) { it.match.correctionDx }
        val medianY = weightedMedian(patches) { it.match.correctionDy }
        val distances = patches.map { hypot(it.match.correctionDx - medianX, it.match.correctionDy - medianY) }
        val medianDistance = median(distances)
        val mad = median(distances.map { abs(it - medianDistance) })
        val outlierLimit = maxOf(MIN_OUTLIER_DISTANCE, medianDistance + MAD_MULTIPLIER * 1.4826f * mad)
        val inliers = patches.filterIndexed { index, _ -> distances[index] <= outlierLimit }
        if (inliers.isEmpty()) return Aggregation(0f, 0f, emptyList(), Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0f)

        var dx = weightedMedian(inliers) { it.match.correctionDx }
        var dy = weightedMedian(inliers) { it.match.correctionDy }
        repeat(IRLS_ITERATIONS) {
            val residuals = inliers.map { hypot(it.match.correctionDx - dx, it.match.correctionDy - dy) }
            val scale = maxOf(MIN_HUBER_SCALE, 1.4826f * median(residuals))
            var weightSum = 0f
            var xSum = 0f
            var ySum = 0f
            inliers.forEachIndexed { index, patch ->
                val residual = residuals[index]
                val huber = if (residual <= HUBER_K * scale) 1f else HUBER_K * scale / residual
                val weight = patch.weight * huber
                weightSum += weight
                xSum += weight * patch.match.correctionDx
                ySum += weight * patch.match.correctionDy
            }
            if (weightSum > 0f) {
                dx = xSum / weightSum
                dy = ySum / weightSum
            }
        }
        val residuals = inliers.map { hypot(it.match.correctionDx - dx, it.match.correctionDy - dy) }
        val medianResidual = median(residuals)
        val percentile90 = percentile(residuals, 0.90f)
        val medianScore = median(inliers.map { it.match.score }).coerceIn(0f, 1f)
        val sectors = inliers.map { it.patch.sector }.distinct().size
        val confidence = (
            0.45f * medianScore +
                0.25f * (inliers.size / 6f).coerceIn(0f, 1f) +
                0.15f * (sectors / 3f).coerceIn(0f, 1f) +
                0.15f * (1f - medianResidual / 0.75f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return Aggregation(dx, dy, inliers, medianResidual, percentile90, confidence)
    }

    private fun weightedMedian(values: List<WeightedPatch>, selector: (WeightedPatch) -> Float): Float {
        val sorted = values.sortedWith(compareBy<WeightedPatch> { selector(it) }.thenBy { it.patch.y }.thenBy { it.patch.x })
        val total = sorted.sumOf { it.weight.toDouble() }.toFloat()
        var cumulative = 0f
        sorted.forEach { value ->
            cumulative += value.weight
            if (cumulative >= total * 0.5f) return selector(value)
        }
        return selector(sorted.last())
    }

    private fun patchWeight(patch: FullResolutionStarPatch, match: StarPatchMatch): Float =
        (
            match.confidence.coerceIn(0.05f, 1f) *
                ((match.score + 1f) * 0.5f).coerceIn(0.05f, 1f) *
                (0.5f + 0.5f * patch.confidence.coerceIn(0f, 1f)) *
                (0.5f + (match.peakSharpness / 0.08f).coerceIn(0f, 0.5f))
            ).coerceAtLeast(0.001f)

    private fun referenceIdentity(frameId: String) = FullResolutionRefinementResult(
        frameId = frameId,
        initialTransform = ReferenceToSourceTransform.Identity,
        refinedTransform = ReferenceToSourceTransform.Identity,
        correctionDx = 0f,
        correctionDy = 0f,
        searchRadius = 0f,
        attemptedPatchCount = 0,
        acceptedPatchCount = 0,
        rejectedPatchCount = 0,
        medianPatchScore = 1f,
        medianResidual = 0f,
        percentile90Residual = 0f,
        spatialSectorCount = 1,
        confidence = 1f,
        verification = FullResolutionFrameVerificationResult(
            refined = FullResolutionTransformEvidence.ReferenceIdentity,
            initial = FullResolutionTransformEvidence.ReferenceIdentity,
            identity = FullResolutionTransformEvidence.ReferenceIdentity,
            inverse = FullResolutionTransformEvidence.ReferenceIdentity,
            doubleApplied = FullResolutionTransformEvidence.ReferenceIdentity,
            spatialSectorCount = 1
        ),
        accepted = true,
        rejectionReason = null,
        patchDiagnostics = emptyList()
    )

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        val index = ceil((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun patchKey(patch: FullResolutionStarPatch): Pair<Int, Int> = patchKey(patch.x, patch.y)
    private fun patchKey(x: Float, y: Float): Pair<Int, Int> = (x * 100f).toInt() to (y * 100f).toInt()

    private data class WeightedPatch(
        val patch: FullResolutionStarPatch,
        val match: StarPatchMatch,
        val weight: Float
    )

    private data class Aggregation(
        val dx: Float,
        val dy: Float,
        val inliers: List<WeightedPatch>,
        val medianResidual: Float,
        val percentile90Residual: Float,
        val confidence: Float
    )

    companion object {
        private const val MIN_OUTLIER_DISTANCE = 0.22f
        private const val MAD_MULTIPLIER = 3f
        private const val MIN_HUBER_SCALE = 0.06f
        private const val HUBER_K = 1.5f
        private const val IRLS_ITERATIONS = 5
    }
}
