package com.example.astrophoto.processing.jpeg.v2.model

import com.example.astrophoto.ArgbPixelImage

data class LinearRgb(
    val red: Float,
    val green: Float,
    val blue: Float
) {
    fun maximumAbsolute(): Float = maxOf(kotlin.math.abs(red), kotlin.math.abs(green), kotlin.math.abs(blue))
}

data class SkyStatisticsResult(
    val skyPixelCount: Int,
    val skyCoverageRatio: Float,
    val luminanceMedian: Float,
    val luminanceMad: Float,
    val lowPercentile: Float,
    val highPercentile: Float,
    val channelMedian: LinearRgb,
    val channelMad: LinearRgb,
    val channelClippingPercent: LinearRgb,
    val chromaNoiseEstimate: Float,
    val largeScaleGradientStrength: Float,
    val gradientDirectionX: Float,
    val gradientDirectionY: Float,
    val starBrightnessMedian: Float,
    val starBrightnessHighPercentile: Float,
    val reliableStarCount: Int,
    val medianStarLocalContrast: Float,
    val brightStarCorePercentile: Float,
    val estimatedBlackPoint: Float,
    val estimatedSafeWhitePoint: Float,
    val confidence: Float
) {
    companion object {
        val EMPTY = SkyStatisticsResult(
            0, 0f, 0f, 0f, 0f, 0f,
            LinearRgb(0f, 0f, 0f), LinearRgb(0f, 0f, 0f), LinearRgb(0f, 0f, 0f),
            0f, 0f, 0f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 1f, 0f
        )
    }
}

data class AdaptiveProcessingParameters(
    val gradientStrength: Float,
    val neutralizationStrength: Float,
    val stretchBlend: Float,
    val asinhStrength: Float,
    val highlightProtection: Float,
    val chromaNoiseStrength: Float,
    val starContrastStrength: Float,
    val maximumSkyMedianFactor: Float,
    val maximumChannelClippingPercent: Float,
    val minimumBlackWhiteSeparation: Float,
    val maximumGradientCorrection: Float,
    val maximumNeutralizationCorrection: Float,
    val maximumStarDetailGain: Float,
    val maximumChromaRadius: Int,
    val maximumStarWidthGrowth: Float
) {
    init {
        require(gradientStrength in 0f..1f && neutralizationStrength in 0f..1f)
        require(stretchBlend in 0f..1f && highlightProtection in 0f..1f)
        require(chromaNoiseStrength in 0f..1f && starContrastStrength in 0f..1f)
        require(asinhStrength >= 0f && maximumSkyMedianFactor >= 1f)
        require(maximumChannelClippingPercent in 0f..100f)
        require(minimumBlackWhiteSeparation > 0f)
        require(maximumGradientCorrection >= 0f && maximumNeutralizationCorrection >= 0f)
        require(maximumStarDetailGain >= 1f && maximumChromaRadius in 0..4)
        require(maximumStarWidthGrowth >= 0f)
    }
}

data class GradientRemovalDiagnostics(
    val modelConfidence: Float,
    val gridColumns: Int,
    val gridRows: Int,
    val validCells: Int,
    val maximumCorrection: Float
)

data class NeutralizationDiagnostics(val correction: LinearRgb)

data class StretchDiagnostics(
    val blackPoint: Float,
    val whitePoint: Float,
    val asinhStrength: Float,
    val highlightProtectionStrength: Float,
    val appliedBlend: Float,
    val medianSafetyScale: Float
)

data class ChromaNoiseDiagnostics(
    val strength: Float,
    val radius: Int
)

data class StarEnhancementDiagnostics(
    val strength: Float,
    val considered: Int,
    val enhanced: Int,
    val rejected: Int,
    val maximumMeasuredWidthGrowth: Float
)

data class AdaptiveProcessingDiagnostics(
    val preset: String,
    val before: SkyStatisticsResult,
    val after: SkyStatisticsResult,
    val gradient: GradientRemovalDiagnostics,
    val neutralization: NeutralizationDiagnostics,
    val stretch: StretchDiagnostics,
    val chromaNoise: ChromaNoiseDiagnostics,
    val starEnhancement: StarEnhancementDiagnostics,
    val foregroundDifferenceOutsideMask: Int,
    val processingDurationMillis: Long
)

data class PresetProcessingResult(
    val image: ArgbPixelImage,
    val diagnostics: AdaptiveProcessingDiagnostics
)
