package com.example.astrophoto.processing.jpeg.v2.artifacts

import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform

data class AutomaticSensorDefectMaskResult(
    val mask: SensorDefectMask,
    val originalFrameIndices: List<Int>,
    val diagnostics: AutomaticSensorDefectMaskDiagnostics
)

data class AutomaticSensorDefectMaskStageDiagnostics(
    val elapsedNanos: Long,
    val inputCount: Int,
    val outputCount: Int,
    val processedUnitCount: Long,
    val estimatedAllocatedBytes: Long
)

data class AutomaticSensorDefectMaskDiagnostics(
    val inputFrameCount: Int,
    val observationCandidateCount: Int,
    val observationProcessedPixelCount: Long,
    val additionalImageDecodeCount: Int,
    val additionalFullFrameScanCount: Int,
    val candidateMatchingAnchorVisitCount: Long,
    val candidateMatchingCandidateVisitCount: Long,
    val candidateMatchingDistanceComparisonCount: Long,
    val candidateMatchingIdentityLookupCount: Long,
    val persistentObservationExtraction: AutomaticSensorDefectMaskStageDiagnostics,
    val candidateMatching: AutomaticSensorDefectMaskStageDiagnostics,
    val recurrenceCalculation: AutomaticSensorDefectMaskStageDiagnostics,
    val footprintConstruction: AutomaticSensorDefectMaskStageDiagnostics,
    val maskValidation: AutomaticSensorDefectMaskStageDiagnostics
)

internal fun buildAutomaticSensorDefectMask(
    observations: List<PersistentSensorFrameObservation>,
    outputWidth: Int,
    outputHeight: Int,
    predictedTransform: (originalCaptureIndex: Int) -> ReferenceToSourceTransform
): AutomaticSensorDefectMaskResult {
    require(observations.isNotEmpty())
    require(outputWidth > 0 && outputHeight > 0)
    val ordered = observations.sortedBy { it.originalCaptureIndex }
    require(ordered.map { it.originalCaptureIndex }.distinct().size == ordered.size) {
        "Persistent sensor observations contain duplicate capture indices"
    }
    val analysisWidth = ordered.first().width
    val analysisHeight = ordered.first().height
    require(ordered.all { it.width == analysisWidth && it.height == analysisHeight }) {
        "Persistent sensor observations have mismatched dimensions"
    }
    val artifactFrames = ordered.map(PersistentSensorFrameObservation::asArtifactFrameObservation)
    val profiledTracks = TemporalPixelConsistency().stationaryTracksProfiled(artifactFrames)
    val tracks = profiledTracks.tracks
    val recurrenceStarted = System.nanoTime()
    val staticMask = StaticArtifactAnalyzer().analyzeTracks(
        tracks,
        artifactFrames.size,
        analysisWidth,
        analysisHeight
    )
    val recurrenceElapsedNanos = System.nanoTime() - recurrenceStarted
    val profiledMask = buildConfirmedSensorDefectMaskProfiled(
        staticMask = staticMask,
        frames = artifactFrames,
        referenceToSourceTransforms = ordered.map {
            predictedTransform(it.originalCaptureIndex)
        }
    )
    val scalingStarted = System.nanoTime()
    val mask = profiledMask.mask.scaledTo(outputWidth, outputHeight)
    val scalingElapsedNanos = System.nanoTime() - scalingStarted
    val candidateCount = artifactFrames.sumOf { it.stars.size }
    val trackObservationCount = tracks.sumOf { it.observations.size }
    return AutomaticSensorDefectMaskResult(
        mask = mask,
        originalFrameIndices = ordered.map { it.originalCaptureIndex },
        diagnostics = AutomaticSensorDefectMaskDiagnostics(
            inputFrameCount = ordered.size,
            observationCandidateCount = candidateCount,
            observationProcessedPixelCount = ordered.sumOf { it.processedPixelCount },
            additionalImageDecodeCount = 0,
            additionalFullFrameScanCount = 0,
            candidateMatchingAnchorVisitCount = profiledTracks.anchorVisitCount,
            candidateMatchingCandidateVisitCount = profiledTracks.candidateVisitCount,
            candidateMatchingDistanceComparisonCount =
                profiledTracks.distanceComparisonCount,
            candidateMatchingIdentityLookupCount = profiledTracks.identityLookupCount,
            persistentObservationExtraction = AutomaticSensorDefectMaskStageDiagnostics(
                elapsedNanos = ordered.sumOf { it.extractionElapsedNanos },
                inputCount = ordered.size,
                outputCount = candidateCount,
                processedUnitCount = ordered.sumOf { it.processedPixelCount },
                estimatedAllocatedBytes = ordered.sumOf { it.estimatedAllocatedBytes }
            ),
            candidateMatching = AutomaticSensorDefectMaskStageDiagnostics(
                elapsedNanos = profiledTracks.elapsedNanos,
                inputCount = candidateCount,
                outputCount = tracks.size,
                processedUnitCount =
                    profiledTracks.candidateVisitCount +
                        profiledTracks.identityLookupCount,
                estimatedAllocatedBytes =
                    candidateCount.toLong() +
                        trackObservationCount * TRACK_OBSERVATION_BYTES_ESTIMATE
            ),
            recurrenceCalculation = AutomaticSensorDefectMaskStageDiagnostics(
                elapsedNanos = recurrenceElapsedNanos +
                    profiledMask.recurrenceCalculation.elapsedNanos,
                inputCount = tracks.size,
                outputCount = staticMask.regions.size,
                processedUnitCount = trackObservationCount.toLong(),
                estimatedAllocatedBytes =
                    staticMask.regions.size * STATIC_REGION_BYTES_ESTIMATE +
                        profiledMask.recurrenceCalculation.estimatedAllocatedBytes
            ),
            footprintConstruction = profiledMask.footprintConstruction.toAutomaticProfile(),
            maskValidation = profiledMask.maskValidation.toAutomaticProfile().copy(
                elapsedNanos =
                    profiledMask.maskValidation.elapsedNanos + scalingElapsedNanos,
                processedUnitCount =
                    profiledMask.maskValidation.processedPixels +
                        outputWidth.toLong() * outputHeight,
                estimatedAllocatedBytes =
                    profiledMask.maskValidation.estimatedAllocatedBytes +
                        outputWidth.toLong() * outputHeight +
                        mask.footprintPixels.size * SENSOR_FOOTPRINT_PIXEL_BYTES_ESTIMATE
            )
        )
    )
}

private fun SensorDefectMaskBuildStageProfile.toAutomaticProfile() =
    AutomaticSensorDefectMaskStageDiagnostics(
        elapsedNanos,
        inputCount,
        outputCount,
        processedPixels,
        estimatedAllocatedBytes
    )

private const val TRACK_OBSERVATION_BYTES_ESTIMATE = 32L
private const val STATIC_REGION_BYTES_ESTIMATE = 64L
private const val SENSOR_FOOTPRINT_PIXEL_BYTES_ESTIMATE = 24L
