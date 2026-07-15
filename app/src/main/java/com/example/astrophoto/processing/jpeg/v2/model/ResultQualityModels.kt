package com.example.astrophoto.processing.jpeg.v2.model

import com.example.astrophoto.ArgbPixelImage

enum class ResultCandidateType {
    REFERENCE,
    CLEAN_STACK,
    PROCESSED
}

data class BandingMetrics(
    val horizontalScore: Float,
    val verticalScore: Float,
    val combinedScore: Float
)

data class ResultQualityMetrics(
    val width: Int,
    val height: Int,
    val aspectRatio: Double,
    val retainedValidAreaRatio: Float,
    val reliableStarCount: Int,
    val medianStarLocalContrast: Float,
    val medianStarWidth: Float,
    val medianStarEllipticity: Float,
    val brightStarClippingPercent: Float,
    val suspiciousPointCount: Int,
    val skyMedian: Float,
    val skyMad: Float,
    val skyLowPercentile: Float,
    val skyHighPercentile: Float,
    val channelMedian: LinearRgb,
    val channelClippingPercent: LinearRgb,
    val chromaNoiseEstimate: Float,
    val banding: BandingMetrics,
    val gradientResidual: Float,
    val foregroundSharpness: Float,
    val foregroundEdgeDifference: Float,
    val foregroundMeanPixelDifference: Float,
    val foregroundMaximumPixelDifference: Int,
    val invalidBorderRatio: Float,
    val blackBorderRatio: Float,
    val processingConfidence: Float
)

data class ResultCandidate(
    val type: ResultCandidateType,
    val image: ArgbPixelImage,
    val metrics: ResultQualityMetrics
) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    init {
        require(metrics.width == image.width && metrics.height == image.height)
    }

    companion object {
        fun snapshot(
            type: ResultCandidateType,
            image: ArgbPixelImage,
            metrics: ResultQualityMetrics
        ) = ResultCandidate(
            type,
            ArgbPixelImage(image.width, image.height, image.pixels.copyOf()),
            metrics
        )
    }
}

data class QualityComparison(
    val hardFailureReasons: List<String> = emptyList(),
    val warningReasons: List<String> = emptyList()
)

data class QualityGateDecision(
    val accepted: Boolean,
    val score: Float,
    val hardFailureReasons: List<String>,
    val warningReasons: List<String>,
    val metrics: ResultQualityMetrics
)

data class FinalResultSelection(
    val selected: ResultCandidate,
    val processedDecision: QualityGateDecision,
    val cleanStackDecision: QualityGateDecision,
    val fallbackUsed: Boolean,
    val fallbackReason: String?,
    val rejectionReasons: List<String>,
    val internalFallbackLabel: String?
)
