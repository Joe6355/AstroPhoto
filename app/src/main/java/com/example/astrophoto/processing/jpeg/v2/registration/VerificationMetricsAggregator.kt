package com.example.astrophoto.processing.jpeg.v2.registration

data class VerificationAggregation(
    val sampleCount: Int,
    val acceptedSampleCount: Int,
    val rejectedSampleCount: Int,
    val acceptedMean: RegistrationVerificationMetrics?
) {
    companion object {
        val Empty = VerificationAggregation(0, 0, 0, null)
    }
}

/** Aggregates defined non-reference samples only; missing verification is never a synthetic failure. */
object VerificationMetricsAggregator {
    fun aggregate(
        perFrame: Map<String, RegistrationVerificationMetrics>,
        acceptedFrameIds: Set<String>,
        rejectedFrameIds: Set<String>,
        referenceFrameId: String
    ): VerificationAggregation {
        val defined = perFrame.filterKeys { it != referenceFrameId }
        val accepted = defined.filterKeys { it in acceptedFrameIds }.values.toList()
        val rejected = defined.filterKeys { it in rejectedFrameIds }.values.toList()
        return VerificationAggregation(
            sampleCount = defined.size,
            acceptedSampleCount = accepted.size,
            rejectedSampleCount = rejected.size,
            acceptedMean = mean(accepted)
        )
    }

    private fun mean(values: List<RegistrationVerificationMetrics>): RegistrationVerificationMetrics? {
        if (values.isEmpty()) return null
        return RegistrationVerificationMetrics(
            referenceRetention = values.map { it.referenceRetention }.average().toFloat(),
            contrastRatio = values.map { it.contrastRatio }.average().toFloat(),
            widthGrowth = values.map { it.widthGrowth }.average().toFloat(),
            smearRate = values.map { it.smearRate }.average().toFloat(),
            reliableStarCount = values.sumOf { it.reliableStarCount },
            backgroundNoise = values.map { it.backgroundNoise }.average().toFloat(),
            stationaryArtifactStreakEvidence = values
                .map { it.stationaryArtifactStreakEvidence }.average().toFloat(),
            score = values.map { it.score }.average().toFloat()
        )
    }
}
