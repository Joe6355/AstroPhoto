package com.example.astrophoto.processing.jpeg.v2.postprocessing

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ChromaNoiseDiagnostics
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyStatisticsResult
import kotlin.math.sqrt

data class ChromaNoiseReductionResult(
    val image: ArgbPixelImage,
    val diagnostics: ChromaNoiseDiagnostics
)

class ChromaNoiseReducer {
    fun apply(
        image: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask,
        stars: List<DetectedStar>,
        statistics: SkyStatisticsResult,
        strength: Float,
        maximumRadius: Int
    ): ChromaNoiseReductionResult {
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val radius = adaptiveRadius(image.width, image.height, statistics, maximumRadius)
        if (strength <= 0f || radius <= 0 || statistics.skyPixelCount == 0) {
            return ChromaNoiseReductionResult(
                image.copy(pixels = image.pixels.copyOf()),
                ChromaNoiseDiagnostics(0f, 0)
            )
        }
        val starProtection = createStarCoreMask(image.width, image.height, stars, radiusScale = 2.2f)
        val output = image.pixels.copyOf()
        val edgeLimit = maxOf(MIN_EDGE_LIMIT, statistics.luminanceMad * 6f)
        val noiseScale = (statistics.chromaNoiseEstimate / CHROMA_NOISE_REFERENCE)
            .coerceIn(MIN_NOISE_SCALE, 1f)
        val confidenceScale = (MIN_CONFIDENCE_SCALE +
            statistics.confidence * (1f - MIN_CONFIDENCE_SCALE)).coerceIn(0f, 1f)
        val appliedStrength = strength.coerceIn(0f, 1f) * noiseScale * confidenceScale
        val lowSignalEnd = maxOf(
            statistics.highPercentile,
            statistics.luminanceMedian + maxOf(statistics.luminanceMad * 8f, 0.025f)
        )
        for (index in output.indices) {
            if (starProtection[index]) continue
            val x = index % image.width
            val y = index / image.width
            val alpha = effectiveSkyAlpha.alphaAt(x, y)
            if (alpha <= OPERATION_ALPHA_THRESHOLD) continue
            val center = channels(image.pixels[index])
            val darkWeight = 1f - smoothStep(
                statistics.luminanceMedian,
                lowSignalEnd,
                center.luminance
            )
            if (darkWeight <= 0f) continue
            var redChromaSum = (center.red - center.luminance) * CENTER_WEIGHT
            var blueChromaSum = (center.blue - center.luminance) * CENTER_WEIGHT
            var weightSum = CENTER_WEIGHT
            for (distance in 1..radius) {
                fun include(nx: Int, ny: Int) {
                    if (nx !in 0 until image.width || ny !in 0 until image.height) return
                    val neighborIndex = ny * image.width + nx
                    if (starProtection[neighborIndex] ||
                        effectiveSkyAlpha.alphaAt(nx, ny) <= OPERATION_ALPHA_THRESHOLD
                    ) return
                    val neighbor = channels(image.pixels[neighborIndex])
                    val luminanceDifference = kotlin.math.abs(neighbor.luminance - center.luminance)
                    if (luminanceDifference > edgeLimit) return
                    val weight = 1f / (1f + distance + luminanceDifference / edgeLimit)
                    redChromaSum += (neighbor.red - neighbor.luminance) * weight
                    blueChromaSum += (neighbor.blue - neighbor.luminance) * weight
                    weightSum += weight
                }
                include(x - distance, y)
                include(x + distance, y)
                include(x, y - distance)
                include(x, y + distance)
            }
            if (weightSum <= CENTER_WEIGHT) continue
            val targetRedChroma = redChromaSum / weightSum
            val targetBlueChroma = blueChromaSum / weightSum
            val localStrength = appliedStrength * darkWeight * sqrt(alpha.coerceIn(0f, 1f))
            val redChroma = (center.red - center.luminance) * (1f - localStrength) +
                targetRedChroma * localStrength
            val blueChroma = (center.blue - center.luminance) * (1f - localStrength) +
                targetBlueChroma * localStrength
            val fitted = fitChroma(center.luminance, redChroma, blueChroma)
            output[index] = packLinear(fitted.red, fitted.green, fitted.blue)
        }
        return ChromaNoiseReductionResult(
            ArgbPixelImage(image.width, image.height, output),
            ChromaNoiseDiagnostics(appliedStrength, radius)
        )
    }

    private data class Channels(
        val red: Float,
        val green: Float,
        val blue: Float,
        val luminance: Float
    )

    private fun channels(color: Int): Channels {
        val red = linearChannel(color, 16)
        val green = linearChannel(color, 8)
        val blue = linearChannel(color, 0)
        return Channels(red, green, blue, 0.2126f * red + 0.7152f * green + 0.0722f * blue)
    }

    private fun fitChroma(luminance: Float, redChroma: Float, blueChroma: Float): Channels {
        var scale = 1f
        repeat(CHROMA_FIT_ITERATIONS) {
            val red = luminance + redChroma * scale
            val blue = luminance + blueChroma * scale
            val green = (luminance - 0.2126f * red - 0.0722f * blue) / 0.7152f
            if (red in 0f..1f && green in 0f..1f && blue in 0f..1f) {
                return Channels(red, green, blue, luminance)
            }
            scale *= 0.75f
        }
        return Channels(luminance, luminance, luminance, luminance)
    }

    private fun adaptiveRadius(
        width: Int,
        height: Int,
        statistics: SkyStatisticsResult,
        maximumRadius: Int
    ): Int {
        if (maximumRadius <= 0) return 0
        val resolutionRadius = 1 + minOf(width, height) / 1800
        val noiseRadius = if (statistics.chromaNoiseEstimate >= CHROMA_NOISE_REFERENCE) 1 else 0
        return (resolutionRadius + noiseRadius).coerceIn(1, maximumRadius)
    }

    companion object {
        private const val CENTER_WEIGHT = 2f
        private const val MIN_EDGE_LIMIT = 0.008f
        private const val CHROMA_NOISE_REFERENCE = 0.012f
        private const val MIN_NOISE_SCALE = 0.20f
        private const val MIN_CONFIDENCE_SCALE = 0.25f
        private const val CHROMA_FIT_ITERATIONS = 6
    }
}
