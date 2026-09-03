package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.SessionFrame
import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis

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
