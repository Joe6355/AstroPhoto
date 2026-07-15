package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.BandingMetrics
import com.example.astrophoto.processing.jpeg.v2.postprocessing.histogramBin
import com.example.astrophoto.processing.jpeg.v2.postprocessing.histogramPercentile
import kotlin.math.sqrt

class BandingEstimator {
    fun estimate(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        skyMad: Float
    ): BandingMetrics {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val rowHistograms = IntArray(image.height * BINS)
        val columnHistograms = IntArray(image.width * BINS)
        val rowCounts = IntArray(image.height)
        val columnCounts = IntArray(image.width)
        for (index in image.pixels.indices) {
            val x = index % image.width
            val y = index / image.width
            if (effectiveSkyAlpha.alphaAt(x, y) < SKY_ALPHA_THRESHOLD) continue
            val bin = histogramBin(qualityLinearLuminance(image.pixels[index]), BINS)
            rowHistograms[y * BINS + bin]++
            columnHistograms[x * BINS + bin]++
            rowCounts[y]++
            columnCounts[x]++
        }
        val rowMedians = lineMedians(
            rowHistograms,
            rowCounts,
            minimumSamples = maxOf(4, (image.width * MIN_LINE_SKY_FRACTION).toInt())
        )
        val columnMedians = lineMedians(
            columnHistograms,
            columnCounts,
            minimumSamples = maxOf(4, (image.height * MIN_LINE_SKY_FRACTION).toInt())
        )
        val normalization = maxOf(skyMad * MAD_NORMALIZATION, MIN_NORMALIZATION)
        val horizontal = normalizedResidual(rowMedians, normalization)
        val vertical = normalizedResidual(columnMedians, normalization)
        return BandingMetrics(
            horizontalScore = horizontal,
            verticalScore = vertical,
            combinedScore = sqrt(horizontal * horizontal + vertical * vertical)
        )
    }

    private fun lineMedians(
        histograms: IntArray,
        counts: IntArray,
        minimumSamples: Int
    ): FloatArray = FloatArray(counts.size) { line ->
        if (counts[line] < minimumSamples) {
            Float.NaN
        } else {
            val histogram = histograms.copyOfRange(line * BINS, (line + 1) * BINS)
            histogramPercentile(histogram, counts[line].toLong(), 0.50f)
        }
    }

    private fun normalizedResidual(values: FloatArray, normalization: Float): Float {
        val radius = (values.size / SMOOTH_DIVISOR).coerceIn(2, MAX_SMOOTH_RADIUS)
        var residualSum = 0f
        var abruptSum = 0f
        var count = 0
        values.indices.forEach { index ->
            val value = values[index]
            if (!value.isFinite()) return@forEach
            var smoothSum = 0f
            var smoothCount = 0
            for (neighbor in maxOf(0, index - radius)..minOf(values.lastIndex, index + radius)) {
                val sample = values[neighbor]
                if (sample.isFinite()) {
                    smoothSum += sample
                    smoothCount++
                }
            }
            if (smoothCount < 3) return@forEach
            residualSum += kotlin.math.abs(value - smoothSum / smoothCount)
            if (index > 0 && index < values.lastIndex &&
                values[index - 1].isFinite() && values[index + 1].isFinite()
            ) {
                abruptSum += kotlin.math.abs(
                    value - (values[index - 1] + values[index + 1]) * 0.5f
                )
            }
            count++
        }
        if (count == 0) return 0f
        return ((residualSum + abruptSum * ABRUPT_WEIGHT) / count / normalization)
            .coerceAtLeast(0f)
    }

    companion object {
        private const val BINS = 256
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val MIN_LINE_SKY_FRACTION = 0.08f
        private const val MAD_NORMALIZATION = 1.4826f
        private const val MIN_NORMALIZATION = 0.002f
        private const val SMOOTH_DIVISOR = 24
        private const val MAX_SMOOTH_RADIUS = 48
        private const val ABRUPT_WEIGHT = 0.75f
    }
}
