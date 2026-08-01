package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.model.AlphaMask
import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.SkyMask
import com.example.astrophoto.processing.jpeg.v2.model.StretchDiagnostics

internal enum class SkyMaskReplayVariantId(val stableId: String) {
    CURRENT("current"),
    NO_MASK("no-mask"),
    HARD_MASK("hard-mask"),
    NO_REFINE("no-refine"),
    NO_PROTECTION("no-protection"),
    NO_POSTPROCESS("no-postprocess")
}

internal data class SkyMaskReplayVariant(
    val id: SkyMaskReplayVariantId,
    val alpha: AlphaMask,
    val processedSky: ArgbPixelImage,
    val output: ArgbPixelImage,
    val initialMaskEnabled: Boolean,
    val refinementEnabled: Boolean,
    val foregroundProtectionEnabled: Boolean,
    val postProcessingEnabled: Boolean
)

internal data class SkyMaskTopologyMetrics(
    val boundaryPixels: Int,
    val disconnectedRegions: Int,
    val smallIslandCount: Int,
    val smallIslandPixels: Int,
    val holeCount: Int,
    val holePixels: Int
)

internal data class SkyMaskAlphaMetrics(
    val zeroPixels: Int,
    val belowOnePercentPixels: Int,
    val transitionPixels: Int,
    val aboveNinetyNinePercentPixels: Int,
    val onePixels: Int,
    val meanTransitionRunWidth: Double,
    val maximumTransitionRunWidth: Int
)

internal data class SkyMaskBoundaryMetrics(
    val initial: SkyMaskTopologyMetrics,
    val refined: SkyMaskTopologyMetrics,
    val foregroundProtection: SkyMaskTopologyMetrics,
    val transitionBandArea: Int,
    val alpha: SkyMaskAlphaMetrics,
    val foregroundRiskInclusionProxyPixels: Int,
    val initialPixelsRemovedByRefinementProxy: Int
)

internal data class SkyMaskDiagnosticWindow(
    val id: String,
    val centerX: Int,
    val centerY: Int,
    val size: Int,
    val source: String
)

internal data class SkyMaskStarStageMetrics(
    val starId: String,
    val stage: String,
    val centroidX: Double,
    val centroidY: Double,
    val peakLuminance: Double,
    val apertureFlux: Double,
    val localBackground: Double,
    val localContrast: Double,
    val robustWidth: Double,
    val ellipticity: Double,
    val chromaResidual: Double,
    val distanceToMaskBoundary: Double,
    val centerAlpha: Double,
    val minimumApertureAlpha: Double,
    val meanApertureAlpha: Double,
    val maximumApertureAlpha: Double,
    val apertureFractionBelowHalfAlpha: Double,
    val apertureFractionProtected: Double,
    val fluxRetentionFromClean: Double,
    val peakAttenuationFromClean: Double,
    val centroidShiftFromClean: Double,
    val widthRatioFromClean: Double
)

internal data class SkyMaskWindowMetrics(
    val windowId: String,
    val centerX: Int,
    val centerY: Int,
    val distanceToBoundary: Double,
    val brightRim: Double,
    val darkRim: Double,
    val haloAsymmetry: Double,
    val haloScore: Double,
    val luminanceJump: Double,
    val chromaJump: Double,
    val firstDerivativeExcess: Double,
    val secondDerivativeSpike: Double,
    val localVarianceMismatch: Double,
    val edgeAlignedResidual: Double,
    val leakageScore: Double
)

internal data class SkyMaskVariantMetrics(
    val variant: SkyMaskReplayVariantId,
    val skyMad: Double,
    val foregroundMeanChange: Double,
    val bandingProxy: Double,
    val meanHaloScore: Double,
    val meanLeakageScore: Double,
    val strictStarMedianFluxRetention: Double,
    val strictStarMaximumCentroidShift: Double
)

internal data class SkyMaskPostProcessStage(
    val id: String,
    val image: ArgbPixelImage
)

internal data class SkyMaskStarStageInput(
    val id: String,
    val image: ArgbPixelImage,
    val alpha: AlphaMask
)

internal data class SkyMaskPostProcessStageMetrics(
    val stage: String,
    val skyMad: Double,
    val bandingProxy: Double,
    val boundaryEdgeExcess: Double,
    val meanAbsoluteChangeFromClean: Double
)

internal enum class SkyMaskReplayCause {
    INITIAL_MASK_ERROR,
    REFINEMENT_ERROR,
    ALPHA_TRANSITION_ERROR,
    FOREGROUND_PROTECTION_ERROR,
    COMPOSITION_ERROR,
    POSTPROCESS_ERROR,
    SOURCE_ARTIFACT,
    REGISTRATION_OR_STACK_ARTIFACT,
    INSUFFICIENT_EVIDENCE
}

internal data class SkyMaskReplayIssue(
    val windowId: String,
    val cause: SkyMaskReplayCause,
    val observedDefect: String,
    val firstBadStage: String,
    val lastCleanStage: String,
    val supportingMetric: String,
    val confidence: Double,
    val minimalFixCandidate: String,
    val fixRisk: String,
    val requiredRegressionTest: String
)

internal data class SkyMaskReplayBundle(
    val fixture: Stage6RegressionFixture,
    val reference: ArgbPixelImage,
    val unmaskedIntegration: ArgbPixelImage,
    val cleanStack: ArgbPixelImage,
    val processedSky: ArgbPixelImage,
    val cleanComposed: ArgbPixelImage,
    val composedCurrent: ArgbPixelImage,
    val finalCurrent: ArgbPixelImage,
    val selectedCandidateType: String,
    val cleanCandidateAccepted: Boolean,
    val processedCandidateAccepted: Boolean,
    val processedCandidateRejectionReasons: List<String>,
    val initialMask: SkyMask,
    val refinedMask: SkyMask,
    val effectiveAlpha: AlphaMask,
    val validCoverage: AlphaMask,
    val sensorDefectAffectedOutput: AlphaMask,
    val sensorDefectMask: SensorDefectMask,
    val foregroundProtection: SkyMask,
    val skySelection: SkyMask,
    val alignedStackStars: List<DetectedStar>,
    val alignmentModelScore: Float,
    val alignmentTransformFingerprint: String,
    val currentStretchDiagnostics: StretchDiagnostics,
    val variants: List<SkyMaskReplayVariant>,
    val boundaryMetrics: SkyMaskBoundaryMetrics,
    val windows: List<SkyMaskDiagnosticWindow>,
    val windowMetrics: List<SkyMaskWindowMetrics>,
    val strictStarMetrics: List<SkyMaskStarStageMetrics>,
    val variantMetrics: List<SkyMaskVariantMetrics>,
    val postProcessingStages: List<SkyMaskPostProcessStage>,
    val postProcessingStageMetrics: List<SkyMaskPostProcessStageMetrics>,
    val issues: List<SkyMaskReplayIssue>,
    val acceptedOriginalFrameIndices: List<Int>,
    val rejectedOriginalFrameIndices: List<Int>,
    val initialMaskConfidence: Float,
    val initialMaskUsedFallback: Boolean,
    val refinedMaskConfidence: Float,
    val refinedMaskUsedFallback: Boolean,
    val featherRadius: Int,
    val foregroundProtectionRadius: Int,
    val adaptiveReplayMatchesProductionPixels: Boolean,
    val activeFileBackedMaximumChannelDifference: Int,
    val activeFileBackedDifferentPixelCount: Int,
    val pipelineManifestJson: String
)
