package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.AstroProcessingProfile
import com.example.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.example.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.example.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper

class AstroResultQualityGate(
    private val starComparator: StarResultComparator = StarResultComparator(),
    private val backgroundComparator: BackgroundQualityComparator = BackgroundQualityComparator(),
    private val foregroundComparator: ForegroundQualityComparator = ForegroundQualityComparator()
) {
    fun evaluateReference(reference: StoredResultCandidate): QualityGateDecision = decision(
        reference.metrics,
        listOf(foregroundComparator.compare(reference.metrics, reference.metrics))
    )

    fun evaluateReference(reference: ResultCandidate): QualityGateDecision = decision(
        reference,
        listOf(foregroundComparator.compare(reference.metrics, reference.metrics))
    )

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

    fun evaluateProcessed(
        reference: StoredResultCandidate,
        cleanStack: StoredResultCandidate,
        processed: StoredResultCandidate,
        profile: AstroProcessingProfile,
        frameCount: Int
    ): QualityGateDecision = decision(
        processed.metrics,
        listOf(
            foregroundComparator.compare(reference.metrics, processed.metrics),
            starComparator.compare(cleanStack.metrics, processed.metrics, profile),
            backgroundComparator.compare(
                cleanStack.metrics,
                processed.metrics,
                ExistingPresetParameterMapper.parametersFor(profile, frameCount)
            )
        )
    )

    fun evaluateCleanStack(
        reference: ResultCandidate,
        cleanStack: ResultCandidate,
        profile: AstroProcessingProfile,
        evidence: CleanStackValidationEvidence? = null
    ): QualityGateDecision = decision(
        cleanStack,
        buildList {
            add(foregroundComparator.compare(reference.metrics, cleanStack.metrics))
            add(
                starComparator.compare(
                    reference.metrics,
                    cleanStack.metrics,
                    profile,
                    matchedStarsValidated =
                        evidence?.referenceStarRetention?.accepted == true
                )
            )
            evidence?.let {
                add(QualityComparison(it.hardFailureReasons, it.warningReasons))
            }
        }
    )

    fun evaluateCleanStack(
        reference: StoredResultCandidate,
        cleanStack: StoredResultCandidate,
        profile: AstroProcessingProfile,
        evidence: CleanStackValidationEvidence? = null
    ): QualityGateDecision = decision(
        cleanStack.metrics,
        buildList {
            add(foregroundComparator.compare(reference.metrics, cleanStack.metrics))
            add(
                starComparator.compare(
                    reference.metrics,
                    cleanStack.metrics,
                    profile,
                    matchedStarsValidated =
                        evidence?.referenceStarRetention?.accepted == true
                )
            )
            evidence?.let { add(QualityComparison(it.hardFailureReasons, it.warningReasons)) }
        }
    )

    private fun decision(
        candidate: ResultCandidate,
        comparisons: List<QualityComparison>
    ): QualityGateDecision = decision(candidate.metrics, comparisons)

    private fun decision(
        metrics: com.example.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
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
            metrics = metrics
        )
    }

    companion object {
        private const val HARD_FAILURE_SCORE_PENALTY = 0.30f
        private const val WARNING_SCORE_PENALTY = 0.05f
    }
}
