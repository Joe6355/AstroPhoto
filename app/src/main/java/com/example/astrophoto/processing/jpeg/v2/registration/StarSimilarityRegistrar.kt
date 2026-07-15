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

class StarSimilarityRegistrar {
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
        private const val MIN_PAIR_DISTANCE = 5f
        private const val MAX_HYPOTHESIS_STARS = 22
        private const val MAX_EVALUATION_STARS = 48
    }
}
