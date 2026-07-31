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
import kotlinx.coroutines.CancellationException
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

internal enum class ManualAlignmentPath {
    SEQUENCE_AWARE,
    LEGACY,
    UNKNOWN;

    companion object {
        fun fromSerialized(value: String?): ManualAlignmentPath =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal enum class ManualAlignmentPathReason(
    val legacyFallbackAllowed: Boolean
) {
    SEQUENCE_AWARE_SELECTED(false),
    LEGACY_SHORT_SEQUENCE(true),
    LEGACY_TOO_FEW_REFERENCE_STARS(true),
    LEGACY_SEQUENCE_QUALITY_GATE(true),
    LEGACY_SEQUENCE_RESIDUAL_GATE(true),
    LEGACY_DYNAMIC_SHIFT_GATE(true),
    SEQUENCE_PLANNER_INSUFFICIENT_ACCEPTED_FRAMES(false),
    SEQUENCE_PLANNER_UNSUPPORTED_INPUT(false),
    SEQUENCE_PLANNER_INVALID_REFERENCE(false),
    SEQUENCE_PLANNER_INTERNAL_ERROR(false),
    UNKNOWN(false);

    companion object {
        fun fromSerialized(value: String?): ManualAlignmentPathReason =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal enum class ManualAlignmentProcessingOutcome {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    UNKNOWN;

    companion object {
        fun fromSerialized(value: String?): ManualAlignmentProcessingOutcome =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal data class ManualAlignmentPathReport(
    val manualAlignmentPath: ManualAlignmentPath,
    val manualAlignmentPathReason: ManualAlignmentPathReason,
    val manualAlignmentAttempted: Boolean,
    val legacyFallbackAllowed: Boolean,
    val legacyFallbackUsed: Boolean,
    val sequencePlannerFailureType: String? = null,
    val sequencePlannerFailureMessage: String? = null,
    val processingOutcome: ManualAlignmentProcessingOutcome,
    val outputPublished: Boolean,
    val cleanupCompleted: Boolean
) {
    fun publishedSuccessfully(): ManualAlignmentPathReport = copy(
        processingOutcome = ManualAlignmentProcessingOutcome.SUCCESS,
        outputPublished = true,
        cleanupCompleted = true
    )

    companion object {
        const val SCHEMA_VERSION = "astrophoto.manual.alignment/1"
    }
}

internal data class ManualAlignmentSelection(
    val inputFrameCount: Int,
    val sequencePlan: ManualSequenceAlignmentPlan?,
    val report: ManualAlignmentPathReport
) {
    init {
        require(inputFrameCount >= 0)
        require(
            (report.manualAlignmentPath == ManualAlignmentPath.SEQUENCE_AWARE) ==
                (sequencePlan != null)
        )
    }
}

internal enum class ManualAlignmentFailureInjectionPoint {
    BEFORE_SEQUENCE_PLANNER,
    AFTER_SEQUENCE_PLANNER_RESULT,
    BEFORE_INTEGRATION
}

internal fun interface ManualAlignmentFailureInjector {
    fun checkpoint(
        point: ManualAlignmentFailureInjectionPoint,
        mode: ManualAlignedStackMode
    )
}

internal object NoOpManualAlignmentFailureInjector : ManualAlignmentFailureInjector {
    override fun checkpoint(
        point: ManualAlignmentFailureInjectionPoint,
        mode: ManualAlignedStackMode
    ) = Unit
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
    val alignmentPathReport: ManualAlignmentPathReport,
    val sensorDefectFiltering: SensorDefectFilteringReport? = null
) {
    val rejectedFrameCount: Int get() = rejectedFrames.size
    val acceptedOriginalFrameIndices: List<Int> get() = integratedOriginalFrameIndices
}

internal sealed interface ManualSequenceAlignmentPlanningResult {
    data class Ready(val plan: ManualSequenceAlignmentPlan) :
        ManualSequenceAlignmentPlanningResult

    data class Unavailable(val reason: ManualAlignmentPathReason) :
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

internal abstract class ManualAlignmentReportedException(
    val alignmentReport: ManualAlignmentPathReport,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal class ManualSequenceInsufficientFramesException(
    message: String,
    report: ManualAlignmentPathReport = failedManualAlignmentReport(
        reason = ManualAlignmentPathReason.SEQUENCE_PLANNER_INSUFFICIENT_ACCEPTED_FRAMES,
        failureType = "ManualSequenceInsufficientFramesException",
        failureMessage = message
    )
) : ManualAlignmentReportedException(report, message)

internal class ManualAlignmentProcessingException(
    report: ManualAlignmentPathReport,
    cause: Throwable? = null
) : ManualAlignmentReportedException(
    alignmentReport = report,
    message = buildString {
        append("Sequence-aware alignment failed; legacy fallback was not used")
        report.sequencePlannerFailureMessage?.takeIf(String::isNotBlank)?.let {
            append(": ")
            append(it)
        }
    },
    cause = cause
)

internal fun resolveManualSequencePlanningResult(
    result: ManualSequenceAlignmentPlanningResult
): ManualSequenceAlignmentPlan? = when (result) {
    is ManualSequenceAlignmentPlanningResult.Ready -> result.plan
    is ManualSequenceAlignmentPlanningResult.Unavailable -> null
    is ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames ->
        throw ManualSequenceInsufficientFramesException(result.message)
}

internal suspend fun selectManualAlignmentPath(
    inputFrameCount: Int,
    mode: ManualAlignedStackMode,
    failureInjector: ManualAlignmentFailureInjector = NoOpManualAlignmentFailureInjector,
    planner: suspend () -> ManualSequenceAlignmentPlanningResult
): ManualAlignmentSelection {
    require(inputFrameCount >= 0)
    return try {
        val selection = if (inputFrameCount < MIN_MANUAL_SEQUENCE_FRAMES) {
            legacyManualAlignmentSelection(
                inputFrameCount,
                ManualAlignmentPathReason.LEGACY_SHORT_SEQUENCE
            )
        } else {
            failureInjector.checkpoint(
                ManualAlignmentFailureInjectionPoint.BEFORE_SEQUENCE_PLANNER,
                mode
            )
            val planning = planner()
            failureInjector.checkpoint(
                ManualAlignmentFailureInjectionPoint.AFTER_SEQUENCE_PLANNER_RESULT,
                mode
            )
            when (planning) {
                is ManualSequenceAlignmentPlanningResult.Ready ->
                    sequenceAwareManualAlignmentSelection(inputFrameCount, planning.plan)

                is ManualSequenceAlignmentPlanningResult.Unavailable -> {
                    if (planning.reason.legacyFallbackAllowed) {
                        legacyManualAlignmentSelection(inputFrameCount, planning.reason)
                    } else {
                        throw ManualAlignmentProcessingException(
                            failedManualAlignmentReport(
                                reason = planning.reason,
                                failureType = "ManualSequenceAlignmentPlanningResult.Unavailable",
                                failureMessage = planning.reason.name
                            )
                        )
                    }
                }

                is ManualSequenceAlignmentPlanningResult.InsufficientAcceptedFrames ->
                    throw ManualSequenceInsufficientFramesException(planning.message)
            }
        }
        failureInjector.checkpoint(
            ManualAlignmentFailureInjectionPoint.BEFORE_INTEGRATION,
            mode
        )
        selection
    } catch (error: CancellationException) {
        throw error
    } catch (error: ManualAlignmentReportedException) {
        throw error
    } catch (error: Exception) {
        throw ManualAlignmentProcessingException(
            report = failedManualAlignmentReport(
                reason = ManualAlignmentPathReason.SEQUENCE_PLANNER_INTERNAL_ERROR,
                failureType = error::class.java.name,
                failureMessage = safeManualAlignmentFailureMessage(error)
            ),
            cause = error
        )
    }
}

internal suspend fun <T> runManualStackingOperation(
    onAlignmentFailure: suspend (ManualAlignmentPathReport) -> Unit,
    operation: suspend () -> T
): Result<T> = try {
    Result.success(operation())
} catch (error: CancellationException) {
    throw error
} catch (error: ManualAlignmentReportedException) {
    val completedReport = error.alignmentReport.copy(cleanupCompleted = true)
    try {
        onAlignmentFailure(completedReport)
    } catch (reportError: CancellationException) {
        error.addSuppressed(reportError)
        throw reportError
    } catch (reportError: Exception) {
        error.addSuppressed(reportError)
    }
    Result.failure(error)
} catch (error: Exception) {
    Result.failure(error)
}

internal fun ManualAlignmentPathReport.toStableFields(): Map<String, String> = linkedMapOf(
    "manualAlignmentReportSchema" to ManualAlignmentPathReport.SCHEMA_VERSION,
    "manualAlignmentPath" to manualAlignmentPath.name,
    "manualAlignmentPathReason" to manualAlignmentPathReason.name,
    "manualAlignmentAttempted" to manualAlignmentAttempted.toString(),
    "legacyFallbackAllowed" to legacyFallbackAllowed.toString(),
    "legacyFallbackUsed" to legacyFallbackUsed.toString(),
    "sequencePlannerFailureType" to sequencePlannerFailureType.orEmpty(),
    "sequencePlannerFailureMessage" to sequencePlannerFailureMessage.orEmpty(),
    "processingOutcome" to processingOutcome.name,
    "outputPublished" to outputPublished.toString(),
    "cleanupCompleted" to cleanupCompleted.toString()
)

internal fun manualAlignmentPathReportJson(
    mode: ManualAlignedStackMode,
    report: ManualAlignmentPathReport
): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": \"")
    append(ManualAlignmentPathReport.SCHEMA_VERSION)
    append("\",\n")
    append("  \"manualSequenceMode\": \"")
    append(escapeManualAlignmentJson(mode.reportName))
    append("\",\n")
    report.toStableFields()
        .filterKeys { it != "manualAlignmentReportSchema" }
        .entries
        .forEachIndexed { index, (name, value) ->
            append("  \"")
            append(name)
            append("\": ")
            when (name) {
                "manualAlignmentAttempted",
                "legacyFallbackAllowed",
                "legacyFallbackUsed",
                "outputPublished",
                "cleanupCompleted" -> append(value)

                "sequencePlannerFailureType",
                "sequencePlannerFailureMessage" -> if (value.isBlank()) {
                    append("null")
                } else {
                    append('"')
                    append(escapeManualAlignmentJson(value))
                    append('"')
                }

                else -> {
                    append('"')
                    append(escapeManualAlignmentJson(value))
                    append('"')
                }
            }
            if (index < report.toStableFields().size - 2) append(',')
            append('\n')
        }
    append("}\n")
}

internal fun manualAlignmentPathReportFromFields(
    fields: Map<String, String>
): ManualAlignmentPathReport? {
    val serializedPath = fields["manualAlignmentPath"] ?: return null
    return ManualAlignmentPathReport(
        manualAlignmentPath = ManualAlignmentPath.fromSerialized(serializedPath),
        manualAlignmentPathReason = ManualAlignmentPathReason.fromSerialized(
            fields["manualAlignmentPathReason"]
        ),
        manualAlignmentAttempted = fields["manualAlignmentAttempted"]
            ?.toBooleanStrictOrNull() ?: false,
        legacyFallbackAllowed = fields["legacyFallbackAllowed"]
            ?.toBooleanStrictOrNull() ?: false,
        legacyFallbackUsed = fields["legacyFallbackUsed"]
            ?.toBooleanStrictOrNull() ?: false,
        sequencePlannerFailureType = fields["sequencePlannerFailureType"]
            ?.takeIf(String::isNotBlank),
        sequencePlannerFailureMessage = fields["sequencePlannerFailureMessage"]
            ?.takeIf(String::isNotBlank),
        processingOutcome = ManualAlignmentProcessingOutcome.fromSerialized(
            fields["processingOutcome"]
        ),
        outputPublished = fields["outputPublished"]?.toBooleanStrictOrNull() ?: false,
        cleanupCompleted = fields["cleanupCompleted"]?.toBooleanStrictOrNull() ?: false
    )
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
    alignmentSelection: ManualAlignmentSelection?,
    mode: ManualAlignedStackMode,
    integratedOriginalFrameIndices: List<Int>,
    sensorDefectFiltering: SensorDefectFilteringReport? = null
): ManualSequenceIntegrationReport? {
    if (alignmentSelection == null) return null
    val plan = alignmentSelection.sequencePlan
    val acceptedIndices = plan?.frames
        ?.filter { it.accepted }
        ?.map { it.originalFrameIndex }
        ?: (0 until alignmentSelection.inputFrameCount).toList()
    require(integratedOriginalFrameIndices == acceptedIndices) {
        "Integrated frame indices do not match the selected alignment path"
    }
    return ManualSequenceIntegrationReport(
        mode = mode,
        inputFrameCount = alignmentSelection.inputFrameCount,
        acceptedFrameCount = acceptedIndices.size,
        rejectedFrames = plan?.frames.orEmpty().filterNot { it.accepted }.map { decision ->
            ManualSequenceRejectedFrame(
                originalFrameIndex = decision.originalFrameIndex,
                frameId = decision.frameId,
                reason = decision.rejectionReason ?: "registration_rejected"
            )
        },
        integratedOriginalFrameIndices = integratedOriginalFrameIndices,
        alignmentPathReport = alignmentSelection.report,
        sensorDefectFiltering = sensorDefectFiltering
    )
}

internal fun ManualSequenceIntegrationReport.publishedSuccessfully() = copy(
    alignmentPathReport = alignmentPathReport.publishedSuccessfully()
)

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
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.LEGACY_SHORT_SEQUENCE
        )
    }
    if (
        analyses.any {
            !it.decodeValid ||
                it.width != analyses.first().width ||
                it.height != analyses.first().height
        }
    ) {
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.SEQUENCE_PLANNER_UNSUPPORTED_INPUT
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
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.LEGACY_TOO_FEW_REFERENCE_STARS
        )
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
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.LEGACY_SEQUENCE_QUALITY_GATE
        )
    }

    val scaleX = outputWidth.toFloat() / reference.width.coerceAtLeast(1)
    val scaleY = outputHeight.toFloat() / reference.height.coerceAtLeast(1)
    val residualScale = (scaleX + scaleY) * 0.5f
    val fullResidual = registration.model.residual * residualScale
    if (fullResidual > MAX_MANUAL_SEQUENCE_MODEL_RESIDUAL_PX) {
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.LEGACY_SEQUENCE_RESIDUAL_GATE
        )
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
            return ManualSequenceAlignmentPlanningResult.Unavailable(
                ManualAlignmentPathReason.LEGACY_DYNAMIC_SHIFT_GATE
            )
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
        return ManualSequenceAlignmentPlanningResult.Unavailable(
            ManualAlignmentPathReason.SEQUENCE_PLANNER_INVALID_REFERENCE
        )
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

private fun sequenceAwareManualAlignmentSelection(
    inputFrameCount: Int,
    plan: ManualSequenceAlignmentPlan
) = ManualAlignmentSelection(
    inputFrameCount = inputFrameCount,
    sequencePlan = plan,
    report = ManualAlignmentPathReport(
        manualAlignmentPath = ManualAlignmentPath.SEQUENCE_AWARE,
        manualAlignmentPathReason = ManualAlignmentPathReason.SEQUENCE_AWARE_SELECTED,
        manualAlignmentAttempted = true,
        legacyFallbackAllowed = false,
        legacyFallbackUsed = false,
        processingOutcome = ManualAlignmentProcessingOutcome.IN_PROGRESS,
        outputPublished = false,
        cleanupCompleted = false
    )
)

private fun legacyManualAlignmentSelection(
    inputFrameCount: Int,
    reason: ManualAlignmentPathReason
): ManualAlignmentSelection {
    require(reason.legacyFallbackAllowed)
    return ManualAlignmentSelection(
        inputFrameCount = inputFrameCount,
        sequencePlan = null,
        report = ManualAlignmentPathReport(
            manualAlignmentPath = ManualAlignmentPath.LEGACY,
            manualAlignmentPathReason = reason,
            manualAlignmentAttempted = true,
            legacyFallbackAllowed = true,
            legacyFallbackUsed = true,
            processingOutcome = ManualAlignmentProcessingOutcome.IN_PROGRESS,
            outputPublished = false,
            cleanupCompleted = false
        )
    )
}

private fun failedManualAlignmentReport(
    reason: ManualAlignmentPathReason,
    failureType: String,
    failureMessage: String
) = ManualAlignmentPathReport(
    manualAlignmentPath = ManualAlignmentPath.SEQUENCE_AWARE,
    manualAlignmentPathReason = reason,
    manualAlignmentAttempted = true,
    legacyFallbackAllowed = false,
    legacyFallbackUsed = false,
    sequencePlannerFailureType = failureType,
    sequencePlannerFailureMessage = failureMessage,
    processingOutcome = ManualAlignmentProcessingOutcome.FAILED,
    outputPublished = false,
    cleanupCompleted = false
)

private fun safeManualAlignmentFailureMessage(error: Exception): String =
    (error.message ?: error::class.java.simpleName)
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(MAX_MANUAL_ALIGNMENT_FAILURE_MESSAGE_LENGTH)

private fun escapeManualAlignmentJson(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

internal const val MIN_MANUAL_SEQUENCE_FRAMES = 8
private const val MIN_MANUAL_SEQUENCE_REFERENCE_STARS = 4
private const val MIN_MANUAL_SEQUENCE_ACCEPTED_FRAMES = 4
private const val MIN_MANUAL_SEQUENCE_ACCEPTED_RATIO = 0.45f
private const val MIN_MANUAL_SEQUENCE_MODEL_SCORE = 0.45f
private const val MAX_MANUAL_SEQUENCE_MODEL_RESIDUAL_PX = 3f
private const val MAX_MANUAL_ALIGNMENT_FAILURE_MESSAGE_LENGTH = 240
