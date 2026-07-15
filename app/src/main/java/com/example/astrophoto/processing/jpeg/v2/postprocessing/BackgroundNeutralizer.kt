package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.LinearRgb
import com.example.astrophoto.processing.jpeg.v2.model.NeutralizationDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import kotlin.math.sqrt

data class BackgroundNeutralizationResult(
    val image: ArgbPixelImage,
    val diagnostics: NeutralizationDiagnostics
)

class BackgroundNeutralizer {
    fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        strength: Float,
        maximumCorrection: Float
    ): BackgroundNeutralizationResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        if (strength <= 0f || statistics.skyPixelCount == 0) {
            return BackgroundNeutralizationResult(
                image.copy(pixels = image.pixels.copyOf()),
                NeutralizationDiagnostics(LinearRgb(0f, 0f, 0f))
            )
        }
        val median = statistics.channelMedian
        val neutralTarget = (median.red + median.green + median.blue) / 3f
        val confidenceScale = statistics.confidence.coerceIn(MIN_CONFIDENCE_SCALE, 1f)
        val correction = LinearRgb(
            ((neutralTarget - median.red) * strength * confidenceScale)
                .coerceIn(-maximumCorrection, maximumCorrection),
            ((neutralTarget - median.green) * strength * confidenceScale)
                .coerceIn(-maximumCorrection, maximumCorrection),
            ((neutralTarget - median.blue) * strength * confidenceScale)
                .coerceIn(-maximumCorrection, maximumCorrection)
        )
        val starCores = createStarCoreMask(image.width, image.height, stars, radiusScale = 2f)
        val output = image.pixels.copyOf()
        val highlightStart = maxOf(statistics.highPercentile, statistics.luminanceMedian + 0.08f)
        val highlightEnd = maxOf(highlightStart + 0.08f, statistics.estimatedSafeWhitePoint)
        for (index in output.indices) {
            val x = index % image.width
            val y = index / image.width
            val alpha = effectiveSkyAlpha.alphaAt(x, y)
            if (alpha <= OPERATION_ALPHA_THRESHOLD || starCores[index]) continue
            val color = image.pixels[index]
            val red = linearChannel(color, 16)
            val green = linearChannel(color, 8)
            val blue = linearChannel(color, 0)
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            val highlightProtection = 1f - smoothStep(highlightStart, highlightEnd, luminance)
            val scale = sqrt(alpha.coerceIn(0f, 1f)) * highlightProtection
            output[index] = packLinear(
                (red + correction.red * scale).coerceAtLeast(0f),
                (green + correction.green * scale).coerceAtLeast(0f),
                (blue + correction.blue * scale).coerceAtLeast(0f)
            )
        }
        return BackgroundNeutralizationResult(
            ArgbPixelImage(image.width, image.height, output),
            NeutralizationDiagnostics(correction)
        )
    }

    companion object {
        private const val MIN_CONFIDENCE_SCALE = 0.20f
    }
}
