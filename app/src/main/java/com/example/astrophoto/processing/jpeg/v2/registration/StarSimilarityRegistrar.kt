package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class StarSimilarityRegistrar(
    private val distributionValidator: SpatialStarDistributionValidator =
        SpatialStarDistributionValidator()
) {
    fun register(
        referenceStars: List<DetectedStar>,
        candidateStars: List<DetectedStar>,
        imageWidth: Int,
        imageHeight: Int
    ): RegistrationResult {
        require(imageWidth > 0 && imageHeight > 0)
        val allReferences = ranked(referenceStars)
        val allCandidates = ranked(candidateStars)
        val references = allReferences.take(MAX_EVALUATION_STARS)
        val candidates = allCandidates.take(MAX_EVALUATION_STARS)
        if (allReferences.size < MIN_DETECTED_STARS || allCandidates.size < MIN_DETECTED_STARS) {
            return RegistrationResult.rejected(
                referenceStars = allReferences.size,
                detectedStars = allCandidates.size,
                reason = "Insufficient detected stars"
            )
        }

        val hypothesisReferences = references.take(MAX_HYPOTHESIS_STARS)
        val hypothesisCandidates = candidates.take(MAX_HYPOTHESIS_STARS)
        val candidatePairs = pairs(hypothesisCandidates)
        val inlierTolerance = (minOf(imageWidth, imageHeight) * 0.0035f).coerceIn(1.25f, 3.5f)
        val matchTolerance = inlierTolerance * 2.2f
        val seen = hashSetOf<TransformKey>()
        var best: Evaluation? = null

        pairs(hypothesisReferences).forEach { referencePair ->
            candidatePairs.forEach { candidatePair ->
                val scale = candidatePair.distance / referencePair.distance
                if (abs(scale - 1f) > MAX_SCALE_DELTA) return@forEach
                val direct = transformFromPairs(
                    referencePair.first,
                    referencePair.second,
                    candidatePair.first,
                    candidatePair.second
                )
                val reversed = transformFromPairs(
                    referencePair.first,
                    referencePair.second,
                    candidatePair.second,
                    candidatePair.first
                )
                listOf(direct, reversed).forEach { transform ->
                    if (!isPlausible(transform, imageWidth, imageHeight)) return@forEach
                    if (!seen.add(transform.key())) return@forEach
                    val evaluation = evaluate(
                        transform,
                        references,
                        candidates,
                        inlierTolerance,
                        matchTolerance
                    )
                    if (evaluation.isBetterThan(best)) best = evaluation
                }
            }
        }

        val initial = best ?: return RegistrationResult.rejected(
            referenceStars = allReferences.size,
            detectedStars = allCandidates.size,
            reason = "No plausible star similarity transform"
        )
        val refinedTransform = refine(initial.inlierPairs) ?: initial.transform
        val refined = if (isPlausible(refinedTransform, imageWidth, imageHeight)) {
            evaluate(refinedTransform, references, candidates, inlierTolerance, matchTolerance)
        } else {
            initial
        }
        val selected = if (refined.isBetterThan(initial)) refined else initial
        val residual = selected.residual
        val coverage = selected.inliers.toFloat() / minOf(references.size, candidates.size)
        val inlierRatio = selected.inliers.toFloat() / selected.matches.coerceAtLeast(1)
        val residualScore = (1f - residual / MAX_RESIDUAL_ERROR).coerceIn(0f, 1f)
        val starQuality = selected.inlierPairs
            .flatMap { listOf(it.first.confidence, it.second.confidence) }
            .average()
            .takeIf { it.isFinite() }
            ?.toFloat()
            ?: 0f
        val confidence = (
            coverage.coerceIn(0f, 1f) * 0.50f +
                inlierRatio.coerceIn(0f, 1f) * 0.20f +
                residualScore * 0.20f +
                starQuality.coerceIn(0f, 1f) * 0.10f
            ).coerceIn(0f, 1f)
        val rejection = when {
            selected.matches < MIN_MATCHED_STARS -> "Insufficient matched stars"
            selected.inliers < MIN_INLIER_STARS -> "Insufficient inlier stars"
            residual > MAX_RESIDUAL_ERROR -> "Residual error is too high"
            coverage < MIN_INLIER_COVERAGE -> "Star match coverage is too low"
            abs(selected.transform.rotation) > MAX_ROTATION_RADIANS -> "Rotation is implausibly large"
            abs(selected.transform.scale - 1f) > MAX_SCALE_DELTA -> "Scale change is implausibly large"
            hypot(selected.transform.dx, selected.transform.dy) > maximumTranslation(imageWidth, imageHeight) ->
                "Translation is implausibly large"
            confidence < MIN_CONFIDENCE -> "Registration confidence is too low"
            else -> null
        }
        return RegistrationResult(
            dx = selected.transform.dx,
            dy = selected.transform.dy,
            rotationRadians = selected.transform.rotation,
            scale = selected.transform.scale,
            detectedStars = allCandidates.size,
            matchedStars = selected.matches,
            inlierStars = selected.inliers,
            residualError = residual,
            confidence = confidence,
            isReliable = rejection == null,
            rejectionReason = rejection,
            referenceStars = allReferences.size
        )
    }

    fun registerAutomatic(
        referenceStars: List<DetectedStar>,
        candidateStars: List<DetectedStar>,
        imageWidth: Int,
        imageHeight: Int,
        forceTranslationOnly: Boolean = false,
        expectedRotationRadians: Float? = null
    ): RegistrationResult {
        require(imageWidth > 0 && imageHeight > 0)
        val allReferences = ranked(referenceStars)
        val allCandidates = ranked(candidateStars)
        if (allReferences.size < MIN_DETECTED_STARS || allCandidates.size < MIN_DETECTED_STARS) {
            return RegistrationResult.rejected(
                allReferences.size,
                allCandidates.size,
                "Insufficient detected stars"
            ).copy(
                registrationModel = "TRANSLATION_ONLY",
                scaleFixed = true,
                rotationRejectionReason = "insufficient_stars_for_rotation"
            )
        }
        val references = allReferences.take(MAX_AUTOMATIC_EVALUATION_STARS)
        val candidates = allCandidates.take(MAX_AUTOMATIC_EVALUATION_STARS)
        val translation = bestTranslation(
            references,
            candidates,
            AUTOMATIC_TRANSLATION_INLIER_TOLERANCE,
            AUTOMATIC_TRANSLATION_MATCH_TOLERANCE
        )
        val translationResult = translation?.let {
            automaticResult(
                evaluation = it,
                allReferenceCount = allReferences.size,
                allCandidateCount = allCandidates.size,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                model = "TRANSLATION_ONLY",
                rotationAllowed = false,
                rotationRejectionReason = if (forceTranslationOnly) {
                    "translation_only_forced"
                } else {
                    "rotation_not_required"
                },
                distribution = null
            )
        } ?: RegistrationResult.rejected(
            allReferences.size,
            allCandidates.size,
            "No reliable translation transform"
        ).copy(
            registrationModel = "TRANSLATION_ONLY",
            scaleFixed = true,
            rotationRejectionReason = "translation_hypothesis_failed"
        )
        if (forceTranslationOnly) return translationResult

        val rigid = bestRigid(
            references,
            candidates,
            AUTOMATIC_ROTATION_INLIER_TOLERANCE,
            AUTOMATIC_ROTATION_MATCH_TOLERANCE
        )
        if (rigid == null) return translationResult.copy(
            rotationRejectionReason = "no_plausible_fixed_scale_rotation"
        )
        val distribution = distributionValidator.evaluate(
            rigid.inlierPairs.map { it.first },
            imageWidth,
            imageHeight
        )
        val inlierRatio = rigid.inliers.toFloat() / rigid.matches.coerceAtLeast(1)
        val rotationRejection = when {
            rigid.matches < MIN_ROTATION_MATCHED_STARS -> "rotation_requires_12_matches"
            rigid.inliers < MIN_ROTATION_INLIERS -> "rotation_requires_10_inliers"
            inlierRatio < MIN_ROTATION_INLIER_RATIO -> "rotation_inlier_ratio_below_0_75"
            !distribution.rotationAllowed -> distribution.rejectionReason
            rigid.residual > MAX_ROTATION_RESIDUAL -> "rotation_residual_above_0_45"
            abs(rigid.transform.rotation) > MAX_AUTOMATIC_ROTATION_RADIANS ->
                "rotation_is_not_physically_plausible"
            expectedRotationRadians != null &&
                abs(normalizeAngle(rigid.transform.rotation - expectedRotationRadians)) >
                MAX_SEQUENCE_ROTATION_DISAGREEMENT -> "rotation_disagrees_with_sequence_model"
            else -> null
        }
        val rotationImprovesFit = !translationResult.isReliable ||
            rigid.residual + MIN_ROTATION_RESIDUAL_IMPROVEMENT < translationResult.residualError
        if (rotationRejection != null || !rotationImprovesFit) {
            return translationResult.copy(
                rotationRejectionReason = rotationRejection ?: "rotation_did_not_materially_improve_fit",
                occupiedDistributionCells = distribution.occupiedCells,
                horizontalDistributionSpan = distribution.horizontalSpan,
                verticalDistributionSpan = distribution.verticalSpan,
                spatialDistributionScore = distribution.score,
                transformRetryUsed = true
            )
        }
        return automaticResult(
            evaluation = rigid,
            allReferenceCount = allReferences.size,
            allCandidateCount = allCandidates.size,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            model = "FIXED_SCALE_RIGID",
            rotationAllowed = true,
            rotationRejectionReason = null,
            distribution = distribution
        )
    }

    private fun bestTranslation(
        references: List<DetectedStar>,
        candidates: List<DetectedStar>,
        inlierTolerance: Float,
        matchTolerance: Float
    ): Evaluation? {
        var best: Evaluation? = null
        references.take(MAX_AUTOMATIC_HYPOTHESIS_STARS).forEach { reference ->
            candidates.take(MAX_AUTOMATIC_HYPOTHESIS_STARS).forEach { candidate ->
                val transform = Transform(candidate.x - reference.x, candidate.y - reference.y, 0f, 1f)
                val evaluated = evaluate(transform, references, candidates, inlierTolerance, matchTolerance)
                if (evaluated.isBetterThan(best)) best = evaluated
            }
        }
        val initial = best ?: return null
        val refined = refineTranslation(initial.inlierPairs)?.let {
            evaluate(it, references, candidates, inlierTolerance, matchTolerance)
        }
        return if (refined != null && refined.isBetterThan(initial)) refined else initial
    }

    private fun bestRigid(
        references: List<DetectedStar>,
        candidates: List<DetectedStar>,
        inlierTolerance: Float,
        matchTolerance: Float
    ): Evaluation? {
        var best: Evaluation? = null
        val referencePairs = pairs(references.take(MAX_AUTOMATIC_HYPOTHESIS_STARS))
        val candidatePairs = pairs(candidates.take(MAX_AUTOMATIC_HYPOTHESIS_STARS))
        referencePairs.forEach { referencePair ->
            candidatePairs.forEach { candidatePair ->
                if (abs(referencePair.distance - candidatePair.distance) > MAX_RIGID_PAIR_DISTANCE_ERROR) {
                    return@forEach
                }
                listOf(
                    rigidTransformFromPairs(
                        referencePair.first,
                        referencePair.second,
                        candidatePair.first,
                        candidatePair.second
                    ),
                    rigidTransformFromPairs(
                        referencePair.first,
                        referencePair.second,
                        candidatePair.second,
                        candidatePair.first
                    )
                ).forEach { transform ->
                    if (abs(transform.rotation) > MAX_AUTOMATIC_ROTATION_RADIANS) return@forEach
                    val evaluated = evaluate(transform, references, candidates, inlierTolerance, matchTolerance)
                    if (evaluated.isBetterThan(best)) best = evaluated
                }
            }
        }
        val initial = best ?: return null
        val refined = refineRigid(initial.inlierPairs)?.let {
            evaluate(it, references, candidates, inlierTolerance, matchTolerance)
        }
        return if (refined != null && refined.isBetterThan(initial)) refined else initial
    }

    private fun refineTranslation(pairs: List<Pair<DetectedStar, DetectedStar>>): Transform? {
        if (pairs.isEmpty()) return null
        val dx = median(pairs.map { it.second.x - it.first.x })
        val dy = median(pairs.map { it.second.y - it.first.y })
        return Transform(dx, dy, 0f, 1f)
    }

    private fun refineRigid(pairs: List<Pair<DetectedStar, DetectedStar>>): Transform? {
        if (pairs.size < 2) return null
        val referenceCenterX = pairs.map { it.first.x }.average().toFloat()
        val referenceCenterY = pairs.map { it.first.y }.average().toFloat()
        val candidateCenterX = pairs.map { it.second.x }.average().toFloat()
        val candidateCenterY = pairs.map { it.second.y }.average().toFloat()
        var dot = 0f
        var cross = 0f
        pairs.forEach { (reference, candidate) ->
            val rx = reference.x - referenceCenterX
            val ry = reference.y - referenceCenterY
            val cx = candidate.x - candidateCenterX
            val cy = candidate.y - candidateCenterY
            dot += rx * cx + ry * cy
            cross += rx * cy - ry * cx
        }
        val rotation = atan2(cross, dot)
        val cosine = cos(rotation)
        val sine = sin(rotation)
        return Transform(
            candidateCenterX - (cosine * referenceCenterX - sine * referenceCenterY),
            candidateCenterY - (sine * referenceCenterX + cosine * referenceCenterY),
            rotation,
            1f
        )
    }

    private fun rigidTransformFromPairs(
        referenceA: DetectedStar,
        referenceB: DetectedStar,
        candidateA: DetectedStar,
        candidateB: DetectedStar
    ): Transform {
        val rotation = normalizeAngle(
            atan2(candidateB.y - candidateA.y, candidateB.x - candidateA.x) -
                atan2(referenceB.y - referenceA.y, referenceB.x - referenceA.x)
        )
        val cosine = cos(rotation)
        val sine = sin(rotation)
        return Transform(
            candidateA.x - (cosine * referenceA.x - sine * referenceA.y),
            candidateA.y - (sine * referenceA.x + cosine * referenceA.y),
            rotation,
            1f
        )
    }

    private fun automaticResult(
        evaluation: Evaluation,
        allReferenceCount: Int,
        allCandidateCount: Int,
        imageWidth: Int,
        imageHeight: Int,
        model: String,
        rotationAllowed: Boolean,
        rotationRejectionReason: String?,
        distribution: SpatialStarDistribution?
    ): RegistrationResult {
        val coverage = evaluation.inliers.toFloat() /
            minOf(allReferenceCount, allCandidateCount).coerceAtLeast(1)
        val inlierRatio = evaluation.inliers.toFloat() / evaluation.matches.coerceAtLeast(1)
        val residualLimit = if (rotationAllowed) MAX_ROTATION_RESIDUAL else MAX_TRANSLATION_RESIDUAL
        val confidence = (
            coverage.coerceIn(0f, 1f) * 0.45f +
                inlierRatio.coerceIn(0f, 1f) * 0.30f +
                (1f - evaluation.residual / residualLimit).coerceIn(0f, 1f) * 0.25f
            ).coerceIn(0f, 1f)
        val rejection = when {
            evaluation.matches < MIN_MATCHED_STARS -> "Insufficient matched stars"
            evaluation.inliers < MIN_INLIER_STARS -> "Insufficient inlier stars"
            evaluation.residual > residualLimit -> "Residual error is too high"
            coverage < MIN_INLIER_COVERAGE -> "Star match coverage is too low"
            hypot(evaluation.transform.dx, evaluation.transform.dy) >
                maximumAutomaticTranslation(imageWidth, imageHeight) -> "Translation is implausibly large"
            confidence < MIN_AUTOMATIC_CONFIDENCE -> "Registration confidence is too low"
            else -> null
        }
        return RegistrationResult(
            dx = evaluation.transform.dx,
            dy = evaluation.transform.dy,
            rotationRadians = if (rotationAllowed) evaluation.transform.rotation else 0f,
            scale = 1f,
            detectedStars = allCandidateCount,
            matchedStars = evaluation.matches,
            inlierStars = evaluation.inliers,
            residualError = evaluation.residual,
            confidence = confidence,
            isReliable = rejection == null,
            rejectionReason = rejection,
            referenceStars = allReferenceCount,
            registrationModel = model,
            scaleFixed = true,
            rotationAllowed = rotationAllowed,
            rotationRejectionReason = rotationRejectionReason,
            occupiedDistributionCells = distribution?.occupiedCells ?: 0,
            horizontalDistributionSpan = distribution?.horizontalSpan ?: 0f,
            verticalDistributionSpan = distribution?.verticalSpan ?: 0f,
            spatialDistributionScore = distribution?.score ?: 0f
        )
    }

    private fun maximumAutomaticTranslation(width: Int, height: Int): Float =
        hypot(width.toFloat(), height.toFloat()) * MAX_AUTOMATIC_TRANSLATION_FRACTION

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private data class Transform(
        val dx: Float,
        val dy: Float,
        val rotation: Float,
        val scale: Float
    ) {
        fun apply(star: DetectedStar): Pair<Float, Float> {
            val cosine = cos(rotation)
            val sine = sin(rotation)
            return (scale * (cosine * star.x - sine * star.y) + dx) to
                (scale * (sine * star.x + cosine * star.y) + dy)
        }

        fun key() = TransformKey(
            (dx * 2f).roundToInt(),
            (dy * 2f).roundToInt(),
            (rotation * 10_000f).roundToInt(),
            (scale * 10_000f).roundToInt()
        )
    }

    private data class TransformKey(val dx: Int, val dy: Int, val rotation: Int, val scale: Int)

    private data class StarPair(
        val first: DetectedStar,
        val second: DetectedStar,
        val distance: Float
    )

    private data class Evaluation(
        val transform: Transform,
        val matches: Int,
        val inliers: Int,
        val residual: Float,
        val inlierPairs: List<Pair<DetectedStar, DetectedStar>>
    ) {
        fun isBetterThan(other: Evaluation?): Boolean {
            if (other == null) return true
            return compareValuesBy(
                this,
                other,
                { -it.inliers },
                { -it.matches },
                { it.residual },
                { abs(it.transform.rotation) },
                { abs(it.transform.scale - 1f) },
                { hypot(it.transform.dx, it.transform.dy) },
                { it.transform.dy },
                { it.transform.dx }
            ) < 0
        }
    }

    private fun ranked(stars: List<DetectedStar>): List<DetectedStar> = stars
        .filter { it.confidence > 0f && it.x.isFinite() && it.y.isFinite() }
        .sortedWith(
            compareByDescending<DetectedStar> { it.confidence }
                .thenByDescending { it.flux }
                .thenBy { it.y }
                .thenBy { it.x }
        )

    private fun pairs(stars: List<DetectedStar>): List<StarPair> {
        val result = mutableListOf<StarPair>()
        for (first in 0 until stars.lastIndex) for (second in first + 1 until stars.size) {
            val distance = hypot(
                stars[second].x - stars[first].x,
                stars[second].y - stars[first].y
            )
            if (distance >= MIN_PAIR_DISTANCE) result += StarPair(stars[first], stars[second], distance)
        }
        return result.sortedWith(
            compareBy<StarPair> { it.distance }
                .thenBy { it.first.y }
                .thenBy { it.first.x }
                .thenBy { it.second.y }
                .thenBy { it.second.x }
        )
    }

    private fun transformFromPairs(
        referenceA: DetectedStar,
        referenceB: DetectedStar,
        candidateA: DetectedStar,
        candidateB: DetectedStar
    ): Transform {
        val referenceDx = referenceB.x - referenceA.x
        val referenceDy = referenceB.y - referenceA.y
        val candidateDx = candidateB.x - candidateA.x
        val candidateDy = candidateB.y - candidateA.y
        val referenceDistance = hypot(referenceDx, referenceDy)
        val candidateDistance = hypot(candidateDx, candidateDy)
        val scale = candidateDistance / referenceDistance
        val rotation = normalizeAngle(atan2(candidateDy, candidateDx) - atan2(referenceDy, referenceDx))
        val cosine = cos(rotation)
        val sine = sin(rotation)
        val dx = candidateA.x - scale * (cosine * referenceA.x - sine * referenceA.y)
        val dy = candidateA.y - scale * (sine * referenceA.x + cosine * referenceA.y)
        return Transform(dx, dy, rotation, scale)
    }

    private fun evaluate(
        transform: Transform,
        references: List<DetectedStar>,
        candidates: List<DetectedStar>,
        inlierTolerance: Float,
        matchTolerance: Float
    ): Evaluation {
        val used = BooleanArray(candidates.size)
        var matches = 0
        var squaredResidual = 0f
        val inlierPairs = mutableListOf<Pair<DetectedStar, DetectedStar>>()
        references.forEach { reference ->
            val expected = transform.apply(reference)
            var bestIndex = -1
            var bestDistance = Float.POSITIVE_INFINITY
            candidates.forEachIndexed { index, candidate ->
                if (used[index]) return@forEachIndexed
                val distance = hypot(candidate.x - expected.first, candidate.y - expected.second)
                if (distance < bestDistance || distance == bestDistance && index < bestIndex) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            if (bestIndex >= 0 && bestDistance <= matchTolerance) {
                used[bestIndex] = true
                matches++
                if (bestDistance <= inlierTolerance) {
                    squaredResidual += bestDistance * bestDistance
                    inlierPairs += reference to candidates[bestIndex]
                }
            }
        }
        val residual = if (inlierPairs.isEmpty()) Float.POSITIVE_INFINITY else
            sqrt(squaredResidual / inlierPairs.size)
        return Evaluation(transform, matches, inlierPairs.size, residual, inlierPairs)
    }

    private fun refine(pairs: List<Pair<DetectedStar, DetectedStar>>): Transform? {
        if (pairs.size < 2) return null
        val referenceCenterX = pairs.map { it.first.x }.average().toFloat()
        val referenceCenterY = pairs.map { it.first.y }.average().toFloat()
        val candidateCenterX = pairs.map { it.second.x }.average().toFloat()
        val candidateCenterY = pairs.map { it.second.y }.average().toFloat()
        var dot = 0f
        var cross = 0f
        var referenceEnergy = 0f
        pairs.forEach { (reference, candidate) ->
            val rx = reference.x - referenceCenterX
            val ry = reference.y - referenceCenterY
            val cx = candidate.x - candidateCenterX
            val cy = candidate.y - candidateCenterY
            dot += rx * cx + ry * cy
            cross += rx * cy - ry * cx
            referenceEnergy += rx * rx + ry * ry
        }
        if (referenceEnergy <= 0f) return null
        val a = dot / referenceEnergy
        val b = cross / referenceEnergy
        val scale = sqrt(a * a + b * b)
        val rotation = atan2(b, a)
        val dx = candidateCenterX - (a * referenceCenterX - b * referenceCenterY)
        val dy = candidateCenterY - (b * referenceCenterX + a * referenceCenterY)
        return Transform(dx, dy, rotation, scale)
    }

    private fun isPlausible(transform: Transform, width: Int, height: Int): Boolean =
        abs(transform.rotation) <= MAX_ROTATION_RADIANS &&
            abs(transform.scale - 1f) <= MAX_SCALE_DELTA &&
            hypot(transform.dx, transform.dy) <= maximumTranslation(width, height)

    private fun maximumTranslation(width: Int, height: Int): Float = hypot(width.toFloat(), height.toFloat()) * 0.28f

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > PI) normalized -= (2.0 * PI).toFloat()
        while (normalized < -PI) normalized += (2.0 * PI).toFloat()
        return normalized
    }

    companion object {
        const val MIN_DETECTED_STARS = 4
        const val MIN_MATCHED_STARS = 4
        const val MIN_INLIER_STARS = 4
        const val MAX_RESIDUAL_ERROR = 2.25f
        const val MIN_CONFIDENCE = 0.42f
        const val MIN_INLIER_COVERAGE = 0.32f
        const val MAX_SCALE_DELTA = 0.035f
        val MAX_ROTATION_RADIANS: Float = Math.toRadians(6.0).toFloat()
        const val MAX_TRANSLATION_RESIDUAL = 0.75f
        const val MAX_ROTATION_RESIDUAL = 0.45f
        const val MIN_ROTATION_MATCHED_STARS = 12
        const val MIN_ROTATION_INLIERS = 10
        const val MIN_ROTATION_INLIER_RATIO = 0.75f
        val MAX_AUTOMATIC_ROTATION_RADIANS: Float = Math.toRadians(2.0).toFloat()
        private const val MIN_PAIR_DISTANCE = 5f
        private const val MAX_HYPOTHESIS_STARS = 22
        private const val MAX_EVALUATION_STARS = 48
        private const val MAX_AUTOMATIC_HYPOTHESIS_STARS = 24
        private const val MAX_AUTOMATIC_EVALUATION_STARS = 64
        private const val AUTOMATIC_TRANSLATION_INLIER_TOLERANCE = 0.75f
        private const val AUTOMATIC_TRANSLATION_MATCH_TOLERANCE = 1.35f
        private const val AUTOMATIC_ROTATION_INLIER_TOLERANCE = 0.45f
        private const val AUTOMATIC_ROTATION_MATCH_TOLERANCE = 1.10f
        private const val MAX_RIGID_PAIR_DISTANCE_ERROR = 1.5f
        private const val MIN_ROTATION_RESIDUAL_IMPROVEMENT = 0.05f
        private val MAX_SEQUENCE_ROTATION_DISAGREEMENT = Math.toRadians(0.20).toFloat()
        private const val MAX_AUTOMATIC_TRANSLATION_FRACTION = 0.18f
        private const val MIN_AUTOMATIC_CONFIDENCE = 0.48f
    }
}
