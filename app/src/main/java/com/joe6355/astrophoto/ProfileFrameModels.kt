package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionRefinementResult
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidRefinementResult
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.CachedArgbFrame
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.SamplingFidelityResult

internal data class AcceptedProfileFrame(
    val frame: SessionFrame,
    val analysis: FrameAnalysis,
    val registration: RegistrationResult,
    val captureIndex: Int = 0
)

internal data class FullResolutionPreparationResult(
    val acceptedFrames: List<AcceptedProfileFrame>,
    val cachedFrames: List<Pair<AcceptedProfileFrame, CachedArgbFrame>>,
    val finalRegistrationsByKey: Map<String, RegistrationResult>,
    val refinementResultsByKey: Map<String, FullResolutionRefinementResult>,
    val centroidResultsByKey: Map<String, StellarCentroidRefinementResult>,
    val samplingFidelity: SamplingFidelityResult,
    val warnings: List<String>
)
