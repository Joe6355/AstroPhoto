package com.example.astrophoto.processing.jpeg.v2.registration

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.example.astrophoto.processing.jpeg.v2.model.RegistrationResult

data class TemporalFeatureFrame(
    val frameId: String,
    val captureIndex: Int,
    val stars: List<DetectedStar>
)

data class TemporalTrackObservation(
    val frameId: String,
    val captureIndex: Int,
    val star: DetectedStar
)

enum class TemporalMotionCluster {
    STATIONARY_CAMERA_SPACE,
    COHERENT_MOVING_SKY,
    UNSTABLE_OR_UNKNOWN
}

data class TemporalFeatureTrack(
    val observations: List<TemporalTrackObservation>,
    val presenceRatio: Float,
    val velocityX: Float,
    val velocityY: Float,
    val fitResidual: Float,
    val cluster: TemporalMotionCluster
)

data class TemporalTrackAnalysis(
    val tracks: List<TemporalFeatureTrack>,
    val motionObservable: Boolean,
    val coherentVelocityX: Float,
    val coherentVelocityY: Float
) {
    val stationaryTrackCount: Int
        get() = tracks.count { it.cluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE }
    val movingTrackCount: Int
        get() = tracks.count { it.cluster == TemporalMotionCluster.COHERENT_MOVING_SKY }
    val unknownTrackCount: Int
        get() = tracks.count { it.cluster == TemporalMotionCluster.UNSTABLE_OR_UNKNOWN }

    fun clusterAt(frameId: String, star: DetectedStar, tolerance: Float = 0.35f): TemporalMotionCluster {
        val match = tracks.asSequence()
            .flatMap { track -> track.observations.asSequence().map { track to it } }
            .filter { (_, observation) -> observation.frameId == frameId }
            .minByOrNull { (_, observation) ->
                val dx = observation.star.x - star.x
                val dy = observation.star.y - star.y
                dx * dx + dy * dy
            } ?: return TemporalMotionCluster.UNSTABLE_OR_UNKNOWN
        val dx = match.second.star.x - star.x
        val dy = match.second.star.y - star.y
        return if (dx * dx + dy * dy <= tolerance * tolerance) {
            match.first.cluster
        } else {
            TemporalMotionCluster.UNSTABLE_OR_UNKNOWN
        }
    }
}

data class TranslationHypothesis(
    val dx: Float,
    val dy: Float,
    val support: Int,
    val weightedSupport: Float,
    val residual: Float,
    val occupiedSectors: Int,
    val movingTrackSupport: Int,
    val stationaryTrackSupport: Int
) {
    fun referenceToSourceTransform(): ReferenceToSourceTransform =
        ReferenceToSourceTransform(dx, dy)
}

data class SequenceRegistrationCandidate(
    val frameId: String,
    val captureIndex: Int,
    val isReference: Boolean,
    val hypotheses: List<TranslationHypothesis>
)

data class StellarSequenceMotionModel(
    val velocityX: Float,
    val velocityY: Float,
    val referenceIndex: Int,
    val acceptedFrameHypotheses: Map<String, TranslationHypothesis>,
    val score: Float,
    val residual: Float,
    val motionObservable: Boolean,
    val rejectedFrameIds: List<String>,
    val competingZeroModelScore: Float,
    val nonZeroModelScore: Float,
    val selectedMotionModel: String,
    val zeroFrameHypotheses: Map<String, TranslationHypothesis> = emptyMap(),
    val nonZeroFrameHypotheses: Map<String, TranslationHypothesis> = emptyMap()
) {
    fun predictedTransform(captureIndex: Int): ReferenceToSourceTransform {
        val delta = captureIndex - referenceIndex
        if (delta == 0) return ReferenceToSourceTransform.Identity
        return ReferenceToSourceTransform(velocityX * delta, velocityY * delta)
    }

    fun predicted(captureIndex: Int): Pair<Float, Float> {
        val transform = predictedTransform(captureIndex)
        return transform.dx to transform.dy
    }
}

data class RegistrationVerificationMetrics(
    val referenceRetention: Float,
    val contrastRatio: Float,
    val widthGrowth: Float,
    val smearRate: Float,
    val reliableStarCount: Int,
    val backgroundNoise: Float,
    val stationaryArtifactStreakEvidence: Float,
    val score: Float
)

data class RegistrationSequenceVerification(
    val identity: RegistrationVerificationMetrics,
    val zeroModel: RegistrationVerificationMetrics,
    val selectedModel: RegistrationVerificationMetrics,
    val selectedModelAccepted: Boolean,
    val inverseModel: RegistrationVerificationMetrics = identity,
    val doubleAppliedModel: RegistrationVerificationMetrics = identity,
    val perFrame: Map<String, RegistrationVerificationMetrics> = emptyMap(),
    val perFrameAccepted: Map<String, Boolean> = emptyMap()
)

data class SequenceAwareRegistrationDiagnostics(
    val trackAnalysis: TemporalTrackAnalysis,
    val model: StellarSequenceMotionModel,
    val verification: RegistrationSequenceVerification,
    val registrations: Map<String, RegistrationResult>,
    val hypothesisCountPerFrame: Map<String, Int>,
    val selectedHypothesisRankPerFrame: Map<String, Int>,
    val rejectedReasons: Map<String, String>,
    val sequenceSmoothnessScore: Float,
    val sequencePriorAgreementScore: Float,
    val referenceCaptureIndex: Int,
    val analysisWidth: Int,
    val analysisHeight: Int
)

data class ExpectedSequenceMotionModel(
    val velocityX: Float,
    val velocityY: Float,
    val referenceIndex: Int,
    val residual: Float,
    val motionObservable: Boolean,
    val verificationScore: Float
) {
    fun predictedTransform(captureIndex: Int): ReferenceToSourceTransform {
        val delta = captureIndex - referenceIndex
        if (delta == 0) return ReferenceToSourceTransform.Identity
        return ReferenceToSourceTransform(velocityX * delta, velocityY * delta)
    }

    fun predicted(captureIndex: Int): Pair<Float, Float> {
        val transform = predictedTransform(captureIndex)
        return transform.dx to transform.dy
    }
}
