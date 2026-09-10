package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis

internal data class ProfileFrameSelectionPreparation(
    val selectedReference: ProfileAnalyzedFrame,
    val selectedFrames: List<SessionFrame>,
    val analyzedByFrameKey: Map<String, ProfileAnalyzedFrame>,
    val analysesByFrameKey: Map<String, FrameAnalysis>,
    val targetWidth: Int,
    val targetHeight: Int,
    val droppedCount: Int,
    val referenceScore: Float
)

/** Owns deterministic quality selection and reference-frame preparation. */
internal object ProfileFrameSelectionCoordinator {
    /** Promote the actual registration reference before any full-resolution work or composition. */
    fun withReference(
        preparation: ProfileFrameSelectionPreparation,
        referenceFrameKey: String,
        dimensionsByFrameKey: Map<String, Pair<Int, Int>>
    ): ProfileFrameSelectionPreparation {
        if (preparation.selectedReference.frame.key == referenceFrameKey) return preparation
        require(preparation.selectedFrames.any { it.key == referenceFrameKey })
        val reference = preparation.analyzedByFrameKey.getValue(referenceFrameKey)
        require(reference.analysis.hardInvalidReason == null)
        val dimensions = dimensionsByFrameKey.getValue(referenceFrameKey)
        val score = ReferenceFrameSelector().scoreAll(preparation.selectedFrames.map {
            preparation.analysesByFrameKey.getValue(it.key)
        }).single { it.analysis.id == referenceFrameKey }.score
        return preparation.copy(
            selectedReference = reference,
            selectedFrames = listOf(reference.frame) + preparation.selectedFrames.filterNot {
                it.key == referenceFrameKey
            },
            targetWidth = dimensions.first,
            targetHeight = dimensions.second,
            referenceScore = score
        )
    }

    fun prepare(
        analyzedFrames: List<ProfileAnalyzedFrame>,
        analysisFrames: List<SessionFrame>,
        captureIndexByFrameKey: Map<String, Int>,
        dimensionsByFrameKey: Map<String, Pair<Int, Int>>,
        maxFrames: Int
    ): ProfileFrameSelectionPreparation {
        val selector = ReferenceFrameSelector()
        val integrationSelection = selector.selectForIntegration(
            analyses = analyzedFrames.map { it.analysis },
            captureIndexByFrameId = captureIndexByFrameKey,
            maxFrames = maxFrames
        )
        val selectedAnalysisIds = integrationSelection.analyses.mapTo(mutableSetOf()) { it.id }
        val qualitySelectedFrames = analysisFrames.filter { it.key in selectedAnalysisIds }
        val referenceSelection = selector.select(integrationSelection.analyses)
        val selectedReference = analyzedFrames.first {
            it.analysis.id == referenceSelection.analysis.id
        }
        val referenceDimensions = checkNotNull(dimensionsByFrameKey[selectedReference.frame.key])
        val selectedFrames = (
            listOf(selectedReference.frame) + qualitySelectedFrames.filterNot {
                it.key == selectedReference.frame.key
            }
            ).take(maxFrames)
        return ProfileFrameSelectionPreparation(
            selectedReference = selectedReference,
            selectedFrames = selectedFrames,
            analyzedByFrameKey = analyzedFrames.associateBy { it.frame.key },
            analysesByFrameKey = analyzedFrames.associate { it.frame.key to it.analysis },
            targetWidth = referenceDimensions.first,
            targetHeight = referenceDimensions.second,
            droppedCount = integrationSelection.droppedCount,
            referenceScore = referenceSelection.score
        )
    }
}
