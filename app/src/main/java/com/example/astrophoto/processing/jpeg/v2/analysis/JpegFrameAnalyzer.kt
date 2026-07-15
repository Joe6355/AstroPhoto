package com.example.astrophoto.processing.jpeg.v2.analysis

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.SkyMaskResult
import kotlin.math.sqrt

class JpegFrameAnalyzer(
    private val detector: JpegStarDetector = JpegStarDetector()
) {
    fun analyze(
        id: String,
        fileName: String,
        image: ArgbPixelImage,
        skyMask: SkyMaskResult
    ): FrameAnalysis {
        val detection = detector.detect(image, skyMask.mask)
        val reliable = detection.stars.filter {
            it.confidence >= MIN_RELIABLE_CONFIDENCE &&
                it.width in 0.6f..4.5f &&
                it.ellipticity <= 0.72f
        }
        var clipped = 0
        image.pixels.forEach { color ->
            if (maxOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF) >= 250) clipped++
        }
        val clippingPercent = clipped * 100f / image.pixels.size
        val exposureSuitability = exposureSuitability(detection.background, clippingPercent)
        val spread = if (reliable.size >= 2) {
            val minX = reliable.minOf { it.x }
            val maxX = reliable.maxOf { it.x }
            val minY = reliable.minOf { it.y }
            val maxY = reliable.maxOf { it.y }
            sqrt(
                ((maxX - minX) * (maxY - minY) / (image.width * image.height).toFloat())
                    .coerceAtLeast(0f)
            ).coerceIn(0f, 1f)
        } else {
            0f
        }
        val medianConfidence = median(reliable.map { it.confidence })
        val alignmentSuitability = (
            (reliable.size / 18f).coerceIn(0f, 1f) * 0.50f +
                spread * 0.22f +
                medianConfidence * 0.18f +
                skyMask.confidence * 0.10f
            ).coerceIn(0f, 1f)
        return FrameAnalysis(
            id = id,
            fileName = fileName,
            width = image.width,
            height = image.height,
            stars = reliable,
            reliableStarCount = reliable.size,
            medianStarContrast = median(reliable.map { it.localContrast }),
            medianStarWidth = median(reliable.map { it.width }, Float.POSITIVE_INFINITY),
            medianStarEllipticity = median(reliable.map { it.ellipticity }, 1f),
            backgroundNoise = detection.noise,
            clippingPercent = clippingPercent,
            exposureSuitability = exposureSuitability,
            decodeValid = true,
            alignmentSuitability = alignmentSuitability,
            skyMaskConfidence = skyMask.confidence,
            skyMaskUsedFallback = skyMask.usedFallback
        )
    }

    private fun exposureSuitability(background: Float, clippingPercent: Float): Float {
        val backgroundScore = when {
            background < 2f -> 0f
            background < 10f -> (background - 2f) / 8f
            background <= 150f -> 1f
            background < 225f -> (225f - background) / 75f
            else -> 0f
        }
        return (backgroundScore * (1f - clippingPercent / 12f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    private fun median(values: List<Float>, emptyValue: Float = 0f): Float {
        if (values.isEmpty()) return emptyValue
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        private const val MIN_RELIABLE_CONFIDENCE = 0.24f
    }
}
