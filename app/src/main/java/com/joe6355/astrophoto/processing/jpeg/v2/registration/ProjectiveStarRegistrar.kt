package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.ProjectiveTransform
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

internal data class ProjectiveStarRegistration(
    val transform: ReferenceToSourceTransform,
    val inliers: List<StellarCentroidMatch>,
    val validationMedian: Float,
    val validationP90: Float,
    private val validationSamples: List<Pair<StellarCentroidMatch, Float>>
) {
    fun compareTo(incumbent: ReferenceToSourceTransform): ProjectiveModelComparison {
        // Both models are scored at exactly the same measured positions. Include a star
        // supported by either model so losing an incumbent's good star cannot hide a regression.
        val pairedErrors = validationSamples.map { (match, heldOutError) ->
            val predicted = incumbent.mapOutputToSource(match.reference.x, match.reference.y)
            hypot(predicted.x - match.candidate.x, predicted.y - match.candidate.y) to heldOutError
        }.filter { (before, after) -> before <= 1.10f || after <= 1.10f }
        fun percentile(values: List<Float>, fraction: Float): Float = values.sorted().let {
            if (it.isEmpty()) Float.POSITIVE_INFINITY
            else it[ceil((it.size - 1) * fraction).toInt()]
        }
        val before = pairedErrors.map { it.first }
        val after = pairedErrors.map { it.second }
        return ProjectiveModelComparison(pairedErrors.size,
            percentile(before, 0.5f), percentile(before, 0.9f),
            percentile(after, 0.5f), percentile(after, 0.9f),
            pairedErrors.count { (a, b) -> b <= a + 0.10f }.toFloat() / pairedErrors.size.coerceAtLeast(1))
    }
}

internal data class ProjectiveModelComparison(
    val starCount: Int,
    val primaryMedian: Float,
    val primaryP90: Float,
    val projectiveMedian: Float,
    val projectiveP90: Float,
    val nonRegressedFraction: Float
) {
    // Prefer the simpler, already accepted model unless held-out predictions improve both
    // typical and outer-field error substantially, without sacrificing its well-aligned stars.
    val materiallyImproves: Boolean get() = starCount >= 24 &&
        projectiveMedian <= primaryMedian * 0.75f && primaryMedian - projectiveMedian >= 0.08f &&
        projectiveP90 <= primaryP90 * 0.75f && primaryP90 - projectiveP90 >= 0.15f &&
        projectiveMedian <= StellarCentroidRefinementPolicy.TARGET_MEDIAN_RESIDUAL &&
        projectiveP90 <= StellarCentroidRefinementPolicy.TARGET_P90_RESIDUAL && nonRegressedFraction >= 0.90f
}

/** Bounded spatial motion that a single image-plane rotation cannot represent. */
internal class ProjectiveStarRegistrar {
    fun register(
        matches: List<StellarCentroidMatch>,
        initial: ReferenceToSourceTransform,
        width: Int,
        height: Int,
        searchRadius: Float,
        cancellationCheck: () -> Unit = {}
    ): ProjectiveStarRegistration? {
        val unique = matches.distinctBy { (it.reference.x to it.reference.y) to (it.candidate.x to it.candidate.y) }
        val references = unique.groupingBy { it.reference.x to it.reference.y }.eachCount()
        val candidates = unique.groupingBy { it.candidate.x to it.candidate.y }.eachCount()
        val usable = unique.filter { it.accepted && references[it.reference.x to it.reference.y] == 1 &&
            candidates[it.candidate.x to it.candidate.y] == 1 }.take(48)
        if (usable.size < MIN_MATCHES) return null
        val normalization = maxOf(width, height).toDouble()
        val model = robustFit(usable, normalization, cancellationCheck) ?: return null
        if (!physicallyBounded(model, initial, width, height, searchRadius, usable)) return null
        val errors = errors(model, usable)
        if (!consensusValid(errors)) return null
        val inliers = usable.filterIndexed { index, _ -> errors[index] <= INLIER_DISTANCE }
        val distribution = SpatialStarDistributionValidator().evaluate(inliers.map { it.reference.star() }, width, height)
        if (!distribution.rotationAllowed || distribution.occupiedCells < 6 ||
            distribution.horizontalSpan < 0.5f || distribution.verticalSpan < 0.5f ||
            distribution.concentrationRatio > 0.35f) return null

        // Held-out stars never participate in their fold's fit. A flexible matrix must predict
        // independent positions, not merely interpolate the stars that selected its parameters.
        val validationSamples = mutableListOf<Pair<StellarCentroidMatch, Float>>()
        for (fold in 0 until VALIDATION_FOLDS) {
            cancellationCheck()
            val training = usable.filterIndexed { index, _ -> index % VALIDATION_FOLDS != fold }
            val heldOut = usable.filterIndexed { index, _ -> index % VALIDATION_FOLDS == fold }
            val validation = robustFit(training, normalization, cancellationCheck) ?: return null
            if (!physicallyBounded(validation, initial, width, height, searchRadius, usable)) return null
            validationSamples += heldOut.zip(errors(validation, heldOut))
        }
        val validationErrors = validationSamples.map { it.second }
        if (!consensusValid(validationErrors)) return null
        val validatedMatches = validationErrors.filter { it <= MATCH_DISTANCE }
        val median = percentile(validatedMatches, 0.5f)
        val p90 = percentile(validatedMatches, 0.9f)
        if (median > StellarCentroidRefinementPolicy.TARGET_MEDIAN_RESIDUAL ||
            p90 > StellarCentroidRefinementPolicy.TARGET_P90_RESIDUAL) return null
        val centerJacobian = model.jacobian(width * 0.5, height * 0.5)
        val angle = atan2(centerJacobian[2] - centerJacobian[1], centerJacobian[0] + centerJacobian[3]).toFloat()
        return ProjectiveStarRegistration(ReferenceToSourceTransform(
            model.tx.toFloat(), model.ty.toFloat(), angle, projective = model
        ), inliers, median, p90, validationSamples)
    }

    private fun consensusValid(errors: List<Float>): Boolean {
        val matched = errors.count { it <= MATCH_DISTANCE }
        val inliers = errors.count { it <= INLIER_DISTANCE }
        return matched >= MIN_MATCHES && inliers >= MIN_INLIERS && inliers.toFloat() / matched >= 0.75f
    }

    private fun robustFit(
        matches: List<StellarCentroidMatch>, normalization: Double, cancellationCheck: () -> Unit
    ): ProjectiveTransform? {
        if (matches.size < 4) return null
        var best: ProjectiveTransform? = null
        var bestCount = -1
        var bestCost = Double.POSITIVE_INFINITY
        fun consider(model: ProjectiveTransform?) {
            if (model == null) return
            val residuals = errors(model, matches)
            val count = residuals.count { it <= INLIER_DISTANCE }
            val cost = residuals.sumOf { minOf(it.toDouble(), MATCH_DISTANCE.toDouble()) }
            if (count > bestCount || count == bestCount && cost < bestCost) {
                best = model; bestCount = count; bestCost = cost
            }
        }
        consider(fit(matches, normalization))
        val random = Random(20261018)
        repeat(MAX_HYPOTHESES) {
            cancellationCheck()
            val indices = mutableSetOf<Int>()
            while (indices.size < 4) indices += random.nextInt(matches.size)
            consider(fit(indices.map { matches[it] }, normalization))
        }
        repeat(3) {
            val model = best ?: return null
            val residuals = errors(model, matches)
            consider(fit(matches.filterIndexed { index, _ -> residuals[index] <= INLIER_DISTANCE }, normalization))
        }
        return best
    }

    private fun fit(matches: List<StellarCentroidMatch>, scale: Double): ProjectiveTransform? {
        if (matches.size < 4) return null
        val normal = Array(8) { DoubleArray(9) }
        fun add(row: DoubleArray, value: Double) {
            for (i in 0..7) {
                for (j in 0..7) normal[i][j] += row[i] * row[j]
                normal[i][8] += row[i] * value
            }
        }
        matches.forEach { match ->
            val x = match.reference.x / scale; val y = match.reference.y / scale
            val u = match.candidate.x / scale; val v = match.candidate.y / scale
            add(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
            add(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
        }
        for (column in 0..7) {
            val pivot = (column..7).maxBy { abs(normal[it][column]) }
            if (abs(normal[pivot][column]) < 1e-12) return null
            val swap = normal[column]; normal[column] = normal[pivot]; normal[pivot] = swap
            val divisor = normal[column][column]
            for (j in column..8) normal[column][j] /= divisor
            for (row in 0..7) if (row != column) {
                val factor = normal[row][column]
                for (j in column..8) normal[row][j] -= factor * normal[column][j]
            }
        }
        val p = DoubleArray(8) { normal[it][8] }
        return runCatching { ProjectiveTransform(p[0], p[1], p[2] * scale,
            p[3], p[4], p[5] * scale, p[6] / scale, p[7] / scale) }.getOrNull()
    }

    private fun physicallyBounded(
        model: ProjectiveTransform, initial: ReferenceToSourceTransform,
        width: Int, height: Int, searchRadius: Float, support: List<StellarCentroidMatch>
    ): Boolean {
        // The local detector radius bounds corrections where stars were measured, plus the
        // frame center. A rotating field can move farther at empty corners without invalidating
        // those measurements. Physical deformation is still checked over the entire grid.
        fun withinLocalSearch(x: Float, y: Float): Boolean {
            val before = initial.mapOutputToSource(x, y)
            val after = model.map(x, y)
            return after.x.isFinite() && after.y.isFinite() &&
                abs(after.x - before.x) <= searchRadius && abs(after.y - before.y) <= searchRadius
        }
        if (!withinLocalSearch(width * 0.5f, height * 0.5f) ||
            support.any { !withinLocalSearch(it.reference.x, it.reference.y) }) return false
        for (row in 0..4) for (column in 0..4) {
            val x = (width - 1) * column / 4f; val y = (height - 1) * row / 4f
            val denominator = model.denominator(x.toDouble(), y.toDouble())
            if (denominator !in 0.95..1.05) return false
            val after = model.map(x, y)
            if (!after.x.isFinite() || !after.y.isFinite()) return false
            val j = model.jacobian(x.toDouble(), y.toDouble())
            val determinant = j[0] * j[3] - j[1] * j[2]
            if (determinant <= 0.0) return false
            val trace = j.sumOf { it * it }
            val root = sqrt(maxOf(0.0, trace * trace - 4 * determinant * determinant))
            val major = sqrt((trace + root) * 0.5); val minor = sqrt(maxOf(0.0, (trace - root) * 0.5))
            if (major > 1.01 || minor < 0.99) return false
            val angle = atan2(j[2] - j[1], j[0] + j[3])
            if (abs(angle) > StarSimilarityRegistrar.MAX_AUTOMATIC_ROTATION_RADIANS ||
                initial.rotationRadians != 0f && abs(angle - initial.rotationRadians) > MAX_PRIOR_DISAGREEMENT
            ) return false
        }
        return true
    }

    private fun errors(model: ProjectiveTransform, matches: List<StellarCentroidMatch>): List<Float> =
        matches.map { match ->
            val predicted = model.map(match.reference.x, match.reference.y)
            hypot(predicted.x - match.candidate.x, predicted.y - match.candidate.y)
        }

    private fun StellarCentroidMeasurement.star() = DetectedStar(
        x, y, flux, background, peak - background, (fwhmX + fwhmY) * 0.5f, ellipticity, confidence
    )

    private fun percentile(values: List<Float>, fraction: Float): Float =
        values.sorted().let { it[ceil((it.size - 1) * fraction).toInt().coerceIn(0, it.lastIndex)] }

    companion object {
        private const val MIN_MATCHES = 24
        private const val MIN_INLIERS = 20
        private const val INLIER_DISTANCE = 0.45f
        private const val MATCH_DISTANCE = 1.10f
        private const val VALIDATION_FOLDS = 4
        private const val MAX_HYPOTHESES = 128
        private val MAX_PRIOR_DISAGREEMENT = Math.toRadians(0.20)
    }
}
