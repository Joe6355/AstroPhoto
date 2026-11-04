package com.joe6355.astrophoto.processing.jpeg.v2.quality

import com.joe6355.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.joe6355.astrophoto.processing.jpeg.v2.color.SrgbTransfer
import com.joe6355.astrophoto.pixelLuminance
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.ArgbPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.AlphaPixelSource
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlaneReader
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImageReader
import kotlin.math.abs

class BackgroundQualityComparator {
    fun compareBoundaryBackground(
        clean: FileBackedImage,
        processed: FileBackedImage,
        effectiveSkyAlpha: FileBackedFloatPlane
    ): QualityComparison = FileBackedImageReader(clean, cachedRows = 64).use { first ->
        FileBackedImageReader(processed, cachedRows = 64).use { second ->
            FileBackedFloatPlaneReader(effectiveSkyAlpha, cachedRows = 64).use { alpha ->
                compareBoundaryBackground(first, second, alpha)
            }
        }
    }

    /** Compare added sky levels across a soft mask only where the original background is smooth. */
    fun compareBoundaryBackground(
        clean: ArgbPixelSource,
        processed: ArgbPixelSource,
        alpha: AlphaPixelSource
    ): QualityComparison {
        require(clean.width == processed.width && clean.height == processed.height)
        require(clean.width == alpha.width && clean.height == alpha.height)
        val radius = 24
        val step = maxOf(8, minOf(clean.width, clean.height) / 128)
        val values = FloatArray(25)
        fun background(image: ArgbPixelSource, x: Int, y: Int): Pair<Float, Float> {
            var count = 0
            for (dy in -2..2) for (dx in -2..2) {
                values[count++] = pixelLuminance(image.argbAt(x + dx, y + dy)).toFloat()
            }
            values.sort()
            val median = values[12]
            for (i in values.indices) values[i] = abs(values[i] - median)
            values.sort()
            return median to values[12]
        }
        var checked = 0
        var discontinuities = 0
        for (y in radius + 2 until clean.height - radius - 2 step step) {
            for (x in radius + 2 until clean.width - radius - 2 step step) {
                if (alpha.alphaAt(x, y) !in 0.05f..0.95f) continue
                for ((dx, dy) in listOf(radius to 0, 0 to radius)) {
                    val firstAlpha = alpha.alphaAt(x - dx, y - dy)
                    val secondAlpha = alpha.alphaAt(x + dx, y + dy)
                    val alphaChange = secondAlpha - firstAlpha
                    if (abs(alphaChange) < 0.5f) continue
                    val first = background(clean, x - dx, y - dy)
                    val second = background(clean, x + dx, y + dy)
                    val beforeChange = second.first - first.first
                    if (abs(beforeChange) > 3.5f || maxOf(first.second, second.second) > 1.5f) continue
                    checked++
                    val afterChange = background(processed, x + dx, y + dy).first -
                        background(processed, x - dx, y - dy).first
                    val addedChange = afterChange - beforeChange
                    if (addedChange * alphaChange > 0f &&
                        abs(addedChange) > maxOf(3f, maxOf(first.second, second.second) * 4f)) {
                        discontinuities++
                    }
                }
            }
        }
        return QualityComparison(hardFailureReasons = if (
            discontinuities >= 3 && discontinuities.toFloat() / checked.coerceAtLeast(1) >= 0.03f
        ) listOf("mask_shaped_background_lift") else emptyList())
    }

    fun compare(
        clean: ResultQualityMetrics,
        processed: ResultQualityMetrics,
        parameters: AdaptiveProcessingParameters
    ): QualityComparison {
        val hard = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val medianLimit = maxOf(
            SrgbTransfer.srgbToLinear(parameters.targetDisplaySkyMedian),
            clean.skyMedian * parameters.maximumSkyMedianFactor,
            clean.skyMedian + maxOf(clean.skyMad * 2f, MIN_MEDIAN_HEADROOM)
        )
        if (processed.skyMedian > medianLimit + METRIC_TOLERANCE) {
            hard += "sky_median_exceeds_preset_limit"
        } else if (processed.skyMedian > clean.skyMedian * WARNING_MEDIAN_FACTOR + MIN_MEDIAN_HEADROOM) {
            warnings += "sky_median_increased"
        }
        if (significantlyWorse(processed.skyMad, clean.skyMad, HARD_NOISE_FACTOR, NOISE_ALLOWANCE)) {
            hard += "sky_mad_increased_excessively"
        } else if (significantlyWorse(
                processed.skyMad,
                clean.skyMad,
                WARNING_NOISE_FACTOR,
                WARNING_NOISE_ALLOWANCE
            )
        ) {
            warnings += "sky_mad_increased"
        }
        if (processed.chromaNoiseEstimate >
            clean.chromaNoiseEstimate * HARD_CHROMA_FACTOR + CHROMA_ALLOWANCE
        ) {
            hard += "chroma_noise_increased_excessively"
        } else if (processed.chromaNoiseEstimate >
            clean.chromaNoiseEstimate * WARNING_CHROMA_FACTOR + CHROMA_ALLOWANCE
        ) {
            warnings += "chroma_noise_increased"
        }
        val clippingLimit = parameters.maximumChannelClippingPercent
        if (exceedsClipping(clean, processed, clippingLimit)) {
            hard += "per_channel_clipping_excessive"
        }
        if (significantlyWorse(
                processed.banding.combinedScore,
                clean.banding.combinedScore,
                HARD_BANDING_FACTOR,
                BANDING_ALLOWANCE
            )
        ) {
            hard += "banding_increased_excessively"
        } else if (significantlyWorse(
                processed.banding.combinedScore,
                clean.banding.combinedScore,
                WARNING_BANDING_FACTOR,
                WARNING_BANDING_ALLOWANCE
            )
        ) {
            warnings += "banding_increased"
        }
        if (significantlyWorse(
                processed.gradientResidual,
                clean.gradientResidual,
                HARD_GRADIENT_FACTOR,
                GRADIENT_ALLOWANCE
            )
        ) {
            hard += "gradient_residual_worsened"
        }
        if (processed.skyMedian >= FLAT_GRAY_MIN_MEDIAN && processed.skyMad <= FLAT_GRAY_MAX_MAD) {
            hard += "sky_became_flat_gray"
        }
        if (clean.skyLowPercentile > 0f && processed.skyLowPercentile <= CRUSHED_BLACK_LIMIT &&
            clean.skyLowPercentile > CRUSHED_BLACK_LIMIT
        ) {
            hard += "sky_black_point_crushed"
        }
        val cleanCast = channelCast(clean)
        val processedCast = channelCast(processed)
        if (processedCast > cleanCast * HARD_COLOR_CAST_FACTOR + COLOR_CAST_ALLOWANCE) {
            hard += "background_color_cast_worsened"
        }
        if (processed.processingConfidence < MIN_PROCESSING_CONFIDENCE) {
            hard += "sky_statistics_confidence_too_low"
        }
        return QualityComparison(hard.distinct(), warnings.distinct())
    }

    private fun exceedsClipping(
        clean: ResultQualityMetrics,
        processed: ResultQualityMetrics,
        limit: Float
    ): Boolean =
        processed.channelClippingPercent.red > maxOf(clean.channelClippingPercent.red, limit) + CLIP_TOLERANCE ||
            processed.channelClippingPercent.green > maxOf(clean.channelClippingPercent.green, limit) + CLIP_TOLERANCE ||
            processed.channelClippingPercent.blue > maxOf(clean.channelClippingPercent.blue, limit) + CLIP_TOLERANCE

    private fun channelCast(metrics: ResultQualityMetrics): Float =
        maxOf(metrics.channelMedian.red, metrics.channelMedian.green, metrics.channelMedian.blue) -
            minOf(metrics.channelMedian.red, metrics.channelMedian.green, metrics.channelMedian.blue)

    private fun significantlyWorse(
        processed: Float,
        clean: Float,
        factor: Float,
        minimumIncrease: Float
    ): Boolean = processed > clean * factor && processed - clean > minimumIncrease

    companion object {

        internal fun maximumGradientResidual(clean: Float): Float =
            maxOf(clean * HARD_GRADIENT_FACTOR, clean + GRADIENT_ALLOWANCE)

        private const val MIN_MEDIAN_HEADROOM = 0.001f
        private const val METRIC_TOLERANCE = 0.001f
        private const val WARNING_MEDIAN_FACTOR = 1.35f
        private const val HARD_NOISE_FACTOR = 1.75f
        private const val WARNING_NOISE_FACTOR = 1.30f
        private const val NOISE_ALLOWANCE = 1f / 4095f
        private const val WARNING_NOISE_ALLOWANCE = 1f / 8191f
        private const val HARD_CHROMA_FACTOR = 1.50f
        private const val WARNING_CHROMA_FACTOR = 1.25f
        private const val CHROMA_ALLOWANCE = 0.003f
        private const val HARD_BANDING_FACTOR = 2.00f
        private const val WARNING_BANDING_FACTOR = 1.25f
        private const val BANDING_ALLOWANCE = 0.05f
        private const val WARNING_BANDING_ALLOWANCE = 0.025f
        private const val HARD_GRADIENT_FACTOR = 2.00f
        private const val GRADIENT_ALLOWANCE = 1f / 1024f
        private const val FLAT_GRAY_MIN_MEDIAN = 0.18f
        private const val FLAT_GRAY_MAX_MAD = 0.001f
        private const val CRUSHED_BLACK_LIMIT = 1f / 4095f
        private const val HARD_COLOR_CAST_FACTOR = 1.35f
        private const val COLOR_CAST_ALLOWANCE = 0.008f
        private const val MIN_PROCESSING_CONFIDENCE = 0.18f
        private const val CLIP_TOLERANCE = 0.1f
    }
}
