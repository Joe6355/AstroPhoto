package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.AdaptiveProcessingParameters
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics

internal enum class AdaptiveAsinhAblationVariantId(
    val stableId: String,
    val operationDescription: String,
    val compositionDescription: String,
    val changedVariables: Int,
    val rootCauseEligible: Boolean
) {
    CURRENT(
        "v0-current",
        "sqrt(effectiveAlpha)",
        "effectiveAlpha",
        0,
        true
    ),
    FULL_STRETCH_SINGLE_COMPOSE(
        "v1-full-stretch-single-compose",
        "1",
        "effectiveAlpha",
        1,
        true
    ),
    LINEAR_ALPHA_THEN_COMPOSE(
        "v2-linear-alpha-then-compose",
        "effectiveAlpha",
        "effectiveAlpha",
        1,
        true
    ),
    SQRT_ALPHA_NO_SECOND_COMPOSE(
        "v3-sqrt-alpha-no-second-compose",
        "sqrt(effectiveAlpha)",
        "1 inside binary skySelection; 0 outside",
        1,
        true
    ),
    FULL_STRETCH_HARD_COMPOSE(
        "v4-full-stretch-hard-compose",
        "1",
        "binary refinedMask",
        2,
        false
    ),
    NO_STRETCH(
        "v5-no-stretch",
        "stretch bypassed",
        "effectiveAlpha",
        1,
        true
    )
}

internal enum class AdaptiveAsinhRootCause {
    DOUBLE_ALPHA_CONFIRMED,
    SQRT_ALPHA_SPECIFIC_REGRESSION,
    GENERAL_STRETCH_PARAMETER_ERROR,
    COMPOSITION_INTERACTION_NOT_ISOLATED,
    INSUFFICIENT_EVIDENCE
}

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
    val parameters: AdaptiveProcessingParameters,
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
