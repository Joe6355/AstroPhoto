package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

data class StellarCentroidRefinementResult(
    val frameId: String,
    val initialTransform: ReferenceToSourceTransform,
    val znccTransform: ReferenceToSourceTransform,
    val refinedTransform: ReferenceToSourceTransform,
    val correctionDx: Float,
    val correctionDy: Float,
    val searchRadius: Float,
    val referenceCentroidCount: Int,
    val referenceCentroidAcceptedCount: Int,
    val referenceCentroidRejectedCount: Int,
    val attemptedStarCount: Int,
    val acceptedStarCount: Int,
    val rejectedStarCount: Int,
    val spatialSectorCount: Int,
    val medianResidual: Float,
    val percentile90Residual: Float,
    val medianSnr: Float,
    val medianFitResidual: Float,
    val confidence: Float,
    val verification: FullResolutionFrameVerificationResult,
    val centroidPrimaryUsed: Boolean,
    val znccSecondaryUsed: Boolean,
    val znccFallbackUsed: Boolean,
    val accepted: Boolean,
    val rejectionReason: String?,
    val matches: List<StellarCentroidMatch>,
    val diagnostics: List<StellarCentroidMatchDiagnostic>
)

class StellarCentroidFrameRefiner(
    private val detector: FullResolutionStarCentroidDetector = FullResolutionStarCentroidDetector(),
    private val selector: FullResolutionStarPatchSelector = FullResolutionStarPatchSelector(),
    private val verifier: FullResolutionFrameVerification = FullResolutionFrameVerification(),
    private val policy: StellarCentroidRefinementPolicy = StellarCentroidRefinementPolicy()
) {
    fun refine(
        frameId: String,
        isReference: Boolean,
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        initialTransform: ReferenceToSourceTransform,
        znccTransform: ReferenceToSourceTransform,
        patches: List<FullResolutionStarPatch>,
        analysisScaleUncertainty: Float,
        stage10Residual: Float,
        sequenceResidual: Float,
        stage10Confidence: Float,
        cancellationCheck: () -> Unit = {}
    ): StellarCentroidRefinementResult {
        require(initialTransform.rotationRadians == 0f && initialTransform.scale == 1f) {
            "Stellar centroid refinement is translation-only"
        }
        val selection = selector.select(patches)
        if (isReference) return referenceIdentity(frameId, reference, selection, cancellationCheck)
        val searchRadius = policy.searchRadius(
            analysisScaleUncertainty,
            stage10Residual,
            sequenceResidual,
            stage10Confidence
        )
        val diagnostics = selection.rejected.map { rejected ->
            diagnostic(rejected.patch, reason = rejected.reason)
        }.toMutableList()
        val matches = mutableListOf<StellarCentroidMatch>()
        var referenceAccepted = 0
        selection.selected.forEach { patch ->
            cancellationCheck()
            val referenceDetection = detector.detect(reference, patch.x, patch.y, REFERENCE_SEARCH_RADIUS)
            val referenceCentroid = referenceDetection.measurement
            if (referenceCentroid == null) {
                diagnostics += diagnostic(patch, reason = "reference_${referenceDetection.rejectionReason}")
                return@forEach
            }
            referenceAccepted++
            val predicted = initialTransform.mapOutputToSource(referenceCentroid.x, referenceCentroid.y)
            val candidateDetection = detector.detect(candidate, predicted.x, predicted.y, searchRadius)
            val candidateCentroid = candidateDetection.measurement
            if (candidateCentroid == null) {
                diagnostics += diagnostic(
                    patch,
                    snr = referenceCentroid.snr,
                    fitResidual = referenceCentroid.fitResidual,
                    reason = "candidate_${candidateDetection.rejectionReason}"
                )
                return@forEach
            }
            val dx = candidateCentroid.x - referenceCentroid.x
            val dy = candidateCentroid.y - referenceCentroid.y
            val residualToPrediction = hypot(dx - initialTransform.dx, dy - initialTransform.dy)
            val reason = when {
                abs(dx - initialTransform.dx) > searchRadius || abs(dy - initialTransform.dy) > searchRadius ->
                    "centroid_match_outside_prediction"
                hypot(initialTransform.dx, initialTransform.dy) > 1.5f &&
                    hypot(dx, dy) < maxOf(0.50f, hypot(initialTransform.dx, initialTransform.dy) * 0.35f) ->
                    "stationary_zero_motion_centroid"
                candidateCentroid.ellipticity - referenceCentroid.ellipticity >
                    MAX_MATCH_ELLIPTICITY_GROWTH -> "centroid_match_ellipticity_growth"
                else -> null
            }
            val confidence = minOf(referenceCentroid.confidence, candidateCentroid.confidence)
            val weight = matchWeight(patch, referenceCentroid, candidateCentroid, confidence)
            val match = StellarCentroidMatch(
                patch,
                referenceCentroid,
                candidateCentroid,
                dx,
                dy,
                residualToPrediction,
                reason == null,
                reason,
                confidence,
                weight
            )
            matches += match
            diagnostics += diagnostic(
                patch,
                dx,
                dy,
                residualToPrediction,
                minOf(referenceCentroid.snr, candidateCentroid.snr),
                maxOf(referenceCentroid.fitResidual, candidateCentroid.fitResidual),
                reason == null,
                reason
            )
        }
        val aggregation = aggregate(matches.filter { it.accepted })
        val inlierKeys = aggregation.inliers.map { patchKey(it.patch) }.toSet()
        diagnostics.indices.forEach { index ->
            val value = diagnostics[index]
            if (value.accepted && patchKey(value.x, value.y) !in inlierKeys) {
                diagnostics[index] = value.copy(accepted = false, rejectionReason = "centroid_mad_outlier")
            }
        }
        val centroidTransform = ReferenceToSourceTransform(aggregation.dx, aggregation.dy)
        val centroidVerification = verifier.verifyCentroids(
            aggregation.inliers,
            initialTransform,
            znccTransform,
            centroidTransform
        )
        val refined = listOf(
            centroidTransform to centroidVerification.refined,
            initialTransform to centroidVerification.initial,
            znccTransform to centroidVerification.zncc
        ).filter { it.second.validPatchCount > 0 }
            .minWithOrNull(compareBy<Pair<ReferenceToSourceTransform, FullResolutionTransformEvidence>> {
                it.second.centroidResidual
            }.thenByDescending { it.second.score })
            ?.first ?: centroidTransform
        val verification = verifier.verifyCentroids(
            aggregation.inliers,
            initialTransform,
            znccTransform,
            refined
        )
        val availableSectors = selection.selected.map { it.sector }.distinct().size
        val acceptedSectors = aggregation.inliers.map { it.patch.sector }.distinct().size
        val decision = policy.decide(
            initialTransform.dx,
            initialTransform.dy,
            aggregation.dx - initialTransform.dx,
            aggregation.dy - initialTransform.dy,
            aggregation.inliers.size,
            availableSectors,
            acceptedSectors,
            aggregation.medianResidual,
            aggregation.percentile90Residual,
            searchRadius,
            hypot(refined.dx - znccTransform.dx, refined.dy - znccTransform.dy),
            verification
        )
        return StellarCentroidRefinementResult(
            frameId = frameId,
            initialTransform = initialTransform,
            znccTransform = znccTransform,
            refinedTransform = refined,
            correctionDx = aggregation.dx - initialTransform.dx,
            correctionDy = aggregation.dy - initialTransform.dy,
            searchRadius = searchRadius,
            referenceCentroidCount = selection.selected.size,
            referenceCentroidAcceptedCount = referenceAccepted,
            referenceCentroidRejectedCount = selection.selected.size - referenceAccepted,
            attemptedStarCount = diagnostics.size,
            acceptedStarCount = aggregation.inliers.size,
            rejectedStarCount = diagnostics.count { !it.accepted },
            spatialSectorCount = acceptedSectors,
            medianResidual = aggregation.medianResidual,
            percentile90Residual = aggregation.percentile90Residual,
            medianSnr = median(aggregation.inliers.map { minOf(it.reference.snr, it.candidate.snr) }),
            medianFitResidual = median(aggregation.inliers.map {
                maxOf(it.reference.fitResidual, it.candidate.fitResidual)
            }),
            confidence = aggregation.confidence,
            verification = verification,
            centroidPrimaryUsed = true,
            znccSecondaryUsed = true,
            znccFallbackUsed = false,
            accepted = decision.accepted,
            rejectionReason = decision.rejectionReason,
            matches = aggregation.inliers,
            diagnostics = diagnostics.sortedWith(compareBy({ it.y }, { it.x }, { it.rejectionReason.orEmpty() }))
        )
    }

    private fun referenceIdentity(
        frameId: String,
        reference: ArgbPixelSource,
        selection: FullResolutionPatchSelection,
        cancellationCheck: () -> Unit
    ): StellarCentroidRefinementResult {
        val diagnostics = selection.rejected.map { rejected -> diagnostic(rejected.patch, reason = rejected.reason) }
            .toMutableList()
        var acceptedCount = 0
        selection.selected.forEach { patch ->
            cancellationCheck()
            val detection = detector.detect(reference, patch.x, patch.y, REFERENCE_SEARCH_RADIUS)
            val measurement = detection.measurement
            if (measurement == null) diagnostics += diagnostic(
                patch,
                reason = "reference_${detection.rejectionReason}"
            ) else {
                acceptedCount++
                diagnostics += diagnostic(
                    patch,
                    snr = measurement.snr,
                    fitResidual = measurement.fitResidual,
                    accepted = true
                )
            }
        }
        return StellarCentroidRefinementResult(
            frameId,
            ReferenceToSourceTransform.Identity,
            ReferenceToSourceTransform.Identity,
            ReferenceToSourceTransform.Identity,
            0f,
            0f,
            0f,
            selection.selected.size,
            acceptedCount,
            selection.selected.size - acceptedCount,
            diagnostics.size,
            acceptedCount,
            diagnostics.count { !it.accepted },
            selection.selected.filter { patch ->
                diagnostics.any { it.accepted && patchKey(it.x, it.y) == patchKey(patch) }
            }.map { it.sector }.distinct().size,
            0f,
            0f,
            median(diagnostics.filter { it.accepted }.map { it.snr }),
            median(diagnostics.filter { it.accepted }.map { it.fitResidual }),
            1f,
            FullResolutionFrameVerificationResult(
                FullResolutionTransformEvidence.ReferenceIdentity,
                FullResolutionTransformEvidence.ReferenceIdentity,
                FullResolutionTransformEvidence.ReferenceIdentity,
                FullResolutionTransformEvidence.ReferenceIdentity,
                FullResolutionTransformEvidence.ReferenceIdentity,
                1,
                FullResolutionTransformEvidence.ReferenceIdentity
            ),
            true,
            false,
            false,
            true,
            null,
            emptyList(),
            diagnostics
        )
    }

    private fun aggregate(matches: List<StellarCentroidMatch>): Aggregation {
        if (matches.isEmpty()) return Aggregation(
            0f, 0f, emptyList(), Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0f
        )
        val medianX = weightedMedian(matches) { it.dx }
        val medianY = weightedMedian(matches) { it.dy }
        val distances = matches.map { hypot(it.dx - medianX, it.dy - medianY) }
        val medianDistance = median(distances)
        val mad = median(distances.map { abs(it - medianDistance) })
        val outlierLimit = maxOf(MIN_OUTLIER_DISTANCE, medianDistance + MAD_MULTIPLIER * 1.4826f * mad)
        val inliers = matches.filterIndexed { index, _ -> distances[index] <= outlierLimit }
        if (inliers.isEmpty()) return Aggregation(
            0f, 0f, emptyList(), Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0f
        )
        var dx = weightedMedian(inliers) { it.dx }
        var dy = weightedMedian(inliers) { it.dy }
        repeat(IRLS_ITERATIONS) {
            val residuals = inliers.map { hypot(it.dx - dx, it.dy - dy) }
            val scale = maxOf(MIN_HUBER_SCALE, 1.4826f * median(residuals))
            var sumWeight = 0f
            var sumX = 0f
            var sumY = 0f
            inliers.forEachIndexed { index, match ->
                val residual = residuals[index]
                val huber = if (residual <= HUBER_K * scale) 1f else HUBER_K * scale / residual
                val weight = match.weight * huber
                sumWeight += weight
                sumX += weight * match.dx
                sumY += weight * match.dy
            }
            if (sumWeight > 0f) {
                dx = sumX / sumWeight
                dy = sumY / sumWeight
            }
        }
        val residuals = inliers.map { hypot(it.dx - dx, it.dy - dy) }
        val medianResidual = median(residuals)
        val percentile90 = percentile(residuals, 0.90f)
        val sectors = inliers.map { it.patch.sector }.distinct().size
        val confidence = (
            0.35f * median(inliers.map { it.confidence }) +
                0.20f * (median(inliers.map { minOf(it.reference.snr, it.candidate.snr) }) / 18f).coerceIn(0f, 1f) +
                0.20f * (inliers.size / 6f).coerceIn(0f, 1f) +
                0.10f * (sectors / 3f).coerceIn(0f, 1f) +
                0.15f * (1f - medianResidual / StellarCentroidRefinementPolicy.TARGET_P90_RESIDUAL).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return Aggregation(dx, dy, inliers, medianResidual, percentile90, confidence)
    }

    private fun weightedMedian(
        values: List<StellarCentroidMatch>,
        selector: (StellarCentroidMatch) -> Float
    ): Float {
        val sorted = values.sortedWith(
            compareBy<StellarCentroidMatch> { selector(it) }.thenBy { it.patch.y }.thenBy { it.patch.x }
        )
        val total = sorted.sumOf { it.weight.toDouble() }.toFloat()
        var cumulative = 0f
        sorted.forEach { value ->
            cumulative += value.weight
            if (cumulative >= total * 0.5f) return selector(value)
        }
        return selector(sorted.last())
    }

    private fun matchWeight(
        patch: FullResolutionStarPatch,
        reference: StellarCentroidMeasurement,
        candidate: StellarCentroidMeasurement,
        confidence: Float
    ): Float = (
        confidence.coerceIn(0.05f, 1f) *
            (minOf(reference.snr, candidate.snr) / 15f).coerceIn(0.15f, 1f) *
            (1f - maxOf(reference.ellipticity, candidate.ellipticity)).coerceIn(0.10f, 1f) *
            (if (patch.motionCluster == TemporalMotionCluster.COHERENT_MOVING_SKY) 1f else 0.75f)
        ).coerceAtLeast(0.001f)

    private fun diagnostic(
        patch: FullResolutionStarPatch,
        dx: Float = 0f,
        dy: Float = 0f,
        residual: Float = 0f,
        snr: Float = 0f,
        fitResidual: Float = 0f,
        accepted: Boolean = false,
        reason: String? = null
    ) = StellarCentroidMatchDiagnostic(
        patch.x, patch.y, patch.sector, dx, dy, residual, snr, fitResidual, accepted, reason
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
        return sorted[ceil((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun patchKey(patch: FullResolutionStarPatch): Pair<Int, Int> = patchKey(patch.x, patch.y)
    private fun patchKey(x: Float, y: Float): Pair<Int, Int> = (x * 100f).toInt() to (y * 100f).toInt()

    private data class Aggregation(
        val dx: Float,
        val dy: Float,
        val inliers: List<StellarCentroidMatch>,
        val medianResidual: Float,
        val percentile90Residual: Float,
        val confidence: Float
    )

    companion object {
        private const val REFERENCE_SEARCH_RADIUS = 4f
        private const val MIN_OUTLIER_DISTANCE = 0.18f
        private const val MAD_MULTIPLIER = 3f
        private const val MIN_HUBER_SCALE = 0.04f
        private const val HUBER_K = 1.5f
        private const val IRLS_ITERATIONS = 5
        private const val MAX_MATCH_ELLIPTICITY_GROWTH = 0.25f
    }
}
