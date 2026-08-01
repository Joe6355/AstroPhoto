package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics

internal enum class AdaptiveAsinhAblationVariantId(
    val stableId: String,
    val blendMode: ReplayStretchBlendMode,
    val appliedBlendDescription: String,
    val changedVariables: Int,
    val rootCauseEligible: Boolean
) {
    CURRENT(
        "t0-current",
        ReplayStretchBlendMode.CURRENT,
        "max(configuredBlend*confidenceScale, targetBlend*confidenceScale)",
        0,
        true
    ),
    HONEST_BLEND(
        "t1-honest-blend",
        ReplayStretchBlendMode.HONEST_BLEND,
        "configuredBlend",
        1,
        true
    ),
    CAPPED_BLEND_025(
        "t2-capped-blend-025",
        ReplayStretchBlendMode.CAPPED_025,
        "min(currentAppliedBlend, 0.25)",
        1,
        true
    ),
    CAPPED_BLEND_035(
        "t2-capped-blend-035",
        ReplayStretchBlendMode.CAPPED_035,
        "min(currentAppliedBlend, 0.35)",
        1,
        true
    ),
    CAPPED_BLEND_050(
        "t2-capped-blend-050",
        ReplayStretchBlendMode.CAPPED_050,
        "min(currentAppliedBlend, 0.50)",
        1,
        true
    ),
    CAPPED_BLEND_075(
        "t2-capped-blend-075",
        ReplayStretchBlendMode.CAPPED_075,
        "min(currentAppliedBlend, 0.75)",
        1,
        true
    ),
    TARGET_MEDIAN_DISABLED(
        "t3-target-median-disabled",
        ReplayStretchBlendMode.TARGET_MEDIAN_DISABLED,
        "configuredBlend*confidenceScale",
        1,
        true
    );

    val operationDescription: String get() = "sqrt(effectiveAlpha)"
    val compositionDescription: String get() = "effectiveAlpha"
}

internal enum class AdaptiveAsinhRootCause {
    TARGET_MEDIAN_ESCALATION_CONFIRMED,
    TARGET_MEDIAN_ESCALATION_NOT_CONFIRMED,
    INSUFFICIENT_EVIDENCE
}

internal data class AdaptiveAsinhBlendFormula(
    val configuredBlend: Float,
    val statisticsConfidence: Float,
    val confidenceScale: Float,
    val statisticsMedian: Float,
    val targetLinearMedian: Float,
    val medianNormalized: Float,
    val fullyMappedMedian: Float,
    val rawTargetBlend: Float,
    val targetBlend: Float,
    val configuredContribution: Float,
    val targetMedianContribution: Float,
    val currentAppliedBlend: Float
)

internal data class AdaptiveAsinhAblationContract(
    val variant: AdaptiveAsinhAblationVariantId,
    val available: Boolean,
    val unavailableReason: String?,
    val changedCondition: String,
    val sharedInputArgbSha256: String,
    val initialMaskSha256: String,
    val refinedMaskSha256: String,
    val effectiveAlphaFloat32LeSha256: String,
    val acceptedOriginalIndices: List<Int>,
    val rejectedOriginalIndices: List<Int>,
    val alignmentTransformFingerprint: String,
    val parameterFingerprint: String,
    val qualityPolicy: String
)

internal data class AdaptiveAsinhAblationStage(
    val id: String,
    val image: ArgbPixelImage
)

internal data class AdaptiveAsinhAblationVariant(
    val id: AdaptiveAsinhAblationVariantId,
    val available: Boolean,
    val unavailableReason: String?,
    val operationMode: ReplayStretchOperationMode,
    val blendMode: ReplayStretchBlendMode,
    val compositionAlpha: AlphaMask,
    val stages: List<AdaptiveAsinhAblationStage>,
    val processedSky: ArgbPixelImage,
    val composed: ArgbPixelImage,
    val selectedOrRejected: ArgbPixelImage,
    val selection: ReplayCandidateSelection,
    val stretchDiagnostics: StretchDiagnostics
)

internal data class AdaptiveAsinhGlobalMetrics(
    val variant: AdaptiveAsinhAblationVariantId,
    val skyMad: Double,
    val bandingProxy: Double,
    val boundaryEdgeExcess: Double,
    val meanHaloScore: Double,
    val meanLeakageScore: Double,
    val foregroundMeanChange: Double,
    val luminanceMean: Double,
    val luminanceMedian: Double,
    val clippedLowPixels: Int,
    val clippedHighPixels: Int,
    val chromaResidual: Double,
    val sensorDefectResidual: Double,
    val processedAccepted: Boolean,
    val rejectionReasons: List<String>,
    val selectedCandidate: String,
    val strictStarGatePassed: Boolean,
    val acceptableProductionCandidate: Boolean
)

internal data class AdaptiveAsinhBoundaryMetric(
    val variant: AdaptiveAsinhAblationVariantId,
    val window: SkyMaskWindowMetrics,
    val transitionBandVariance: Double
)

internal data class AdaptiveAsinhStrictStarMetric(
    val variant: AdaptiveAsinhAblationVariantId,
    val starId: String,
    val apertureFluxRetention: Double,
    val peakRetention: Double,
    val centroidShift: Double,
    val widthRatio: Double,
    val ellipticityChange: Double,
    val localContrast: Double,
    val localContrastRetention: Double,
    val chromaResidual: Double,
    val centerAlpha: Double,
    val distanceToBoundary: Double,
    val establishedGatePassed: Boolean
)

internal data class AdaptiveAsinhStageMetric(
    val variant: AdaptiveAsinhAblationVariantId,
    val metric: SkyMaskPostProcessStageMetrics
)

internal data class AdaptiveAsinhBaselineHashes(
    val cleanInputArgbSha256: String,
    val backgroundNeutralizedArgbSha256: String,
    val currentAdaptiveStretchArgbSha256: String,
    val currentComposedArgbSha256: String,
    val currentSelectedFinalArgbSha256: String,
    val initialMaskSha256: String,
    val refinedMaskSha256: String,
    val effectiveAlphaFloat32LeSha256: String
)

internal data class AdaptiveAsinhAblationBundle(
    val baseline: SkyMaskReplayBundle,
    val baselineHashes: AdaptiveAsinhBaselineHashes,
    val cleanStackMetrics: SkyMaskPostProcessStageMetrics,
    val parameters: AdaptiveProcessingParameters,
    val blendFormula: AdaptiveAsinhBlendFormula,
    val contracts: List<AdaptiveAsinhAblationContract>,
    val variants: List<AdaptiveAsinhAblationVariant>,
    val globalMetrics: List<AdaptiveAsinhGlobalMetrics>,
    val boundaryMetrics: List<AdaptiveAsinhBoundaryMetric>,
    val strictStarMetrics: List<AdaptiveAsinhStrictStarMetric>,
    val stageMetrics: List<AdaptiveAsinhStageMetric>,
    val rootCause: AdaptiveAsinhRootCause,
    val rootCauseEvidence: String,
    val productionCandidate: String?,
    val configurableCurrentMaximumChannelDifference: Int,
    val configurableCurrentDifferentPixelCount: Int,
    val productionSourceChanged: Boolean
)

internal data class AdaptiveAsinhAblationWriteResult(
    val fileCount: Int,
    val treeSha256: String,
    val manifestSha256: String,
    val summarySha256: String,
    val strictStarSha256: String,
    val htmlSha256: String
)
