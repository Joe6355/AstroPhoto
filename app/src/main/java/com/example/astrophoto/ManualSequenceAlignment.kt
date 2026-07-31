package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.example.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.example.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.example.astrophoto.processing.jpeg.v2.artifacts.SensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.example.astrophoto.processing.jpeg.v2.artifacts.PersistentSensorCandidateDetector
import com.example.astrophoto.processing.jpeg.v2.artifacts.buildConfirmedSensorDefectMask
import com.example.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.example.astrophoto.processing.jpeg.v2.model.FrameAnalysis
import com.example.astrophoto.processing.jpeg.v2.model.SensorDefectFilteringReport
import com.example.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.example.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ManualSequenceAlignmentPlan(
    val referenceFrameIndex: Int,
    val frames: List<ManualSequenceFrameDecision>,
    val modelScore: Float,
    val modelResidualPx: Float,
    val stationaryArtifactCount: Int,
    val sensorDefectMask: SensorDefectMask? = null
) {
    val shifts: List<AlignmentShift> get() = frames.map { it.shift }
    val acceptedRegistrationCount: Int get() = frames.count { it.accepted }
    val rejectedRegistrationCount: Int get() = frames.size - acceptedRegistrationCount
}

internal data class ManualSequenceFrameDecision(
    val originalFrameIndex: Int,
    val frameId: String?,
    val accepted: Boolean,
    val rejectionReason: String?,
    val shift: AlignmentShift,
    val registrationResidualPx: Float?,
    val registrationConfidence: Float?
) {
    val originalFrameNumber: Int get() = originalFrameIndex + 1
}

internal enum class ManualAlignedStackMode(
    val reportName: String,
    val minimumFrameCount: Int
) {
    AVERAGE("average_aligned", 2),
    MEDIAN("median_aligned", 2),
    SIGMA("sigma_aligned", 2),
    DARK_SUBTRACTED_AVERAGE("dark_subtracted_average_aligned", 2)
}

internal data class ManualSequenceFrameWork<T>(
    val originalFrameIndex: Int,
    val compactFrameIndex: Int,
    val value: T,
    val decision: ManualSequenceFrameDecision?
) {
    val originalFrameNumber: Int get() = originalFrameIndex + 1
    val compactFrameNumber: Int get() = compactFrameIndex + 1
    val shift: AlignmentShift? get() = decision?.shift
}

internal data class ManualSequenceRejectedFrame(
    val originalFrameIndex: Int,
    val frameId: String?,
    val reason: String
) {
    val originalFrameNumber: Int get() = originalFrameIndex + 1
}

internal data class ManualSequenceIntegrationReport(
    val mode: ManualAlignedStackMode,
    val inputFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrames: List<ManualSequenceRejectedFrame>,
    val integratedOriginalFrameIndices: List<Int>,
    val sensorDefectFiltering: SensorDefectFilteringReport? = null
) {
    val rejectedFrameCount: Int get() = rejectedFrames.size
    val acceptedOriginalFrameIndices: List<Int> get() = integratedOriginalFrameIndices
}

internal sealed interface ManualSequenceAlignmentPlanningResult {
    data class Ready(val plan: ManualSequenceAlignmentPlan) :
        ManualSequenceAlignmentPlanningResult

    data class Unavailable(val reason: String) :
        ManualSequenceAlignmentPlanningResult

    data class InsufficientAcceptedFrames(
        val acceptedFrameCount: Int,
        val requiredFrameCount: Int,
        val rejectedFrames: List<ManualSequenceRejectedFrame>
    ) : ManualSequenceAlignmentPlanningResult {
        val message: String = buildString {
            append(
                "Недостаточно принятых кадров после sequence-регистрации: " +
                    "$acceptedFrameCount из требуемых $requiredFrameCount."
            )
            if (rejectedFrames.isNotEmpty()) {
                append(
                    " Отклонены исходные кадры: " +
                        rejectedFrames.joinToString { frame ->
                            "${frame.originalFrameNumber} (${frame.reason})"
                        }
                )
            }
        }
    }
}

internal class ManualSequenceInsufficientFramesException(message: String) :
    IllegalStateException(message)

internal fun resolveManualSequencePlanningResult(
    result: ManualSequenceAlignmentPlanningResult
): ManualSequenceAlignmentPlan? = when (result) {
    is ManualSequenceAlignmentPlanningResult.Ready -> result.plan
    is ManualSequenceAlignmentPlanningResult.Unavailable -> null
    is ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames ->
        throw ManualSequenceInsufficientFramesException(result.message)
}

internal fun <T> manualSequenceFrameWork(
    inputFrames: List<T>,
    plan: ManualSequenceAlignmentPlan?,
    mode: ManualAlignedStackMode
): List<ManualSequenceFrameWork<T>> {
    if (plan == null) {
        return inputFrames.mapIndexed { index, frame ->
            ManualSequenceFrameWork(index, index, frame, null)
        }
    }
    require(plan.frames.size == inputFrames.size) {
        "Sequence plan contains ${plan.frames.size} frames for ${inputFrames.size} inputs"
    }
    require(plan.frames.map { it.originalFrameIndex } == inputFrames.indices.toList()) {
        "Sequence plan original frame indices do not match the input order"
    }
    require(plan.frames[plan.referenceFrameIndex].accepted) {
        "Sequence reference frame ${plan.referenceFrameIndex + 1} was rejected"
    }
    val accepted = plan.frames.filter { it.accepted }
    require(accepted.size >= mode.minimumFrameCount) {
        "Недостаточно принятых кадров для ${mode.reportName}: " +
            "${accepted.size}, требуется ${mode.minimumFrameCount}. " +
            "Отклонённые кадры не будут возвращены в стек."
    }
    return accepted.mapIndexed { compactIndex, decision ->
        ManualSequenceFrameWork(
            originalFrameIndex = decision.originalFrameIndex,
            compactFrameIndex = compactIndex,
            value = inputFrames[decision.originalFrameIndex],
            decision = decision
        )
    }
}

internal fun manualSequenceIntegrationReport(
    plan: ManualSequenceAlignmentPlan?,
    mode: ManualAlignedStackMode,
    integratedOriginalFrameIndices: List<Int>,
    sensorDefectFiltering: SensorDefectFilteringReport? = null
): ManualSequenceIntegrationReport? {
    if (plan == null) return null
    val acceptedIndices = plan.frames.filter { it.accepted }.map { it.originalFrameIndex }
    require(integratedOriginalFrameIndices == acceptedIndices) {
        "Integrated frame indices do not match the accepted sequence frames"
    }
    return ManualSequenceIntegrationReport(
        mode = mode,
        inputFrameCount = plan.frames.size,
        acceptedFrameCount = acceptedIndices.size,
        rejectedFrames = plan.frames.filterNot { it.accepted }.map { decision ->
            ManualSequenceRejectedFrame(
                originalFrameIndex = decision.originalFrameIndex,
                frameId = decision.frameId,
                reason = decision.rejectionReason ?: "registration_rejected"
            )
        },
        integratedOriginalFrameIndices = integratedOriginalFrameIndices,
        sensorDefectFiltering = sensorDefectFiltering
    )
}

internal fun planManualSequenceAlignment(
    frames: List<ArgbPixelImage>,
    outputWidth: Int,
    outputHeight: Int
): ManualSequenceAlignmentPlan? {
    if (frames.size < MIN_MANUAL_SEQUENCE_FRAMES) return null
    if (frames.any { it.width != frames.first().width || it.height != frames.first().height }) {
        return null
    }
    require(outputWidth > 0 && outputHeight > 0)

    val analyzer = JpegFrameAnalyzer()
    val maskEstimator = SkyMaskEstimator()
    val persistentDetector = PersistentSensorCandidateDetector()
    val persistentObservations = mutableListOf<ArtifactFrameObservation>()
    val analyses = frames.mapIndexed { index, image ->
        val id = "manual-${index + 1}"
        val skyMask = maskEstimator.estimate(image)
        persistentObservations += ArtifactFrameObservation(
            id,
            persistentDetector.detect(image, skyMask.mask)
        )
        analyzer.analyze(id, id, image, skyMask)
    }
    return when (
        val result = evaluateManualSequenceAlignmentFromAnalyses(
            analyses,
            outputWidth,
            outputHeight,
            persistentObservations
        )
    ) {
        is ManualSequenceAlignmentPlanningResult.Ready -> result.plan
        is ManualSequenceAlignmentPlanningResult.Unavailable,
        is ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames -> null
    }
}

internal fun planManualSequenceAlignmentFromAnalyses(
    analyses: List<FrameAnalysis>,
    outputWidth: Int,
    outputHeight: Int
): ManualSequenceAlignmentPlan? =
    when (
        val result = evaluateManualSequenceAlignmentFromAnalyses(
            analyses,
            outputWidth,
            outputHeight
        )
    ) {
        is ManualSequenceAlignmentPlanningResult.Ready -> result.plan
        is ManualSequenceAlignmentPlanningResult.Unavailable,
        is ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames -> null
    }

internal fun evaluateManualSequenceAlignmentFromAnalyses(
    analyses: List<FrameAnalysis>,
    outputWidth: Int,
    outputHeight: Int,
    persistentArtifactObservations: List<ArtifactFrameObservation>? = null
): ManualSequenceAlignmentPlanningResult {
    if (analyses.size < MIN_MANUAL_SEQUENCE_FRAMES) {
        return ManualSequenceAlignmentPlanningResult.Unavailable("too_few_sequence_frames")
    }
    if (
        analyses.any {
            !it.decodeValid ||
                it.width != analyses.first().width ||
                it.height != analyses.first().height
        }
    ) {
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            "invalid_or_mismatched_sequence_frames"
        )
    }
    require(outputWidth > 0 && outputHeight > 0)
    require(
        persistentArtifactObservations == null ||
            persistentArtifactObservations.size == analyses.size
    )

    val artifactAnalyzer = StaticArtifactAnalyzer()
    val artifactMask = artifactAnalyzer.analyze(
        analyses.map { analysis -> ArtifactFrameObservation(analysis.id, analysis.stars) },
        analyses.first().width,
        analyses.first().height
    )
    val filtered = analyses.mapIndexed { index, analysis ->
        artifactAnalyzer.excludeFrom(analysis, artifactMask) to (index + 1)
    }
    val reference = ReferenceFrameSelector().select(filtered.map { it.first }).analysis
    if (reference.reliableStarCount < MIN_MANUAL_SEQUENCE_REFERENCE_STARS) {
        return ManualSequenceAlignmentPlanningResult.Unavailable("too_few_reference_stars")
    }

    val registration = SequenceAwareRegistrationEngine().register(
        frames = filtered.map { (analysis, captureIndex) ->
            TemporalFeatureFrame(analysis.id, captureIndex, analysis.stars)
        },
        referenceFrameId = reference.id,
        imageWidth = reference.width,
        imageHeight = reference.height
    )
    val acceptedCount = registration.registrations.count { it.value.isReliable }
    val minimumAccepted = maxOf(
        MIN_MANUAL_SEQUENCE_ACCEPTED_FRAMES,
        ceil(analyses.size * MIN_MANUAL_SEQUENCE_ACCEPTED_RATIO).toInt()
    )
    if (acceptedCount < minimumAccepted) {
        return ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames(
            acceptedFrameCount = acceptedCount,
            requiredFrameCount = minimumAccepted,
            rejectedFrames = analyses.mapIndexedNotNull { index, analysis ->
                val result = registration.registrations[analysis.id]
                if (result?.isReliable == true) {
                    null
                } else {
                    ManualSequenceRejectedFrame(
                        originalFrameIndex = index,
                        frameId = analysis.id,
                        reason = result?.rejectionReason
                            ?: registration.frameAcceptanceReasons[analysis.id]
                            ?: registration.rejectedReasons[analysis.id]
                            ?: "registration_rejected"
                    )
                }
            }
        )
    }
    if (
        !registration.model.motionObservable ||
        registration.model.score < MIN_MANUAL_SEQUENCE_MODEL_SCORE
    ) {
        return ManualSequenceAlignmentPlanningResult.Unavailable("sequence_quality_gate")
    }

    val scaleX = outputWidth.toFloat() / reference.width.coerceAtLeast(1)
    val scaleY = outputHeight.toFloat() / reference.height.coerceAtLeast(1)
    val residualScale = (scaleX + scaleY) * 0.5f
    val fullResidual = registration.model.residual * residualScale
    if (fullResidual > MAX_MANUAL_SEQUENCE_MODEL_RESIDUAL_PX) {
        return ManualSequenceAlignmentPlanningResult.Unavailable("sequence_residual_gate")
    }

    val decisions = analyses.mapIndexed { index, analysis ->
        val registrationResult = checkNotNull(registration.registrations[analysis.id]) {
            "Missing registration diagnostics for ${analysis.id}"
        }
        val transform = registration.model.predictedTransform(index + 1)
        val dx = (transform.dx * scaleX).roundToInt()
        val dy = (transform.dy * scaleY).roundToInt()
        val limit = manualAlignmentShiftLimitPx(
            frameNumber = index + 1,
            totalFrames = analyses.size,
            imageWidth = outputWidth,
            imageHeight = outputHeight
        )
        if (abs(dx) > limit || abs(dy) > limit) {
            return ManualSequenceAlignmentPlanningResult.Unavailable("dynamic_shift_gate")
        }
        ManualSequenceFrameDecision(
            originalFrameIndex = index,
            frameId = analysis.id,
            accepted = registrationResult.isReliable,
            rejectionReason = if (registrationResult.isReliable) {
                null
            } else {
                registrationResult.rejectionReason
                    ?: registration.frameAcceptanceReasons[analysis.id]
                    ?: registration.rejectedReasons[analysis.id]
                    ?: "registration_rejected"
            },
            shift = AlignmentShift(
                dx = dx,
                dy = dy,
                confidence = registrationResult.confidence.toDouble()
            ),
            registrationResidualPx = registrationResult.residualError
                .takeIf { it.isFinite() }
                ?.times(residualScale),
            registrationConfidence = registrationResult.confidence
        )
    }
    val sensorArtifactFrames = persistentArtifactObservations
        ?: analyses.map { analysis -> ArtifactFrameObservation(analysis.id, analysis.stars) }
    val sensorArtifactMask = if (persistentArtifactObservations == null) {
        artifactMask
    } else {
        artifactAnalyzer.analyze(
            sensorArtifactFrames,
            analyses.first().width,
            analyses.first().height
        )
    }
    val sensorDefectMask = buildConfirmedSensorDefectMask(
        staticMask = sensorArtifactMask,
        frames = sensorArtifactFrames,
        referenceToSourceTransforms = analyses.indices.map { index ->
            registration.model.predictedTransform(index + 1)
        }
    ).scaledTo(outputWidth, outputHeight)
    val referenceIndex = analyses.indexOfFirst { it.id == reference.id }
    if (
        referenceIndex !in decisions.indices ||
        !decisions[referenceIndex].shift.isZero ||
        !decisions[referenceIndex].accepted
    ) {
        return ManualSequenceAlignmentPlanningResult.Unavailable("invalid_reference_frame")
    }
    return ManualSequenceAlignmentPlanningResult.Ready(
        ManualSequenceAlignmentPlan(
            referenceFrameIndex = referenceIndex,
            frames = decisions,
            modelScore = registration.model.score,
            modelResidualPx = fullResidual,
            stationaryArtifactCount = artifactMask.regions.size,
            sensorDefectMask = sensorDefectMask
        )
    )
}

private const val MIN_MANUAL_SEQUENCE_FRAMES = 8
private const val MIN_MANUAL_SEQUENCE_REFERENCE_STARS = 4
private const val MIN_MANUAL_SEQUENCE_ACCEPTED_FRAMES = 4
private const val MIN_MANUAL_SEQUENCE_ACCEPTED_RATIO = 0.45f
private const val MIN_MANUAL_SEQUENCE_MODEL_SCORE = 0.45f
private const val MAX_MANUAL_SEQUENCE_MODEL_RESIDUAL_PX = 3f
