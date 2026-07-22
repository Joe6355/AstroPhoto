package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.example.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics
import com.example.astrophoto.processing.jpeg.v2.color.SrgbTransfer

class BackgroundQualityComparator {
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
