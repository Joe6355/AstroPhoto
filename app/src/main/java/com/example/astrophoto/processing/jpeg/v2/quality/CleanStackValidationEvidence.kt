package com.example.astrophoto.processing.jpeg.v2.quality

data class CleanStackValidationEvidence(
    val referenceStarRetention: ReferenceStarRetentionResult,
    val coverageUniformity: CoverageUniformityResult,
    val lineArtifacts: LineArtifactResult,
    val transformSequenceScore: Float,
    val acceptedFrameCount: Int,
    val transformSequenceValid: Boolean
) {
    val hardFailureReasons: List<String> = buildList {
        addAll(referenceStarRetention.hardFailureReasons)
        addAll(coverageUniformity.hardFailureReasons)
        addAll(lineArtifacts.hardFailureReasons)
        if (!transformSequenceValid) add("transform_sequence_inconsistent")
        if (acceptedFrameCount < 2) add("insufficient_registered_frames")
    }.distinct()

    val warningReasons: List<String> = buildList {
        addAll(referenceStarRetention.warningReasons)
        addAll(coverageUniformity.warningReasons)
        addAll(lineArtifacts.warningReasons)
        if (transformSequenceScore < WARNING_SEQUENCE_SCORE) add("transform_sequence_score_is_low")
    }.distinct()

    val accepted: Boolean get() = hardFailureReasons.isEmpty()

    companion object {
        private const val WARNING_SEQUENCE_SCORE = 0.75f
    }
}
