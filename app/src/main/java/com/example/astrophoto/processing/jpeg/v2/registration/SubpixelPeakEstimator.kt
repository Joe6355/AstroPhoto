package com.example.astrophoto.processing.jpeg.v2.registration

import kotlin.math.abs

data class SubpixelPeak(
    val x: Float,
    val y: Float,
    val score: Float,
    val sharpness: Float,
    val confidence: Float,
    val accepted: Boolean,
    val rejectionReason: String?
)

/** Deterministic separable quadratic fit around a bounded correlation maximum. */
class SubpixelPeakEstimator {
    fun estimate(
        scores: FloatArray,
        width: Int,
        height: Int,
        peakX: Int,
        peakY: Int
    ): SubpixelPeak {
        require(width >= 3 && height >= 3 && scores.size == width * height)
        require(peakX in 0 until width && peakY in 0 until height)
        val center = scores[peakY * width + peakX]
        if (!center.isFinite()) return rejected(peakX, peakY, "non_finite_correlation")
        if (peakX == 0 || peakY == 0 || peakX == width - 1 || peakY == height - 1) {
            return rejected(peakX, peakY, "edge_clipped_peak", center)
        }

        var finiteCount = 0
        var mean = 0f
        scores.forEach { value ->
            if (value.isFinite()) {
                mean += value
                finiteCount++
            }
        }
        if (finiteCount < 9) return rejected(peakX, peakY, "insufficient_correlation_surface", center)
        mean /= finiteCount
        var variance = 0f
        scores.forEach { value -> if (value.isFinite()) variance += (value - mean) * (value - mean) }
        variance /= finiteCount
        if (variance < MIN_SURFACE_VARIANCE || center < MIN_PEAK_SCORE) {
            return rejected(peakX, peakY, "flat_or_low_correlation_surface", center)
        }

        var secondBest = -Float.MAX_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (abs(x - peakX) <= 1 && abs(y - peakY) <= 1) continue
            val value = scores[y * width + x]
            if (value.isFinite() && value > secondBest) secondBest = value
        }
        val peakSeparation = if (secondBest == -Float.MAX_VALUE) 1f else center - secondBest
        if (peakSeparation < MIN_PEAK_SEPARATION) {
            return rejected(peakX, peakY, "ambiguous_multi_peak_surface", center)
        }

        val left = scores[peakY * width + peakX - 1]
        val right = scores[peakY * width + peakX + 1]
        val top = scores[(peakY - 1) * width + peakX]
        val bottom = scores[(peakY + 1) * width + peakX]
        if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !bottom.isFinite()) {
            return rejected(peakX, peakY, "invalid_peak_neighborhood", center)
        }
        val curvatureX = left - 2f * center + right
        val curvatureY = top - 2f * center + bottom
        if (curvatureX >= -MIN_CURVATURE || curvatureY >= -MIN_CURVATURE) {
            return rejected(peakX, peakY, "non_concave_peak", center)
        }
        val offsetX = (0.5f * (left - right) / curvatureX).coerceIn(-MAX_SUBPIXEL, MAX_SUBPIXEL)
        val offsetY = (0.5f * (top - bottom) / curvatureY).coerceIn(-MAX_SUBPIXEL, MAX_SUBPIXEL)
        val sharpness = ((-curvatureX - curvatureY) * 0.25f).coerceAtLeast(0f)
        val confidence = (
            0.55f * ((center - MIN_PEAK_SCORE) / (1f - MIN_PEAK_SCORE)).coerceIn(0f, 1f) +
                0.25f * (peakSeparation / 0.15f).coerceIn(0f, 1f) +
                0.20f * (sharpness / 0.08f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return SubpixelPeak(
            x = peakX + offsetX,
            y = peakY + offsetY,
            score = center,
            sharpness = sharpness,
            confidence = confidence,
            accepted = true,
            rejectionReason = null
        )
    }

    private fun rejected(
        x: Int,
        y: Int,
        reason: String,
        score: Float = 0f
    ) = SubpixelPeak(x.toFloat(), y.toFloat(), score, 0f, 0f, false, reason)

    companion object {
        private const val MIN_PEAK_SCORE = 0.30f
        private const val MIN_SURFACE_VARIANCE = 0.00002f
        private const val MIN_PEAK_SEPARATION = 0.012f
        private const val MIN_CURVATURE = 0.0005f
        private const val MAX_SUBPIXEL = 0.75f
    }
}
