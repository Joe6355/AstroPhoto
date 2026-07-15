package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics
import kotlin.math.asinh
import kotlin.math.sqrt

data class AdaptiveStretchResult(
    val image: ArgbPixelImage,
    val diagnostics: StretchDiagnostics
)

class AdaptiveAsinhStretch(
    private val skyStatistics: SkyStatistics = SkyStatistics()
) {
    fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        stretchBlend: Float,
        asinhStrength: Float,
        highlightProtection: Float,
        maximumSkyMedianFactor: Float,
        minimumBlackWhiteSeparation: Float
    ): AdaptiveStretchResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val blackPoint = minOf(
            statistics.lowPercentile,
            statistics.estimatedBlackPoint
        ).coerceIn(0f, 1f - minimumBlackWhiteSeparation)
        val whitePoint = maxOf(
            statistics.estimatedSafeWhitePoint,
            blackPoint + minimumBlackWhiteSeparation
        ).coerceAtMost(1f)
        if (stretchBlend <= 0f || asinhStrength <= 0f || statistics.skyPixelCount == 0) {
            return AdaptiveStretchResult(
                image.copy(pixels = image.pixels.copyOf()),
                StretchDiagnostics(blackPoint, whitePoint, asinhStrength, highlightProtection, 0f, 1f)
            )
        }
        val denominator = asinh(asinhStrength.toDouble()).toFloat().coerceAtLeast(0.0001f)
        val range = (whitePoint - blackPoint).coerceAtLeast(minimumBlackWhiteSeparation)
        val confidenceScale = (MIN_CONFIDENCE_SCALE +
            (1f - MIN_CONFIDENCE_SCALE) * statistics.confidence).coerceIn(0f, 1f)
        val appliedBlend = stretchBlend.coerceIn(0f, 1f) * confidenceScale
        val stretchedPixels = image.pixels.copyOf()
        for (index in stretchedPixels.indices) {
            val x = index % image.width
            val y = index / image.width
            val alpha = effectiveSkyAlpha.alphaAt(x, y)
            if (alpha <= OPERATION_ALPHA_THRESHOLD) continue
            val color = image.pixels[index]
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            if (luminance <= MIN_LUMINANCE) continue
            val normalized = ((luminance - blackPoint) / range).coerceIn(0f, 1f)
            val mapped = (asinh(asinhStrength * normalized.toDouble()) / denominator).toFloat()
            val highlightWeight = 1f - highlightProtection.coerceIn(0f, 1f) *
                smoothStep(HIGHLIGHT_START, 1f, normalized)
            val localBlend = appliedBlend * sqrt(alpha.coerceIn(0f, 1f)) * highlightWeight
            val targetLuminance = (luminance + (mapped - luminance) * localBlend)
                .coerceIn(0f, MAX_UNCLIPPED_VALUE)
            var scale = targetLuminance / luminance
            val maximumChannel = maxOf(red, green, blue)
            if (maximumChannel < MAX_UNCLIPPED_VALUE && maximumChannel * scale > MAX_UNCLIPPED_VALUE) {
                scale = MAX_UNCLIPPED_VALUE / maximumChannel.coerceAtLeast(MIN_LUMINANCE)
            }
            stretchedPixels[index] = packLinear(red * scale, green * scale, blue * scale)
        }
        var stretched = ArgbPixelImage(image.width, image.height, stretchedPixels)
        val stretchedStatistics = skyStatistics.calculate(stretched, effectiveSkyAlpha, stars)
        val allowedMedian = maxOf(
            statistics.luminanceMedian * maximumSkyMedianFactor,
            statistics.luminanceMedian + maxOf(statistics.luminanceMad * 2f, MIN_MEDIAN_HEADROOM)
        )
        val safetyScale = when {
            stretchedStatistics.luminanceMedian <= allowedMedian -> 1f
            stretchedStatistics.luminanceMedian <= statistics.luminanceMedian -> 1f
            else -> ((allowedMedian - statistics.luminanceMedian) /
                (stretchedStatistics.luminanceMedian - statistics.luminanceMedian)).coerceIn(0f, 1f)
        }
        if (safetyScale < 0.999f) {
            val safePixels = image.pixels.copyOf()
            for (index in safePixels.indices) {
                val x = index % image.width
                val y = index / image.width
                if (effectiveSkyAlpha.alphaAt(x, y) <= OPERATION_ALPHA_THRESHOLD) continue
                val original = image.pixels[index]
                val processed = stretched.pixels[index]
                safePixels[index] = packLinear(
                    linearChannel(original, 16) +
                        (linearChannel(processed, 16) - linearChannel(original, 16)) * safetyScale,
                    linearChannel(original, 8) +
                        (linearChannel(processed, 8) - linearChannel(original, 8)) * safetyScale,
                    linearChannel(original, 0) +
                        (linearChannel(processed, 0) - linearChannel(original, 0)) * safetyScale
                )
            }
            stretched = ArgbPixelImage(image.width, image.height, safePixels)
        }
        return AdaptiveStretchResult(
            stretched,
            StretchDiagnostics(
                blackPoint = blackPoint,
                whitePoint = whitePoint,
                asinhStrength = asinhStrength,
                highlightProtectionStrength = highlightProtection,
                appliedBlend = appliedBlend,
                medianSafetyScale = safetyScale
            )
        )
    }

    companion object {
        private const val HIGHLIGHT_START = 0.52f
        private const val MIN_CONFIDENCE_SCALE = 0.18f
        private const val MIN_LUMINANCE = 0.000001f
        private const val MIN_MEDIAN_HEADROOM = 1f / 4095f
        private const val MAX_UNCLIPPED_VALUE = 0.995f
    }
}
