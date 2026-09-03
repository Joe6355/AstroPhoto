package com.joe6355.astrophoto.processing.jpeg.v2.quality

import com.joe6355.astrophoto.AstroProcessingProfile
import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityComparison
import com.joe6355.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.joe6355.astrophoto.processing.jpeg.v2.model.ResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.model.StoredResultCandidate
import com.joe6355.astrophoto.processing.jpeg.v2.profile.ExistingPresetParameterMapper

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
            backgroundComparisonFor(
                cleanStack.metrics,
                processed.metrics,
                profile,
                frameCount
            ),
            experimentalNoOpComparison(cleanStack.metrics, processed.metrics, profile)
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
            backgroundComparisonFor(
                cleanStack.metrics,
                processed.metrics,
                profile,
                frameCount
            ),
            experimentalNoOpComparison(cleanStack.metrics, processed.metrics, profile)
        )
    )

    private fun experimentalNoOpComparison(
        clean: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
        processed: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
        profile: AstroProcessingProfile
    ): QualityComparison = if (
        profile == AstroProcessingProfile.EXPERIMENTAL_STARS && processed == clean
    ) {
        QualityComparison(
            hardFailureReasons = listOf("experimental_processing_returned_clean_fallback")
        )
    } else {
        QualityComparison()
    }

    private fun backgroundComparisonFor(
        clean: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
        processed: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
        profile: AstroProcessingProfile,
        frameCount: Int
    ): QualityComparison {
        val comparison = backgroundComparator.compare(
            clean,
            processed,
            ExistingPresetParameterMapper.parametersFor(profile, frameCount)
        )
        if (profile != AstroProcessingProfile.EXPERIMENTAL_STARS) return comparison
        val relaxed = comparison.hardFailureReasons.filter(EXPERIMENTAL_REVIEW_ONLY_REASONS::contains)
        return QualityComparison(
            hardFailureReasons = comparison.hardFailureReasons - relaxed.toSet(),
            warningReasons = (comparison.warningReasons + relaxed.map { "experimental_review_$it" }).distinct()
        )
    }

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
        metrics: com.joe6355.astrophoto.processing.jpeg.v2.model.ResultQualityMetrics,
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
        private val EXPERIMENTAL_REVIEW_ONLY_REASONS = setOf(
            "sky_mad_increased_excessively",
            "banding_increased_excessively",
            "gradient_residual_worsened"
        )
    }
}
