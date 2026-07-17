package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ModelGuidedRegistrationResult(
    val transform: ReferenceToSourceTransform,
    val predictedDx: Float,
    val predictedDy: Float,
    val correctionDx: Float,
    val correctionDy: Float,
    val matchedStars: Int,
    val inlierStars: Int,
    val residual: Float,
    val confidence: Float,
    val searchRadius: Float,
    val occupiedSectors: Int,
    val movingTrackSupport: Int,
    val stationaryTrackSupport: Int,
    val usedSparseSeed: Boolean,
    val usedSequencePriorOnly: Boolean,
    val retryUsed: Boolean,
    val rejectionReason: String?
)

/** Bounded translation refinement centered on an already fitted sequence prediction. */
class ModelGuidedLocalRegistrar {
    fun register(
        reference: TemporalFeatureFrame,
        candidate: TemporalFeatureFrame,
        predicted: ReferenceToSourceTransform,
        imageWidth: Int,
        imageHeight: Int,
        modelResidual: Float,
        modelConfidence: Float,
        tracks: TemporalTrackAnalysis,
        sparseSeed: TranslationHypothesis?,
        retry: Boolean
    ): ModelGuidedRegistrationResult {
        require(imageWidth > 0 && imageHeight > 0)
        val radius = searchRadius(modelResidual, modelConfidence, retry)
        val references = ranked(reference.stars, MAX_REFERENCE_STARS)
        val candidates = ranked(candidate.stars, MAX_CANDIDATE_STARS)
        if (references.size < MIN_CORRESPONDENCES || candidates.size < MIN_CORRESPONDENCES) {
            return rejected(
                predicted,
                radius,
                sparseSeed,
                retry,
                "Insufficient stars for model-guided registration"
            )
        }

        val grid = CandidateGrid(candidates, radius)
        val potential = buildList {
            references.forEachIndexed { referenceIndex, referenceStar ->
                val expected = predicted.mapOutputToSource(referenceStar.x, referenceStar.y)
                grid.nearby(expected.x, expected.y, radius).forEach { indexedCandidate ->
                    val candidateStar = indexedCandidate.star
                    val distance = hypot(candidateStar.x - expected.x, candidateStar.y - expected.y)
                    if (distance > radius || !compatible(referenceStar, candidateStar)) return@forEach
                    val referenceCluster = tracks.clusterAt(reference.frameId, referenceStar)
                    val candidateCluster = tracks.clusterAt(candidate.frameId, candidateStar)
                    val stationaryPenalty = if (
                        referenceCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE ||
                        candidateCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE
                    ) STATIONARY_PAIR_PENALTY else 0f
                    val movingBonus = if (
                        referenceCluster == TemporalMotionCluster.COHERENT_MOVING_SKY ||
                        candidateCluster == TemporalMotionCluster.COHERENT_MOVING_SKY
                    ) MOVING_PAIR_BONUS else 0f
                    add(
                        PotentialPair(
                            referenceIndex = referenceIndex,
                            candidateIndex = indexedCandidate.index,
                            reference = referenceStar,
                            candidate = candidateStar,
                            score = distance / radius + compatibilityPenalty(referenceStar, candidateStar) +
                                stationaryPenalty - movingBonus,
                            distanceFromPrediction = distance,
                            referenceCluster = referenceCluster,
                            candidateCluster = candidateCluster
                        )
                    )
                }
            }
        }.sortedWith(
            compareBy<PotentialPair> { it.score }
                .thenBy { it.distanceFromPrediction }
                .thenBy { it.reference.y }
                .thenBy { it.reference.x }
                .thenBy { it.candidate.y }
                .thenBy { it.candidate.x }
        )
        val movingPotential = potential.filter { pair ->
            pair.referenceCluster == TemporalMotionCluster.COHERENT_MOVING_SKY &&
                pair.candidateCluster == TemporalMotionCluster.COHERENT_MOVING_SKY
        }
        val eligiblePotential = if (
            tracks.motionObservable && movingPotential.size >= MIN_CORRESPONDENCES
        ) movingPotential else potential
        val usedReferences = BooleanArray(references.size)
        val usedCandidates = BooleanArray(candidates.size)
        val oneToOne = eligiblePotential.filter { pair ->
            if (usedReferences[pair.referenceIndex] || usedCandidates[pair.candidateIndex]) {
                false
            } else {
                usedReferences[pair.referenceIndex] = true
                usedCandidates[pair.candidateIndex] = true
                true
            }
        }
        if (oneToOne.size < MIN_CORRESPONDENCES) {
            return rejected(
                predicted,
                radius,
                sparseSeed,
                retry,
                "Insufficient local correspondences near sequence prediction",
                matchedStars = oneToOne.size
            )
        }

        val refined = robustCorrection(oneToOne, predicted)
        val selected = ReferenceToSourceTransform(
            dx = predicted.dx + refined.dx,
            dy = predicted.dy + refined.dy
        )
        val correctionMagnitude = hypot(refined.dx, refined.dy)
        val maximumCorrection = min(MAXIMUM_CORRECTION, radius * MAXIMUM_CORRECTION_RADIUS_FRACTION)
        val selectedMagnitude = hypot(selected.dx, selected.dy)
        val predictedMagnitude = hypot(predicted.dx, predicted.dy)
        val occupiedSectors = refined.inliers.map {
            sector(it.reference, imageWidth, imageHeight)
        }.distinct().size
        val movingSupport = refined.inliers.count { pair ->
            pair.referenceCluster == TemporalMotionCluster.COHERENT_MOVING_SKY ||
                pair.candidateCluster == TemporalMotionCluster.COHERENT_MOVING_SKY
        }
        val stationarySupport = refined.inliers.count { pair ->
            pair.referenceCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE ||
                pair.candidateCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE
        }
        val evidenceScore = (refined.inliers.size / TARGET_LOCAL_INLIERS.toFloat()).coerceIn(0f, 1f)
        val residualScore = (1f - refined.residual / MAX_LOCAL_RESIDUAL).coerceIn(0f, 1f)
        val movingScore = (movingSupport / TARGET_MOVING_SUPPORT.toFloat()).coerceIn(0f, 1f)
        val confidence = (
            evidenceScore * 0.35f +
                residualScore * 0.25f +
                modelConfidence.coerceIn(0f, 1f) * 0.25f +
                movingScore * 0.15f
            ).coerceIn(0f, 1f)
        val rejection = when {
            refined.inliers.size < MIN_CORRESPONDENCES -> "Insufficient robust local inliers"
            correctionMagnitude > maximumCorrection -> "Local correction exceeds model uncertainty"
            predictedMagnitude >= ZERO_MODE_GUARD_MINIMUM_PREDICTION &&
                selectedMagnitude < ZERO_MODE_GUARD_MINIMUM_SELECTED ->
                "Local correction attempted to jump to zero mode"
            refined.inliers.size >= MIN_DISTRIBUTION_EVIDENCE && occupiedSectors < MIN_SECTORS ->
                "Model-guided stars lack spatial distribution"
            refined.residual > MAX_LOCAL_RESIDUAL -> "Model-guided residual is too high"
            confidence < MIN_LOCAL_CONFIDENCE -> "Model-guided confidence is too low"
            else -> null
        }
        return ModelGuidedRegistrationResult(
            transform = if (rejection == null) selected else predicted,
            predictedDx = predicted.dx,
            predictedDy = predicted.dy,
            correctionDx = refined.dx,
            correctionDy = refined.dy,
            matchedStars = oneToOne.size,
            inlierStars = refined.inliers.size,
            residual = refined.residual,
            confidence = confidence,
            searchRadius = radius,
            occupiedSectors = occupiedSectors,
            movingTrackSupport = movingSupport,
            stationaryTrackSupport = stationarySupport,
            usedSparseSeed = sparseSeed != null,
            usedSequencePriorOnly = sparseSeed == null,
            retryUsed = retry,
            rejectionReason = rejection
        )
    }

    fun searchRadius(modelResidual: Float, modelConfidence: Float, retry: Boolean): Float {
        val safeResidual = modelResidual.takeIf { it.isFinite() }?.coerceAtLeast(0f)
            ?: MAX_MODEL_RESIDUAL_CONTRIBUTION
        val base = MIN_SEARCH_RADIUS +
            min(safeResidual, MAX_MODEL_RESIDUAL_CONTRIBUTION) * RESIDUAL_RADIUS_SCALE +
            (1f - modelConfidence.coerceIn(0f, 1f)) * UNCERTAINTY_RADIUS_SCALE
        return if (retry) {
            (base * RETRY_RADIUS_MULTIPLIER).coerceIn(MIN_SEARCH_RADIUS, MAX_RETRY_RADIUS)
        } else {
            base.coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)
        }
    }

    private fun robustCorrection(
        pairs: List<PotentialPair>,
        predicted: ReferenceToSourceTransform
    ): RefinedCorrection {
        var correctionX = median(pairs.map { it.candidate.x - it.reference.x - predicted.dx })
        var correctionY = median(pairs.map { it.candidate.y - it.reference.y - predicted.dy })
        repeat(REFINEMENT_ITERATIONS) {
            var weightedX = 0f
            var weightedY = 0f
            var totalWeight = 0f
            pairs.forEach { pair ->
                val dx = pair.candidate.x - pair.reference.x - predicted.dx
                val dy = pair.candidate.y - pair.reference.y - predicted.dy
                val error = hypot(dx - correctionX, dy - correctionY)
                val robustWeight = if (error <= HUBER_SCALE) 1f else HUBER_SCALE / error
                val featureWeight = pair.candidate.confidence.coerceIn(0.15f, 1f) *
                    pair.reference.confidence.coerceIn(0.15f, 1f)
                val stationaryWeight = if (
                    pair.referenceCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE ||
                    pair.candidateCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE
                ) STATIONARY_REFINEMENT_WEIGHT else 1f
                val weight = robustWeight * featureWeight * stationaryWeight
                weightedX += dx * weight
                weightedY += dy * weight
                totalWeight += weight
            }
            if (totalWeight > 0f) {
                correctionX = weightedX / totalWeight
                correctionY = weightedY / totalWeight
            }
        }
        val errors = pairs.map { pair ->
            hypot(
                pair.candidate.x - pair.reference.x - predicted.dx - correctionX,
                pair.candidate.y - pair.reference.y - predicted.dy - correctionY
            )
        }
        val mad = median(errors.map { abs(it - median(errors)) })
        val inlierLimit = (BASE_INLIER_RADIUS + mad * MAD_INLIER_SCALE)
            .coerceIn(MIN_INLIER_RADIUS, MAX_INLIER_RADIUS)
        val inliers = pairs.filterIndexed { index, _ -> errors[index] <= inlierLimit }
        if (inliers.isNotEmpty()) {
            correctionX = median(inliers.map { it.candidate.x - it.reference.x - predicted.dx })
            correctionY = median(inliers.map { it.candidate.y - it.reference.y - predicted.dy })
        }
        val residual = if (inliers.isEmpty()) Float.POSITIVE_INFINITY else {
            median(inliers.map {
                hypot(
                    it.candidate.x - it.reference.x - predicted.dx - correctionX,
                    it.candidate.y - it.reference.y - predicted.dy - correctionY
                )
            })
        }
        return RefinedCorrection(correctionX, correctionY, residual, inliers)
    }

    private fun compatible(reference: DetectedStar, candidate: DetectedStar): Boolean {
        val minimumWidth = min(reference.width, candidate.width).coerceAtLeast(MIN_FEATURE_VALUE)
        val widthRatio = max(reference.width, candidate.width) / minimumWidth
        return widthRatio <= MAX_WIDTH_RATIO &&
            abs(reference.ellipticity - candidate.ellipticity) <= MAX_ELLIPTICITY_DIFFERENCE
    }

    private fun compatibilityPenalty(reference: DetectedStar, candidate: DetectedStar): Float {
        val contrastScale = max(reference.localContrast, candidate.localContrast)
            .coerceAtLeast(MIN_FEATURE_VALUE)
        val widthScale = max(reference.width, candidate.width).coerceAtLeast(MIN_FEATURE_VALUE)
        return abs(reference.confidence - candidate.confidence) * 0.20f +
            abs(reference.localContrast - candidate.localContrast) / contrastScale * 0.18f +
            abs(reference.width - candidate.width) / widthScale * 0.18f +
            abs(reference.ellipticity - candidate.ellipticity) * 0.12f
    }

    private fun ranked(stars: List<DetectedStar>, limit: Int): List<DetectedStar> = stars
        .filter { it.x.isFinite() && it.y.isFinite() && it.confidence > 0f }
        .sortedWith(
            compareByDescending<DetectedStar> { it.confidence }
                .thenByDescending { it.localContrast }
                .thenByDescending { it.flux }
                .thenBy { it.y }
                .thenBy { it.x }
        ).take(limit)

    private fun sector(star: DetectedStar, width: Int, height: Int): Int {
        val x = (star.x / width * 3f).toInt().coerceIn(0, 2)
        val y = (star.y / height * 3f).toInt().coerceIn(0, 2)
        return y * 3 + x
    }

    private fun rejected(
        predicted: ReferenceToSourceTransform,
        radius: Float,
        sparseSeed: TranslationHypothesis?,
        retry: Boolean,
        reason: String,
        matchedStars: Int = 0
    ) = ModelGuidedRegistrationResult(
        transform = predicted,
        predictedDx = predicted.dx,
        predictedDy = predicted.dy,
        correctionDx = 0f,
        correctionDy = 0f,
        matchedStars = matchedStars,
        inlierStars = 0,
        residual = Float.POSITIVE_INFINITY,
        confidence = 0f,
        searchRadius = radius,
        occupiedSectors = 0,
        movingTrackSupport = 0,
        stationaryTrackSupport = 0,
        usedSparseSeed = sparseSeed != null,
        usedSequencePriorOnly = sparseSeed == null,
        retryUsed = retry,
        rejectionReason = reason
    )

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private data class PotentialPair(
        val referenceIndex: Int,
        val candidateIndex: Int,
        val reference: DetectedStar,
        val candidate: DetectedStar,
        val score: Float,
        val distanceFromPrediction: Float,
        val referenceCluster: TemporalMotionCluster,
        val candidateCluster: TemporalMotionCluster
    )

    private data class RefinedCorrection(
        val dx: Float,
        val dy: Float,
        val residual: Float,
        val inliers: List<PotentialPair>
    )

    private data class IndexedCandidate(val index: Int, val star: DetectedStar)

    private class CandidateGrid(stars: List<DetectedStar>, private val cellSize: Float) {
        private val cells = mutableMapOf<Long, MutableList<IndexedCandidate>>()

        init {
            stars.forEachIndexed { index, star ->
                cells.getOrPut(key(cell(star.x), cell(star.y))) { mutableListOf() }
                    .add(IndexedCandidate(index, star))
            }
        }

        fun nearby(x: Float, y: Float, radius: Float): Sequence<IndexedCandidate> = sequence {
            val minimumX = cell(x - radius)
            val maximumX = cell(x + radius)
            val minimumY = cell(y - radius)
            val maximumY = cell(y + radius)
            for (cellY in minimumY..maximumY) for (cellX in minimumX..maximumX) {
                cells[key(cellX, cellY)]?.forEach { yield(it) }
            }
        }

        private fun cell(value: Float): Int = floor(value / cellSize).toInt()
        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    }

    companion object {
        private const val MAX_REFERENCE_STARS = 96
        private const val MAX_CANDIDATE_STARS = 128
        private const val MIN_CORRESPONDENCES = 2
        private const val MIN_SEARCH_RADIUS = 1.25f
        private const val MAX_SEARCH_RADIUS = 4.5f
        private const val MAX_RETRY_RADIUS = 6f
        private const val MAX_MODEL_RESIDUAL_CONTRIBUTION = 2f
        private const val RESIDUAL_RADIUS_SCALE = 1.5f
        private const val UNCERTAINTY_RADIUS_SCALE = 1.5f
        private const val RETRY_RADIUS_MULTIPLIER = 1.6f
        private const val MAXIMUM_CORRECTION = 3f
        private const val MAXIMUM_CORRECTION_RADIUS_FRACTION = 0.80f
        private const val ZERO_MODE_GUARD_MINIMUM_PREDICTION = 2.5f
        private const val ZERO_MODE_GUARD_MINIMUM_SELECTED = 1.25f
        private const val MAX_LOCAL_RESIDUAL = 1.25f
        private const val MIN_LOCAL_CONFIDENCE = 0.35f
        private const val MIN_DISTRIBUTION_EVIDENCE = 4
        private const val MIN_SECTORS = 2
        private const val TARGET_LOCAL_INLIERS = 4
        private const val TARGET_MOVING_SUPPORT = 3
        private const val STATIONARY_PAIR_PENALTY = 0.8f
        private const val MOVING_PAIR_BONUS = 0.22f
        private const val STATIONARY_REFINEMENT_WEIGHT = 0.25f
        private const val REFINEMENT_ITERATIONS = 3
        private const val HUBER_SCALE = 0.65f
        private const val BASE_INLIER_RADIUS = 0.45f
        private const val MAD_INLIER_SCALE = 2.5f
        private const val MIN_INLIER_RADIUS = 0.55f
        private const val MAX_INLIER_RADIUS = 1.5f
        private const val MIN_FEATURE_VALUE = 0.001f
        private const val MAX_WIDTH_RATIO = 3f
        private const val MAX_ELLIPTICITY_DIFFERENCE = 0.65f
    }
}
