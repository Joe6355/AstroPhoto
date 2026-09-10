package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.SessionFrame
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ScoredFrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

internal fun buildTemporalFeatureFrames(
    selectedFrames: List<SessionFrame>,
    analysesByFrameKey: Map<String, FrameAnalysis>,
    captureIndexByFrameKey: Map<String, Int>
): List<TemporalFeatureFrame> = selectedFrames.map { frame ->
    TemporalFeatureFrame(
        frameId = frame.key,
        captureIndex = captureIndexByFrameKey.getValue(frame.key),
        stars = analysesByFrameKey.getValue(frame.key).stars
    )
}

internal suspend fun restoreOrComputeRegistration(
    store: ProfileRegistrationCheckpointStore,
    onRestored: suspend () -> Unit,
    compute: suspend () -> SequenceAwareRegistrationDiagnostics
): SequenceAwareRegistrationDiagnostics {
    val restored = store.read()
    if (restored != null) {
        onRestored()
        return restored
    }
    return compute().also { diagnostics ->
        try {
            store.write(diagnostics)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Optional cache failure must not discard successfully computed registration.
            store.clear()
        }
    }
}

/** Retry only a failed initial alignment, using the same registration/acceptance policy. */
internal suspend fun registerWithReferenceRecovery(
    selectedAnalyses: List<FrameAnalysis>,
    captureIndexByFrameKey: Map<String, Int>,
    referenceFrameId: String,
    minimumFrames: Int,
    onAttempt: suspend (Int, Int, String) -> Unit = { _, _, _ -> },
    register: suspend (String) -> SequenceAwareRegistrationDiagnostics
): SequenceAwareRegistrationDiagnostics {
    require(minimumFrames > 0)
    currentCoroutineContext().ensureActive()
    val initial = register(referenceFrameId)
    if (initial.registrations.values.count { it.isReliable } >= minimumFrames) return initial
    val candidates = rankedReferenceRecoveryCandidates(
        selectedAnalyses,
        captureIndexByFrameKey,
        referenceFrameId
    )
    for ((index, candidate) in candidates.withIndex()) {
        currentCoroutineContext().ensureActive()
        onAttempt(index + 1, candidates.size, candidate.analysis.id)
        val retry = register(candidate.analysis.id)
        if (retry.registrations.values.count { it.isReliable } >= minimumFrames) return retry
    }
    // Do not replace a failed result with another failed result or manufacture accepted frames.
    return initial
}

/** One deterministic candidate order is shared by thumbnail and full-resolution recovery. */
internal fun rankedReferenceRecoveryCandidates(
    selectedAnalyses: List<FrameAnalysis>,
    captureIndexByFrameKey: Map<String, Int>,
    referenceFrameId: String
): List<ScoredFrameAnalysis> {
    val original = selectedAnalyses.single { it.id == referenceFrameId }
    val indices = selectedAnalyses.map { captureIndexByFrameKey.getValue(it.id) }
    val center = (indices.min().toDouble() + indices.max()) / 2.0
    return ReferenceFrameSelector().scoreAll(selectedAnalyses)
        .filter {
            it.analysis.id != referenceFrameId && it.analysis.hardInvalidReason == null &&
                it.score.isFinite() && it.analysis.reliableStarCount >= 4 &&
                it.analysis.width == original.width && it.analysis.height == original.height
        }.sortedWith(compareByDescending<ScoredFrameAnalysis> { it.score }
            .thenBy { abs(captureIndexByFrameKey.getValue(it.analysis.id) - center) }
            .thenBy { captureIndexByFrameKey.getValue(it.analysis.id) }
            .thenBy { it.analysis.fileName }.thenBy { it.analysis.id })
}
