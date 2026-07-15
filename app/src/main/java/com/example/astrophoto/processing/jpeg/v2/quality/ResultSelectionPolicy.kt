package com.example.astrophoto.processing.jpeg.v2.quality

import com.example.astrophoto.processing.jpeg.v2.model.FinalResultSelection
import com.example.astrophoto.processing.jpeg.v2.model.QualityGateDecision
import com.example.astrophoto.processing.jpeg.v2.model.ResultCandidate

class ResultSelectionPolicy {
    fun select(
        reference: ResultCandidate,
        cleanStack: ResultCandidate,
        processed: ResultCandidate,
        processedDecision: QualityGateDecision,
        cleanStackDecision: QualityGateDecision
    ): FinalResultSelection {
        val selected = when {
            processedDecision.accepted -> processed
            cleanStackDecision.accepted -> cleanStack
            else -> reference
        }
        val rejectionReasons = buildList {
            if (!processedDecision.accepted) addAll(processedDecision.hardFailureReasons)
            if (!cleanStackDecision.accepted) addAll(cleanStackDecision.hardFailureReasons)
        }.distinct()
        val fallbackReason = when {
            selected === processed -> null
            selected === cleanStack -> processedDecision.hardFailureReasons.firstOrNull()
                ?: "processed_candidate_rejected"
            else -> cleanStackDecision.hardFailureReasons.firstOrNull()
                ?: "clean_stack_structurally_invalid"
        }
        return FinalResultSelection(
            selected = selected,
            processedDecision = processedDecision,
            cleanStackDecision = cleanStackDecision,
            fallbackUsed = selected !== processed,
            fallbackReason = fallbackReason,
            rejectionReasons = rejectionReasons,
            internalFallbackLabel = if (selected !== processed) INTERNAL_FALLBACK_LABEL else null
        )
    }

    companion object {
        const val INTERNAL_FALLBACK_LABEL = "RecoveredStars"
    }
}
