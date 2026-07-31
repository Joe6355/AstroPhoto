package com.example.astrophoto.processing.jpeg.v2.model

import com.example.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionStage

data class SensorDefectRegionReport(
    val stableRegionId: String,
    val footprintPixelCount: Int,
    val recurrence: Int,
    val totalFrameCount: Int,
    val skySpaceSupport: Int,
    val confidence: Float,
    val classificationReason: String
)

data class SensorDefectConstructionStageReport(
    val stage: String,
    val elapsedNanos: Long,
    val inputCount: Int,
    val outputCount: Int,
    val processedUnitCount: Long,
    val estimatedAllocatedBytes: Long
)

data class SensorDefectFilteringReport(
    val regions: List<SensorDefectRegionReport> = emptyList(),
    val maskedSourcePixelCount: Int = 0,
    val maskedSourceFraction: Float = 0f,
    val excludedSampleCount: Long = 0L,
    val affectedOutputPixelCount: Int = 0,
    val minimumRemainingSampleCount: Int = 0,
    val medianRemainingSampleCount: Int = 0,
    val maximumRemainingSampleCount: Int = 0,
    val expectedUnmaskedWeight: Float = 0f,
    val minimumValidWeight: Float = 0f,
    val medianValidWeight: Float = 0f,
    val maximumValidWeight: Float = 0f,
    val minimumValidWeightRatio: Float = 0f,
    val medianValidWeightRatio: Float = 0f,
    val maximumValidWeightRatio: Float = 0f,
    val insufficientCoveragePixelCount: Int = 0,
    val insufficientCoverageFraction: Float = 0f,
    val maskEnabled: Boolean = false,
    val sampleLevelFilteringApplied: Boolean = false,
    val filteringAppliedToFinalResult: Boolean = sampleLevelFilteringApplied,
    val unmaskedRetryUsed: Boolean = false,
    val fallbackOrRejectionReason: String? = null,
    val originalFrameIndices: List<Int> = emptyList(),
    val maskConstructionDurationMillis: Long = 0L,
    val integrationDurationMillis: Long = 0L,
    val cleanCandidateMasked: Boolean = false,
    val processedCandidateMasked: Boolean = false,
    val selectedCandidateMasked: Boolean = false,
    val selectedCandidateHash: String? = null,
    val publishedOutputHash: String? = null,
    val starPreservationMaskAware: Boolean = false,
    val starPreservationMaskedReferenceSamplesSkipped: Long = 0L,
    val starPreservationAffectedOutputPixelCount: Int = 0,
    val starPreservationReason: String? = null,
    val compositionMaskAware: Boolean = false,
    val compositionMaskedReferenceSamplesSkipped: Long = 0L,
    val compositionAffectedOutputPixelCount: Int = 0,
    val compositionMeanOriginalAlpha: Float = 0f,
    val compositionReason: String? = null,
    val compositionSafeBehavior: String? = null,
    val observationFrameCount: Int = 0,
    val observationCandidateCount: Int = 0,
    val observationProcessedPixelCount: Long = 0L,
    val additionalImageDecodeCount: Int = 0,
    val additionalFullFrameScanCount: Int = 0,
    val candidateMatchingAnchorVisitCount: Long = 0L,
    val candidateMatchingCandidateVisitCount: Long = 0L,
    val candidateMatchingDistanceComparisonCount: Long = 0L,
    val candidateMatchingIdentityLookupCount: Long = 0L,
    val constructionStages: List<SensorDefectConstructionStageReport> = emptyList(),
    val referenceStarRetentionStages: List<ReferenceStarRetentionStage> = emptyList()
) {
    val regionCount: Int get() = regions.size
}
