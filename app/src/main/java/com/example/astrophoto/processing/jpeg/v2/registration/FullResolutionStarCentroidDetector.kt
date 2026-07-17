package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

data class StellarCentroidMeasurement(
    val x: Float,
    val y: Float,
    val background: Float,
    val backgroundUncertainty: Float,
    val peak: Float,
    val flux: Float,
    val fwhmX: Float,
    val fwhmY: Float,
    val ellipticity: Float,
    val snr: Float,
    val saturationRatio: Float,
    val fitResidual: Float,
    val confidence: Float
)

data class StellarCentroidDetection(
    val measurement: StellarCentroidMeasurement?,
    val rejectionReason: String?
) {
    val accepted: Boolean get() = measurement != null
}

/** Finds and measures a compact stellar peak near a predicted original-resolution position. */
class FullResolutionStarCentroidDetector(
    private val backgroundEstimator: LocalBackgroundEstimator = LocalBackgroundEstimator(),
    private val psfFitter: StellarPsfFitter = StellarPsfFitter()
) {
    fun detect(
        source: ArgbPixelSource,
        predictedX: Float,
        predictedY: Float,
        searchRadius: Float
    ): StellarCentroidDetection {
        require(searchRadius in 0f..MAX_SEARCH_RADIUS)
        if (!predictedX.isFinite() || !predictedY.isFinite()) return rejected("invalid_prediction")
        val margin = BACKGROUND_RADIUS + ceil(searchRadius).toInt() + 1
        if (predictedX < margin || predictedY < margin ||
            predictedX > source.width - 1 - margin || predictedY > source.height - 1 - margin
        ) return rejected("centroid_near_invalid_coverage_edge")
        val background = backgroundEstimator.estimate(source, predictedX, predictedY)
            ?: return rejected("local_background_unavailable")
        val centerX = predictedX.roundToInt()
        val centerY = predictedY.roundToInt()
        val radius = ceil(searchRadius).toInt()
        val peaks = mutableListOf<PeakCandidate>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (hypot(dx.toFloat(), dy.toFloat()) <= searchRadius + 0.75f) {
                val x = centerX + dx
                val y = centerY + dy
                val score = compactScore(source, x, y, background)
                if (isLocalMaximum(source, x, y, background, score)) {
                    peaks += PeakCandidate(x, y, score)
                }
            }
        }
        val best = peaks.sortedWith(
            compareByDescending<PeakCandidate> { it.score }
                .thenBy { hypot(it.x - predictedX, it.y - predictedY) }
                .thenBy { it.y }.thenBy { it.x }
        ).firstOrNull() ?: return rejected("no_compact_stellar_peak")
        if (best.score < MIN_COMPACT_PEAK) return rejected("low_snr_stellar_peak")
        val competing = peaks.firstOrNull { peak ->
            peak !== best && hypot((peak.x - best.x).toFloat(), (peak.y - best.y).toFloat()) >= 2f &&
                peak.score >= best.score * COMPARABLE_PEAK_RATIO
        }
        if (competing != null) return rejected("multiple_comparable_peaks")
        val fit = psfFitter.fit(source, best.x, best.y, background)
            ?: return rejected("invalid_stellar_psf_width")
        val distance = hypot(fit.x - predictedX, fit.y - predictedY)
        if (distance > searchRadius + MAX_CENTROID_OVERSHOOT) return rejected("centroid_outside_local_search")
        val peakLuminance = luminance(source.argbAt(best.x, best.y))
        val peakSignal = (peakLuminance - background.valueAt(best.x.toFloat(), best.y.toFloat())).coerceAtLeast(0f)
        val snr = peakSignal / background.noise.coerceAtLeast(1f / 510f)
        if (snr < MIN_SNR) return rejected("low_snr_stellar_peak")
        if (fit.saturationRatio > MAX_SATURATION_RATIO) return rejected("saturated_stellar_peak")
        if (fit.ellipticity > MAX_ELLIPTICITY) return rejected("elongated_nonstellar_peak")
        if (fit.residual > MAX_FIT_RESIDUAL) return rejected("stellar_psf_fit_residual_high")
        return StellarCentroidDetection(
            StellarCentroidMeasurement(
                x = fit.x,
                y = fit.y,
                background = background.valueAt(fit.x, fit.y),
                backgroundUncertainty = background.uncertainty,
                peak = peakLuminance,
                flux = fit.flux,
                fwhmX = fit.fwhmX,
                fwhmY = fit.fwhmY,
                ellipticity = fit.ellipticity,
                snr = snr,
                saturationRatio = fit.saturationRatio,
                fitResidual = fit.residual,
                confidence = fit.confidence
            ),
            null
        )
    }

    private fun compactScore(
        source: ArgbPixelSource,
        x: Int,
        y: Int,
        background: LocalBackgroundModel
    ): Float {
        var center = 0f
        var ring = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val signal = (luminance(source.argbAt(x + dx, y + dy)) -
                background.valueAt((x + dx).toFloat(), (y + dy).toFloat())).coerceAtLeast(0f)
            if (dx == 0 && dy == 0) center += signal * 0.40f else {
                center += signal * 0.075f
                ring += signal
            }
        }
        return (center - ring * RING_PENALTY).coerceAtLeast(0f)
    }

    private fun isLocalMaximum(
        source: ArgbPixelSource,
        x: Int,
        y: Int,
        background: LocalBackgroundModel,
        score: Float
    ): Boolean {
        if (score <= 0f) return false
        for (dy in -1..1) for (dx in -1..1) {
            if ((dx != 0 || dy != 0) && compactScore(source, x + dx, y + dy, background) > score) return false
        }
        return true
    }

    private fun luminance(argb: Int): Float =
        0.2126f * (argb ushr 16 and 0xFF) / 255f +
            0.7152f * (argb ushr 8 and 0xFF) / 255f +
            0.0722f * (argb and 0xFF) / 255f

    private fun rejected(reason: String) = StellarCentroidDetection(null, reason)
    private data class PeakCandidate(val x: Int, val y: Int, val score: Float)

    companion object {
        private const val MAX_SEARCH_RADIUS = 6f
        private const val BACKGROUND_RADIUS = 9
        // This is only a cheap pre-filter. The fitted PSF SNR/shape checks below are authoritative.
        private const val MIN_COMPACT_PEAK = 0.0025f
        private const val MIN_SNR = 4.5f
        private const val COMPARABLE_PEAK_RATIO = 0.92f
        private const val RING_PENALTY = 0.0125f
        private const val MAX_CENTROID_OVERSHOOT = 0.75f
        private const val MAX_SATURATION_RATIO = 0.08f
        private const val MAX_ELLIPTICITY = 0.78f
        private const val MAX_FIT_RESIDUAL = 0.45f
    }
}
