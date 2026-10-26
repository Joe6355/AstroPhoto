package com.joe6355.astrophoto.processing.jpeg.v2.masking

import com.joe6355.astrophoto.ArgbPixelImage
import kotlin.math.abs
import kotlin.math.sqrt

/** Local color trends distinguish a smooth sky gradient from a discontinuous object boundary. */
internal class SmoothSkyColorModel private constructor(
    private val mean: FloatArray,
    private val horizontal: FloatArray,
    private val vertical: FloatArray,
    private val horizontalResidual: Float,
    private val verticalResidual: Float,
    private val spatialResidual: Float,
    private val noise: Float
) {
    fun continuesTo(other: SmoothSkyColorModel, dx: Int, dy: Int): Boolean {
        val first = if (dx != 0) horizontal else vertical
        val second = if (dx != 0) other.horizontal else other.vertical
        val firstResidual = if (dx != 0) horizontalResidual else verticalResidual
        val secondResidual = if (dx != 0) other.horizontalResidual else other.verticalResidual
        if (firstResidual > MAX_PROFILE_RESIDUAL || secondResidual > MAX_PROFILE_RESIDUAL ||
            !isSmooth() || !other.isSmooth()
        ) return false
        val direction = (dx + dy).toFloat()
        var seamSquared = 0f
        for (channel in 0..2) {
            val seam = mean[channel] + direction * first[channel] * 0.5f -
                other.mean[channel] + direction * second[channel] * 0.5f
            seamSquared += seam * seam
        }
        return seamSquared <= MAX_SEAM_DISTANCE * MAX_SEAM_DISTANCE
    }

    private fun isSmooth(): Boolean = spatialResidual <= maxOf(MIN_SPATIAL_RESIDUAL, noise * NOISE_HEADROOM)

    companion object {
        private const val MAX_SEAM_DISTANCE = 1.5f
        private const val MAX_PROFILE_RESIDUAL = 1f
        private const val MIN_SPATIAL_RESIDUAL = 1.5f
        private const val NOISE_HEADROOM = 1.8f
        // Median |a-b-c+d| for independent Gaussian noise is 1.349 times its standard deviation.
        private const val DIAGONAL_NOISE_SCALE = 1.349f

        fun create(image: ArgbPixelImage, left: Int, top: Int, right: Int, bottom: Int): SmoothSkyColorModel {
            val width = right - left
            val height = bottom - top
            require(width > 0 && height > 0)
            val columns = FloatArray(width * 3)
            val rows = FloatArray(height * 3)
            val sum = DoubleArray(3)
            val diagonalHistograms = Array(3) { IntArray(511) }
            for (y in top until bottom) for (x in left until right) {
                val color = image.pixels[y * image.width + x]
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    val value = color ushr shift and 255
                    columns[(x - left) * 3 + channel] += value
                    rows[(y - top) * 3 + channel] += value
                    sum[channel] += value
                    if (x + 1 < right && y + 1 < bottom) {
                        val across = image.pixels[y * image.width + x + 1] ushr shift and 255
                        val below = image.pixels[(y + 1) * image.width + x] ushr shift and 255
                        val diagonal = image.pixels[(y + 1) * image.width + x + 1] ushr shift and 255
                        diagonalHistograms[channel][abs(value - across - below + diagonal)]++
                    }
                }
            }
            val mean = FloatArray(3) { (sum[it] / (width.toDouble() * height)).toFloat() }
            fun fit(profiles: FloatArray, count: Int, samplesPerPosition: Int): Pair<FloatArray, Float> {
                val slope = FloatArray(3)
                var denominator = 0.0
                for (i in 0 until count) {
                    val position = (i + 0.5) / count - 0.5
                    denominator += position * position
                    for (channel in 0..2) {
                        slope[channel] += (profiles[i * 3 + channel] / samplesPerPosition * position).toFloat()
                    }
                }
                for (channel in 0..2) slope[channel] = (slope[channel] / denominator.coerceAtLeast(1e-12)).toFloat()
                var squared = 0.0
                for (i in 0 until count) for (channel in 0..2) {
                    val position = (i + 0.5) / count - 0.5
                    val residual = profiles[i * 3 + channel] / samplesPerPosition - mean[channel] - slope[channel] * position
                    squared += residual * residual
                }
                return slope to sqrt(squared / count).toFloat()
            }
            val horizontal = fit(columns, width, height)
            val vertical = fit(rows, height, width)
            var spatialSquared = 0.0
            for (y in top until bottom) for (x in left until right) {
                val color = image.pixels[y * image.width + x]
                val px = (x - left + 0.5) / width - 0.5
                val py = (y - top + 0.5) / height - 0.5
                for (channel in 0..2) {
                    val value = color ushr (16 - channel * 8) and 255
                    val residual = value - mean[channel] - horizontal.first[channel] * px - vertical.first[channel] * py
                    spatialSquared += residual * residual
                }
            }
            val diagonalCount = (width - 1) * (height - 1)
            var noiseSquared = 0f
            for (histogram in diagonalHistograms) {
                var cumulative = 0
                var median = 0
                if (diagonalCount > 0) {
                    for (value in histogram.indices) {
                        cumulative += histogram[value]
                        if (cumulative > (diagonalCount - 1) / 2) {
                            median = value
                            break
                        }
                    }
                }
                val channelNoise = median / DIAGONAL_NOISE_SCALE
                noiseSquared += channelNoise * channelNoise
            }
            return SmoothSkyColorModel(mean, horizontal.first, vertical.first, horizontal.second,
                vertical.second, sqrt(spatialSquared / (width.toDouble() * height)).toFloat(), sqrt(noiseSquared))
        }
    }
}
