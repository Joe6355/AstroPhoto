package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.example.astrophoto.processing.jpeg.v2.sampling.TransformedBitmapSampler
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

data class FullResolutionStarPatch(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val localContrast: Float,
    val width: Float,
    val ellipticity: Float,
    val sector: Int,
    val motionCluster: TemporalMotionCluster,
    val skyCoverage: Float = 1f
)

data class StarPatchMatch(
    val correctionDx: Float,
    val correctionDy: Float,
    val score: Float,
    val peakSharpness: Float,
    val confidence: Float,
    val accepted: Boolean,
    val rejectionReason: String?
)

data class DirectPatchComparison(
    val valid: Boolean,
    val score: Float,
    val retained: Boolean,
    val contrastRatio: Float,
    val centroidResidual: Float,
    val widthGrowth: Float,
    val smear: Float,
    val rejectionReason: String? = null
)

/**
 * Matches original-resolution stellar patches with plane-detrended ZNCC. The search is strictly
 * local around the canonical output/reference -> candidate/source transform.
 */
class FullResolutionStarPatchMatcher(
    private val peakEstimator: SubpixelPeakEstimator = SubpixelPeakEstimator(),
    private val patchRadius: Int = DEFAULT_PATCH_RADIUS,
    private val sampler: TransformedBitmapSampler = TransformedBitmapSampler()
) {
    fun match(
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        patch: FullResolutionStarPatch,
        initialTransform: ReferenceToSourceTransform,
        searchRadius: Float,
        cancellationCheck: () -> Unit = {}
    ): StarPatchMatch {
        require(searchRadius in 1f..MAX_SEARCH_RADIUS)
        val mapped = initialTransform.mapOutputToSource(patch.x, patch.y)
        val margin = patchRadius + ceil(searchRadius).toInt() + 2
        if (!hasMargin(reference, patch.x, patch.y, patchRadius + 1) ||
            !hasMargin(candidate, mapped.x, mapped.y, margin)
        ) return rejected("patch_near_invalid_coverage_edge")

        val referencePatch = samplePatch(reference, patch.x, patch.y)
        val referenceQuality = signalMetrics(referencePatch)
        if (referenceQuality.saturatedPixels > MAX_SATURATED_PIXELS) return rejected("saturated_patch")
        if (referenceQuality.contrast < MIN_PATCH_CONTRAST || referenceQuality.energy < MIN_PATCH_ENERGY) {
            return rejected("low_information_patch")
        }
        if (referenceQuality.ellipticity > MAX_PATCH_ELLIPTICITY) return rejected("line_or_building_edge_patch")
        detrend(referencePatch)

        val integerRadius = ceil(searchRadius).toInt()
        val side = integerRadius * 2 + 1
        val scores = FloatArray(side * side) { Float.NEGATIVE_INFINITY }
        var bestIndex = -1
        var bestScore = -Float.MAX_VALUE
        for (gridY in 0 until side) {
            cancellationCheck()
            val correctionY = gridY - integerRadius
            for (gridX in 0 until side) {
                val correctionX = gridX - integerRadius
                val sample = samplePatch(
                    candidate,
                    mapped.x + correctionX,
                    mapped.y + correctionY
                )
                detrend(sample)
                val score = zncc(referencePatch, sample)
                val index = gridY * side + gridX
                scores[index] = score
                if (score > bestScore || (score == bestScore && index < bestIndex)) {
                    bestScore = score
                    bestIndex = index
                }
            }
        }
        if (bestIndex < 0) return rejected("empty_correlation_surface")
        val peak = peakEstimator.estimate(scores, side, side, bestIndex % side, bestIndex / side)
        if (!peak.accepted) return rejected(peak.rejectionReason ?: "invalid_subpixel_peak", peak.score)
        val dx = peak.x - integerRadius
        val dy = peak.y - integerRadius
        if (kotlin.math.abs(dx) > searchRadius || kotlin.math.abs(dy) > searchRadius) {
            return rejected("correction_outside_local_search", peak.score)
        }
        val qualityWeight = (
            0.55f * peak.confidence +
                0.30f * patch.confidence.coerceIn(0f, 1f) +
                0.15f * (referenceQuality.contrast / 0.20f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return StarPatchMatch(dx, dy, peak.score, peak.sharpness, qualityWeight, true, null)
    }

    fun compareAt(
        reference: ArgbPixelSource,
        candidate: ArgbPixelSource,
        patch: FullResolutionStarPatch,
        transform: ReferenceToSourceTransform
    ): DirectPatchComparison {
        val mapped = transform.mapOutputToSource(patch.x, patch.y)
        if (!hasMargin(reference, patch.x, patch.y, patchRadius + 1) ||
            !hasMargin(candidate, mapped.x, mapped.y, patchRadius + 1)
        ) return DirectPatchComparison(false, 0f, false, 0f, Float.POSITIVE_INFINITY, 1f, 1f, "invalid_coverage")
        val referenceRaw = samplePatch(reference, patch.x, patch.y)
        val candidateRaw = samplePatch(candidate, mapped.x, mapped.y)
        val referenceMetrics = signalMetrics(referenceRaw)
        val candidateMetrics = signalMetrics(candidateRaw)
        if (referenceMetrics.energy < MIN_PATCH_ENERGY || candidateMetrics.energy < MIN_PATCH_ENERGY) {
            return DirectPatchComparison(false, 0f, false, 0f, Float.POSITIVE_INFINITY, 1f, 1f, "low_information")
        }
        detrend(referenceRaw)
        detrend(candidateRaw)
        val score = zncc(referenceRaw, candidateRaw)
        val contrastRatio = (candidateMetrics.contrast / referenceMetrics.contrast.coerceAtLeast(0.0001f))
            .coerceIn(0f, 4f)
        val centroidResidual = hypot(
            candidateMetrics.centroidX - referenceMetrics.centroidX,
            candidateMetrics.centroidY - referenceMetrics.centroidY
        )
        val widthGrowth = (
            candidateMetrics.width / referenceMetrics.width.coerceAtLeast(0.1f) - 1f
            ).coerceIn(-1f, 4f)
        val smear = when {
            candidateMetrics.ellipticity >= 0.82f -> 1f
            widthGrowth > 0.45f && candidateMetrics.ellipticity >= 0.65f -> 1f
            else -> 0f
        }
        return DirectPatchComparison(
            valid = score.isFinite(),
            score = score.takeIf(Float::isFinite) ?: 0f,
            retained = score >= MIN_DIRECT_SCORE && contrastRatio >= MIN_RETENTION_CONTRAST_RATIO,
            contrastRatio = contrastRatio,
            centroidResidual = centroidResidual,
            widthGrowth = widthGrowth,
            smear = smear
        )
    }

    private fun samplePatch(source: ArgbPixelSource, centerX: Float, centerY: Float): FloatArray {
        val side = patchRadius * 2 + 1
        val values = FloatArray(side * side)
        var index = 0
        for (y in -patchRadius..patchRadius) for (x in -patchRadius..patchRadius) {
            values[index++] = luminanceAt(source, centerX + x, centerY + y)
        }
        return values
    }

    private fun luminanceAt(source: ArgbPixelSource, x: Float, y: Float): Float {
        return sampler.sampleLuminanceAt(source, x, y) ?: 0f
    }

    private fun detrend(values: FloatArray) {
        val side = patchRadius * 2 + 1
        val mean = values.sum() / values.size
        var numeratorX = 0f
        var numeratorY = 0f
        var denominatorX = 0f
        var denominatorY = 0f
        var index = 0
        for (y in -patchRadius..patchRadius) for (x in -patchRadius..patchRadius) {
            val centered = values[index++] - mean
            numeratorX += centered * x
            numeratorY += centered * y
            denominatorX += x * x.toFloat()
            denominatorY += y * y.toFloat()
        }
        val slopeX = numeratorX / denominatorX.coerceAtLeast(1f)
        val slopeY = numeratorY / denominatorY.coerceAtLeast(1f)
        index = 0
        for (y in -patchRadius..patchRadius) for (x in -patchRadius..patchRadius) {
            values[index] = values[index] - mean - slopeX * x - slopeY * y
            index++
        }
        check(index == side * side)
    }

    private fun zncc(first: FloatArray, second: FloatArray): Float {
        var dot = 0f
        var firstEnergy = 0f
        var secondEnergy = 0f
        for (index in first.indices) {
            dot += first[index] * second[index]
            firstEnergy += first[index] * first[index]
            secondEnergy += second[index] * second[index]
        }
        val denominator = sqrt(firstEnergy * secondEnergy)
        return if (denominator <= 0.000001f) Float.NEGATIVE_INFINITY else dot / denominator
    }

    private fun signalMetrics(values: FloatArray): PatchSignalMetrics {
        val side = patchRadius * 2 + 1
        var borderSum = 0f
        var borderCount = 0
        var maximum = 0f
        var saturated = 0
        for (y in 0 until side) for (x in 0 until side) {
            val value = values[y * side + x]
            maximum = maxOf(maximum, value)
            if (value >= SATURATION_LUMINANCE) saturated++
            if (x == 0 || y == 0 || x == side - 1 || y == side - 1) {
                borderSum += value
                borderCount++
            }
        }
        val background = borderSum / borderCount.coerceAtLeast(1)
        var energy = 0f
        var weightedX = 0f
        var weightedY = 0f
        for (y in 0 until side) for (x in 0 until side) {
            val signal = (values[y * side + x] - background).coerceAtLeast(0f)
            energy += signal
            weightedX += signal * (x - patchRadius)
            weightedY += signal * (y - patchRadius)
        }
        val centroidX = weightedX / energy.coerceAtLeast(0.000001f)
        val centroidY = weightedY / energy.coerceAtLeast(0.000001f)
        var xx = 0f
        var yy = 0f
        var xy = 0f
        for (y in 0 until side) for (x in 0 until side) {
            val signal = (values[y * side + x] - background).coerceAtLeast(0f)
            val dx = x - patchRadius - centroidX
            val dy = y - patchRadius - centroidY
            xx += signal * dx * dx
            yy += signal * dy * dy
            xy += signal * dx * dy
        }
        xx /= energy.coerceAtLeast(0.000001f)
        yy /= energy.coerceAtLeast(0.000001f)
        xy /= energy.coerceAtLeast(0.000001f)
        val trace = xx + yy
        val determinantTerm = sqrt(((xx - yy) * (xx - yy) + 4f * xy * xy).coerceAtLeast(0f))
        val major = sqrt(((trace + determinantTerm) * 0.5f).coerceAtLeast(0f))
        val minor = sqrt(((trace - determinantTerm) * 0.5f).coerceAtLeast(0f))
        return PatchSignalMetrics(
            contrast = (maximum - background).coerceAtLeast(0f),
            energy = energy,
            centroidX = centroidX,
            centroidY = centroidY,
            width = sqrt((xx + yy).coerceAtLeast(0f) * 0.5f),
            ellipticity = if (major <= 0.0001f) 1f else (1f - minor / major).coerceIn(0f, 1f),
            saturatedPixels = saturated
        )
    }

    private fun hasMargin(source: ArgbPixelSource, x: Float, y: Float, margin: Int): Boolean =
        x >= margin && y >= margin && x <= source.width - 1 - margin && y <= source.height - 1 - margin

    private fun rejected(reason: String, score: Float = 0f) =
        StarPatchMatch(0f, 0f, score, 0f, 0f, false, reason)

    private data class PatchSignalMetrics(
        val contrast: Float,
        val energy: Float,
        val centroidX: Float,
        val centroidY: Float,
        val width: Float,
        val ellipticity: Float,
        val saturatedPixels: Int
    )

    companion object {
        private const val DEFAULT_PATCH_RADIUS = 5
        private const val MAX_SEARCH_RADIUS = 5f
        private const val MIN_PATCH_CONTRAST = 0.015f
        private const val MIN_PATCH_ENERGY = 0.04f
        private const val SATURATION_LUMINANCE = 0.992f
        private const val MAX_SATURATED_PIXELS = 2
        private const val MAX_PATCH_ELLIPTICITY = 0.88f
        private const val MIN_DIRECT_SCORE = 0.28f
        private const val MIN_RETENTION_CONTRAST_RATIO = 0.55f
    }
}
