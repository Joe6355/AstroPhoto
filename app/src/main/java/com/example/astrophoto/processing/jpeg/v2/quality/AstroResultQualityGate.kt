package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper

class AstroResultQualityGate(
    private val starComparator: StarResultComparator = StarResultComparator(),
    private val backgroundComparator: BackgroundQualityComparator = BackgroundQualityComparator(),
    private val foregroundComparator: ForegroundQualityComparator = ForegroundQualityComparator()
) {
    fun evaluateProcessed(
        reference: ResultCandidate,
        cleanStack: ResultCandidate,
        processed: ResultCandidate,
        profile: AstroProcessingProfile,
        frameCount: Int
    ): QualityGateDecision {
        val comparisons = listOf(
            foregroundComparator.compare(reference.metrics, processed.metrics),
            starComparator.compare(cleanStack.metrics, processed.metrics, profile),
            backgroundComparator.compare(
                cleanStack.metrics,
                processed.metrics,
                ExistingPresetParameterMapper.parametersFor(profile, frameCount)
            )
        )
        return decision(processed, comparisons)
    }

    fun evaluateCleanStack(
        reference: ResultCandidate,
        cleanStack: ResultCandidate,
        profile: AstroProcessingProfile
    ): QualityGateDecision = decision(
        cleanStack,
        listOf(
            foregroundComparator.compare(reference.metrics, cleanStack.metrics),
            starComparator.compare(reference.metrics, cleanStack.metrics, profile)
        )
    )

    private fun decision(
        candidate: ResultCandidate,
        comparisons: List<QualityComparison>
    ): QualityGateDecision {
        val hard = comparisons.flatMap { it.hardFailureReasons }.distinct()
        val warnings = comparisons.flatMap { it.warningReasons }.distinct()
        val score = (
            1f - hard.size * HARD_FAILURE_SCORE_PENALTY -
                warnings.size * WARNING_SCORE_PENALTY
            ).coerceIn(0f, 1f)
        return QualityGateDecision(
            accepted = hard.isEmpty(),
            score = score,
            hardFailureReasons = hard,
            warningReasons = warnings,
            metrics = candidate.metrics
        )
    }

    companion object {
        private const val HARD_FAILURE_SCORE_PENALTY = 0.30f
        private const val WARNING_SCORE_PENALTY = 0.05f
    }
}
