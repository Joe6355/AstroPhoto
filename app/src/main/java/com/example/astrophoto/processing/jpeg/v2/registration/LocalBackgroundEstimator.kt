package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import kotlin.math.abs

data class LocalBackgroundModel(
    val originX: Float,
    val originY: Float,
    val intercept: Float,
    val slopeX: Float,
    val slopeY: Float,
    val noise: Float,
    val uncertainty: Float,
    val sampleCount: Int
) {
    fun valueAt(x: Float, y: Float): Float =
        (intercept + slopeX * (x - originX) + slopeY * (y - originY)).coerceIn(0f, 1f)
}

/** Robust local plane from the outer annulus, excluding stellar-core and bright outliers. */
class LocalBackgroundEstimator {
    fun estimate(
        source: ArgbPixelSource,
        centerX: Float,
        centerY: Float,
        innerRadius: Int = DEFAULT_INNER_RADIUS,
        outerRadius: Int = DEFAULT_OUTER_RADIUS
    ): LocalBackgroundModel? {
        require(innerRadius >= 1 && outerRadius > innerRadius)
        val anchorX = centerX.toInt()
        val anchorY = centerY.toInt()
        if (anchorX - outerRadius < 0 || anchorY - outerRadius < 0 ||
            anchorX + outerRadius >= source.width || anchorY + outerRadius >= source.height
        ) return null
        val samples = mutableListOf<BackgroundSample>()
        for (dy in -outerRadius..outerRadius) for (dx in -outerRadius..outerRadius) {
            val radius = maxOf(abs(dx), abs(dy))
            if (radius in innerRadius..outerRadius) {
                samples += BackgroundSample(dx.toFloat(), dy.toFloat(), luminance(source.argbAt(anchorX + dx, anchorY + dy)))
            }
        }
        if (samples.size < MIN_SAMPLES) return null
        val initialMedian = median(samples.map { it.value })
        val initialMad = median(samples.map { abs(it.value - initialMedian) })
        val upperLimit = initialMedian + maxOf(MIN_OUTLIER_MARGIN, OUTLIER_MAD_MULTIPLIER * 1.4826f * initialMad)
        val retained = samples.filter { it.value <= upperLimit }
        if (retained.size < MIN_SAMPLES) return null

        val meanX = retained.sumOf { it.x.toDouble() }.toFloat() / retained.size
        val meanY = retained.sumOf { it.y.toDouble() }.toFloat() / retained.size
        val meanV = retained.sumOf { it.value.toDouble() }.toFloat() / retained.size
        var xx = 0f
        var yy = 0f
        var xy = 0f
        var xv = 0f
        var yv = 0f
        retained.forEach { sample ->
            val x = sample.x - meanX
            val y = sample.y - meanY
            val value = sample.value - meanV
            xx += x * x
            yy += y * y
            xy += x * y
            xv += x * value
            yv += y * value
        }
        val determinant = xx * yy - xy * xy
        val slopeX = if (abs(determinant) < 0.000001f) 0f else (xv * yy - yv * xy) / determinant
        val slopeY = if (abs(determinant) < 0.000001f) 0f else (yv * xx - xv * xy) / determinant
        val intercept = meanV - slopeX * meanX - slopeY * meanY
        val residuals = retained.map { abs(it.value - intercept - slopeX * it.x - slopeY * it.y) }
        val residualMedian = median(residuals)
        val noise = maxOf(MIN_NOISE, 1.4826f * residualMedian)
        return LocalBackgroundModel(
            originX = anchorX.toFloat(),
            originY = anchorY.toFloat(),
            intercept = intercept.coerceIn(0f, 1f),
            slopeX = slopeX.coerceIn(-MAX_GRADIENT, MAX_GRADIENT),
            slopeY = slopeY.coerceIn(-MAX_GRADIENT, MAX_GRADIENT),
            noise = noise,
            uncertainty = noise / kotlin.math.sqrt(retained.size.toFloat()),
            sampleCount = retained.size
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun luminance(argb: Int): Float =
        0.2126f * (argb ushr 16 and 0xFF) / 255f +
            0.7152f * (argb ushr 8 and 0xFF) / 255f +
            0.0722f * (argb and 0xFF) / 255f

    private data class BackgroundSample(val x: Float, val y: Float, val value: Float)

    companion object {
        private const val DEFAULT_INNER_RADIUS = 6
        private const val DEFAULT_OUTER_RADIUS = 9
        private const val MIN_SAMPLES = 48
        private const val MIN_OUTLIER_MARGIN = 0.012f
        private const val OUTLIER_MAD_MULTIPLIER = 3f
        private const val MIN_NOISE = 1f / 510f
        private const val MAX_GRADIENT = 0.04f
    }
}
