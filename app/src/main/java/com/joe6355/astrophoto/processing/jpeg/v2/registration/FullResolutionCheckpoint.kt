package com.joe6355.astrophoto.processing.jpeg.v2.registration

import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.SamplingFidelityResult
import java.io.Serializable

/** Only completed verification results are stored; source caches are rebuilt under the memory budget. */
data class FullResolutionCheckpoint(
    val width: Int,
    val height: Int,
    val initialRegistrations: Map<String, RegistrationResult>,
    val finalRegistrations: Map<String, RegistrationResult>,
    val refinements: Map<String, FullResolutionRefinementResult>,
    val centroids: Map<String, StellarCentroidRefinementResult>,
    val samplingFidelity: SamplingFidelityResult,
    val warnings: List<String>
) : Serializable {
    fun matches(width: Int, height: Int, initial: Map<String, RegistrationResult>): Boolean =
        this.width == width && this.height == height && initialRegistrations == initial &&
            initial.keys == finalRegistrations.keys && initial.keys == refinements.keys &&
            initial.keys == centroids.keys && finalRegistrations.all { (id, registration) ->
                registration.isReliable == centroids.getValue(id).accepted &&
                    registration.referenceToSourceTransform() == centroids.getValue(id).refinedTransform
            }
}
