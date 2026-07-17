package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

data class StellarPsfFit(
    val x: Float,
    val y: Float,
    val flux: Float,
    val fwhmX: Float,
    val fwhmY: Float,
    val ellipticity: Float,
    val saturationRatio: Float,
    val residual: Float,
    val confidence: Float
)

/** Bounded moment-based PSF fit; it estimates position only and never modifies image samples. */
class StellarPsfFitter(
    private val fitRadius: Float = DEFAULT_FIT_RADIUS
) {
    fun fit(
        source: ArgbPixelSource,
        peakX: Int,
        peakY: Int,
        background: LocalBackgroundModel
    ): StellarPsfFit? {
        val integerRadius = kotlin.math.ceil(fitRadius).toInt()
        if (peakX - integerRadius < 0 || peakY - integerRadius < 0 ||
            peakX + integerRadius >= source.width || peakY + integerRadius >= source.height
        ) return null
        val samples = mutableListOf<PsfSample>()
        var peakSignal = 0f
        var saturated = 0
        for (dy in -integerRadius..integerRadius) for (dx in -integerRadius..integerRadius) {
            if (hypot(dx.toFloat(), dy.toFloat()) <= fitRadius) {
                val x = peakX + dx
                val y = peakY + dy
                val luminance = luminance(source.argbAt(x, y))
                val signal = (luminance - background.valueAt(x.toFloat(), y.toFloat())).coerceAtLeast(0f)
                peakSignal = maxOf(peakSignal, signal)
                if (luminance >= SATURATION_LUMINANCE) saturated++
                samples += PsfSample(x.toFloat(), y.toFloat(), signal)
            }
        }
        if (samples.isEmpty() || peakSignal < MIN_PEAK_SIGNAL) return null
        val threshold = maxOf(background.noise * NOISE_THRESHOLD, peakSignal * PEAK_FRACTION_THRESHOLD)
        val core = samples.filter { it.signal > threshold }
        if (core.size < MIN_CORE_SAMPLES) return null

        var centroidX = peakX.toFloat()
        var centroidY = peakY.toFloat()
        repeat(CENTROID_ITERATIONS) {
            var weightSum = 0f
            var weightedX = 0f
            var weightedY = 0f
            core.forEach { sample ->
                if (hypot(sample.x - centroidX, sample.y - centroidY) <= fitRadius) {
                    val weight = (sample.signal - threshold).coerceAtLeast(0f).pow(CENTROID_POWER)
                    weightSum += weight
                    weightedX += weight * sample.x
                    weightedY += weight * sample.y
                }
            }
            if (weightSum > MIN_FLUX) {
                centroidX = weightedX / weightSum
                centroidY = weightedY / weightSum
            }
        }
        var flux = 0f
        var xx = 0f
        var yy = 0f
        var xy = 0f
        core.forEach { sample ->
            val weight = (sample.signal - threshold).coerceAtLeast(0f)
            val dx = sample.x - centroidX
            val dy = sample.y - centroidY
            flux += weight
            xx += weight * dx * dx
            yy += weight * dy * dy
            xy += weight * dx * dy
        }
        if (flux <= MIN_FLUX) return null
        xx /= flux
        yy /= flux
        xy /= flux
        val trace = xx + yy
        val root = sqrt(((xx - yy) * (xx - yy) + 4f * xy * xy).coerceAtLeast(0f))
        val majorSigma = sqrt(((trace + root) * 0.5f).coerceAtLeast(0f))
        val minorSigma = sqrt(((trace - root) * 0.5f).coerceAtLeast(0f))
        val fwhmMajor = FWHM_FACTOR * majorSigma
        val fwhmMinor = FWHM_FACTOR * minorSigma
        if (fwhmMinor !in MIN_FWHM..MAX_FWHM || fwhmMajor !in MIN_FWHM..MAX_FWHM) return null
        val ellipticity = if (majorSigma <= 0.0001f) 1f else
            (1f - minorSigma / majorSigma).coerceIn(0f, 1f)

        val sigmaX = sqrt(xx.coerceAtLeast(MIN_SIGMA_SQUARED))
        val sigmaY = sqrt(yy.coerceAtLeast(MIN_SIGMA_SQUARED))
        var residualSum = 0f
        var residualCount = 0
        samples.forEach { sample ->
            if (hypot(sample.x - centroidX, sample.y - centroidY) <= RESIDUAL_RADIUS) {
                val dx = (sample.x - centroidX) / sigmaX
                val dy = (sample.y - centroidY) / sigmaY
                val predicted = peakSignal * exp((-0.5f * (dx * dx + dy * dy)).toDouble()).toFloat()
                residualSum += kotlin.math.abs(sample.signal - predicted) / peakSignal.coerceAtLeast(MIN_PEAK_SIGNAL)
                residualCount++
            }
        }
        val residual = residualSum / residualCount.coerceAtLeast(1)
        val saturationRatio = saturated.toFloat() / samples.size
        val snr = peakSignal / background.noise.coerceAtLeast(1f / 510f)
        val confidence = (
            0.35f * (snr / 18f).coerceIn(0f, 1f) +
                0.25f * (1f - ellipticity / MAX_CONFIDENCE_ELLIPTICITY).coerceIn(0f, 1f) +
                0.25f * (1f - residual / MAX_CONFIDENCE_RESIDUAL).coerceIn(0f, 1f) +
                0.15f * (core.size / 12f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return StellarPsfFit(
            x = centroidX,
            y = centroidY,
            flux = flux,
            fwhmX = FWHM_FACTOR * sigmaX,
            fwhmY = FWHM_FACTOR * sigmaY,
            ellipticity = ellipticity,
            saturationRatio = saturationRatio,
            residual = residual,
            confidence = confidence
        )
    }

    private fun luminance(argb: Int): Float =
        0.2126f * (argb ushr 16 and 0xFF) / 255f +
            0.7152f * (argb ushr 8 and 0xFF) / 255f +
            0.0722f * (argb and 0xFF) / 255f

    private data class PsfSample(val x: Float, val y: Float, val signal: Float)

    companion object {
        private const val DEFAULT_FIT_RADIUS = 5f
        private const val RESIDUAL_RADIUS = 3.5f
        private const val MIN_PEAK_SIGNAL = 0.012f
        private const val NOISE_THRESHOLD = 1.25f
        private const val PEAK_FRACTION_THRESHOLD = 0.015f
        private const val MIN_CORE_SAMPLES = 4
        private const val CENTROID_ITERATIONS = 3
        private const val CENTROID_POWER = 1.35f
        private const val MIN_FLUX = 0.002f
        private const val MIN_SIGMA_SQUARED = 0.16f
        private const val FWHM_FACTOR = 2.35482f
        private const val MIN_FWHM = 0.75f
        private const val MAX_FWHM = 8.5f
        private const val SATURATION_LUMINANCE = 0.992f
        private const val MAX_CONFIDENCE_ELLIPTICITY = 0.75f
        private const val MAX_CONFIDENCE_RESIDUAL = 0.45f
    }
}
