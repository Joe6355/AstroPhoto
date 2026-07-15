package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.ArgbPixelImage
import com.example.astrophoto.pixelLuminance
import com.example.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.postprocessing.SkyStatistics
import kotlin.math.abs

class ResultQualityAnalyzer(
    private val starDetector: JpegStarDetector = JpegStarDetector(),
    private val skyStatistics: SkyStatistics = SkyStatistics(),
    private val bandingEstimator: BandingEstimator = BandingEstimator()
) {
    fun analyze(
        image: ArgbPixelImage,
        reference: ArgbPixelImage,
        effectiveSkyAlpha: AlphaMask
    ): ResultQualityMetrics {
        require(image.width == reference.width && image.height == reference.height)
        require(image.width == effectiveSkyAlpha.width && image.height == effectiveSkyAlpha.height)
        val binarySky = SkyMask(
            image.width,
            image.height,
            BooleanArray(image.pixels.size) { index ->
                effectiveSkyAlpha.alphaAt(index % image.width, index / image.width) >=
                    SKY_ALPHA_THRESHOLD
            }
        )
        val detectedStars = starDetector.detect(image, binarySky).stars
        val sky = skyStatistics.calculate(image, effectiveSkyAlpha, detectedStars)
        val starWidths = detectedStars.map { it.width }.sorted()
        val starEllipticities = detectedStars.map { it.ellipticity }.sorted()
        val starContrasts = detectedStars.map { it.localContrast }.sorted()
        val clippedStars = detectedStars.count { star ->
            val x = star.x.toInt().coerceIn(0, image.width - 1)
            val y = star.y.toInt().coerceIn(0, image.height - 1)
            maximumChannel(image.pixelAt(x, y)) >= BRIGHT_CLIP_CHANNEL
        }
        val foreground = foregroundMetrics(image, reference, effectiveSkyAlpha)
        val border = borderMetrics(image)
        return ResultQualityMetrics(
            width = image.width,
            height = image.height,
            aspectRatio = image.width.toDouble() / image.height,
            retainedValidAreaRatio = image.pixels.count { it ushr 24 and 0xFF == 255 }
                .toFloat() / image.pixels.size,
            reliableStarCount = detectedStars.size,
            medianStarLocalContrast = median(starContrasts),
            medianStarWidth = median(starWidths),
            medianStarEllipticity = median(starEllipticities),
            brightStarClippingPercent = if (detectedStars.isEmpty()) 0f
            else clippedStars * 100f / detectedStars.size,
            suspiciousPointCount = suspiciousPointCount(image, effectiveSkyAlpha, sky.luminanceMedian, sky.luminanceMad),
            skyMedian = sky.luminanceMedian,
            skyMad = sky.luminanceMad,
            skyLowPercentile = sky.lowPercentile,
            skyHighPercentile = sky.highPercentile,
            channelMedian = sky.channelMedian,
            channelClippingPercent = sky.channelClippingPercent,
            chromaNoiseEstimate = sky.chromaNoiseEstimate,
            banding = bandingEstimator.estimate(image, effectiveSkyAlpha, sky.luminanceMad),
            gradientResidual = sky.largeScaleGradientStrength,
            foregroundSharpness = foreground.sharpness,
            foregroundEdgeDifference = foreground.edgeDifference,
            foregroundMeanPixelDifference = foreground.meanPixelDifference,
            foregroundMaximumPixelDifference = foreground.maximumPixelDifference,
            invalidBorderRatio = border.invalidRatio,
            blackBorderRatio = border.blackRatio,
            processingConfidence = sky.confidence
        )
    }

    private data class ForegroundMetrics(
        val sharpness: Float,
        val edgeDifference: Float,
        val meanPixelDifference: Float,
        val maximumPixelDifference: Int
    )

    private fun foregroundMetrics(
        image: ArgbPixelImage,
        reference: ArgbPixelImage,
        alpha: AlphaMask
    ): ForegroundMetrics {
        var sharpnessSum = 0L
        var edgeDifferenceSum = 0L
        var edgeCount = 0L
        var pixelDifferenceSum = 0L
        var pixelCount = 0L
        var maximumDifference = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (alpha.alphaAt(x, y) > FOREGROUND_ALPHA_THRESHOLD) continue
            val index = y * image.width + x
            val difference = maximumChannelDifference(image.pixels[index], reference.pixels[index])
            pixelDifferenceSum += difference
            pixelCount++
            maximumDifference = maxOf(maximumDifference, difference)
            fun compareEdge(nx: Int, ny: Int) {
                if (nx !in 0 until image.width || ny !in 0 until image.height ||
                    alpha.alphaAt(nx, ny) > FOREGROUND_ALPHA_THRESHOLD
                ) return
                val candidateEdge = abs(
                    pixelLuminance(image.pixelAt(x, y)) - pixelLuminance(image.pixelAt(nx, ny))
                )
                val referenceEdge = abs(
                    pixelLuminance(reference.pixelAt(x, y)) - pixelLuminance(reference.pixelAt(nx, ny))
                )
                sharpnessSum += candidateEdge
                edgeDifferenceSum += abs(candidateEdge - referenceEdge)
                edgeCount++
            }
            compareEdge(x + 1, y)
            compareEdge(x, y + 1)
        }
        return ForegroundMetrics(
            sharpness = sharpnessSum.toFloat() / edgeCount.coerceAtLeast(1L),
            edgeDifference = edgeDifferenceSum.toFloat() / edgeCount.coerceAtLeast(1L),
            meanPixelDifference = pixelDifferenceSum.toFloat() / pixelCount.coerceAtLeast(1L),
            maximumPixelDifference = maximumDifference
        )
    }

    private data class BorderMetrics(val invalidRatio: Float, val blackRatio: Float)

    private fun borderMetrics(image: ArgbPixelImage): BorderMetrics {
        val band = maxOf(1, minOf(image.width, image.height) / 80)
        var borderPixels = 0
        var invalid = 0
        var black = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (x >= band && x < image.width - band && y >= band && y < image.height - band) continue
            borderPixels++
            val color = image.pixelAt(x, y)
            if (color ushr 24 and 0xFF != 255) invalid++
            if (pixelLuminance(color) <= BLACK_BORDER_LUMINANCE) black++
        }
        return BorderMetrics(
            invalid.toFloat() / borderPixels.coerceAtLeast(1),
            black.toFloat() / borderPixels.coerceAtLeast(1)
        )
    }

    private fun suspiciousPointCount(
        image: ArgbPixelImage,
        mask: AlphaMask,
        background: Float,
        mad: Float
    ): Int {
        val threshold = background + maxOf(mad * 8f, MIN_SUSPICIOUS_DETAIL)
        var suspicious = 0
        for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
            if (mask.alphaAt(x, y) < SKY_ALPHA_THRESHOLD) continue
            val color = image.pixelAt(x, y)
            val luminance = qualityLinearLuminance(color)
            if (luminance < threshold) continue
            var localMaximum = true
            var support = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbor = qualityLinearLuminance(image.pixelAt(x + dx, y + dy))
                if (neighbor > luminance) localMaximum = false
                if (neighbor - background >= (luminance - background) * MIN_POINT_SUPPORT_FRACTION) {
                    support++
                }
            }
            if (!localMaximum) continue
            val deltas = floatArrayOf(
                qualityLinearChannel(color, 16) - background,
                qualityLinearChannel(color, 8) - background,
                qualityLinearChannel(color, 0) - background
            ).sortedDescending()
            val singleChannel = deltas[0] > MIN_SINGLE_CHANNEL_DETAIL &&
                deltas[1] < deltas[0] * MAX_SECOND_CHANNEL_FRACTION
            if (support == 0 || singleChannel) suspicious++
        }
        return suspicious
    }

    private fun median(values: List<Float>): Float = when {
        values.isEmpty() -> 0f
        values.size % 2 == 1 -> values[values.size / 2]
        else -> (values[values.size / 2 - 1] + values[values.size / 2]) * 0.5f
    }

    private fun maximumChannel(color: Int): Int = maxOf(
        color ushr 16 and 0xFF,
        color ushr 8 and 0xFF,
        color and 0xFF
    )

    private fun maximumChannelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)),
        abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)),
        abs((first and 0xFF) - (second and 0xFF))
    )

    companion object {
        private const val SKY_ALPHA_THRESHOLD = 0.98f
        private const val FOREGROUND_ALPHA_THRESHOLD = 0.001f
        private const val BRIGHT_CLIP_CHANNEL = 254
        private const val BLACK_BORDER_LUMINANCE = 2
        private const val MIN_SUSPICIOUS_DETAIL = 0.008f
        private const val MIN_POINT_SUPPORT_FRACTION = 0.22f
        private const val MIN_SINGLE_CHANNEL_DETAIL = 0.04f
        private const val MAX_SECOND_CHANNEL_FRACTION = 0.30f
    }
}
